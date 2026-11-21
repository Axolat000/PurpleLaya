package com.music.laya.data.purplemusic

import com.music.laya.data.DebugLog as Log
import com.music.laya.data.LikeState
import com.music.laya.data.innertube.Innertube
import com.music.laya.data.model.Account
import com.music.laya.data.model.AccountChannel
import com.music.laya.data.model.ArtistPage
import com.music.laya.data.model.ArtistRef
import com.music.laya.data.model.BrowseItem
import com.music.laya.data.model.BrowseType
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
import com.music.laya.data.model.SubscriptionState
import com.music.laya.data.model.UserPlaylist
import com.music.laya.platform.ioDispatcher
import com.music.laya.platform.ktorClient
import com.russhwolf.settings.Settings
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class PurpleAccount(
    val accountId: String,
    val serverUrl: String,
    val username: String,
    val password: String,
    val userId: Int = 0,
    val isAdmin: Boolean = false,
) {
    val displayHost: String
        get() = serverUrl
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
}

data class PurpleTrack(
    val id: Int,
    val title: String,
    val artist: String,
    val album: String?,
    val cover: String?,
    val genre: String,
    val playCount: Int,
    val durationSec: Int,
    val uploaderId: Int,
    val likeCount: Int,
    val filename: String?,
    val coverUrl: String,
    val streamUrl: String,
    val uploadDate: String? = null,
) {
    val isLossless: Boolean
        get() {
            val ext = filename?.substringAfterLast('.', "")?.lowercase().orEmpty()
            return ext == "flac" || ext == "wav"
        }

    val mimeType: String
        get() = when (filename?.substringAfterLast('.', "")?.lowercase()) {
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            else -> "audio/mpeg"
        }

    fun toSong(setVideoId: String? = null): Song {
        val artistsList = PurpleMusicClient.splitArtistNames(artist).map { name ->
            ArtistRef(name = name, browseId = "UC$name")
        }
        val leadArtistId = artistsList.firstOrNull()?.browseId ?: "UC$artist"
        val cleanAlbum = album?.trim()?.takeIf { it.isNotEmpty() }
        return Song(
            videoId = id.toString(),
            title = title.ifBlank { "Titre #$id" },
            artist = artist.ifBlank { "Artiste inconnu" },
            thumbnailUrl = coverUrl,
            durationText = PurpleMusicClient.formatDuration(durationSec),
            artistId = leadArtistId,
            artists = artistsList,
            albumId = cleanAlbum?.let { "MPREb_$it" },
            albumName = cleanAlbum,
            setVideoId = setVideoId ?: id.toString(),
            sourceQuality = if (isLossless) "LOSSLESS" else "HIGH",
        )
    }
}

data class PurplePlaylist(
    val id: Int,
    val name: String,
    val creatorId: Int,
    val creatorName: String,
    val songIds: List<Int>,
    val cover: String?,
    val isPrivate: Boolean,
    val shareToken: String? = null,
) {
    fun coverUrl(baseUrl: String, firstTrackId: Int? = songIds.firstOrNull()): String? {
        if (!cover.isNullOrBlank() && cover != "default.png") {
            return "${baseUrl}covers/$cover"
        }
        return firstTrackId?.let { "${baseUrl}api.php?action=cover&q=$it" }
    }
}

data class PurpleAlbum(
    val id: Int,
    val name: String,
    val artist: String,
    val year: Int?,
    val cover: String?,
    val trackCount: Int,
    val totalDuration: Int,
    val firstTrackId: Int? = null,
) {
    fun coverUrl(baseUrl: String): String? {
        if (!cover.isNullOrBlank() && cover != "default.png") {
            return "${baseUrl}covers/$cover"
        }
        return firstTrackId?.let { "${baseUrl}api.php?action=cover&q=$it" }
    }
}

data class PurpleDailyMix(
    val key: String,
    val title: String,
    val cover: String?,
    val count: Int,
    val tracks: List<PurpleTrack>,
)

data class PurpleLyrics(
    val synced: String?,
    val plain: String?,
    val found: Boolean,
)

/**
 * Multiplatform client for a self-hosted PurpleMusic server instance.
 *
 * Replaces YouTube Music / InnerTube as the single source of truth for
 * catalogue browsing, search, playlists, likes, recommendations, lyrics, and
 * direct audio streaming in PurpleLaya.
 */
object PurpleMusicClient {

    private const val TAG = "PurpleMusic"
    private const val KEY_SERVER_URL = "purple_server_url_v1"
    private const val KEY_USERNAME = "purple_username_v1"
    private const val KEY_PASSWORD = "purple_password_v1"
    private const val KEY_USER_ID = "purple_user_id_v1"
    private const val KEY_IS_ADMIN = "purple_is_admin_v1"
    private const val KEY_ACCOUNTS = "purple_accounts_v1"
    private const val KEY_ACTIVE_ACCOUNT = "purple_active_account_v1"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private var settings: Settings? = null

    fun attachSettings(s: Settings) {
        settings = s
        loadFromSettings()
    }

    private val http = ktorClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 20_000
        }
    }

    private val _serverUrl = MutableStateFlow("")
    val serverUrlFlow: StateFlow<String> = _serverUrl.asStateFlow()

    private val _username = MutableStateFlow("")
    val usernameFlow: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow("")
    private val _userId = MutableStateFlow(0)
    private val _isAdmin = MutableStateFlow(false)

    private val _accounts = MutableStateFlow<List<PurpleAccount>>(emptyList())
    val accountsFlow: StateFlow<List<PurpleAccount>> = _accounts.asStateFlow()

    private val _activeAccountId = MutableStateFlow<String?>(null)
    val activeAccountIdFlow: StateFlow<String?> = _activeAccountId.asStateFlow()

    private val _isSignedIn = MutableStateFlow(false)
    val isSignedInFlow: StateFlow<Boolean> = _isSignedIn.asStateFlow()

    /** Callback notified whenever the active PurpleMusic session changes. */
    var onSessionChanged: (() -> Unit)? = null

    val serverUrl: String get() = _serverUrl.value
    val username: String get() = _username.value
    val password: String get() = _password.value
    val userId: Int get() = _userId.value
    val isAdmin: Boolean get() = _isAdmin.value
    val isSignedIn: Boolean get() = _isSignedIn.value && _serverUrl.value.isNotBlank() && _username.value.isNotBlank()
    val hasServerConfigured: Boolean get() = _serverUrl.value.isNotBlank()

    // In-memory cache of the server's track catalogue for fast lookups, album/artist grouping, and stream resolution.
    @Volatile
    private var cachedTracks: List<PurpleTrack> = emptyList()

    @Volatile
    private var cachedTracksAtMs: Long = 0L
    private const val TRACKS_CACHE_TTL_MS = 30_000L

    @Volatile
    private var cachedMixes: List<PurpleDailyMix> = emptyList()

    init {
        loadFromSettings()
    }

    fun loadFromSettings() {
        val s = settings ?: return
        val savedAccountsRaw = s.getStringOrNull(KEY_ACCOUNTS)
        val parsedAccounts = savedAccountsRaw?.let {
            runCatching { json.decodeFromString(ListSerializer(PurpleAccount.serializer()), it) }.getOrNull()
        }.orEmpty()

        val activeId = s.getStringOrNull(KEY_ACTIVE_ACCOUNT)
        if (parsedAccounts.isNotEmpty()) {
            _accounts.value = parsedAccounts
            val active = parsedAccounts.firstOrNull { it.accountId == activeId } ?: parsedAccounts.first()
            applyAccount(active, persist = false)
            return
        }

        val url = s.getStringOrNull(KEY_SERVER_URL).orEmpty()
        val user = s.getStringOrNull(KEY_USERNAME).orEmpty()
        val pass = s.getStringOrNull(KEY_PASSWORD).orEmpty()
        val uid = s.getInt(KEY_USER_ID, 0)
        val admin = s.getBoolean(KEY_IS_ADMIN, false)
        if (url.isNotBlank()) {
            val normalized = normalizeServerUrl(url)
            _serverUrl.value = normalized
            _username.value = user
            _password.value = pass
            _userId.value = uid
            _isAdmin.value = admin
            _isSignedIn.value = user.isNotBlank() && pass.isNotBlank()
            if (_isSignedIn.value) {
                val acc = PurpleAccount(
                    accountId = accountIdFor(normalized, user),
                    serverUrl = normalized,
                    username = user,
                    password = pass,
                    userId = uid,
                    isAdmin = admin,
                )
                _accounts.value = listOf(acc)
                _activeAccountId.value = acc.accountId
            }
            syncInnertubeBridge()
        }
    }

    /**
     * Normalizes any server URL entered by the user:
     * - `music.example.com` -> `https://music.example.com/`
     * - `localhost:51837` -> `http://localhost:51837/`
     * - `https://music.example.com/api.php` -> `https://music.example.com/`
     * - `https://music.example.com/index.php` -> `https://music.example.com/`
     */
    fun normalizeServerUrl(raw: String): String {
        var u = raw.trim()
        if (u.isEmpty()) return ""
        if (!u.startsWith("http://", ignoreCase = true) && !u.startsWith("https://", ignoreCase = true)) {
            val hostPart = u.substringBefore('/').substringBefore(':').lowercase()
            val isLocal = hostPart == "localhost" ||
                hostPart == "127.0.0.1" ||
                hostPart == "10.0.2.2" ||
                hostPart.startsWith("192.168.") ||
                hostPart.startsWith("10.") ||
                hostPart.endsWith(".local")
            u = (if (isLocal) "http://" else "https://") + u
        }
        u = u.substringBefore('?').substringBefore('#')
        if (u.endsWith("/api.php", ignoreCase = true)) {
            u = u.dropLast("/api.php".length)
        } else if (u.endsWith("/index.php", ignoreCase = true)) {
            u = u.dropLast("/index.php".length)
        }
        u = u.trimEnd('/')
        return if (u.isEmpty()) "" else "$u/"
    }

    fun accountIdFor(serverUrl: String, username: String): String =
        "${normalizeServerUrl(serverUrl).lowercase()}|${username.trim().lowercase()}"

    /**
     * Configures the PurpleMusic server URL even before account sign-in
     * (since `api.php?action=list`, `stream`, `cover`, and public `playlists` work without login!).
     */
    fun setServerUrl(url: String) {
        val normalized = normalizeServerUrl(url)
        if (normalized == _serverUrl.value) return
        _serverUrl.value = normalized
        cachedTracks = emptyList()
        cachedTracksAtMs = 0L
        settings?.putString(KEY_SERVER_URL, normalized)
        syncInnertubeBridge()
        onSessionChanged?.invoke()
    }

    /**
     * Synthesizes an `Innertube.cookie` whenever a PurpleMusic account is active so
     * existing UI gates in Laya (`Innertube.cookie != null`, `AuthStore.hasApiSid`) treat
     * the session as authenticated without needing a Google cookie.
     */
    fun syntheticCookieFor(account: PurpleAccount? = activeAccount()): String? {
        val acc = account ?: if (isSignedIn) {
            PurpleAccount(
                accountId = accountIdFor(_serverUrl.value, _username.value),
                serverUrl = _serverUrl.value,
                username = _username.value,
                password = _password.value,
                userId = _userId.value,
                isAdmin = _isAdmin.value,
            )
        } else null
        acc ?: return null
        return "SAPISID=purplemusic_${acc.userId}_${acc.username.hashCode()}; PURPLE_URL=${acc.serverUrl}; PURPLE_USER=${acc.username}"
    }

    fun activeAccount(): PurpleAccount? {
        val id = _activeAccountId.value
        return _accounts.value.firstOrNull { it.accountId == id }
            ?: _accounts.value.firstOrNull()
    }

    private fun syncInnertubeBridge() {
        Innertube.cookie = if (isSignedIn) syntheticCookieFor() else null
    }

    private fun applyAccount(account: PurpleAccount, persist: Boolean = true) {
        val normalized = normalizeServerUrl(account.serverUrl)
        _serverUrl.value = normalized
        _username.value = account.username
        _password.value = account.password
        _userId.value = account.userId
        _isAdmin.value = account.isAdmin
        _activeAccountId.value = account.accountId
        _isSignedIn.value = account.username.isNotBlank() && account.password.isNotBlank()
        cachedTracks = emptyList()
        cachedTracksAtMs = 0L
        cachedMixes = emptyList()
        syncInnertubeBridge()
        if (persist) {
            persistState()
        }
        onSessionChanged?.invoke()
    }

    private fun persistState() {
        val s = settings ?: return
        s.putString(KEY_SERVER_URL, _serverUrl.value)
        s.putString(KEY_USERNAME, _username.value)
        s.putString(KEY_PASSWORD, _password.value)
        s.putInt(KEY_USER_ID, _userId.value)
        s.putBoolean(KEY_IS_ADMIN, _isAdmin.value)
        s.putString(KEY_ACTIVE_ACCOUNT, _activeAccountId.value.orEmpty())
        s.putString(
            KEY_ACCOUNTS,
            json.encodeToString(ListSerializer(PurpleAccount.serializer()), _accounts.value),
        )
    }

    fun selectAccount(accountId: String) {
        val target = _accounts.value.firstOrNull { it.accountId == accountId } ?: return
        applyAccount(target, persist = true)
    }

    fun removeAccount(accountId: String) {
        val remaining = _accounts.value.filterNot { it.accountId == accountId }
        _accounts.value = remaining
        val next = remaining.firstOrNull()
        if (next != null) {
            applyAccount(next, persist = true)
        } else {
            signOut()
        }
    }

    fun signOut() {
        _username.value = ""
        _password.value = ""
        _userId.value = 0
        _isAdmin.value = false
        _isSignedIn.value = false
        _activeAccountId.value = null
        _accounts.value = emptyList()
        cachedTracks = emptyList()
        cachedTracksAtMs = 0L
        cachedMixes = emptyList()
        LikeState.clear()
        syncInnertubeBridge()
        persistState()
        onSessionChanged?.invoke()
    }

    /**
     * Directly adopts credentials saved by platform stores (DesktopPersistence or Android AuthStore).
     */
    fun adoptSession(
        serverUrl: String,
        username: String,
        password: String,
        userId: Int = 0,
        isAdmin: Boolean = false,
    ) {
        val normalized = normalizeServerUrl(serverUrl)
        if (normalized.isBlank()) return
        if (username.isBlank() || password.isBlank()) {
            setServerUrl(normalized)
            return
        }
        val id = accountIdFor(normalized, username)
        val account = PurpleAccount(
            accountId = id,
            serverUrl = normalized,
            username = username.trim(),
            password = password,
            userId = userId,
            isAdmin = isAdmin,
        )
        val updated = _accounts.value.filterNot { it.accountId == id } + account
        _accounts.value = updated
        applyAccount(account, persist = true)
    }

    // ── Authentication & Server Discovery ────────────────────────────────────

    data class AuthOutcome(
        val account: PurpleAccount,
        val termsAccepted: Boolean = true,
        val termsUrl: String? = null,
    )

    /**
     * Logs in against `<serverUrl>/api.php?action=login`.
     * If `terms_accepted == false` comes back from the server, automatically calls `accept_terms`
     * when [autoAcceptTerms] is true.
     */
    suspend fun login(
        rawServerUrl: String,
        rawUsername: String,
        rawPassword: String,
        autoAcceptTerms: Boolean = true,
    ): Result<AuthOutcome> = withContext(ioDispatcher) {
        runCatching {
            val base = normalizeServerUrl(rawServerUrl)
            require(base.isNotBlank()) { "Veuillez entrer l'URL du serveur PurpleMusic" }
            val user = rawUsername.trim()
            require(user.isNotBlank()) { "Veuillez entrer votre nom d'utilisateur" }
            require(rawPassword.isNotEmpty()) { "Veuillez entrer votre mot de passe" }

            val responseText = http.post("${base}api.php?action=login") {
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("username", user)
                            append("password", rawPassword)
                        },
                    ),
                )
            }.bodyAsText()

            val obj = parseJsonObject(responseText)
                ?: error("Réponse invalide du serveur PurpleMusic ($base). Vérifiez l'URL.")
            val status = obj.str("status")
            if (status != "success") {
                val msg = obj.str("message") ?: "Identifiants invalides"
                error(msg)
            }

            val uid = obj.int("user_id") ?: 0
            val resolvedUser = obj.str("username")?.ifBlank { user } ?: user
            val admin = obj.bool("is_admin") ?: false
            val termsAccepted = obj.bool("terms_accepted") ?: true
            val termsUrl = obj.str("terms_url")

            val account = PurpleAccount(
                accountId = accountIdFor(base, resolvedUser),
                serverUrl = base,
                username = resolvedUser,
                password = rawPassword,
                userId = uid,
                isAdmin = admin,
            )

            val updated = _accounts.value.filterNot { it.accountId == account.accountId } + account
            _accounts.value = updated
            applyAccount(account, persist = true)

            if (!termsAccepted && autoAcceptTerms) {
                runCatching { acceptTerms() }
            }

            // Warm likes right after login
            runCatching { fetchLikedIds() }

            AuthOutcome(
                account = account,
                termsAccepted = termsAccepted || autoAcceptTerms,
                termsUrl = termsUrl,
            )
        }
    }

    /**
     * Registers a new account on `<serverUrl>/api.php?action=register` and immediately logs in.
     */
    suspend fun register(
        rawServerUrl: String,
        rawUsername: String,
        rawPassword: String,
        acceptTerms: Boolean = true,
    ): Result<AuthOutcome> = withContext(ioDispatcher) {
        runCatching {
            val base = normalizeServerUrl(rawServerUrl)
            require(base.isNotBlank()) { "Veuillez entrer l'URL du serveur PurpleMusic" }
            val user = rawUsername.trim()
            require(user.isNotBlank()) { "Veuillez entrer un nom d'utilisateur" }
            require(rawPassword.length >= 6) { "Mot de passe trop court (6 caractères min)" }

            val responseText = http.post("${base}api.php?action=register") {
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("username", user)
                            append("password", rawPassword)
                            if (acceptTerms) append("accept_terms", "1")
                        },
                    ),
                )
            }.bodyAsText()

            val obj = parseJsonObject(responseText)
                ?: error("Réponse invalide du serveur PurpleMusic ($base). Vérifiez l'URL.")
            if (obj.str("status") != "success") {
                error(obj.str("message") ?: "Impossible de créer le compte")
            }

            login(base, user, rawPassword, autoAcceptTerms = acceptTerms).getOrThrow()
        }
    }

    suspend fun acceptTerms(): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val obj = postActionObject("accept_terms")
            if (obj.str("status") != "success") {
                error(obj.str("message") ?: "Impossible d'accepter les CGU")
            }
        }
    }

    /**
     * Probes whether `<rawServerUrl>/api.php?action=list` responds with a valid JSON track array.
     */
    suspend fun probeServer(rawServerUrl: String): Result<Int> = withContext(ioDispatcher) {
        runCatching {
            val base = normalizeServerUrl(rawServerUrl)
            require(base.isNotBlank()) { "URL du serveur vide" }
            val text = http.get("${base}api.php?action=list").bodyAsText()
            val arr = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull()
                ?: error("Ce serveur ne répond pas comme une instance PurpleMusic (${base}api.php)")
            arr.size
        }
    }

    // ── Core HTTP Helpers ────────────────────────────────────────────────────

    private fun requireBaseUrl(): String {
        val base = _serverUrl.value
        if (base.isBlank()) {
            error("Aucun serveur PurpleMusic configuré. Cliquez sur le bouton Compte pour configurer l'URL du serveur.")
        }
        return base
    }

    private suspend fun postActionText(
        action: String,
        queryParams: Map<String, String> = emptyMap(),
        formFields: Map<String, String> = emptyMap(),
        extraListFields: List<Pair<String, String>> = emptyList(),
    ): String {
        val base = requireBaseUrl()
        return http.post("${base}api.php") {
            parameter("action", action)
            queryParams.forEach { (k, v) -> parameter(k, v) }
            if (_username.value.isNotBlank()) {
                header("X-Purple-User", _username.value)
                header("X-Purple-Pass", _password.value)
            }
            setBody(
                FormDataContent(
                    Parameters.build {
                        if (_username.value.isNotBlank()) {
                            append("username", _username.value)
                            append("password", _password.value)
                        }
                        formFields.forEach { (k, v) -> append(k, v) }
                        extraListFields.forEach { (k, v) -> append(k, v) }
                    },
                ),
            )
        }.bodyAsText()
    }

    private suspend fun postActionElement(
        action: String,
        queryParams: Map<String, String> = emptyMap(),
        formFields: Map<String, String> = emptyMap(),
        extraListFields: List<Pair<String, String>> = emptyList(),
    ): JsonElement {
        val raw = postActionText(action, queryParams, formFields, extraListFields)
        return runCatching { json.parseToJsonElement(raw) }.getOrElse { e ->
            Log.w(TAG, "Invalid JSON from action=$action: ${raw.take(200)}")
            throw IllegalStateException("Réponse invalide du serveur PurpleMusic pour '$action'", e)
        }
    }

    private suspend fun postActionObject(
        action: String,
        queryParams: Map<String, String> = emptyMap(),
        formFields: Map<String, String> = emptyMap(),
        extraListFields: List<Pair<String, String>> = emptyList(),
    ): JsonObject = postActionElement(action, queryParams, formFields, extraListFields).jsonObject

    private suspend fun postActionArray(
        action: String,
        queryParams: Map<String, String> = emptyMap(),
        formFields: Map<String, String> = emptyMap(),
    ): JsonArray = postActionElement(action, queryParams, formFields).jsonArray

    // ── Tracks & Streaming ───────────────────────────────────────────────────

    fun streamUrlFor(trackId: String, maxKbps: Int = Int.MAX_VALUE): String {
        val cleanId = trackId.removePrefix("purple:").trim()
        // If already a full HTTP URL, return as-is
        if (cleanId.startsWith("http://") || cleanId.startsWith("https://")) return cleanId
        val base = requireBaseUrl()
        return "${base}api.php?action=stream&q=$cleanId"
    }

    fun coverUrlFor(trackId: String): String {
        val cleanId = trackId.removePrefix("purple:").trim()
        val base = _serverUrl.value
        if (base.isBlank()) return ""
        return "${base}api.php?action=cover&q=$cleanId"
    }

    fun cachedTrack(trackId: String): PurpleTrack? {
        val id = trackId.removePrefix("purple:").trim().toIntOrNull() ?: return null
        return cachedTracks.firstOrNull { it.id == id }
    }

    suspend fun listTracks(forceRefresh: Boolean = false): List<PurpleTrack> = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedTracks.isNotEmpty() && (now - cachedTracksAtMs) < TRACKS_CACHE_TTL_MS) {
            return@withContext cachedTracks
        }
        val base = requireBaseUrl()
        val arr = postActionArray("list")
        val parsed = arr.mapNotNull { el ->
            (el as? JsonObject)?.toPurpleTrack(base)
        }
        cachedTracks = parsed
        cachedTracksAtMs = now
        parsed
    }

    fun invalidateTracksCache() {
        cachedTracksAtMs = 0L
    }

    private fun JsonObject.toPurpleTrack(baseUrl: String): PurpleTrack? {
        val id = int("id") ?: return null
        val title = str("title")?.let(::decodeHtmlEntities) ?: "Titre #$id"
        val artist = str("artist")?.let(::decodeHtmlEntities) ?: "Artiste inconnu"
        val album = str("album")?.let(::decodeHtmlEntities)?.takeIf { it.isNotBlank() }
        val cover = str("cover")
        val genre = str("genre")?.let(::decodeHtmlEntities) ?: "Autre"
        val playCount = int("play_count") ?: int("plays") ?: 0
        val duration = int("duration") ?: 0
        val uploaderId = int("uploader_id") ?: 0
        val likeCount = int("like_count") ?: 0
        val filename = str("filename")
        val coverUrl = str("cover_url")?.takeIf { it.isNotBlank() }
            ?: "${baseUrl}api.php?action=cover&q=$id"
        val streamUrl = str("stream_url")?.takeIf { it.isNotBlank() }
            ?: "${baseUrl}api.php?action=stream&q=$id"
        val uploadDate = str("upload_date")
        return PurpleTrack(
            id = id,
            title = title,
            artist = artist,
            album = album,
            cover = cover,
            genre = genre,
            playCount = playCount,
            durationSec = duration,
            uploaderId = uploaderId,
            likeCount = likeCount,
            filename = filename,
            coverUrl = coverUrl,
            streamUrl = streamUrl,
            uploadDate = uploadDate,
        )
    }

    // ── Likes & Recommendations ──────────────────────────────────────────────

    suspend fun fetchLikedIds(): Set<String> = withContext(ioDispatcher) {
        if (!isSignedIn) return@withContext emptySet()
        val obj = runCatching { postActionObject("my_likes") }.getOrNull() ?: return@withContext emptySet()
        val arr = obj["liked_ids"] as? JsonArray ?: return@withContext emptySet()
        val ids = arr.mapNotNullTo(HashSet()) { it.jsonPrimitive.intOrNull?.toString() ?: it.jsonPrimitive.contentOrNull }
        LikeState.seedLiked(ids)
        ids
    }

    suspend fun setRate(videoId: String, status: LikeStatus): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val cleanId = videoId.removePrefix("purple:").trim()
            val currentlyLiked = LikeState.overrides.value[cleanId] == LikeStatus.LIKE
            val wantsLike = status == LikeStatus.LIKE
            if (currentlyLiked != wantsLike) {
                val obj = postActionObject("toggle_like", formFields = mapOf("track_id" to cleanId))
                if (obj.str("status") != "success") {
                    error(obj.str("message") ?: "Impossible de modifier le like")
                }
                val likedNow = obj.bool("liked") ?: wantsLike
                LikeState.set(cleanId, if (likedNow) LikeStatus.LIKE else LikeStatus.INDIFFERENT)
            }
        }
    }

    suspend fun fetchRecommendations(full: Boolean = false): List<PurpleTrack> = withContext(ioDispatcher) {
        val base = requireBaseUrl()
        if (!isSignedIn) {
            return@withContext listTracks().take(if (full) Int.MAX_VALUE else 20)
        }
        val element = runCatching {
            postActionElement(
                action = "recommendations",
                queryParams = if (full) mapOf("full" to "1") else emptyMap(),
                formFields = if (full) mapOf("full" to "1") else emptyMap(),
            )
        }.getOrNull()
        val arr = element as? JsonArray
        if (arr != null) {
            val tracks = arr.mapNotNull { (it as? JsonObject)?.toPurpleTrack(base) }
            if (tracks.isNotEmpty()) return@withContext tracks
        }
        listTracks().take(if (full) Int.MAX_VALUE else 20)
    }

    suspend fun fetchDailyMixes(): List<PurpleDailyMix> = withContext(ioDispatcher) {
        if (!isSignedIn) return@withContext emptyList()
        val base = requireBaseUrl()
        val obj = runCatching { postActionObject("daily_mixes") }.getOrNull() ?: return@withContext emptyList()
        val mixesArr = obj["mixes"] as? JsonArray ?: return@withContext emptyList()
        val parsed = mixesArr.mapNotNull { item ->
            val m = item as? JsonObject ?: return@mapNotNull null
            val key = m.str("key") ?: return@mapNotNull null
            val title = m.str("title") ?: key
            val cover = m.str("cover")
            val tracks = (m["tracks"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.toPurpleTrack(base) }
                .orEmpty()
            if (tracks.isEmpty()) return@mapNotNull null
            PurpleDailyMix(
                key = key,
                title = title,
                cover = cover,
                count = tracks.size,
                tracks = tracks,
            )
        }
        cachedMixes = parsed
        parsed
    }

    suspend fun fetchServerTop(): List<PurpleTrack> = withContext(ioDispatcher) {
        if (!isSignedIn) return@withContext emptyList()
        val base = requireBaseUrl()
        val obj = runCatching { postActionObject("server_top") }.getOrNull() ?: return@withContext emptyList()
        val tracksArr = obj["tracks"] as? JsonArray ?: return@withContext emptyList()
        tracksArr.mapNotNull { (it as? JsonObject)?.toPurpleTrack(base) }
    }

    // ── Playlists ────────────────────────────────────────────────────────────

    suspend fun fetchPlaylists(): List<PurplePlaylist> = withContext(ioDispatcher) {
        val el = runCatching { postActionElement("playlists") }.getOrNull() ?: return@withContext emptyList()
        val arr = el as? JsonArray ?: return@withContext emptyList()
        arr.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.int("id") ?: return@mapNotNull null
            val name = obj.str("name")?.let(::decodeHtmlEntities) ?: "Playlist #$id"
            val creatorId = obj.int("creator_id") ?: 0
            val creator = obj.str("creator")?.let(::decodeHtmlEntities) ?: ""
            val songIds = obj.str("song_ids")
                .orEmpty()
                .split(',')
                .mapNotNull { it.trim().toIntOrNull()?.takeIf { n -> n > 0 } }
            val cover = obj.str("cover")
            val isPrivate = (obj.int("is_private") ?: 0) != 0
            val shareToken = obj.str("share_token")
            PurplePlaylist(
                id = id,
                name = name,
                creatorId = creatorId,
                creatorName = creator,
                songIds = songIds,
                cover = cover,
                isPrivate = isPrivate,
                shareToken = shareToken,
            )
        }
    }

    suspend fun fetchPlaylistTracks(songIds: List<Int>): List<PurpleTrack> = withContext(ioDispatcher) {
        if (songIds.isEmpty()) return@withContext emptyList()
        val base = requireBaseUrl()
        val csv = songIds.joinToString(",")
        val arr = runCatching {
            postActionArray("get_playlist_tracks", queryParams = mapOf("q" to csv))
        }.getOrNull()
        if (arr != null) {
            return@withContext arr.mapNotNull { (it as? JsonObject)?.toPurpleTrack(base) }
        }
        // Fallback: resolve from listTracks() in order
        val byId = listTracks().associateBy { it.id }
        songIds.mapNotNull { byId[it] }
    }

    suspend fun createPlaylist(
        title: String,
        privacy: PlaylistPrivacy,
        videoIds: List<String>,
    ): String = withContext(ioDispatcher) {
        val cleanIds = videoIds.mapNotNull { it.removePrefix("purple:").trim().toIntOrNull()?.takeIf { n -> n > 0 } }
        val isPrivate = if (privacy == PlaylistPrivacy.PRIVATE) "1" else "0"

        // Try playlist_save first (creates playlist with title, privacy and initial tracks in one shot)
        val saveRes = runCatching {
            postActionObject(
                action = "playlist_save",
                formFields = buildMap {
                    put("playlist_name", title)
                    if (isPrivate == "1") put("is_private", "1")
                },
                extraListFields = cleanIds.map { "selected_songs[]" to it.toString() },
            )
        }.getOrNull()

        if (saveRes?.str("status") != "success") {
            // Fallback to legacy playlist_create + playlist_mod
            val createRes = postActionObject(
                action = "playlist_create",
                formFields = mapOf("name" to title),
            )
            if (createRes.str("status") != "success") {
                error(createRes.str("message") ?: "Impossible de créer la playlist")
            }
            val created = fetchPlaylists().lastOrNull { it.name == title }
                ?: fetchPlaylists().maxByOrNull { it.id }
                ?: error("Playlist créée mais introuvable")
            for (tid in cleanIds) {
                postActionObject(
                    action = "playlist_mod",
                    formFields = mapOf(
                        "playlist_id" to created.id.toString(),
                        "mode" to "add",
                        "track_id" to tid.toString(),
                    ),
                )
            }
            return@withContext created.id.toString()
        }

        val created = fetchPlaylists().lastOrNull { it.name == title }
            ?: fetchPlaylists().maxByOrNull { it.id }
        created?.id?.toString() ?: "1"
    }

    suspend fun addToPlaylist(playlistId: String, videoIds: List<String>): Map<String, String> =
        withContext(ioDispatcher) {
            val pid = playlistId.removePrefix("VL").trim()
            val out = LinkedHashMap<String, String>()
            for (vid in videoIds) {
                val tid = vid.removePrefix("purple:").trim()
                val res = postActionObject(
                    action = "playlist_mod",
                    formFields = mapOf(
                        "playlist_id" to pid,
                        "mode" to "add",
                        "track_id" to tid,
                    ),
                )
                if (res.str("status") == "success") {
                    out[vid] = tid
                }
            }
            out
        }

    suspend fun removeFromPlaylist(playlistId: String, entries: List<Pair<String, String>>) =
        withContext(ioDispatcher) {
            val pid = playlistId.removePrefix("VL").trim()
            for ((setVideoId, videoId) in entries) {
                val tid = videoId.ifBlank { setVideoId }.substringAfterLast('_').removePrefix("purple:").trim()
                postActionObject(
                    action = "playlist_mod",
                    formFields = mapOf(
                        "playlist_id" to pid,
                        "mode" to "remove",
                        "track_id" to tid,
                    ),
                )
            }
        }

    suspend fun reorderPlaylist(playlistId: String, current: List<String>, target: List<String>) =
        withContext(ioDispatcher) {
            val pid = playlistId.removePrefix("VL").trim()
            val trackIds = target.mapNotNull { entry ->
                entry.substringAfterLast('_').removePrefix("purple:").trim().toIntOrNull()?.takeIf { it > 0 }
            }
            if (trackIds.isEmpty()) return@withContext
            val res = postActionObject(
                action = "playlist_reorder",
                formFields = mapOf(
                    "playlist_id" to pid,
                    "song_ids" to trackIds.joinToString(","),
                ),
            )
            if (res.str("status") != "success") {
                error(res.str("message") ?: "Impossible de réordonner la playlist")
            }
        }

    suspend fun renamePlaylist(playlistId: String, title: String) = withContext(ioDispatcher) {
        val pid = playlistId.removePrefix("VL").trim()
        val res = postActionObject(
            action = "playlist_mod",
            formFields = mapOf(
                "playlist_id" to pid,
                "mode" to "rename",
                "new_name" to title,
            ),
        )
        if (res.str("status") != "success") {
            error(res.str("message") ?: "Impossible de renommer la playlist")
        }
    }

    suspend fun deletePlaylist(playlistId: String) = withContext(ioDispatcher) {
        val pid = playlistId.removePrefix("VL").trim()
        val res = runCatching {
            postActionObject("delete_playlist", formFields = mapOf("playlist_id" to pid))
        }.getOrNull()
        if (res?.str("status") != "success") {
            val fallback = postActionObject(
                action = "playlist_mod",
                formFields = mapOf("playlist_id" to pid, "mode" to "delete"),
            )
            if (fallback.str("status") != "success") {
                error(fallback.str("message") ?: "Impossible de supprimer la playlist")
            }
        }
    }

    // ── Albums & Artists ─────────────────────────────────────────────────────

    suspend fun fetchAlbums(): List<PurpleAlbum> = withContext(ioDispatcher) {
        val tracks = runCatching { listTracks() }.getOrDefault(emptyList())
        val serverAlbums = if (isSignedIn) {
            runCatching {
                val obj = postActionObject("albums")
                val arr = obj["albums"] as? JsonArray ?: JsonArray(emptyList())
                arr.mapNotNull { item ->
                    val a = item as? JsonObject ?: return@mapNotNull null
                    val name = a.str("name")?.let(::decodeHtmlEntities)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val matchingTracks = tracks.filter { it.album?.equals(name, ignoreCase = true) == true }
                    PurpleAlbum(
                        id = a.int("id") ?: name.lowercase().hashCode(),
                        name = name,
                        artist = a.str("artist")?.let(::decodeHtmlEntities)?.ifBlank {
                            matchingTracks.firstOrNull()?.artist.orEmpty()
                        } ?: matchingTracks.firstOrNull()?.artist.orEmpty(),
                        year = a.int("year"),
                        cover = a.str("cover"),
                        trackCount = a.int("track_count") ?: matchingTracks.size,
                        totalDuration = a.int("total_duration") ?: matchingTracks.sumOf { it.durationSec },
                        firstTrackId = matchingTracks.firstOrNull()?.id,
                    )
                }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        // Also include any albums present on track tags that weren't in the albums table yet
        val byLowerName = LinkedHashMap<String, PurpleAlbum>()
        serverAlbums.forEach { byLowerName[it.name.lowercase()] = it }

        tracks.groupBy { it.album?.trim()?.takeIf(String::isNotEmpty) }
            .forEach { (albumName, albumTracks) ->
                if (albumName == null) return@forEach
                val key = albumName.lowercase()
                if (!byLowerName.containsKey(key)) {
                    byLowerName[key] = PurpleAlbum(
                        id = key.hashCode(),
                        name = albumName,
                        artist = albumTracks.firstOrNull()?.artist ?: "",
                        year = null,
                        cover = albumTracks.firstOrNull()?.cover,
                        trackCount = albumTracks.size,
                        totalDuration = albumTracks.sumOf { it.durationSec },
                        firstTrackId = albumTracks.firstOrNull()?.id,
                    )
                }
            }

        byLowerName.values.filter { it.trackCount > 0 }.sortedBy { it.name.lowercase() }
    }

    suspend fun fetchArtistBio(artistName: String): String? = withContext(ioDispatcher) {
        if (!isSignedIn || artistName.isBlank()) return@withContext null
        val obj = runCatching {
            postActionObject("artist_bio", queryParams = mapOf("name" to artistName, "lang" to "fr"))
        }.getOrNull() ?: return@withContext null
        obj.str("extract")?.takeIf { it.isNotBlank() }
    }

    suspend fun fetchFollowedArtists(): Set<String> = withContext(ioDispatcher) {
        if (!isSignedIn) return@withContext emptySet()
        val obj = runCatching { postActionObject("my_follows") }.getOrNull() ?: return@withContext emptySet()
        val arr = obj["artists"] as? JsonArray ?: return@withContext emptySet()
        arr.mapNotNullTo(HashSet()) { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
    }

    suspend fun setArtistFollowed(artistName: String, followed: Boolean): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val clean = artistName.removePrefix("UC").trim()
            val action = if (followed) "follow_artist" else "unfollow_artist"
            val obj = postActionObject(action, formFields = mapOf("artist" to clean))
            if (obj.str("status") != "success") {
                error(obj.str("message") ?: "Impossible de modifier l'abonnement à l'artiste")
            }
        }
    }

    // ── Lyrics & Playback Tracking ───────────────────────────────────────────

    suspend fun fetchLyrics(trackId: String): PurpleLyrics? = withContext(ioDispatcher) {
        if (!isSignedIn) return@withContext null
        val cleanId = trackId.removePrefix("purple:").trim()
        if (cleanId.toIntOrNull() == null) return@withContext null
        val obj = runCatching {
            postActionObject("get_lyrics", queryParams = mapOf("q" to cleanId))
        }.getOrNull() ?: return@withContext null
        val synced = obj.str("synced")?.takeIf { it.isNotBlank() }
        val plain = obj.str("plain")?.takeIf { it.isNotBlank() }
        val found = obj.bool("found") ?: (synced != null || plain != null)
        PurpleLyrics(synced = synced, plain = plain, found = found)
    }

    suspend fun reportListen(trackId: String, seconds: Long): Result<Boolean> = withContext(ioDispatcher) {
        runCatching {
            if (!isSignedIn) return@runCatching false
            val cleanId = trackId.removePrefix("purple:").trim()
            if (cleanId.toIntOrNull() == null) return@runCatching false
            val obj = postActionObject(
                action = "report_listen",
                formFields = mapOf(
                    "track_id" to cleanId,
                    "seconds" to seconds.coerceAtLeast(0L).toString(),
                ),
            )
            if (obj.str("status") == "success") {
                invalidateTracksCache()
                obj.bool("counted") ?: (seconds >= 10)
            } else {
                false
            }
        }
    }

    // ── Higher-Level YtMusicRepository Adapter Methods ───────────────────────

    suspend fun buildHomeFeed(): HomeFeed = withContext(ioDispatcher) {
        val base = requireBaseUrl()
        val tracks = listTracks()
        if (isSignedIn) {
            runCatching { fetchLikedIds() }
        }
        val shelves = mutableListOf<HomeShelf>()

        // 1. Recommended for you
        val recos = runCatching { fetchRecommendations(full = false) }.getOrDefault(emptyList())
        if (recos.isNotEmpty()) {
            shelves += HomeShelf(
                title = "Recommandé pour toi",
                subtitle = "Pour vous",
                items = recos.take(20).map { it.toShelfItem() },
                moreBrowseId = "VLreco",
            )
        }

        // 2. Daily Mixes
        val mixes = runCatching { fetchDailyMixes() }.getOrDefault(emptyList())
        if (mixes.isNotEmpty()) {
            shelves += HomeShelf(
                title = "Mix du jour",
                subtitle = "Sélections par genre",
                items = mixes.map { mix ->
                    val coverUrl = mix.tracks.firstOrNull()?.coverUrl
                        ?: mix.cover?.let { "${base}covers/$it" }
                    ShelfItem(
                        title = "Mix ${mix.title}",
                        subtitle = "${mix.count} titres",
                        thumbnailUrl = coverUrl,
                        videoId = null,
                        browseId = "VLmix:${mix.key}",
                    )
                },
            )
        }

        // 3. Recently Added (sorted by id DESC)
        val recentAdds = tracks.sortedByDescending { it.id }.take(20)
        if (recentAdds.isNotEmpty()) {
            shelves += HomeShelf(
                title = "Ajouts récents",
                subtitle = "Derniers titres sur le serveur",
                items = recentAdds.map { it.toShelfItem() },
                moreBrowseId = "VLrecent",
            )
        }

        // 4. Server Top / Most Played
        val serverTop = runCatching { fetchServerTop() }.getOrDefault(emptyList())
            .ifEmpty { tracks.sortedByDescending { it.playCount }.take(20) }
        if (serverTop.isNotEmpty()) {
            shelves += HomeShelf(
                title = "Les plus écoutés",
                subtitle = "Tendances du serveur",
                items = serverTop.map { it.toShelfItem() },
                moreBrowseId = "VLtop",
            )
        }

        // 5. Playlists
        val playlists = runCatching { fetchPlaylists() }.getOrDefault(emptyList())
        if (playlists.isNotEmpty()) {
            shelves += HomeShelf(
                title = "Playlists",
                subtitle = "Mix & sélections",
                items = playlists.map { pl ->
                    ShelfItem(
                        title = pl.name,
                        subtitle = buildString {
                            if (pl.creatorName.isNotBlank()) append("Par ${pl.creatorName} • ")
                            append("${pl.songIds.size} titres")
                        },
                        thumbnailUrl = pl.coverUrl(base),
                        videoId = null,
                        browseId = "VL${pl.id}",
                    )
                },
            )
        }

        // 6. Albums
        val albums = runCatching { fetchAlbums() }.getOrDefault(emptyList())
        if (albums.isNotEmpty()) {
            shelves += HomeShelf(
                title = "Albums",
                subtitle = "Catalogue complet",
                items = albums.map { album ->
                    ShelfItem(
                        title = album.name,
                        subtitle = listOfNotNull(
                            album.artist.takeIf { it.isNotBlank() },
                            album.year?.toString(),
                        ).joinToString(" • ").ifBlank { "${album.trackCount} titres" },
                        thumbnailUrl = album.coverUrl(base),
                        videoId = null,
                        browseId = "MPREb_${album.name}",
                    )
                },
            )
        }

        // 7. Artists
        val artistItems = aggregateArtists(tracks)
        if (artistItems.isNotEmpty()) {
            shelves += HomeShelf(
                title = "Artistes",
                subtitle = "Artistes de la bibliothèque",
                items = artistItems.take(24).map { a ->
                    ShelfItem(
                        title = a.name,
                        subtitle = "${a.count} titre${if (a.count > 1) "s" else ""}",
                        thumbnailUrl = a.coverUrl,
                        videoId = null,
                        browseId = "UC${a.name}",
                    )
                },
            )
        }

        HomeFeed(shelves = shelves, continuation = null)
    }

    suspend fun buildRecentlyPlayedShelf(): HomeShelf? = withContext(ioDispatcher) {
        val tracks = runCatching { listTracks() }.getOrDefault(emptyList())
        if (tracks.isEmpty()) return@withContext null
        val topOrRecent = tracks.sortedByDescending { it.playCount }.take(20)
        HomeShelf(
            title = "Recents",
            items = topOrRecent.map { it.toShelfItem() },
        )
    }

    suspend fun buildMoodAndGenres(): List<MoodGenreSection> = withContext(ioDispatcher) {
        val tracks = listTracks()
        val genreGroups = LinkedHashMap<String, MutableList<PurpleTrack>>()
        val genreDisplayNames = LinkedHashMap<String, String>()

        for (t in tracks) {
            for (g in splitGenres(t.genre)) {
                val key = g.lowercase()
                genreDisplayNames.putIfAbsent(key, g)
                genreGroups.getOrPut(key) { mutableListOf() }.add(t)
            }
        }

        val palette = listOf(
            0xFF9C27B0L, // Purple
            0xFF7C4DFFL, // Deep Purple
            0xFFE91E63L, // Pink
            0xFF3F51B5L, // Indigo
            0xFF00BCD4L, // Cyan
            0xFF009688L, // Teal
            0xFF4CAF50L, // Green
            0xFFFF9800L, // Orange
            0xFFFF5722L, // Deep Orange
        )

        val items = genreGroups.entries
            .sortedByDescending { it.value.size }
            .mapIndexed { idx, (key, list) ->
                val covers = list.mapNotNull { it.coverUrl.takeIf(String::isNotBlank) }.distinct()
                MoodGenre(
                    title = genreDisplayNames[key] ?: key,
                    browseId = "genre:$key",
                    params = genreDisplayNames[key] ?: key,
                    thumbnailUrl = covers.firstOrNull(),
                    backThumbnailUrl = covers.getOrNull(1),
                    stripeColor = palette[idx % palette.size],
                )
            }

        if (items.isEmpty()) emptyList()
        else listOf(MoodGenreSection(title = "Genres", items = items))
    }

    suspend fun buildMoodGenreShelves(browseId: String, params: String?): List<HomeShelf> =
        withContext(ioDispatcher) {
            val genreName = params?.takeIf { it.isNotBlank() }
                ?: browseId.removePrefix("genre:").removePrefix("VLgenre:")
            val tracks = listTracks().filter { t ->
                splitGenres(t.genre).any { it.equals(genreName, ignoreCase = true) }
            }
            if (tracks.isEmpty()) return@withContext emptyList()
            listOf(
                HomeShelf(
                    title = genreName,
                    subtitle = "${tracks.size} titres",
                    items = tracks.map { it.toShelfItem() },
                ),
            )
        }

    suspend fun searchCatalogue(query: String, filter: SearchFilter): List<SearchResult> =
        withContext(ioDispatcher) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            val base = requireBaseUrl()

            // Try server-side search endpoint first when signed in
            val serverResults = if (isSignedIn) {
                runCatching {
                    val obj = postActionObject(
                        action = "search",
                        queryParams = mapOf("q" to q, "limit" to "80", "offset" to "0"),
                    )
                    if (obj.str("status") == "success" && obj["tracks"] is JsonObject) obj else null
                }.getOrNull()
            } else null

            val matchingTracks: List<PurpleTrack>
            val matchingArtists: List<ArtistSummary>
            val matchingAlbums: List<PurpleAlbum>

            if (serverResults != null) {
                val tracksObj = serverResults["tracks"] as? JsonObject
                val itemsArr = tracksObj?.get("items") as? JsonArray ?: JsonArray(emptyList())
                matchingTracks = itemsArr.mapNotNull { (it as? JsonObject)?.toPurpleTrack(base) }

                val artistsArr = serverResults["artists"] as? JsonArray ?: JsonArray(emptyList())
                matchingArtists = artistsArr.mapNotNull { el ->
                    val a = el as? JsonObject ?: return@mapNotNull null
                    val name = a.str("name")?.let(::decodeHtmlEntities) ?: return@mapNotNull null
                    val count = a.int("count") ?: 1
                    val topId = a.int("top_id")
                    val coverUrl = topId?.let { "${base}api.php?action=cover&q=$it" }
                    ArtistSummary(name = name, count = count, coverUrl = coverUrl)
                }

                val albumsArr = serverResults["albums"] as? JsonArray ?: JsonArray(emptyList())
                matchingAlbums = albumsArr.mapNotNull { el ->
                    val al = el as? JsonObject ?: return@mapNotNull null
                    val name = al.str("name")?.let(::decodeHtmlEntities) ?: return@mapNotNull null
                    val count = al.int("count") ?: 1
                    val topId = al.int("top_id")
                    PurpleAlbum(
                        id = name.lowercase().hashCode(),
                        name = name,
                        artist = "",
                        year = null,
                        cover = al.str("cover"),
                        trackCount = count,
                        totalDuration = 0,
                        firstTrackId = topId,
                    )
                }
            } else {
                val allTracks = listTracks()
                val needle = q.lowercase()
                matchingTracks = allTracks.filter { t ->
                    t.title.lowercase().contains(needle) ||
                        t.artist.lowercase().contains(needle) ||
                        (t.album?.lowercase()?.contains(needle) == true) ||
                        t.genre.lowercase().contains(needle)
                }
                matchingArtists = aggregateArtists(allTracks).filter {
                    it.name.lowercase().contains(needle)
                }
                matchingAlbums = fetchAlbums().filter {
                    it.name.lowercase().contains(needle) || it.artist.lowercase().contains(needle)
                }
            }

            val matchingPlaylists = runCatching {
                val needle = q.lowercase()
                fetchPlaylists().filter { it.name.lowercase().contains(needle) }
            }.getOrDefault(emptyList())

            val out = mutableListOf<SearchResult>()
            when (filter) {
                SearchFilter.SONGS, SearchFilter.VIDEOS -> {
                    matchingTracks.forEach { out += SearchResult.Track(it.toSong()) }
                }
                SearchFilter.ALBUMS -> {
                    matchingAlbums.forEach { al ->
                        out += SearchResult.Browse(
                            BrowseItem(
                                browseId = "MPREb_${al.name}",
                                title = al.name,
                                subtitle = listOfNotNull(
                                    al.artist.takeIf { it.isNotBlank() },
                                    "${al.trackCount} titres",
                                ).joinToString(" • "),
                                thumbnailUrl = al.coverUrl(base),
                                type = BrowseType.ALBUM,
                            ),
                        )
                    }
                }
                SearchFilter.ARTISTS -> {
                    matchingArtists.forEach { ar ->
                        out += SearchResult.Browse(
                            BrowseItem(
                                browseId = "UC${ar.name}",
                                title = ar.name,
                                subtitle = "${ar.count} titre${if (ar.count > 1) "s" else ""}",
                                thumbnailUrl = ar.coverUrl,
                                type = BrowseType.ARTIST,
                            ),
                        )
                    }
                }
                SearchFilter.PLAYLISTS -> {
                    matchingPlaylists.forEach { pl ->
                        out += SearchResult.Browse(
                            BrowseItem(
                                browseId = "VL${pl.id}",
                                title = pl.name,
                                subtitle = "${pl.songIds.size} titres",
                                thumbnailUrl = pl.coverUrl(base),
                                type = BrowseType.PLAYLIST,
                            ),
                        )
                    }
                }
                SearchFilter.ALL -> {
                    matchingTracks.firstOrNull()?.let { top ->
                        out += SearchResult.TopTrack(top.toSong())
                    }
                    matchingTracks.drop(1).forEach { t ->
                        out += SearchResult.Track(t.toSong())
                    }
                    matchingArtists.forEach { ar ->
                        out += SearchResult.Browse(
                            BrowseItem(
                                browseId = "UC${ar.name}",
                                title = ar.name,
                                subtitle = "Artiste • ${ar.count} titre${if (ar.count > 1) "s" else ""}",
                                thumbnailUrl = ar.coverUrl,
                                type = BrowseType.ARTIST,
                            ),
                        )
                    }
                    matchingAlbums.forEach { al ->
                        out += SearchResult.Browse(
                            BrowseItem(
                                browseId = "MPREb_${al.name}",
                                title = al.name,
                                subtitle = "Album • ${al.trackCount} titres",
                                thumbnailUrl = al.coverUrl(base),
                                type = BrowseType.ALBUM,
                            ),
                        )
                    }
                    matchingPlaylists.forEach { pl ->
                        out += SearchResult.Browse(
                            BrowseItem(
                                browseId = "VL${pl.id}",
                                title = pl.name,
                                subtitle = "Playlist • ${pl.songIds.size} titres",
                                thumbnailUrl = pl.coverUrl(base),
                                type = BrowseType.PLAYLIST,
                            ),
                        )
                    }
                }
            }
            out
        }

    suspend fun searchSuggestions(input: String): List<String> = withContext(ioDispatcher) {
        val needle = input.trim().lowercase()
        if (needle.isEmpty()) return@withContext emptyList()
        val tracks = runCatching { listTracks() }.getOrDefault(emptyList())
        val candidates = LinkedHashSet<String>()
        tracks.forEach { t ->
            if (t.title.lowercase().startsWith(needle)) candidates += t.title
            splitArtistNames(t.artist).forEach { a ->
                if (a.lowercase().startsWith(needle)) candidates += a
            }
            t.album?.takeIf { it.lowercase().startsWith(needle) }?.let { candidates += it }
        }
        tracks.forEach { t ->
            if (candidates.size >= 10) return@forEach
            if (t.title.lowercase().contains(needle)) candidates += t.title
            splitArtistNames(t.artist).forEach { a ->
                if (a.lowercase().contains(needle)) candidates += a
            }
        }
        candidates.take(10)
    }

    suspend fun buildLibraryPage(): LibraryPage = withContext(ioDispatcher) {
        val base = requireBaseUrl()
        val allTracks = listTracks()
        val likedIds = fetchLikedIds()
        val byId = allTracks.associateBy { it.id.toString() }
        val likedSongs = likedIds.mapNotNull { byId[it]?.toSong() }
        val librarySongs = allTracks.filterNot { it.id.toString() in likedIds }.map { it.toSong() }

        val shelves = mutableListOf<HomeShelf>()

        // 1. Playlists shelf (must be titled "Playlists" to match YtMusicRepository.PLAYLISTS_SHELF)
        val playlists = runCatching { fetchPlaylists() }.getOrDefault(emptyList())
        shelves += HomeShelf(
            title = "Playlists",
            items = playlists.map { pl ->
                ShelfItem(
                    title = pl.name,
                    subtitle = buildString {
                        if (pl.creatorName.isNotBlank()) append("${pl.creatorName} • ")
                        append("${pl.songIds.size} titres")
                    },
                    thumbnailUrl = pl.coverUrl(base),
                    videoId = null,
                    browseId = "VL${pl.id}",
                )
            },
        )

        // 2. Albums shelf
        val albums = runCatching { fetchAlbums() }.getOrDefault(emptyList())
        if (albums.isNotEmpty()) {
            shelves += HomeShelf(
                title = "Albums",
                items = albums.map { al ->
                    ShelfItem(
                        title = al.name,
                        subtitle = al.artist.ifBlank { "${al.trackCount} titres" },
                        thumbnailUrl = al.coverUrl(base),
                        videoId = null,
                        browseId = "MPREb_${al.name}",
                    )
                },
            )
        }

        // 3. Artists shelf
        val artists = aggregateArtists(allTracks)
        if (artists.isNotEmpty()) {
            shelves += HomeShelf(
                title = "Artists",
                items = artists.map { ar ->
                    ShelfItem(
                        title = ar.name,
                        subtitle = "${ar.count} titre${if (ar.count > 1) "s" else ""}",
                        thumbnailUrl = ar.coverUrl,
                        videoId = null,
                        browseId = "UC${ar.name}",
                    )
                },
            )
        }

        // 4. Followed artists (Subscriptions)
        val followed = runCatching { fetchFollowedArtists() }.getOrDefault(emptySet())
        if (followed.isNotEmpty()) {
            val artistByLower = artists.associateBy { it.name.lowercase() }
            shelves += HomeShelf(
                title = "Subscriptions",
                items = followed.map { name ->
                    val summary = artistByLower[name.lowercase()]
                    ShelfItem(
                        title = summary?.name ?: name,
                        subtitle = summary?.let { "${it.count} titres" } ?: "Artiste suivi",
                        thumbnailUrl = summary?.coverUrl,
                        videoId = null,
                        browseId = "UC${summary?.name ?: name}",
                    )
                },
            )
        }

        LibraryPage(
            likedSongs = likedSongs,
            librarySongs = librarySongs,
            shelves = shelves,
            likedContinuation = null,
        )
    }

    data class BrowsedCollection(
        val songs: List<Song>,
        val title: String,
        val subtitle: String,
        val thumbnailUrl: String?,
        val description: String? = null,
        val owned: Boolean? = null,
        val library: LibraryState? = null,
    )

    suspend fun browseCollection(browseId: String): BrowsedCollection = withContext(ioDispatcher) {
        val base = requireBaseUrl()
        val allTracks = listTracks()
        when {
            browseId == "VLLM" || browseId == "LM" -> {
                val likedIds = fetchLikedIds()
                val songs = allTracks.filter { it.id.toString() in likedIds }.map { it.toSong() }
                BrowsedCollection(
                    songs = songs,
                    title = "Titres likés",
                    subtitle = "${songs.size} titres",
                    thumbnailUrl = songs.firstOrNull()?.thumbnailUrl,
                    owned = false,
                )
            }
            browseId == "VLreco" -> {
                val recos = fetchRecommendations(full = true).map { it.toSong() }
                BrowsedCollection(
                    songs = recos,
                    title = "Recommandé pour toi",
                    subtitle = "${recos.size} titres",
                    thumbnailUrl = recos.firstOrNull()?.thumbnailUrl,
                    owned = false,
                )
            }
            browseId == "VLrecent" -> {
                val recent = allTracks.sortedByDescending { it.id }.map { it.toSong() }
                BrowsedCollection(
                    songs = recent,
                    title = "Ajouts récents",
                    subtitle = "${recent.size} titres",
                    thumbnailUrl = recent.firstOrNull()?.thumbnailUrl,
                    owned = false,
                )
            }
            browseId == "VLtop" -> {
                val top = fetchServerTop().ifEmpty { allTracks.sortedByDescending { it.playCount } }.map { it.toSong() }
                BrowsedCollection(
                    songs = top,
                    title = "Les plus écoutés",
                    subtitle = "${top.size} titres",
                    thumbnailUrl = top.firstOrNull()?.thumbnailUrl,
                    owned = false,
                )
            }
            browseId.startsWith("VLmix:") -> {
                val key = browseId.removePrefix("VLmix:")
                val mixes = cachedMixes.ifEmpty { fetchDailyMixes() }
                val mix = mixes.firstOrNull { it.key.equals(key, ignoreCase = true) }
                val songs = mix?.tracks?.map { it.toSong() }
                    ?: allTracks.filter { t -> splitGenres(t.genre).any { it.equals(key, ignoreCase = true) } }
                        .map { it.toSong() }
                BrowsedCollection(
                    songs = songs,
                    title = mix?.let { "Mix ${it.title}" } ?: "Mix $key",
                    subtitle = "${songs.size} titres",
                    thumbnailUrl = songs.firstOrNull()?.thumbnailUrl,
                    owned = false,
                )
            }
            browseId.startsWith("VLgenre:") || browseId.startsWith("genre:") -> {
                val genreName = browseId.removePrefix("VLgenre:").removePrefix("genre:")
                val songs = allTracks.filter { t ->
                    splitGenres(t.genre).any { it.equals(genreName, ignoreCase = true) }
                }.map { it.toSong() }
                BrowsedCollection(
                    songs = songs,
                    title = genreName,
                    subtitle = "${songs.size} titres",
                    thumbnailUrl = songs.firstOrNull()?.thumbnailUrl,
                    owned = false,
                )
            }
            browseId.startsWith("MPREb_") || browseId.startsWith("OLAK5uy_") -> {
                val albumKey = browseId.removePrefix("MPREb_").removePrefix("OLAK5uy_").trim()
                val albums = fetchAlbums()
                val matchedAlbum = albums.firstOrNull {
                    it.name.equals(albumKey, ignoreCase = true) || it.id.toString() == albumKey
                }
                val targetName = matchedAlbum?.name ?: albumKey
                val albumTracks = allTracks.filter {
                    it.album?.equals(targetName, ignoreCase = true) == true
                }
                val songs = albumTracks.map { it.toSong() }
                val artist = matchedAlbum?.artist?.ifBlank { albumTracks.firstOrNull()?.artist.orEmpty() }
                    ?: albumTracks.firstOrNull()?.artist.orEmpty()
                val subtitle = listOfNotNull(
                    artist.takeIf { it.isNotBlank() },
                    matchedAlbum?.year?.toString(),
                    "${songs.size} titres",
                ).joinToString(" • ")
                BrowsedCollection(
                    songs = songs,
                    title = targetName,
                    subtitle = subtitle,
                    thumbnailUrl = matchedAlbum?.coverUrl(base) ?: songs.firstOrNull()?.thumbnailUrl,
                    owned = null,
                )
            }
            browseId.startsWith("UC") -> {
                val artistName = browseId.removePrefix("UC").trim()
                val artistTracks = allTracks.filter { t ->
                    splitArtistNames(t.artist).any { it.equals(artistName, ignoreCase = true) } ||
                        t.artist.equals(artistName, ignoreCase = true)
                }
                val songs = artistTracks.map { it.toSong() }
                BrowsedCollection(
                    songs = songs,
                    title = artistName,
                    subtitle = "${songs.size} titres",
                    thumbnailUrl = songs.firstOrNull()?.thumbnailUrl,
                    owned = null,
                )
            }
            else -> {
                val pid = browseId.removePrefix("VL").trim().toIntOrNull()
                val playlists = fetchPlaylists()
                val playlist = playlists.firstOrNull { it.id == pid }
                if (playlist != null) {
                    val tracks = fetchPlaylistTracks(playlist.songIds)
                    val songs = tracks.mapIndexed { index, track ->
                        track.toSong(setVideoId = "${index}_${track.id}")
                    }
                    val isOwner = isSignedIn && (isAdmin || playlist.creatorId == userId)
                    BrowsedCollection(
                        songs = songs,
                        title = playlist.name,
                        subtitle = buildString {
                            if (playlist.creatorName.isNotBlank()) append("Par ${playlist.creatorName} • ")
                            append("${songs.size} titres")
                        },
                        thumbnailUrl = playlist.coverUrl(base) ?: songs.firstOrNull()?.thumbnailUrl,
                        owned = isOwner,
                    )
                } else {
                    BrowsedCollection(
                        songs = emptyList(),
                        title = "Collection",
                        subtitle = "",
                        thumbnailUrl = null,
                        owned = false,
                    )
                }
            }
        }
    }

    suspend fun buildArtistPage(browseId: String): ArtistPage = withContext(ioDispatcher) {
        val base = requireBaseUrl()
        val rawName = browseId.removePrefix("UC").trim()
        val allTracks = listTracks()
        val artistTracks = allTracks.filter { t ->
            splitArtistNames(t.artist).any { it.equals(rawName, ignoreCase = true) } ||
                t.artist.equals(rawName, ignoreCase = true)
        }.sortedByDescending { it.playCount }

        val canonicalName = artistTracks.firstNotNullOfOrNull { t ->
            splitArtistNames(t.artist).firstOrNull { it.equals(rawName, ignoreCase = true) }
        } ?: rawName

        val songs = artistTracks.map { it.toSong() }
        val totalPlays = artistTracks.sumOf { it.playCount }

        // Artist's albums shelf
        val artistAlbums = artistTracks
            .mapNotNull { it.album?.trim()?.takeIf(String::isNotEmpty) }
            .distinctBy { it.lowercase() }
        val sections = mutableListOf<HomeShelf>()
        if (artistAlbums.isNotEmpty()) {
            val allAlbums = fetchAlbums().associateBy { it.name.lowercase() }
            sections += HomeShelf(
                title = "Albums",
                items = artistAlbums.map { albumName ->
                    val albumMeta = allAlbums[albumName.lowercase()]
                    val firstTrack = artistTracks.firstOrNull { it.album.equals(albumName, ignoreCase = true) }
                    ShelfItem(
                        title = albumName,
                        subtitle = albumMeta?.year?.toString() ?: "Album",
                        thumbnailUrl = albumMeta?.coverUrl(base) ?: firstTrack?.coverUrl,
                        videoId = null,
                        browseId = "MPREb_$albumName",
                    )
                },
            )
        }

        val bio = runCatching { fetchArtistBio(canonicalName) }.getOrNull()
        val followedSet = runCatching { fetchFollowedArtists() }.getOrDefault(emptySet())
        val isFollowed = followedSet.any { it.equals(canonicalName, ignoreCase = true) }

        ArtistPage(
            songs = songs,
            moreSongsBrowseId = null,
            sections = sections,
            thumbnailUrl = songs.firstOrNull()?.thumbnailUrl,
            name = canonicalName,
            description = bio,
            subscriberCountText = "${songs.size} titre${if (songs.size > 1) "s" else ""}",
            monthlyListenerCount = "$totalPlays écoute${if (totalPlays > 1) "s" else ""}",
            subscription = if (isSignedIn) {
                SubscriptionState(channelId = "UC$canonicalName", subscribed = isFollowed)
            } else null,
        )
    }

    suspend fun buildRadioQueue(seedVideoId: String): List<Song> = withContext(ioDispatcher) {
        val cleanId = seedVideoId.removePrefix("purple:").trim()
        val allTracks = listTracks()
        val seedTrack = allTracks.firstOrNull { it.id.toString() == cleanId }
        val recos = runCatching { fetchRecommendations(full = false) }.getOrDefault(emptyList())

        val sameArtistOrGenre = if (seedTrack != null) {
            val seedArtists = splitArtistNames(seedTrack.artist).map { it.lowercase() }.toSet()
            val seedGenres = splitGenres(seedTrack.genre).map { it.lowercase() }.toSet()
            allTracks.filter { t ->
                t.id != seedTrack.id && (
                    splitArtistNames(t.artist).any { it.lowercase() in seedArtists } ||
                        splitGenres(t.genre).any { it.lowercase() in seedGenres }
                    )
            }
        } else {
            emptyList()
        }

        val combined = buildList {
            seedTrack?.let(::add)
            addAll(sameArtistOrGenre)
            addAll(recos)
            addAll(allTracks)
        }.distinctBy { it.id }

        combined.take(30).map { it.toSong() }
    }

    fun currentAccountModel(): Account {
        val host = _serverUrl.value
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
        return Account(
            name = _username.value.ifBlank { "PurpleMusic" },
            email = host,
            thumbnailUrl = null,
        )
    }

    fun currentAccountChannels(): List<AccountChannel> {
        val acc = currentAccountModel()
        return listOf(
            AccountChannel(
                name = acc.name,
                subtitle = acc.email,
                thumbnailUrl = null,
                pageId = null,
                dataSyncId = "purple_${_userId.value}",
                activeOnWeb = true,
            ),
        )
    }

    private fun PurpleTrack.toShelfItem(): ShelfItem = ShelfItem(
        title = title,
        subtitle = artist,
        thumbnailUrl = coverUrl,
        videoId = id.toString(),
        browseId = null,
    )

    // ── Text & Metadata Utilities ────────────────────────────────────────────

    data class ArtistSummary(
        val name: String,
        val count: Int,
        val coverUrl: String?,
    )

    fun aggregateArtists(tracks: List<PurpleTrack>): List<ArtistSummary> {
        val map = LinkedHashMap<String, ArtistSummary>()
        for (t in tracks) {
            for (name in splitArtistNames(t.artist)) {
                val key = name.lowercase()
                val existing = map[key]
                if (existing == null) {
                    map[key] = ArtistSummary(name = name, count = 1, coverUrl = t.coverUrl)
                } else {
                    map[key] = existing.copy(count = existing.count + 1)
                }
            }
        }
        return map.values.sortedByDescending { it.count }
    }

    fun splitArtistNames(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val unbracketed = raw.replace(
            Regex("""[(\[]\s*((?:feat|ft|featuring|avec|with)\b\.?\s*[^)\]]*)[)\]]""", RegexOption.IGNORE_CASE),
            " $1 ",
        )
        val parts = unbracketed.split(
            Regex(
                """\s*,\s*|\s*&amp;\s*|\s*&\s*|\s+feat\.?\s+|\s+ft\.?\s+|\s+featuring\s+|\s+vs\.?\s+|\s+x\s+|\s+et\s+|\s*;\s*""",
                RegexOption.IGNORE_CASE,
            ),
        )
        val out = mutableListOf<String>()
        val seen = HashSet<String>()
        for (part in parts) {
            val cleaned = part.trim()
                .replace(Regex("""^(?:feat|ft|featuring|avec|with|and|et|x)\b\.?\s*""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s{2,}"""), " ")
                .trim()
            if (cleaned.isNotEmpty() && seen.add(cleaned.lowercase())) {
                out += cleaned
            }
        }
        return if (out.isEmpty()) listOf(raw.trim()) else out
    }

    fun splitGenres(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = mutableListOf<String>()
        val seen = HashSet<String>()
        for (part in raw.split(Regex("""\s*[,;/]\s*"""))) {
            val g = part.trim()
            if (g.isEmpty() || g.equals("Autre", ignoreCase = true)) continue
            if (seen.add(g.lowercase())) {
                out += g
            }
        }
        return out
    }

    fun formatDuration(seconds: Int): String? {
        if (seconds <= 0) return null
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hrs > 0) {
            "$hrs:${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
        } else {
            "$mins:${secs.toString().padStart(2, '0')}"
        }
    }

    private fun decodeHtmlEntities(input: String): String = input
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private fun parseJsonObject(raw: String): JsonObject? =
        runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.let {
            it.booleanOrNull ?: when (it.contentOrNull?.lowercase()) {
                "1", "true" -> true
                "0", "false" -> false
                else -> null
            }
        }
}
