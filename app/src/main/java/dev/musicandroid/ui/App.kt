package dev.musicandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.musicandroid.data.Track
import dev.musicandroid.ui.screens.AlbumScreen
import dev.musicandroid.ui.screens.ArtistScreen
import dev.musicandroid.ui.screens.DeezerPlaylistScreen
import dev.musicandroid.ui.screens.ExploreScreen
import dev.musicandroid.ui.screens.ExploreSearch
import dev.musicandroid.ui.screens.LibraryScreen
import dev.musicandroid.ui.screens.LocalAlbumScreen
import dev.musicandroid.ui.screens.LocalArtistScreen
import dev.musicandroid.ui.screens.MyPlaylistScreen
import dev.musicandroid.ui.screens.PodcastSearch
import dev.musicandroid.ui.screens.PodcastsScreen
import dev.musicandroid.ui.screens.RadioScreen
import dev.musicandroid.ui.screens.RadioSearch
import dev.musicandroid.ui.screens.SectionScreen
import dev.musicandroid.ui.screens.SettingsScreen
import dev.musicandroid.ui.screens.ShowScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selected: ImageVector)

private val ALL_TABS = listOf(
    Tab("explore", "Explore", Icons.Outlined.Explore, Icons.Rounded.Explore),
    Tab("library", "Library", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
    Tab("radio", "Radio", Icons.Outlined.Radio, Icons.Rounded.Radio),
    Tab("podcasts", "Podcasts", Icons.Outlined.Podcasts, Icons.Rounded.Podcasts),
    Tab("settings", "Settings", Icons.Outlined.Settings, Icons.Rounded.Settings),
)

@Composable
fun App(model: AppModel) {
    val settings by model.settings.collectAsState()
    val tabs = ALL_TABS.filter {
        when (it.route) {
            "explore" -> settings.showExplore
            "radio" -> settings.showRadio
            "podcasts" -> settings.showPodcasts
            else -> true
        }
    }
    val nav = rememberNavController()
    val start = remember { tabs.first().route }
    var tab by rememberSaveable { mutableStateOf(start) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf<MenuTarget?>(null) }
    val snack = remember { SnackbarHostState() }
    val current by model.player.current.collectAsState()

    fun goTab(route: String) {
        tab = route
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    LaunchedEffect(tabs.map { it.route }) { if (tabs.none { it.route == tab }) goTab(tabs.first().route) }
    LaunchedEffect(Unit) { model.messages.collect { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(it) } }
    LaunchedEffect(Unit) { model.navigation.collect { expanded = false; nav.navigate(it) } }
    BackHandler(expanded) { expanded = false }

    val trackMenu: (Track) -> Unit = { menu = MenuTarget(it) }
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                Column {
                    if (current != null) MiniPlayer(model) { expanded = true }
                    NavigationBar {
                        tabs.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t.route,
                                onClick = { if (tab == t.route) nav.popBackStack(t.route, inclusive = false) else goTab(t.route) },
                                icon = { Icon(if (tab == t.route) t.selected else t.icon, null) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                }
            },
        ) { inner ->
            val bottom = PaddingValues(bottom = inner.calculateBottomPadding())
            Routes(nav, start, model, bottom, trackMenu) { menu = it }
        }
        AnimatedVisibility(expanded && current != null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            FullPlayer(model, onClose = { expanded = false }) { menu = it }
        }
        menu?.let { TrackMenu(it, model) { menu = null } }
    }
}

@Composable
private fun Routes(nav: NavHostController, start: String, model: AppModel, bottom: PaddingValues, onMenu: (Track) -> Unit, onTarget: (MenuTarget) -> Unit) {
    val go: (String) -> Unit = { nav.navigate(it) }
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(nav, startDestination = start) {
        composable("explore") { ExploreScreen(model, go, onMenu, bottom) }
        composable("library") { LibraryScreen(model, go, onTarget, bottom) }
        composable("radio") { RadioScreen(model, go, onMenu, bottom) }
        composable("podcasts") { PodcastsScreen(model, go, onMenu, bottom) }
        composable("settings") { SettingsScreen(model, bottom) }
        composable("search?q={q}") { ExploreSearch(model, it.arguments?.getString("q").orEmpty(), go, back, onMenu, bottom) }
        composable("radiosearch?q={q}") { RadioSearch(model, it.arguments?.getString("q").orEmpty(), back, onMenu, bottom) }
        composable("podsearch?q={q}") { PodcastSearch(model, it.arguments?.getString("q").orEmpty(), go, back, bottom) }
        composable("section/{id}") { SectionScreen(model, it.arguments?.getString("id").orEmpty(), go, back, onMenu, bottom) }
        composable("artist/{id}") { ArtistScreen(model, it.arguments?.getString("id")?.toLongOrNull() ?: 0, go, back, onMenu, bottom) }
        composable("album/{id}") { AlbumScreen(model, it.arguments?.getString("id")?.toLongOrNull() ?: 0, go, back, onMenu, bottom) }
        composable("dzplaylist/{id}") { DeezerPlaylistScreen(model, it.arguments?.getString("id")?.toLongOrNull() ?: 0, back, onMenu, bottom) }
        composable("list/{id}") { MyPlaylistScreen(model, it.arguments?.getString("id").orEmpty(), back, onTarget, bottom) }
        composable("localalbum/{key}") { LocalAlbumScreen(model, it.arguments?.getString("key").orEmpty(), go, back, onTarget, bottom) }
        composable("localartist/{name}") { LocalArtistScreen(model, it.arguments?.getString("name").orEmpty(), go, back, onTarget, bottom) }
        composable("show/{id}") { ShowScreen(model, it.arguments?.getString("id").orEmpty(), back, onMenu, bottom) }
    }
}
