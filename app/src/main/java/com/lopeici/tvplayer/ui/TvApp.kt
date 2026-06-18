package com.lopeici.tvplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lopeici.tvplayer.data.Channel
import com.lopeici.tvplayer.ui.components.MiniPlayer
import com.lopeici.tvplayer.ui.components.PlayerSurface
import com.lopeici.tvplayer.ui.screens.ChannelsScreen
import com.lopeici.tvplayer.ui.screens.FavoritesScreen
import com.lopeici.tvplayer.ui.screens.PlayerContent
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

/** Switches between the phone (compact) layout and a two-pane layout on wide / unfolded screens. */
@Composable
fun TvApp(vm: TvViewModel, isInPip: Boolean = false, onImportFile: () -> Unit) {
    if (isInPip) {
        // In Picture-in-Picture, show only the video surface (no list/controls).
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PlayerSurface(vm.playerManager.player, Modifier.fillMaxSize())
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 720.dp) {
            WideLayout(vm, onImportFile)
        } else {
            CompactLayout(vm, onImportFile)
        }
    }
}

/** Two-pane layout for unfolded foldables / tablets: rail + channel list + always-present player. */
@Composable
private fun WideLayout(vm: TvViewModel, onImportFile: () -> Unit) {
    var selected by remember { mutableStateOf(Tab.Channels) }
    var listVisible by remember { mutableStateOf(true) }
    var playerFullScreen by remember { mutableStateOf(false) }
    val onPlay: (Channel, List<Channel>) -> Unit = { channel, queue -> vm.play(channel, queue) }

    if (playerFullScreen) {
        BackHandler { playerFullScreen = false }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PlayerContent(vm, fullScreen = true, onToggleFullScreen = { playerFullScreen = false })
        }
        return
    }

    Row(Modifier.fillMaxSize()) {
        NavigationRail {
            Tab.entries.forEach { tab ->
                NavigationRailItem(
                    selected = selected == tab,
                    onClick = { selected = tab },
                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                    label = { Text(tab.label) },
                )
            }
        }
        if (listVisible) {
            Box(Modifier.weight(1f).fillMaxHeight().statusBarsPadding()) {
                when (selected) {
                    Tab.Channels -> ChannelsScreen(vm, onPlay)
                    Tab.Favorites -> FavoritesScreen(vm, onPlay)
                    Tab.Recents -> RecentsScreen(vm, onPlay)
                    Tab.Playlists -> PlaylistsScreen(vm, onImportFile)
                }
            }
            VerticalDivider()
        }
        Box(Modifier.weight(1.6f).fillMaxHeight().background(Color.Black)) {
            PlayerContent(
                vm,
                fullScreen = false,
                onToggleFullScreen = { playerFullScreen = true },
                onToggleList = { listVisible = !listVisible },
                listVisible = listVisible,
            )
        }
    }
}

/** Phone layout: bottom navigation + a full-screen player route. */
@Composable
private fun CompactLayout(vm: TvViewModel, onImportFile: () -> Unit) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = Tab.entries.any { it.route == currentRoute }

    val currentChannel by vm.currentChannel.collectAsStateWithLifecycle()
    val isPlaying by vm.isPlaying.collectAsStateWithLifecycle()
    val nowNext by vm.currentNowNext.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            Column {
                val playing = currentChannel
                if (playing != null && currentRoute != ROUTE_PLAYER) {
                    MiniPlayer(
                        channel = playing,
                        nowTitle = nowNext.first?.title,
                        isPlaying = isPlaying,
                        onPlayPause = { vm.togglePlayPause() },
                        onClick = { navController.navigate(ROUTE_PLAYER) { launchSingleTop = true } },
                    )
                }
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
