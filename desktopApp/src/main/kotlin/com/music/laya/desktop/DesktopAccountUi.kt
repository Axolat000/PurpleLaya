package com.music.laya.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.laya.data.purplemusic.PurpleMusicClient
import com.music.laya.ui.icons.LayaIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The account button in the toolbar: the listener's photo, or a person glyph when there is nobody
 * signed in.
 */
@Composable
internal fun DesktopAccountButton(
    avatar: String?,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(36.dp).clip(CircleShape)
            .desktopHoverWash(restAlpha = 0.12f, hoverAlpha = 0.2f)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (avatar != null) {
            DesktopArtwork(
                avatar,
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                contentScale = ContentScale.Crop,
                px = 96,
            )
        } else {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Person,
                    DesktopStrings["accounts", "Accounts"],
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * The account switcher: every PurpleMusic login this app holds, and the actions that change the set.
 */
@Composable
internal fun DesktopAccountSelector(
    accounts: List<DesktopGoogleAccount>,
    activeAccountId: String?,
    activeProfileId: String?,
    busy: Boolean,
    onSelect: (DesktopGoogleAccount, DesktopYouTubeProfile) -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (DesktopGoogleAccount) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    var managing by remember { mutableStateOf(false) }
    val currentServer = PurpleMusicClient.serverUrl
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 64.dp, end = 24.dp)
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .clip(shape)
                .desktopBarGlass(shape),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (accounts.isEmpty()) "Serveur PurpleMusic" else "Comptes PurpleMusic",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Spacer(Modifier.weight(1f))
                if (busy) CircularProgressIndicator(color = DesktopAccent, modifier = Modifier.size(16.dp))
            }
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                if (currentServer.isNotBlank()) {
                    item(key = "current-server-banner") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.Cloud,
                                contentDescription = null,
                                tint = Color(0xFFB388FF),
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                currentServer.removePrefix("https://").removePrefix("http://").trimEnd('/'),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFB388FF),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (accounts.isEmpty()) {
                    item {
                        Text(
                            if (currentServer.isBlank()) {
                                "Aucun serveur PurpleMusic configuré. Configurez l'URL de votre instance et connectez-vous."
                            } else {
                                "Non connecté. Connectez-vous pour synchroniser vos likes, playlists et recommandations."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = DesktopSecondary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        )
                    }
                }
                accounts.forEach { account ->
                    item(key = "header-${account.accountId}") {
                        Text(
                            account.email.ifBlank { account.name.ifBlank { "Serveur PurpleMusic" } },
                            style = MaterialTheme.typography.labelMedium,
                            color = DesktopSecondary,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(account.profiles.size, key = { "p-${account.accountId}-$it" }) { index ->
                        val profile = account.profiles[index]
                        DesktopProfileRow(
                            profile = profile,
                            selected = account.accountId == activeAccountId &&
                                profile.profileId == activeProfileId,
                            managing = managing,
                            onClick = { onSelect(account, profile); onDismiss() },
                            onRemove = { onRemoveAccount(account) },
                        )
                    }
                }
                item {
                    DesktopSelectorAction(
                        LayaIcons.Plus,
                        if (accounts.isEmpty()) "Configurer / Se connecter" else "Changer de serveur / Ajouter un compte",
                        onAddAccount,
                    )
                }
                if (accounts.isNotEmpty()) {
                    item {
                        DesktopSelectorAction(Icons.Rounded.ManageAccounts, "Gérer les comptes") {
                            managing = !managing
                        }
                    }
                }
                item { DesktopSelectorAction(Icons.Rounded.Settings, "Paramètres", onOpenSettings) }
                item { Spacer(Modifier.height(10.dp)) }
            }
        }
    }
}

@Composable
private fun DesktopProfileRow(
    profile: DesktopYouTubeProfile,
    selected: Boolean,
    managing: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = if (managing) onRemove else onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (profile.avatar != null) {
            DesktopArtwork(
                profile.avatar,
                Modifier.size(38.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
                px = 128,
            )
        } else {
            Icon(
                Icons.Rounded.Person,
                null,
                tint = Color(0xFFB388FF),
                modifier = Modifier.size(38.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(profile.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                profile.handle.ifBlank { if (profile.isBrandAccount) "Admin" else "PurpleMusic" },
                style = MaterialTheme.typography.bodySmall,
                color = DesktopSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            managing -> Text("Déconnexion", color = DesktopDestructive, style = MaterialTheme.typography.labelMedium)
            selected -> Icon(LayaIcons.Check, "Sélectionné", tint = DesktopAccent, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun DesktopSelectorAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = Color.White)
    }
}

internal const val WEB_SIGN_IN = "PurpleMusic"

/**
 * PurpleMusic Server URL & Account Login / Registration dialog.
 */
@Composable
internal fun DesktopSignInDialog(
    @Suppress("UNUSED_PARAMETER") webSignIn: Boolean = false,
    @Suppress("UNUSED_PARAMETER") onWebSignIn: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onImport: (DesktopBrowserCookies.Profile) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onPaste: (String) -> Unit = {},
    busy: String? = null,
    error: String? = null,
    onDismiss: () -> Unit,
    onPurpleSignedIn: () -> Unit = onDismiss,
) {
    val scope = rememberCoroutineScope()
    var serverUrl by remember {
        mutableStateOf(
            PurpleMusicClient.serverUrl.ifBlank { DesktopAccounts.savedServerUrl() },
        )
    }
    var username by remember { mutableStateOf(PurpleMusicClient.username) }
    var password by remember { mutableStateOf("") }
    var isRegisterMode by remember { mutableStateOf(false) }
    var localBusy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var localSuccess by remember { mutableStateOf<String?>(null) }

    val effectiveBusy = localBusy || busy != null
    val effectiveError = localError ?: error
    val purpleAccent = Color(0xFFB388FF)

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedBorderColor = purpleAccent,
        unfocusedBorderColor = Color.White.copy(alpha = 0.22f),
        focusedLabelColor = purpleAccent,
        unfocusedLabelColor = DesktopSecondary,
        cursorColor = purpleAccent,
    )

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        )
        val shape = RoundedCornerShape(22.dp)
        Column(
            Modifier
                .widthIn(max = 500.dp)
                .fillMaxWidth()
                .clip(shape)
                .desktopBarGlass(shape)
                .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Cloud,
                    contentDescription = null,
                    tint = purpleAccent,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Connexion à PurpleMusic",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, "Fermer", tint = DesktopSecondary)
                }
            }

            Text(
                "Indiquez l'URL du site hébergeant votre instance PurpleMusic, puis connectez-vous ou créez un compte.",
                style = MaterialTheme.typography.bodySmall,
                color = DesktopSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
            )

            OutlinedTextField(
                value = serverUrl,
                onValueChange = {
                    serverUrl = it
                    localError = null
                    localSuccess = null
                },
                label = { Text("URL du serveur PurpleMusic") },
                placeholder = { Text("https://music.monsite.com ou http://localhost:51837", color = DesktopSecondary.copy(alpha = 0.6f)) },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilterChip(
                    selected = !isRegisterMode,
                    onClick = { isRegisterMode = false; localError = null },
                    label = { Text("Se connecter") },
                    colors = desktopChipColors(),
                )
                FilterChip(
                    selected = isRegisterMode,
                    onClick = { isRegisterMode = true; localError = null },
                    label = { Text("Créer un compte") },
                    colors = desktopChipColors(),
                )
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = username,
                onValueChange = {
                    username = it
                    localError = null
                },
                label = { Text("Nom d'utilisateur") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    localError = null
                },
                label = { Text(if (isRegisterMode) "Mot de passe (6 caractères min)" else "Mot de passe") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            effectiveError?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = DesktopDestructive,
                )
            }

            localSuccess?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = purpleAccent,
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    enabled = !effectiveBusy && serverUrl.isNotBlank(),
                    onClick = {
                        scope.launch {
                            localBusy = true
                            localError = null
                            localSuccess = null
                            val res = PurpleMusicClient.probeServer(serverUrl)
                            localBusy = false
                            res.onSuccess { count ->
                                withContext(Dispatchers.IO) {
                                    DesktopAccounts.saveServerOnly(serverUrl)
                                }
                                localSuccess = "Serveur connecté ($count titres disponibles)."
                                onPurpleSignedIn()
                            }.onFailure { err ->
                                localError = err.message ?: "Impossible de joindre ce serveur PurpleMusic."
                            }
                        }
                    },
                ) {
                    Text("Utiliser l'URL sans compte", color = DesktopSecondary)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (effectiveBusy) {
                        CircularProgressIndicator(
                            color = purpleAccent,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Button(
                        enabled = !effectiveBusy && serverUrl.isNotBlank() && username.isNotBlank() && password.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = purpleAccent,
                            contentColor = Color.Black,
                        ),
                        onClick = {
                            scope.launch {
                                localBusy = true
                                localError = null
                                localSuccess = null
                                val result = if (isRegisterMode) {
                                    PurpleMusicClient.register(serverUrl, username, password, acceptTerms = true)
                                } else {
                                    PurpleMusicClient.login(serverUrl, username, password, autoAcceptTerms = true)
                                }
                                result.onSuccess { outcome ->
                                    withContext(Dispatchers.IO) {
                                        DesktopAccounts.savePurple(outcome.account)
                                    }
                                    localBusy = false
                                    onPurpleSignedIn()
                                }.onFailure { err ->
                                    localBusy = false
                                    localError = err.message ?: "Échec de la connexion à PurpleMusic."
                                }
                            }
                        },
                    ) {
                        Text(
                            if (isRegisterMode) "Créer le compte" else "Se connecter",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
