package com.music.laya.data.innertube

import com.music.laya.data.TrackLog
import com.music.laya.data.model.Song
import com.music.laya.data.purplemusic.PurpleMusicClient
import com.music.laya.data.stats.ListenIdentity
import com.music.laya.platform.ioDispatcher
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Registers plays against the signed-in account's PurpleMusic history (`action=report_listen`),
 * so `play_count`, `listen_events`, personal stats, and recommendations reflect listening activity.
 */
object PlaybackTracker {

    private const val TAG = "PurpleLaya"

    private class Session(
        val trackId: String,
    ) {
        var maxPositionSeconds = 0L
        var reported = false
    }

    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())
    private val _registeredPlays = MutableStateFlow(0)
    val registeredPlays: StateFlow<Int> = _registeredPlays.asStateFlow()
    private val lock = Mutex()

    @Volatile
    private var session: Session? = null

    fun onPlaying(played: Song) {
        val id = ListenIdentity.idOf(played.videoId)
        register(id)
    }

    private fun register(trackId: String) {
        val cleanId = trackId.removePrefix("purple:").trim()
        if (cleanId.toIntOrNull() == null) return
        if (!PurpleMusicClient.isSignedIn) return
        val current = session
        if (current?.trackId == cleanId) return
        if (current != null) {
            flushSession(current, current.maxPositionSeconds)
        }
        session = Session(cleanId)
    }

    fun onVersionSwapped(from: Song, to: Song) {
        val before = ListenIdentity.of(from).videoId
        val after = ListenIdentity.onVersionSwapped(from, to).videoId
        if (before == after) return
        onPlaying(to)
    }

    fun onTrackChanged(positionSeconds: Long) {
        val closing = session ?: return
        session = null
        flushSession(closing, maxOf(closing.maxPositionSeconds, positionSeconds))
    }

    fun onProgress(playedId: String, positionSeconds: Long) {
        val cleanId = ListenIdentity.idOf(playedId).removePrefix("purple:").trim()
        val current = session ?: return
        if (current.trackId != cleanId) return
        if (positionSeconds > current.maxPositionSeconds) {
            current.maxPositionSeconds = positionSeconds
        }
        // As soon as 10s of real listening have elapsed, report once so play_count increments promptly
        if (!current.reported && current.maxPositionSeconds >= 10L) {
            current.reported = true
            val secs = current.maxPositionSeconds
            scope.launch(TrackLog.about(cleanId)) {
                lock.withLock {
                    PurpleMusicClient.reportListen(cleanId, secs)
                        .onSuccess { counted ->
                            if (counted) _registeredPlays.value++
                            TrackLog.d(TAG, "reported ${secs}s listen for track #$cleanId (counted=$counted)")
                        }
                }
            }
        }
    }

    fun onPlaybackFinished(positionSeconds: Long) {
        val closing = session ?: return
        session = null
        flushSession(closing, maxOf(closing.maxPositionSeconds, positionSeconds), nonCancellable = true)
    }

    private fun flushSession(target: Session, seconds: Long, nonCancellable: Boolean = false) {
        if (target.reported || seconds <= 0L) return
        target.reported = true
        scope.launch(TrackLog.about(target.trackId)) {
            val block: suspend () -> Unit = {
                lock.withLock {
                    PurpleMusicClient.reportListen(target.trackId, seconds)
                        .onSuccess { counted ->
                            if (counted) _registeredPlays.value++
                            TrackLog.d(TAG, "final listen report ${seconds}s for track #${target.trackId}")
                        }
                }
            }
            if (nonCancellable) withContext(NonCancellable) { block() } else block()
        }
    }
}
