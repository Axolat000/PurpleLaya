package com.music.laya.data.innertube

import com.music.laya.data.NerdStats
import com.music.laya.data.TrackLog
import com.music.laya.data.purplemusic.PurpleMusicClient
import com.music.laya.platform.ConcurrentMap
import okio.IOException
import kotlin.concurrent.Volatile

/**
 * Turns a PurpleMusic track ID into a directly streamable HTTP URL.
 */
object StreamResolver {
    /** The bitrate ceiling in force — the phone's quality setting for the connection it is on. */
    @Volatile
    var maxKbps: () -> Int = { Int.MAX_VALUE }

    private const val TAG = "PurpleLaya"

    suspend fun resolve(videoId: String): String {
        val url = PurpleMusicClient.streamUrlFor(videoId, maxKbps())
        val track = PurpleMusicClient.cachedTrack(videoId)
        val kbps = if (track?.isLossless == true) 1411 else 320
        NerdStats.onStreamPicked(videoId, kbps)
        return url
    }

    private val loudness = ConcurrentMap<String, Double>()

    fun loudnessDbFor(videoId: String): Double? = loudness[videoId]

    class PermanentlyUnplayableException(reason: String) : IOException(reason)

    fun onSessionChanged() {
        UploadedTracks.clear()
    }

    fun mediaHeadersFor(@Suppress("UNUSED_PARAMETER") url: String): Map<String, String> = buildMap {
        put("User-Agent", "PurpleLaya/1.0 (+https://github.com/Axolat000/PurpleLaya)")
        if (PurpleMusicClient.username.isNotBlank()) {
            put("X-Purple-User", PurpleMusicClient.username)
            put("X-Purple-Pass", PurpleMusicClient.password)
        }
    }

    class Stream(val url: String, val kbps: Int, val mimeType: String, val loudnessDb: Double? = null) {
        val downloadExtension: String
            get() = when {
                "flac" in mimeType -> "flac"
                "wav" in mimeType -> "wav"
                "ogg" in mimeType -> "ogg"
                "mpeg" in mimeType || "mp3" in mimeType -> "mp3"
                "mp4" in mimeType || "m4a" in mimeType -> "m4a"
                else -> "mp3"
            }

        val downloadMimeType: String
            get() = when (downloadExtension) {
                "flac" -> "audio/flac"
                "wav" -> "audio/wav"
                "ogg" -> "audio/ogg"
                "m4a" -> "audio/mp4"
                else -> "audio/mpeg"
            }
    }

    suspend fun resolveForDownload(
        videoId: String,
        maxKbps: Int,
        @Suppress("UNUSED_PARAMETER") requireM4a: Boolean = false,
    ): Stream {
        val url = PurpleMusicClient.streamUrlFor(videoId, maxKbps)
        val track = PurpleMusicClient.cachedTrack(videoId)
        val mime = track?.mimeType ?: "audio/mpeg"
        val kbps = if (track?.isLossless == true) 1411 else 320
        return Stream(url = url, kbps = kbps, mimeType = mime)
    }

    suspend fun signatureTimestamp(@Suppress("UNUSED_PARAMETER") videoId: String): Int? = 1

    fun onPlaybackRefused(url: String, responseCode: Int) {
        TrackLog.w(TAG, "stream refused ($responseCode): $url")
    }

    suspend fun contentLength(@Suppress("UNUSED_PARAMETER") videoId: String): Long? = null
}
