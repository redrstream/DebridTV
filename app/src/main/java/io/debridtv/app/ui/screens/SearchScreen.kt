package io.debridtv.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import io.debridtv.app.di.ServiceLocator
import io.debridtv.app.ui.Routes
import io.debridtv.app.ui.components.CardItem
import io.debridtv.app.ui.components.PosterCard
import io.debridtv.app.ui.components.TvButton
import io.debridtv.app.ui.components.toCard
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(nav: NavHostController) {
    val repo = ServiceLocator.mediaRepo
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CardItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var retryTick by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }

    BackHandler { nav.popBackStack() }

    LaunchedEffect(query, retryTick) {
        if (query.trim().length < 2) {
            results = emptyList()
            failed = false
            return@LaunchedEffect
        }
        loading = true
        failed = false
        delay(400) // debounce
        try {
            results = repo.search(query.trim()).map { it.toCard(it.type ?: "movie") }
        } catch (e: Exception) {
            results = emptyList()
            failed = true
        }
        loading = false
    }

    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.fillMaxSize().padding(top = 20.dp)) {
        TopBar(nav, current = Routes.SEARCH)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search movies & shows") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .focusRequester(focus)
        )

        when {
            loading && results.isEmpty() ->
                Text("Searching…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp))
            failed ->
                Column(Modifier.padding(24.dp)) {
                    Text("Couldn't search — check your connection.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TvButton(onClick = { retryTick++ }, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Retry")
                    }
                }
            query.trim().length >= 2 && results.isEmpty() ->
                Text("No results", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp))
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(140.dp),
            contentPadding = PaddingValues(24.dp)
        ) {
            items(results, key = { it.type + it.id }) { item ->
                PosterCard(
                    item = item,
                    onClick = { nav.navigate(Routes.detail(item.type, item.id)) },
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}
