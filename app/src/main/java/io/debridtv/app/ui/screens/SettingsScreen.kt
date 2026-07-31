package io.debridtv.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import io.debridtv.app.di.ServiceLocator
import io.debridtv.app.ui.Routes
import io.debridtv.app.ui.components.TvButton
import io.debridtv.app.ui.components.TvOutlinedButton
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val storedKey by ServiceLocator.settings.apiKey.collectAsState(initial = null)
    val preferSurround by ServiceLocator.settings.preferSurround.collectAsState(initial = true)
    var field by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    BackHandler { nav.popBackStack() }

    LaunchedEffect(storedKey) {
        if (field.isBlank() && !storedKey.isNullOrBlank()) field = storedKey!!
    }

    Column(Modifier.fillMaxSize().padding(top = 20.dp)) {
        TopBar(nav, current = Routes.SETTINGS)
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
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
        }
    }
}
