package com.pypyradio.aacplayer.ui

import android.app.Activity
import android.content.ComponentName
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.pypyradio.aacplayer.playback.RadioPlaybackService
import com.pypyradio.aacplayer.ui.components.SimpleNowPlayingBar
import com.pypyradio.aacplayer.ui.components.SleepTimerButton
import com.pypyradio.aacplayer.ui.components.SleepTimerDialog
import com.pypyradio.aacplayer.ui.components.rememberSleepTimerState
import com.pypyradio.aacplayer.ui.screens.AboutScreen
import com.pypyradio.aacplayer.ui.screens.BrowseScreen
import com.pypyradio.aacplayer.ui.screens.FavoritesScreen
import com.pypyradio.aacplayer.ui.screens.PodcastScreen
import com.pypyradio.aacplayer.ui.vm.StationsViewModel

// Main navigation tabs
private enum class MainNavTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    RADIO("Radio", Icons.Filled.Radio, Icons.Outlined.Radio),
    PODCASTS("Podcasts", Icons.Filled.Podcasts, Icons.Outlined.Podcasts),
    FAVORITES("Favorites", Icons.Filled.Favorite, Icons.Outlined.FavoriteBorder)
}

@Composable
fun AppRoot() {
    var selectedTab by remember { mutableStateOf(MainNavTab.RADIO) }
    var showAbout by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    
    val darkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val activity = context as? Activity
    
    val colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
    
    // Connect to RadioPlaybackService via MediaController for background playback
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var isConnecting by remember { mutableStateOf(true) }
    
    // Connect to the playback service
    DisposableEffect(context) {
        val sessionToken = SessionToken(context, ComponentName(context, RadioPlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        
        controllerFuture.addListener({
            try {
                controller = controllerFuture.get()
                isConnecting = false
            } catch (e: Exception) {
                isConnecting = false
            }
        }, MoreExecutors.directExecutor())
        
        onDispose {
            MediaController.releaseFuture(controllerFuture)
            controller = null
        }
    }
    
    // Track current playing media ID for favorites
    var currentMediaId by remember { mutableStateOf<String?>(null) }
    
    // Listen to controller state changes
    DisposableEffect(controller) {
        val ctrl = controller ?: return@DisposableEffect onDispose { }
        
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                currentMediaId = player.currentMediaItem?.mediaId
            }
        }
        ctrl.addListener(listener)
        currentMediaId = ctrl.currentMediaItem?.mediaId
        
        onDispose { 
            ctrl.removeListener(listener) 
        }
    }
    
    // Handle back button
    BackHandler(enabled = true) {
        when {
            showAbout -> showAbout = false
            selectedTab != MainNavTab.RADIO -> selectedTab = MainNavTab.RADIO
            else -> showExitDialog = true
        }
    }
    
    // Exit confirmation dialog
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Exit App") },
            text = { Text("Are you sure you want to exit?") },
            confirmButton = {
                TextButton(onClick = { 
                    controller?.stop()
                    activity?.finish() 
                }) {
                    Text("Exit")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    MaterialTheme(colorScheme = colorScheme) {
        val vm: StationsViewModel = viewModel()
        val favorites by vm.favorites.collectAsState()
        val favoriteIds = remember(favorites) { favorites.map { it.stationuuid }.toSet() }
        
        // Check if current playing item is a favorite
        val isCurrentFavorite = currentMediaId != null && favoriteIds.contains(currentMediaId)
        
        // Show loading while connecting to service
        if (isConnecting) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@MaterialTheme
        }
        
        val player = controller
        
        // Show About screen if requested
        if (showAbout && player != null) {
            AboutScreen(onBack = { showAbout = false })
            return@MaterialTheme
        }
        
        // Sleep timer state
        val sleepTimerState = rememberSleepTimerState(
            onTimerEnd = {
                player?.stop()
            }
        )
        
        // Sleep timer dialog
        SleepTimerDialog(
            isVisible = sleepTimerState.showDialog,
            currentMinutes = sleepTimerState.remainingMinutes,
            onDismiss = sleepTimerState.onDismissDialog,
            onSetTimer = sleepTimerState.onSetTimer
        )
        
        Scaffold(
            bottomBar = { 
                Column {
                    // Now Playing Bar with Sleep Timer
                    if (player != null) {
                        SimpleNowPlayingBar(
                            player = player,
                            isFavorite = isCurrentFavorite,
                            onToggleFavorite = {
                                currentMediaId?.let { mediaId ->
                                    val station = favorites.find { it.stationuuid == mediaId }
                                    if (station != null) {
                                        vm.toggleFavorite(station)
                                    } else {
                                        val browseState = vm.browse.value
                                        browseState.stations.find { it.stationuuid == mediaId }?.let { st ->
                                            vm.toggleFavorite(st)
                                        }
                                    }
                                }
                            },
                            onStationFailed = { failedMediaId ->
                                vm.markStationFailed(failedMediaId, "Playback failed")
                            },
                            sleepTimerMinutes = sleepTimerState.remainingMinutes,
                            onSleepTimerClick = sleepTimerState.onShowDialog
                        )
                    }
                    
                    // Bottom Navigation Bar
                    NavigationBar {
                        MainNavTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selectedTab == tab) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) }
                            )
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            if (player == null) {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("Connecting to playback service...")
                }
                return@Scaffold
            }
            
            when (selectedTab) {
                MainNavTab.RADIO -> BrowseScreen(
                    vm = vm,
                    player = player,
                    onGoAbout = { showAbout = true },
                    snackbarHostState = snackbarHostState,
                    modifier = Modifier.padding(padding)
                )
                MainNavTab.PODCASTS -> PodcastScreen(
                    player = player,
                    modifier = Modifier.padding(padding)
                )
                MainNavTab.FAVORITES -> FavoritesScreen(
                    vm = vm,
                    player = player,
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}
