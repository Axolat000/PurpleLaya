package com.music.laya.desktop

import com.music.laya.data.innertube.InnertubeParser
import com.music.laya.data.model.Account
import com.music.laya.data.model.AccountChannel
import com.music.laya.data.purplemusic.PurpleAccount
import com.music.laya.data.purplemusic.PurpleMusicClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/** One Personal or Brand identity available under a login. */
@Serializable
data class DesktopYouTubeProfile(
    val profileId: String,
    val name: String,
    val handle: String = "",
    val avatar: String? = null,
    val pageId: String? = null,
    val dataSyncId: String? = null,
    val authUser: String = "0",
    val isBrandAccount: Boolean = false,
)

/** A login kept independently from every other saved login. */
@Serializable
data class DesktopGoogleAccount(
    val accountId: String,
    val name: String = "",
    val email: String = "",
    val avatar: String? = null,
    val profiles: List<DesktopYouTubeProfile> = emptyList(),
    val activeProfileId: String? = null,
    /** For PurpleMusic accounts, stores the normalized server URL. */
    val sourceProfile: String? = null,
)

/** Every PurpleMusic login this app is holding, and which server/account it is acting as. */
internal object DesktopAccounts {

    private const val KEY_ACCOUNTS = "purple_desktop_accounts_v1"
    private const val KEY_ACTIVE_ACCOUNT = "active_purple_desktop_account_v1"
    private const val KEY_ACTIVE_PROFILE = "active_purple_desktop_profile_v1"
    private const val KEY_SERVER_URL = "purple_desktop_server_url_v1"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val persistence get() = DesktopPersistence()

    fun accounts(): List<DesktopGoogleAccount> {
        val raw = DesktopPreferenceChunks.read(persistence.preferences, KEY_ACCOUNTS) ?: return emptyList()
        return runCatching { json.decodeFromString<List<DesktopGoogleAccount>>(raw) }.getOrDefault(emptyList())
    }

    fun activeAccountId(): String? = persistence.string(KEY_ACTIVE_ACCOUNT).takeIf { it.isNotBlank() }

    fun activeProfileId(): String? = persistence.string(KEY_ACTIVE_PROFILE).takeIf { it.isNotBlank() }

    fun active(): DesktopGoogleAccount? =
        accounts().firstOrNull { it.accountId == activeAccountId() } ?: accounts().firstOrNull()

    fun activeProfile(): DesktopYouTubeProfile? {
        val account = active() ?: return null
        return account.profiles.firstOrNull { it.profileId == activeProfileId() }
            ?: account.profiles.firstOrNull { it.profileId == account.activeProfileId }
            ?: account.profiles.firstOrNull()
    }

    fun savedServerUrl(): String =
        persistence.string(KEY_SERVER_URL).takeIf { it.isNotBlank() }
            ?: active()?.sourceProfile.orEmpty()

    fun saveServerOnly(serverUrl: String) {
        val normalized = PurpleMusicClient.normalizeServerUrl(serverUrl)
        persistence.saveString(KEY_SERVER_URL, normalized)
        PurpleMusicClient.setServerUrl(normalized)
    }

    /** The identity for a stored account, or null when its credential has gone. */
    fun sessionFor(account: DesktopGoogleAccount, profile: DesktopYouTubeProfile?): DesktopYouTubeAuth.Session? {
        val rawSecret = cookieOf(account.accountId) ?: return null
        val pmAccount = runCatching { json.decodeFromString(PurpleAccount.serializer(), rawSecret) }.getOrNull()
        val cookie = if (pmAccount != null) {
            PurpleMusicClient.adoptSession(
                serverUrl = pmAccount.serverUrl,
                username = pmAccount.username,
                password = pmAccount.password,
                userId = pmAccount.userId,
                isAdmin = pmAccount.isAdmin,
            )
            persistence.saveString(KEY_SERVER_URL, pmAccount.serverUrl)
            PurpleMusicClient.syntheticCookieFor(pmAccount) ?: "SAPISID=purplemusic"
        } else {
            rawSecret
        }
        return DesktopYouTubeAuth.Session(
            cookie = cookie,
            authUser = profile?.authUser ?: "0",
            pageId = profile?.pageId,
            dataSyncId = profile?.dataSyncId ?: account.accountId,
        )
    }

    /** Puts the chosen PurpleMusic account (or standalone server URL) into force. */
    suspend fun activate(): DesktopYouTubeAuth.Session? {
        val standaloneUrl = savedServerUrl()
        if (standaloneUrl.isNotBlank() && !PurpleMusicClient.hasServerConfigured) {
            PurpleMusicClient.setServerUrl(standaloneUrl)
        }
        val account = active() ?: run {
            DesktopYouTubeAuth.adopt(null)
            return null
        }
        val profile = activeProfile()
        val stored = sessionFor(account, profile)
        if (stored == null) {
            DesktopTrackLog.log("account '${account.email.ifBlank { account.name }}' has no stored credential any more")
            DesktopYouTubeAuth.adopt(null)
            return null
        }
        DesktopYouTubeAuth.adopt(stored)
        return stored
    }

    /** Puts the chosen account and channel into force for this process. */
    fun applyActive(): DesktopYouTubeAuth.Session? {
        val account = active()
        if (account == null) {
            DesktopYouTubeAuth.adopt(null)
            return null
        }
        val session = sessionFor(account, activeProfile())
        DesktopYouTubeAuth.adopt(session)
        return session
    }

    /** Saves a PurpleMusic account and makes it the active one. */
    fun savePurple(pmAccount: PurpleAccount): DesktopGoogleAccount {
        persistence.saveString(KEY_SERVER_URL, pmAccount.serverUrl)
        val profile = DesktopYouTubeProfile(
            profileId = pmAccount.accountId,
            name = pmAccount.username,
            handle = pmAccount.displayHost,
            avatar = null,
            pageId = null,
            dataSyncId = pmAccount.accountId,
            authUser = "0",
            isBrandAccount = pmAccount.isAdmin,
        )
        val account = DesktopGoogleAccount(
            accountId = pmAccount.accountId,
            name = pmAccount.username,
            email = pmAccount.displayHost,
            avatar = null,
            profiles = listOf(profile),
            activeProfileId = profile.profileId,
            sourceProfile = pmAccount.serverUrl,
        )
        val encodedSecret = json.encodeToString(PurpleAccount.serializer(), pmAccount)
        writeCookie(pmAccount.accountId, encodedSecret)
        write(accounts().filterNot { it.accountId == pmAccount.accountId } + account)
        select(pmAccount.accountId, profile.profileId)
        return account
    }

    /** Legacy signature kept for callers in DesktopApp. */
    fun save(
        cookie: String,
        scope: DesktopYouTubeAuth.Session,
        details: Account?,
        channels: List<AccountChannel>,
        sourceProfile: String? = null,
    ): DesktopGoogleAccount {
        val accountId = scope.dataSyncId?.takeIf { it.isNotBlank() } ?: digest(cookie).take(24)
        val profiles = channels.map { channel ->
            DesktopYouTubeProfile(
                profileId = channel.pageId ?: channel.dataSyncId ?: "profile:" + digest(channel.name).take(16),
                name = channel.name,
                handle = channel.subtitle,
                avatar = channel.thumbnailUrl,
                pageId = channel.pageId,
                dataSyncId = channel.dataSyncId,
                authUser = scope.authUser,
                isBrandAccount = channel.pageId != null,
            )
        }.ifEmpty {
            listOf(
                DesktopYouTubeProfile(
                    profileId = scope.pageId ?: scope.dataSyncId ?: accountId,
                    name = details?.name?.ifBlank { null } ?: "PurpleMusic",
                    handle = details?.email.orEmpty(),
                    avatar = details?.thumbnailUrl,
                    pageId = scope.pageId,
                    dataSyncId = scope.dataSyncId,
                    authUser = scope.authUser,
                    isBrandAccount = scope.pageId != null,
                ),
            )
        }
        val chosen = profiles.first()
        val account = DesktopGoogleAccount(
            accountId = accountId,
            name = details?.name.orEmpty(),
            email = details?.email.orEmpty(),
            avatar = details?.thumbnailUrl,
            profiles = profiles,
            activeProfileId = chosen.profileId,
            sourceProfile = sourceProfile,
        )
        writeCookie(accountId, cookie)
        write(accounts().filterNot { it.accountId == accountId } + account)
        select(accountId, chosen.profileId)
        return account
    }

    fun select(accountId: String, profileId: String?) {
        persistence.saveString(KEY_ACTIVE_ACCOUNT, accountId)
        persistence.saveString(KEY_ACTIVE_PROFILE, profileId.orEmpty())
        applyActive()
    }

    fun remove(accountId: String) {
        DesktopSecretStore.remove(attributesFor(accountId))
        persistence.saveString(cookieFallbackKey(accountId), "")
        PurpleMusicClient.removeAccount(accountId)
        val remaining = accounts().filterNot { it.accountId == accountId }
        write(remaining)
        val next = remaining.firstOrNull()
        persistence.saveString(KEY_ACTIVE_ACCOUNT, next?.accountId.orEmpty())
        persistence.saveString(KEY_ACTIVE_PROFILE, next?.activeProfileId.orEmpty())
        applyActive()
    }

    private fun write(value: List<DesktopGoogleAccount>) {
        DesktopPreferenceChunks.write(
            persistence.preferences,
            KEY_ACCOUNTS,
            json.encodeToString(kotlinx.serialization.builtins.ListSerializer(DesktopGoogleAccount.serializer()), value),
        )
    }

    private fun attributesFor(accountId: String) =
        mapOf("application" to "purplelaya", "account" to accountId)

    private fun writeCookie(accountId: String, cookie: String) {
        val stored = DesktopSecretStore.store(
            label = "PurpleLaya — PurpleMusic session",
            attributes = attributesFor(accountId),
            secret = cookie.toByteArray(Charsets.UTF_8),
        )
        if (stored) {
            persistence.saveString(cookieFallbackKey(accountId), "")
        } else {
            DesktopPreferenceChunks.write(persistence.preferences, cookieFallbackKey(accountId), cookie)
        }
    }

    fun cookieOf(accountId: String): String? =
        DesktopSecretStore.lookup(attributesFor(accountId))?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
            ?: DesktopPreferenceChunks.read(persistence.preferences, cookieFallbackKey(accountId))
                ?.takeIf { it.isNotBlank() }

    private fun cookieFallbackKey(accountId: String) = "purple_secret_$accountId"

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal object DesktopAccountParser {
    fun account(root: JsonElement): Account? {
        val header = DesktopSearchClient.renderers(root, "activeAccountHeaderRenderer").firstOrNull() ?: return null
        val email = header.text("email").ifBlank { header.text("channelHandle") }
        return Account(
            name = InnertubeParser.accountDisplayName(header.text("accountName"), email),
            email = email,
            thumbnailUrl = header.photo(),
        )
    }

    fun channels(root: JsonElement): List<AccountChannel> =
        DesktopSearchClient.renderers(root, "accountItem").mapNotNull { item ->
            val pageId = item.findString("pageId")
            val dataSyncId = item.findString("datasyncIdToken")?.substringBefore("||")?.takeIf { it.isNotBlank() }
            if (pageId == null && dataSyncId == null) return@mapNotNull null
            val subtitle = item.text("channelHandle").ifBlank { item.text("accountByline") }
            AccountChannel(
                name = InnertubeParser.accountDisplayName(item.text("accountName"), subtitle),
                subtitle = subtitle,
                thumbnailUrl = item.photo(),
                pageId = pageId,
                dataSyncId = dataSyncId,
                activeOnWeb = (item["isSelected"] as? JsonPrimitive)?.content == "true",
            )
        }.distinctBy { it.key }

    private fun JsonObject.text(field: String): String {
        val node = this[field] as? JsonObject ?: return ""
        (node["runs"] as? JsonArray)?.let { runs ->
            return runs.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        }
        return node["simpleText"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private fun JsonObject.photo(): String? =
        ((this["accountPhoto"] as? JsonObject)?.get("thumbnails") as? JsonArray)
            ?.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull

    private fun JsonElement.findString(key: String): String? = when (this) {
        is JsonObject -> (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: values.firstNotNullOfOrNull { it.findString(key) }
        is JsonArray -> firstNotNullOfOrNull { it.findString(key) }
        else -> null
    }
}
