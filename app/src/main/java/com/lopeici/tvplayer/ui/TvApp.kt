package com.lopeici.tvplayer.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lopeici.tvplayer.data.Channel
import com.lopeici.tvplayer.ui.screens.ChannelsScreen
import com.lopeici.tvplayer.ui.screens.FavoritesScreen
import com.lopeici.tvplayer.ui.screens.PlayerScreen
import com.lopeici.tvplayer.ui.screens.PlaylistsScreen
import com.lopeici.tvplayer.ui.screens.RecentsScreen

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Channels("channels", "Channels", Icons.Filled.LiveTv),
    Favorites("favorites", "Favorites", Icons.Filled.Favorite),
    Recents("recents", "Recent", Icons.Filled.History),
    Playlists("playlists", "Playlists", Icons.Filled.VideoLibrary),
}

private const val ROUTE_PLAYER = "player"

@Composable
fun TvApp(vm: TvViewModel, onImportFile: () -> Unit) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = Tab.entries.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val onPlay: (Channel, List<Channel>) -> Unit = { channel, queue ->
            vm.play(channel, queue)
            navController.navigate(ROUTE_PLAYER)
        }
        NavHost(
            navController = navController,
            startDestination = Tab.Channels.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Tab.Channels.route) { ChannelsScreen(vm, onPlay) }
            composable(Tab.Favorites.route) { FavoritesScreen(vm, onPlay) }
            composable(Tab.Recents.route) { RecentsScreen(vm, onPlay) }
            composable(Tab.Playlists.route) { PlaylistsScreen(vm, onImportFile) }
            composable(ROUTE_PLAYER) { PlayerScreen(vm, onBack = { navController.popBackStack() }) }
        }
    }
}
