package com.music.laya.auth

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.music.laya.data.purplemusic.PurpleMusicClient
import kotlinx.coroutines.launch

/**
 * Native Material 3 login & server configuration screen for PurpleMusic.
 *
 * Replaces the Google WebView sign-in while preserving the exact composable
 * signature (`YtMusicLoginScreen`) so Android phone and Android TV hosts work
 * seamlessly.
 */
@Composable
fun YtMusicLoginScreen(
    @Suppress("UNUSED_PARAMETER") mode: WebSessionMode,
    @Suppress("UNUSED_PARAMETER") initialCookie: String? = null,
    onCaptured: (CapturedSession) -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") captureRequest: Int = 0,
    @Suppress("UNUSED_PARAMETER") onCaptureUnavailable: () -> Unit = {},
    onPageReady: (Boolean) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") configureWebView: (WebView) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf(PurpleMusicClient.serverUrl) }
    var username by remember { mutableStateOf(PurpleMusicClient.username) }
    var password by remember { mutableStateOf("") }
    var isRegisterMode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val purpleAccent = Color(0xFFB388FF)

    LaunchedEffect(Unit) {
        onPageReady(false)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(purpleAccent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Cloud,
                        contentDescription = null,
                        tint = purpleAccent,
                        modifier = Modifier.size(30.dp),
                    )
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = "PurpleMusic",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text = "Entrez l'URL du site hébergeant votre serveur PurpleMusic, puis connectez-vous à votre compte.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(18.dp))

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = {
                        serverUrl = it
                        errorMessage = null
                    },
                    label = { Text("URL du serveur PurpleMusic") },
                    placeholder = { Text("https://music.monsite.com") },
                    leadingIcon = { Icon(Icons.Rounded.Cloud, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilterChip(
                        selected = !isRegisterMode,
                        onClick = {
                            isRegisterMode = false
                            errorMessage = null
                        },
                        label = { Text("Connexion") },
                    )
                    FilterChip(
                        selected = isRegisterMode,
                        onClick = {
                            isRegisterMode = true
                            errorMessage = null
                        },
                        label = { Text("Créer un compte") },
                    )
                }

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        errorMessage = null
                    },
                    label = { Text("Nom d'utilisateur") },
                    leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                    },
                    label = { Text(if (isRegisterMode) "Mot de passe (6 caractères min)" else "Mot de passe") },
                    leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                errorMessage?.let { msg ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(20.dp))

                Button(
                    enabled = !busy && serverUrl.isNotBlank() && username.isNotBlank() && password.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            busy = true
                            errorMessage = null
                            val res = if (isRegisterMode) {
                                PurpleMusicClient.register(serverUrl, username, password, acceptTerms = true)
                            } else {
                                PurpleMusicClient.login(serverUrl, username, password, autoAcceptTerms = true)
                            }
                            busy = false
                            res.onSuccess { outcome ->
                                val acc = outcome.account
                                val cookie = PurpleMusicClient.syntheticCookieFor(acc) ?: "SAPISID=purplemusic_${acc.userId}"
                                onCaptured(
                                    CapturedSession(
                                        cookie = cookie,
                                        dataSyncId = acc.accountId,
                                        pageId = null,
                                        authUser = "0",
                                        visitorData = acc.serverUrl,
                                        clientVersion = acc.username,
                                        loggedIn = true,
                                    ),
                                )
                            }.onFailure { err ->
                                errorMessage = err.message ?: "Échec de la connexion au serveur PurpleMusic"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = purpleAccent,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            color = Color.Black,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        text = if (isRegisterMode) "Créer le compte et se connecter" else "Se connecter à PurpleMusic",
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Spacer(Modifier.height(10.dp))

                OutlinedButton(
                    enabled = !busy && serverUrl.isNotBlank(),
                    onClick = {
                        scope.launch {
                            busy = true
                            errorMessage = null
                            val res = PurpleMusicClient.probeServer(serverUrl)
                            busy = false
                            res.onSuccess {
                                val normalized = PurpleMusicClient.normalizeServerUrl(serverUrl)
                                PurpleMusicClient.setServerUrl(normalized)
                                onCaptured(
                                    CapturedSession(
                                        cookie = "SAPISID=purple_guest_${normalized.hashCode()}",
                                        dataSyncId = "guest|$normalized",
                                        pageId = null,
                                        authUser = "0",
                                        visitorData = normalized,
                                        clientVersion = "Invité",
                                        loggedIn = true,
                                    ),
                                )
                            }.onFailure { err ->
                                errorMessage = err.message ?: "Impossible de joindre ce serveur PurpleMusic"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Utiliser cette URL sans compte")
                }
            }
        }
    }
}
