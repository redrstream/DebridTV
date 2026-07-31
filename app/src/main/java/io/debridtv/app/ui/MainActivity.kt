package io.debridtv.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.debridtv.app.ui.screens.DetailScreen
import io.debridtv.app.ui.screens.HomeScreen
import io.debridtv.app.ui.screens.LibraryScreen
import io.debridtv.app.ui.screens.SearchScreen
import io.debridtv.app.ui.screens.SettingsScreen
import io.debridtv.app.ui.theme.DebridTvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DebridTvTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNav()
                }
            }
        }
    }
}

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    fun detail(type: String, id: String, season: Int? = null, episode: Int? = null): String {
        val base = "detail/$type/$id"
        return if (season != null && episode != null) "$base?s=$season&e=$episode" else base
    }
}

@Composable
private fun AppNav() {
    val nav: NavHostController = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(nav) }
        composable(Routes.SEARCH) { SearchScreen(nav) }
        composable(Routes.LIBRARY) { LibraryScreen(nav) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable(
            route = "detail/{type}/{id}?s={s}&e={e}",
            arguments = listOf(
                navArgument("type") { type = NavType.StringType },
                navArgument("id") { type = NavType.StringType },
                navArgument("s") { type = NavType.IntType; defaultValue = -1 },
                navArgument("e") { type = NavType.IntType; defaultValue = -1 }
            )
        ) { entry ->
            DetailScreen(
                nav = nav,
                type = entry.arguments?.getString("type") ?: "movie",
                id = entry.arguments?.getString("id") ?: "",
                season = (entry.arguments?.getInt("s") ?: -1).takeIf { it > 0 },
                episode = (entry.arguments?.getInt("e") ?: -1).takeIf { it > 0 }
            )
        }
    }
}
