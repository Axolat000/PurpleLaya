package com.music.laya.desktop

import com.music.laya.data.purplemusic.PurpleMusicClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Registers plays against the signed-in account's PurpleMusic history (`action=report_listen`).
 */
internal object DesktopPlaybackTracker {

    private class Session(
        val trackId: String,
    ) {
        var maxPositionSeconds = 0L
        var reported = false
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Mutex()

    @Volatile
    private var session: Session? = null

    fun onPlaying(videoId: String) {
        val cleanId = videoId.removePrefix("purple:").trim()
        if (cleanId.toIntOrNull() == null) return
        if (!PurpleMusicClient.isSignedIn) return
        val current = session
        if (current?.trackId == cleanId) return
        if (current != null) {
            flushSession(current, current.maxPositionSeconds)
        }
        session = Session(cleanId)
    }

    fun onTrackChanged(positionSeconds: Long) {
        val closing = session ?: return
        session = null
        flushSession(closing, maxOf(closing.maxPositionSeconds, positionSeconds))
    }

    fun onProgress(videoId: String, positionSeconds: Long) {
        val cleanId = videoId.removePrefix("purple:").trim()
        val current = session ?: return
        if (current.trackId != cleanId) return
        if (positionSeconds > current.maxPositionSeconds) {
            current.maxPositionSeconds = positionSeconds
        }
        if (!current.reported && current.maxPositionSeconds >= 10L) {
            current.reported = true
            val secs = current.maxPositionSeconds
            scope.launch {
                lock.withLock {
                    PurpleMusicClient.reportListen(cleanId, secs)
                        .onSuccess {
                            DesktopTrackLog.log("history: reported ${secs}s listen for track #$cleanId")
                        }
                        .onFailure {
                            DesktopTrackLog.log("history: report_listen failed for #$cleanId — ${it.message}")
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
        scope.launch {
            val block: suspend () -> Unit = {
                lock.withLock {
                    PurpleMusicClient.reportListen(target.trackId, seconds)
                        .onSuccess {
                            DesktopTrackLog.log("history: final ${seconds}s listen reported for #${target.trackId}")
                        }
                }
            }
            if (nonCancellable) withContext(NonCancellable) { block() } else block()
        }
    }
}
