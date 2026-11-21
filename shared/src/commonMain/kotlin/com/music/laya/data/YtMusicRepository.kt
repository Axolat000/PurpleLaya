package com.music.laya.data

import com.music.laya.data.DebugLog as Log
import com.music.laya.data.innertube.InnertubeParser
import com.music.laya.data.model.Account
import com.music.laya.data.model.AccountChannel
import com.music.laya.data.model.ArtistPage
import com.music.laya.data.model.HomeFeed
import com.music.laya.data.model.HomeShelf
import com.music.laya.data.model.LibraryPage
import com.music.laya.data.model.LibraryState
import com.music.laya.data.model.LikeStatus
import com.music.laya.data.model.MoodGenre
import com.music.laya.data.model.MoodGenreSection
import com.music.laya.data.model.PlaylistPrivacy
import com.music.laya.data.model.SearchFilter
import com.music.laya.data.model.SearchResult
import com.music.laya.data.model.ShelfItem
import com.music.laya.data.model.Song
import com.music.laya.data.model.SongMenu
import com.music.laya.data.model.UserPlaylist
import com.music.laya.data.purplemusic.PurpleMusicClient
import com.music.laya.platform.ConcurrentMap
import com.music.laya.platform.ioDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Suspend API backed by PurpleMusic (`PurpleMusicClient`).
 *
 * Keeps the original `YtMusicRepository` interface intact so every screen across
 * Desktop, Android, and sharedUi works seamlessly against the self-hosted
 * PurpleMusic server.
 */
object YtMusicRepository {

    private const val TAG = "PurpleLaya"
    private val moodGenreShelfCache = ConcurrentMap<String, List<HomeShelf>>()
    private val audioVersionCache = ConcurrentMap<String, Song>()
    private val videoVersionCache = ConcurrentMap<String, Song?>()

    fun cachedAudioVersion(videoId: String): Song? = audioVersionCache[videoId]
    fun cachedVideoVersion(videoId: String): Song? = videoVersionCache[videoId]

    suspend fun home(): Result<HomeFeed> = call("home") {
        PurpleMusicClient.buildHomeFeed()
    }

    suspend fun homeRecentlyPlayed(): Result<HomeShelf?> = call("home:recent") {
        PurpleMusicClient.buildRecentlyPlayedShelf()
    }

    suspend fun homeSupplement(browseId: String): Result<List<HomeShelf>> = call("home:$browseId") {
        emptyList()
    }

    val HOME_SUPPLEMENT_BROWSE_IDS = emptyList<String>()

    suspend fun moreHome(token: String): Result<HomeFeed> = call("home:more") {
        HomeFeed(shelves = emptyList(), continuation = null)
    }

    suspend fun history(): Result<List<Song>> = call("history") {
        PurpleMusicClient.listTracks()
            .filter { it.playCount > 0 }
            .sortedByDescending { it.playCount }
            .take(50)
            .map { it.toSong() }
    }

    suspend fun recents(): Result<List<Song>> = call("recents") {
        PurpleMusicClient.listTracks()
            .sortedByDescending { it.id }
            .take(30)
            .map { it.toSong() }
    }

    suspend fun quickPicks(excludeSongIds: Set<String> = emptySet()): Result<List<Song>> = call("quickPicks") {
        val recos = PurpleMusicClient.fetchRecommendations(full = false)
            .map { it.toSong() }
            .filterNot { it.videoId in excludeSongIds }
        if (recos.isNotEmpty()) return@call recos
        PurpleMusicClient.listTracks()
            .map { it.toSong() }
            .filterNot { it.videoId in excludeSongIds }
            .take(20)
    }

    suspend fun moodAndGenres(): Result<List<MoodGenreSection>> = call("moods-and-genres") {
        PurpleMusicClient.buildMoodAndGenres()
    }

    suspend fun moodGenreShelves(browseId: String, params: String?): Result<List<HomeShelf>> {
        val key = "$browseId:${params.orEmpty()}"
        moodGenreShelfCache[key]?.let { return Result.success(it) }
        return call("mood-genre:$browseId") {
            PurpleMusicClient.buildMoodGenreShelves(browseId, params)
        }.also { result ->
            result.getOrNull()?.let { moodGenreShelfCache.getOrPut(key) { it } }
        }
    }

    suspend fun moodGenreArtwork(browseId: String, params: String?): Result<List<String>> =
        moodGenreShelves(browseId, params).map { shelves ->
            shelves.asSequence().flatMap { it.items.asSequence() }
                .mapNotNull(ShelfItem::thumbnailUrl)
                .distinct()
                .take(2)
                .toList()
        }

    fun withMoodGenreArtwork(
        sections: List<MoodGenreSection>,
        item: MoodGenre,
        artwork: List<String>,
    ): List<MoodGenreSection> =
        sections.map { section ->
            section.copy(
                items = section.items.map {
                    if (it.browseId == item.browseId && it.params == item.params) {
                        it.copy(thumbnailUrl = artwork.firstOrNull(), backThumbnailUrl = artwork.getOrNull(1))
                    } else {
                        it
                    }
                },
            )
        }

    data class SearchPage(
        val rows: List<SearchResult>,
        val continuation: String?,
    )

    suspend fun searchPage(
        query: String,
        filter: SearchFilter,
        @Suppress("UNUSED_PARAMETER") anonymous: Boolean = true,
    ): Result<SearchPage> = call("search:${filter.name}") {
        val rows = PurpleMusicClient.searchCatalogue(query, filter)
            .distinctBy { it.identityKey() }
        SearchPage(rows = rows, continuation = null)
    }

    suspend fun searchContinuation(
        @Suppress("UNUSED_PARAMETER") token: String,
        @Suppress("UNUSED_PARAMETER") filter: SearchFilter,
    ): Result<SearchPage> = call("search:continuation") {
        SearchPage(rows = emptyList(), continuation = null)
    }

    suspend fun search(query: String, filter: SearchFilter): Result<List<SearchResult>> =
        searchPage(query, filter).map { it.rows }

    private fun SearchResult.identityKey(): String = when (this) {
        is SearchResult.TopTrack -> "v:${song.videoId}"
        is SearchResult.Track -> "v:${song.videoId}"
        is SearchResult.Browse -> "b:${item.browseId}"
    }

    suspend fun searchSuggestions(input: String): Result<List<String>> = call("suggest") {
        PurpleMusicClient.searchSuggestions(input)
    }

    suspend fun searchTypeahead(input: String): Result<SearchPage> = call("typeahead:$input") {
        val rows = PurpleMusicClient.searchCatalogue(input, SearchFilter.ALL)
            .distinctBy { it.identityKey() }
            .take(12)
        SearchPage(rows = rows, continuation = null)
    }

    suspend fun resolveAudio(song: Song): Song = song

    suspend fun resolveVideo(@Suppress("UNUSED_PARAMETER") song: Song): Song? = null

    internal fun Song.isStillPictureUpload(): Boolean =
        STILL_PICTURE.containsMatchIn(title) && !SAYS_VIDEO.containsMatchIn(title)

    private val STILL_PICTURE = Regex(
        """\b(official\s+audio|audio\s+(song|track|only|jukebox)|full\s+audio|visuali[sz]er)\b|[(\[]\s*audio\s*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val SAYS_VIDEO = Regex("""\b(music\s+)?video\b""", RegexOption.IGNORE_CASE)

    suspend fun account(): Result<Account> = call("account") {
        PurpleMusicClient.currentAccountModel()
    }

    suspend fun accountChannels(): Result<List<AccountChannel>> = call("channels") {
        PurpleMusicClient.currentAccountChannels()
    }

    suspend fun library(): Result<LibraryPage> = call("library") {
        PurpleMusicClient.buildLibraryPage()
    }

    suspend fun syncLikedMusic(
        firstToken: String?,
        loadNext: suspend (String) -> SongPage? = { moreSongs(it).getOrNull() },
    ) {
        if (firstToken == null) return
        val seen = HashSet<String>()
        var next: String? = firstToken
        while (next != null && seen.add(next)) {
            val page = loadNext(next) ?: return
            LikeState.seedLiked(page.songs.mapTo(HashSet()) { it.videoId })
            next = page.continuation
        }
    }

    suspend fun radio(videoId: String): Result<List<Song>> = call("radio:$videoId") {
        PurpleMusicClient.buildRadioQueue(videoId)
    }

    suspend fun findArtistPageId(name: String): Result<String?> = call("artistByName:$name") {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) null else "UC$trimmed"
    }

    suspend fun trackLinks(videoId: String): Result<Song> = call("links:$videoId") {
        val cleanId = videoId.removePrefix("purple:").trim()
        val track = PurpleMusicClient.cachedTrack(cleanId)
            ?: PurpleMusicClient.listTracks().firstOrNull { it.id.toString() == cleanId }
            ?: error("Piste introuvable: $videoId")
        track.toSong()
    }

    data class SongPage(
        val songs: List<Song>,
        val continuation: String?,
        val suggested: List<Song> = emptyList(),
        val library: LibraryState? = null,
        val owned: Boolean? = null,
        val header: InnertubeParser.BrowseHeader? = null,
        val description: String? = null,
    )

    suspend fun browseSongs(browseId: String): Result<SongPage> = call("browse:$browseId") {
        val col = PurpleMusicClient.browseCollection(browseId)
        SongPage(
            songs = col.songs,
            continuation = null,
            suggested = emptyList(),
            library = col.library,
            owned = col.owned,
            header = InnertubeParser.BrowseHeader(
                title = col.title,
                subtitle = col.subtitle,
                thumbnailUrl = col.thumbnailUrl,
            ),
            description = col.description,
        )
    }

    suspend fun moreSongs(@Suppress("UNUSED_PARAMETER") token: String): Result<SongPage> = call("browse:more") {
        SongPage(songs = emptyList(), continuation = null)
    }

    suspend fun playlistOwned(browseId: String): Result<Boolean?> = call("owner:$browseId") {
        PurpleMusicClient.browseCollection(browseId).owned
    }

    suspend fun allSongs(browseId: String): Result<List<Song>> = call("all:$browseId") {
        PurpleMusicClient.browseCollection(browseId).songs.ifEmpty { error("No tracks here") }
    }

    const val MAX_PAGES = 10
    const val LIKED_MUSIC = "VLLM"
    const val PLAYLISTS_SHELF = "Playlists"

    suspend fun songMenu(videoId: String): Result<SongMenu> = call("menu:$videoId") {
        val cleanId = videoId.removePrefix("purple:").trim()
        val liked = LikeState.overrides.value[cleanId] == LikeStatus.LIKE
        SongMenu(
            likeStatus = if (liked) LikeStatus.LIKE else LikeStatus.INDIFFERENT,
            inLibrary = true,
            addToLibraryToken = null,
            removeFromLibraryToken = null,
        )
    }

    suspend fun rate(videoId: String, status: LikeStatus): Result<Unit> = call("rate:$videoId") {
        PurpleMusicClient.setRate(videoId, status).getOrThrow()
    }

    suspend fun setLibraryStatus(@Suppress("UNUSED_PARAMETER") token: String): Result<Unit> =
        call("library:feedback") {}

    suspend fun releaseLibraryState(@Suppress("UNUSED_PARAMETER") browseId: String): Result<LibraryState?> =
        call("library-state:$browseId") { null }

    suspend fun setSaved(
        @Suppress("UNUSED_PARAMETER") playlistId: String,
        @Suppress("UNUSED_PARAMETER") saved: Boolean,
    ): Result<Unit> = call("library:$playlistId") {}

    suspend fun setSubscribed(channelId: String, subscribed: Boolean): Result<Unit> =
        call("subscription:$channelId") {
            PurpleMusicClient.setArtistFollowed(channelId, subscribed).getOrThrow()
        }

    suspend fun userPlaylists(): Result<List<UserPlaylist>> = call("playlists") {
        val base = PurpleMusicClient.serverUrl
        val uid = PurpleMusicClient.userId
        val admin = PurpleMusicClient.isAdmin
        PurpleMusicClient.fetchPlaylists()
            .filter { admin || uid == 0 || it.creatorId == uid }
            .map { pl ->
                UserPlaylist(
                    playlistId = pl.id.toString(),
                    title = pl.name,
                    subtitle = "${pl.songIds.size} titres",
                    thumbnailUrl = pl.coverUrl(base),
                )
            }
    }

    suspend fun libraryPlaylists(): Result<List<ShelfItem>> = call("libraryPlaylists") {
        val base = PurpleMusicClient.serverUrl
        PurpleMusicClient.fetchPlaylists().map { pl ->
            ShelfItem(
                title = pl.name,
                subtitle = "${pl.songIds.size} titres",
                thumbnailUrl = pl.coverUrl(base),
                videoId = null,
                browseId = "VL${pl.id}",
            )
        }
    }

    suspend fun createPlaylist(
        title: String,
        privacy: PlaylistPrivacy,
        videoIds: List<String> = emptyList(),
    ): Result<String> = call("playlist:create") {
        PurpleMusicClient.createPlaylist(title, privacy, videoIds)
    }

    suspend fun addToPlaylist(
        playlistId: String,
        videoIds: List<String>,
    ): Result<Map<String, String>> = call("playlist:add") {
        PurpleMusicClient.addToPlaylist(playlistId, videoIds)
    }

    suspend fun removeFromPlaylist(
        playlistId: String,
        entries: List<Pair<String, String>>,
    ): Result<Unit> = call("playlist:remove") {
        PurpleMusicClient.removeFromPlaylist(playlistId, entries)
    }

    suspend fun playlistEntries(browseId: String): Result<List<Song>> = call("entries:$browseId") {
        PurpleMusicClient.browseCollection(browseId).songs
    }

    suspend fun reorderPlaylist(
        playlistId: String,
        current: List<String>,
        target: List<String>,
    ): Result<Unit> = call("playlist:reorder") {
        PurpleMusicClient.reorderPlaylist(playlistId, current, target)
    }

    suspend fun renamePlaylist(playlistId: String, title: String): Result<Unit> =
        call("playlist:rename") {
            PurpleMusicClient.renamePlaylist(playlistId, title)
        }

    suspend fun deletePlaylist(playlistId: String): Result<Unit> =
        call("playlist:delete") {
            PurpleMusicClient.deletePlaylist(playlistId)
        }

    suspend fun artistPage(browseId: String): Result<ArtistPage> = call("artist:$browseId") {
        PurpleMusicClient.buildArtistPage(browseId)
    }

    private suspend fun <T> call(label: String, block: suspend () -> T): Result<T> =
        withContext(ioDispatcher) {
            runCatching { block() }
                .onFailure { if (it is CancellationException) throw it }
                .onSuccess { Log.d(TAG, "$label ok") }
                .onFailure { Log.w(TAG, "$label failed: ${it.message}") }
        }
}
