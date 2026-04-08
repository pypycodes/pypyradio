package com.pypyradio.aacplayer.ui

import android.app.Activity
import android.content.ComponentName
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.pypyradio.aacplayer.playback.RadioPlaybackService
import com.pypyradio.aacplayer.ui.components.SimpleNowPlayingBar
import com.pypyradio.aacplayer.ui.screens.AboutScreen
import com.pypyradio.aacplayer.ui.screens.BrowseScreen
import com.pypyradio.aacplayer.ui.screens.FavoritesScreen
import com.pypyradio.aacplayer.ui.screens.PodcastScreen
import com.pypyradio.aacplayer.ui.vm.StationsViewModel

@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf("browse") }
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
        // Initialize current media ID
        currentMediaId = ctrl.currentMediaItem?.mediaId
        
        onDispose { 
            ctrl.removeListener(listener) 
        }
    }
    
    // Handle back button
    BackHandler(enabled = true) {
        when (screen) {
            "browse" -> showExitDialog = true
            else -> screen = "browse"
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
        
        Scaffold(
            bottomBar = { 
                if (player != null) {
                    SimpleNowPlayingBar(
                        player = player,
                        isFavorite = isCurrentFavorite,
                        onToggleFavorite = {
                            currentMediaId?.let { mediaId ->
                                // Find the station by mediaId and toggle favorite
                                val station = favorites.find { it.stationuuid == mediaId }
                                if (station != null) {
                                    vm.toggleFavorite(station)
                                } else {
                                    // Try to find in browse stations
                                    val browseState = vm.browse.value
                                    browseState.stations.find { it.stationuuid == mediaId }?.let { st ->
                                        vm.toggleFavorite(st)
                                    }
                                }
                            }
                        },
                        onStationFailed = { failedMediaId ->
                            // Mark station as failed in ViewModel
                            vm.markStationFailed(failedMediaId, "Playback failed")
                        }
                    )
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
            
            when (screen) {
                "browse" -> BrowseScreen(
                    vm = vm,
                    player = player,
                    onGoFavorites = { screen = "fav" },
                    onGoAbout = { screen = "about" },
                    onGoPodcasts = { screen = "podcasts" },
                    snackbarHostState = snackbarHostState,
                    modifier = Modifier.padding(padding)
                )
                "fav" -> FavoritesScreen(vm = vm, player = player, onBack = { screen = "browse" }, modifier = Modifier.padding(padding))
                "about" -> AboutScreen(onBack = { screen = "browse" }, modifier = Modifier.padding(padding))
                "podcasts" -> PodcastScreen(player = player, onBack = { screen = "browse" }, modifier = Modifier.padding(padding))
                else -> BrowseScreen(
                    vm = vm,
                    player = player,
                    onGoFavorites = { screen = "fav" },
                    onGoAbout = { screen = "about" },
                    onGoPodcasts = { screen = "podcasts" },
                    snackbarHostState = snackbarHostState,
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}
