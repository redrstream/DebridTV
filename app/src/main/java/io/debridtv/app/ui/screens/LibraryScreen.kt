package io.debridtv.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import io.debridtv.app.data.alldebrid.MagnetInfo
import io.debridtv.app.di.ServiceLocator
import io.debridtv.app.ui.Routes
import io.debridtv.app.ui.player.PlayerActivity
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiKey by ServiceLocator.settings.apiKey.collectAsState(initial = null)
    val preferSurround by ServiceLocator.settings.preferSurround.collectAsState(initial = true)

    BackHandler { nav.popBackStack() }

    var magnets by remember { mutableStateOf<List<MagnetInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(apiKey) {
        if (apiKey.isNullOrBlank()) { loading = false; return@LaunchedEffect }
        loading = true
        error = null
        runCatching { ServiceLocator.allDebrid.listMagnets() }
            .onSuccess { magnets = it; loading = false }
            .onFailure { error = it.message; loading = false }
    }

    fun playMagnet(info: MagnetInfo) {
        if (!info.isReady) {
            Toast.makeText(context, "Not ready yet (${info.status ?: "downloading"})", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        scope.launch {
            try {
                val key = "ad:${info.id}"
                val start = ServiceLocator.history.get(key)?.positionMs ?: 0L
                val resolved = ServiceLocator.resolver.resolveReadyMagnet(info)
                busy = false
                PlayerActivity.start(
                    context = context,
                    url = resolved.url,
                    title = info.filename ?: resolved.filename,
                    key = key,
                    type = "debrid",
                    poster = null,
                    startMs = start,
                    preferSurround = preferSurround
                )
            } catch (e: Exception) {
                busy = false
                Toast.makeText(context, e.message ?: "Failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = 20.dp)) {
            TopBar(nav, current = Routes.LIBRARY)
            Text(
                "On AllDebrid",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            when {
                apiKey.isNullOrBlank() -> Msg("Add your AllDebrid API key in Settings.")
                loading -> Msg("Loading your AllDebrid items…")
                error != null -> Msg("Error: $error")
                magnets.isEmpty() -> Msg("Nothing on your AllDebrid account yet.")
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                    items(magnets, key = { it.id ?: it.hashCode().toLong() }) { m ->
                        MagnetRow(m) { playMagnet(m) }
                    }
                }
            }
        }
        if (busy) {
            Box(
                Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xCC000000)),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun Msg(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp))
}

@Composable
private fun MagnetRow(m: MagnetInfo, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val statusLine = if (m.isReady) "Ready" else "${m.status ?: "…"} · ${m.progressPercent}%"
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(12.dp)
    ) {
        Column {
            Text(m.filename ?: "Untitled", color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold, maxLines = 1)
            Text(
                statusLine,
                style = MaterialTheme.typography.bodySmall,
                color = if (m.isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
