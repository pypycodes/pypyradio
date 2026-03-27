package com.pypyradio.aacplayer.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
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
    
    // Retry state for auto-reconnect
    var retryCount by remember { mutableStateOf(0) }
    val maxRetries = 5
    val handler = remember { Handler(Looper.getMainLooper()) }
    
    // Shared ExoPlayer instance with optimized buffering for streaming
    val player = remember {
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                5000,   // Min buffer before playback starts (5 sec)
                60000,  // Max buffer size (60 sec - larger for stability)
                2500,   // Buffer for playback (2.5 sec)
                5000    // Buffer for rebuffering (5 sec)
            )
            .build()
        
        androidx.media3.exoplayer.ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build().apply {
                playWhenReady = true
            }
    }
    
    // Track current playing media ID for favorites
    var currentMediaId by remember { mutableStateOf<String?>(null) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isRetrying by remember { mutableStateOf(false) }
    
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onEvents(p: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) {
                currentMediaId = p.currentMediaItem?.mediaId
            }
            
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Auto-retry on network/buffering errors (like Spotify)
                if (retryCount < maxRetries && player.currentMediaItem != null) {
                    isRetrying = true
                    hasError = false
                    errorMessage = null
                    
                    // Exponential backoff: 2s, 4s, 6s, 8s, 10s
                    val delayMs = (retryCount + 1) * 2000L
                    retryCount++
                    
                    handler.postDelayed({
                        try {
                            player.prepare()
                            player.play()
                        } catch (e: Exception) {
                            // Ignore retry errors
                        }
                    }, delayMs)
                } else {
                    // Max retries reached, show error
                    hasError = true
                    errorMessage = "Failed to play"
                    isRetrying = false
                    retryCount = 0
                }
            }
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    // Playback recovered, reset retry count
                    hasError = false
                    errorMessage = null
                    isRetrying = false
                    retryCount = 0
                }
            }
            
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                // Reset retry count when changing stations
                retryCount = 0
                isRetrying = false
            }
        }
        player.addListener(listener)
        onDispose { 
            handler.removeCallbacksAndMessages(null)
            player.removeListener(listener) 
        }
    }
    
    // Clean up player when app closes
    DisposableEffect(Unit) {
        onDispose {
            player.release()
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
                    player.release()
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
        
        Scaffold(
            bottomBar = { 
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
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
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
