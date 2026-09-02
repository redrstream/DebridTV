package io.debridtv.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import io.debridtv.app.BuildConfig
import io.debridtv.app.data.simkl.SimklClient
import io.debridtv.app.di.ServiceLocator
import io.debridtv.app.ui.Routes
import io.debridtv.app.ui.components.TvButton
import io.debridtv.app.ui.components.TvOutlinedButton
import io.debridtv.app.update.UpdateInfo
import io.debridtv.app.update.UpdateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** UI state for the "Check for updates" flow. */
private sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data object UpToDate : UpdateUi
    data class Available(val info: UpdateInfo) : UpdateUi
    data class Downloading(val pct: Int) : UpdateUi
    data class Failed(val message: String) : UpdateUi
}

/** UI state for the SimKL PIN login flow. */
private sealed interface SimklUi {
    data object Idle : SimklUi
    data object Requesting : SimklUi
    data class AwaitingAuth(val userCode: String, val url: String) : SimklUi
    data class Failed(val message: String) : SimklUi
}

@Composable
fun SettingsScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val storedKey by ServiceLocator.settings.apiKey.collectAsState(initial = null)
    val preferSurround by ServiceLocator.settings.preferSurround.collectAsState(initial = true)
    var field by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateUi>(UpdateUi.Idle) }

    // SimKL cross-device sync.
    val simklConfigured = remember { ServiceLocator.simkl.isConfigured() }
    val simklConnected by ServiceLocator.settings.simklConnected.collectAsState(initial = false)
    val simklEnabled by ServiceLocator.settings.simklEnabled.collectAsState(initial = true)
    var simklUi by remember { mutableStateOf<SimklUi>(SimklUi.Idle) }

    fun connectSimkl() {
        scope.launch {
            simklUi = SimklUi.Requesting
            val pin = runCatching { ServiceLocator.simkl.requestPin() }.getOrElse {
                simklUi = SimklUi.Failed(it.message ?: "Couldn't reach SimKL.")
                return@launch
            }
            simklUi = SimklUi.AwaitingAuth(pin.user_code, pin.verification_url)
            val deadline = System.currentTimeMillis() + pin.expires_in * 1000L
            val intervalMs = pin.interval.coerceAtLeast(1) * 1000L
            while (System.currentTimeMillis() < deadline) {
                delay(intervalMs)
                when (ServiceLocator.simkl.pollForToken(pin.user_code)) {
                    SimklClient.PollResult.Success -> { simklUi = SimklUi.Idle; return@launch }
                    SimklClient.PollResult.Pending -> {}
                    is SimklClient.PollResult.Error -> {
                        simklUi = SimklUi.Failed("SimKL error. Try again.")
                        return@launch
                    }
                }
            }
            simklUi = SimklUi.Failed("Code expired — try connecting again.")
        }
    }

    BackHandler { nav.popBackStack() }

    LaunchedEffect(storedKey) {
        if (field.isBlank() && !storedKey.isNullOrBlank()) field = storedKey!!
    }

    Column(Modifier.fillMaxSize().padding(top = 20.dp)) {
        TopBar(nav, current = Routes.SETTINGS)
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Text("AllDebrid API key", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground)
            Text(
                "Get it from alldebrid.com → My Account → API keys. Stored only on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            OutlinedTextField(
                value = field,
                onValueChange = { field = it; message = null },
                singleLine = true,
                label = { Text("API key") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TvButton(
                    enabled = !checking,
                    onClick = {
                        scope.launch {
                            checking = true
                            message = null
                            ServiceLocator.settings.setApiKey(field)
                            val result = runCatching { ServiceLocator.allDebrid.validate() }
                            message = result.fold(
                                onSuccess = { u ->
                                    val plan = if (u.isPremium) "Premium" else "Free"
                                    "Saved. Signed in as ${u.username} ($plan)."
                                },
                                onFailure = { "Saved, but validation failed: ${it.message}" }
                            )
                            checking = false
                        }
                    }
                ) { Text(if (checking) "Checking…" else "Save & verify") }

                TvOutlinedButton(onClick = {
                    scope.launch {
                        ServiceLocator.settings.clearApiKey()
                        field = ""
                        message = "Key cleared."
                    }
                }) { Text("Clear") }
            }
            message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp))
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Surround / Atmos passthrough",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        "Send 5.1 / 7.1 / Atmos to a receiver or soundbar instead of " +
                            "downmixing to stereo. Turn OFF if audio is silent or garbled " +
                            "on a TV's built-in speakers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Switch(
                    checked = preferSurround,
                    onCheckedChange = { scope.launch { ServiceLocator.settings.setPreferSurround(it) } },
                    modifier = Modifier.padding(start = 16.dp)
                )
            }

            // ---- Updates ----------------------------------------------------
            Column(Modifier.fillMaxWidth().padding(top = 32.dp)) {
                Text("Updates", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "Current version ${BuildConfig.VERSION_NAME}. Updates come from GitHub Releases.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                val u = update
                Row(
                    Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TvButton(
                        enabled = u !is UpdateUi.Checking && u !is UpdateUi.Downloading,
                        onClick = {
                            scope.launch {
                                update = UpdateUi.Checking
                                update = runCatching { UpdateManager.check() }.fold(
                                    onSuccess = { info ->
                                        if (info == null) UpdateUi.UpToDate else UpdateUi.Available(info)
                                    },
                                    onFailure = { UpdateUi.Failed(it.message ?: "Check failed") }
                                )
                            }
                        }
                    ) {
                        Text(
                            when (u) {
                                is UpdateUi.Checking -> "Checking…"
                                is UpdateUi.Downloading -> "Downloading ${u.pct}%"
                                else -> "Check for updates"
                            }
                        )
                    }

                    if (u is UpdateUi.Available) {
                        TvButton(onClick = {
                            scope.launch {
                                update = UpdateUi.Downloading(0)
                                runCatching {
                                    val file = UpdateManager.download(context, u.info) { pct ->
                                        update = UpdateUi.Downloading(pct)
                                    }
                                    UpdateManager.install(context, file)
                                }.onFailure {
                                    update = UpdateUi.Failed(it.message ?: "Download failed")
                                }.onSuccess {
                                    // Hand-off to the system installer; keep the button
                                    // available in case the user backs out of it.
                                    update = UpdateUi.Available(u.info)
                                }
                            }
                        }) { Text("Install ${u.info.versionName}") }
                    }
                }

                val status = when (val s = update) {
                    is UpdateUi.UpToDate -> "You're on the latest version."
                    is UpdateUi.Available -> "Update available: ${s.info.versionName}"
                    is UpdateUi.Downloading -> "Downloading update… ${s.pct}%"
                    is UpdateUi.Failed -> "Couldn't update: ${s.message}"
                    else -> null
                }
                status?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp))
                }
                (update as? UpdateUi.Available)?.info?.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                    Text(notes, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }

            // ---- SimKL cross-device sync -----------------------------------
            Column(Modifier.fillMaxWidth().padding(top = 32.dp)) {
                Text("SimKL sync", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "Sync watch history + resume position across your TVs — stop on one, " +
                        "pick up on another. Connect the same SimKL account on each TV.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                when {
                    !simklConfigured -> {
                        Text(
                            "Not available in this build.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    simklConnected -> {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Connected",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "Sync ${if (simklEnabled) "on" else "off"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = simklEnabled,
                                onCheckedChange = {
                                    scope.launch { ServiceLocator.settings.setSimklEnabled(it) }
                                },
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                        Row(Modifier.padding(top = 12.dp)) {
                            TvOutlinedButton(onClick = {
                                scope.launch {
                                    ServiceLocator.simkl.disconnect()
                                    simklUi = SimklUi.Idle
                                }
                            }) { Text("Disconnect") }
                        }
                    }

                    else -> when (val s = simklUi) {
                        is SimklUi.AwaitingAuth -> {
                            Text(
                                "On your phone or computer, go to:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                s.url,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            Text(
                                "and enter this code:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                            Text(
                                s.userCode,
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            Text(
                                "Waiting for you to authorize…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }

                        else -> {
                            TvButton(
                                enabled = s !is SimklUi.Requesting,
                                onClick = { connectSimkl() }
                            ) {
                                Text(if (s is SimklUi.Requesting) "Starting…" else "Connect SimKL")
                            }
                            if (s is SimklUi.Failed) {
                                Text(
                                    s.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
