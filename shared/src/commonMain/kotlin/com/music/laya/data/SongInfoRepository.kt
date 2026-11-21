package com.music.laya.data

import com.music.laya.data.model.Song
import com.music.laya.data.purplemusic.PurpleMusicClient
import com.music.laya.platform.decimalSeparator
import com.music.laya.platform.ioDispatcher
import kotlinx.coroutines.withContext

data class SongInfo(
    val album: String? = null,
    val releaseYear: String? = null,
    val releaseDate: String? = null,
    val views: String? = null,
    val likes: String? = null,
    val comments: String? = null,
    val failed: Boolean = false,
)

fun compactCount(raw: String): String {
    val n = raw.toLongOrNull()?.takeIf { it >= 0 } ?: return raw
    val (unit, suffix) = when {
        n >= 1_000_000_000 -> 1_000_000_000L to "B"
        n >= 1_000_000 -> 1_000_000L to "M"
        n >= 1_000 -> 1_000L to "K"
        else -> return n.toString()
    }
    val whole = n / unit
    if (whole >= 10) return "$whole$suffix"
    val tenth = n % unit * 10 / unit
    val separator = decimalSeparator()
    return if (tenth == 0L) "$whole$suffix" else "$whole$separator$tenth$suffix"
}

/**
 * Loads song metadata (album, upload date, play count, like count) from PurpleMusic.
 */
object SongInfoRepository {
    suspend fun load(song: Song): SongInfo = withContext(ioDispatcher) {
        val cleanId = song.videoId.removePrefix("purple:").trim()
        val track = PurpleMusicClient.cachedTrack(cleanId)
            ?: runCatching { PurpleMusicClient.listTracks().firstOrNull { it.id.toString() == cleanId } }.getOrNull()

        if (track == null) {
            return@withContext SongInfo(album = song.albumName)
        }

        val uploadDate = track.uploadDate?.substringBefore(' ')?.takeIf { it.isNotBlank() }
        val year = uploadDate?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
        SongInfo(
            album = track.album ?: song.albumName,
            releaseYear = year,
            releaseDate = uploadDate,
            views = track.playCount.toString(),
            likes = track.likeCount.toString(),
            comments = null,
            failed = false,
        )
    }
}

internal object SongInfoParser {
    fun parse(html: String, videoId: String): SongInfo {
        val scripts = com.fleeksoft.ksoup.Ksoup.parse(html).select("script").map { it.data() }
        val player = scripts.firstNotNullOfOrNull { extract(it, "ytInitialPlayerResponse") }
            ?: error("Missing player metadata")
        val details = player.obj("videoDetails")
        check(details.str("videoId") == videoId) { "Metadata belongs to another track" }
        val data = scripts.firstNotNullOfOrNull { extract(it, "ytInitialData") }
        val primary = data?.find("videoPrimaryInfoRenderer")?.firstOrNull()
        val oldLike = primary?.find("toggleButtonRenderer")?.firstOrNull {
            it.obj("defaultIcon").str("iconType") == "LIKE"
        }?.get("defaultText")?.text()
        val newLike = primary?.find("likeButtonViewModel")?.firstOrNull()
            ?.find("buttonViewModel")?.firstOrNull()?.str("title")
        val comments = data?.find("commentsHeaderRenderer")?.firstNotNullOfOrNull {
            it["countText"]?.text()?.withCount()
        }
            ?: data?.find("commentsEntryPointHeaderRenderer")?.firstOrNull()
                ?.get("commentCount")?.text()?.withCount()
            ?: data?.find("engagementPanelSectionListRenderer")?.firstOrNull {
                it.str("targetId") == "engagement-panel-comments-section" ||
                    it.str("panelIdentifier") == "engagement-panel-comments-section"
            }?.find("engagementPanelTitleHeaderRenderer")?.firstOrNull()
                ?.get("contextualInfo")?.text()?.withCount()
        return SongInfo(
            releaseDate = releaseDate(details.str("shortDescription")),
            views = details.str("viewCount")?.takeIf { it.toLongOrNull()?.let { n -> n >= 0 } == true },
            likes = (newLike ?: oldLike)?.withCount(),
            comments = comments,
            failed = data == null,
        )
    }

    private fun releaseDate(description: String?): String? {
        val date = description?.lineSequence()?.firstNotNullOfOrNull { line ->
            Regex("Released on:\\s*(\\d{4}-\\d{2}-\\d{2})", RegexOption.IGNORE_CASE)
                .matchEntire(line.trim())?.groupValues?.get(1)
        } ?: return null
        val (year, month, day) = date.split('-').map { it.toInt() }
        val days = when (month) {
            2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        return date.takeIf { month in 1..12 && day in 1..days }
    }

    private fun extract(script: String, name: String): kotlinx.serialization.json.JsonObject? {
        val assignment = Regex("(?:var\\s+)?${name}\\s*=\\s*").find(script) ?: return null
        val start = assignment.range.last + 1
        if (script.getOrNull(start) != '{') return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in start until script.length) {
            val c = script[i]
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> if (--depth == 0) return runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(script.substring(start, i + 1)) as? kotlinx.serialization.json.JsonObject
                }.getOrNull()
            }
        }
        return null
    }

    private fun kotlinx.serialization.json.JsonObject.obj(key: String) =
        this[key] as? kotlinx.serialization.json.JsonObject ?: kotlinx.serialization.json.JsonObject(emptyMap())
    private fun kotlinx.serialization.json.JsonObject.str(key: String) =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.let { if (it.isString) it.content else it.content }
    private fun String.withCount(): String? = takeIf { it.any(Char::isDigit) }
    private fun kotlinx.serialization.json.JsonElement.text(): String? =
        (this as? kotlinx.serialization.json.JsonObject)?.let {
            it.str("simpleText") ?: (it["runs"] as? kotlinx.serialization.json.JsonArray)?.joinToString("") { run ->
                (run as? kotlinx.serialization.json.JsonObject)?.str("text").orEmpty()
            }
        }?.takeIf { it.isNotBlank() }
    private fun kotlinx.serialization.json.JsonElement.find(key: String): List<kotlinx.serialization.json.JsonObject> = when (this) {
        is kotlinx.serialization.json.JsonObject -> listOfNotNull(this[key] as? kotlinx.serialization.json.JsonObject) + values.flatMap { it.find(key) }
        is kotlinx.serialization.json.JsonArray -> flatMap { it.find(key) }
        else -> emptyList()
    }
}
