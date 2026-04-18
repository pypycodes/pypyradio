package com.pypyradio.aacplayer.ui

import android.app.Activity
import android.content.ComponentName
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.tv.foundation.lazy.grid.TvGridCells
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import androidx.tv.foundation.lazy.grid.items
import androidx.tv.material3.*
import com.google.common.util.concurrent.MoreExecutors
import com.pypyradio.aacplayer.playback.RadioPlaybackService
import com.pypyradio.aacplayer.ui.vm.StationsViewModel
import com.pypyradio.aacplayer.data.model.Station
import coil.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.ui.text.style.TextOverflow
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvAppRoot(vm: StationsViewModel = viewModel()) {
    val context = LocalContext.current
    
    // Connect to RadioPlaybackService
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var isConnecting by remember { mutableStateOf(true) }
    
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
    
    val state by vm.browse.collectAsState()
    val favorites by vm.favorites.collectAsState()
    
    // Track selected tab on TV
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("Discovery", "Favorites")
    
    val stations = remember(state.stations, favorites, selectedTabIndex) {
        if (selectedTabIndex == 0) {
            state.stations.filter { it.urlResolved.isNotBlank() }
        } else {
            favorites
        }
    }
    
    // Track playback state
    var currentMediaId by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    
    DisposableEffect(controller) {
        val ctrl = controller ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                currentMediaId = player.currentMediaItem?.mediaId
                isPlaying = player.isPlaying
            }
        }
        ctrl.addListener(listener)
        currentMediaId = ctrl.currentMediaItem?.mediaId
        isPlaying = ctrl.isPlaying
        onDispose { ctrl.removeListener(listener) }
    }
    
    LaunchedEffect(Unit) {
        vm.loadTop()
    }
    
    MaterialTheme(
        colorScheme = darkColorScheme()
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RectangleShape
        ) {
            if (isConnecting) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Connecting to Radio Service...")
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    // Left Panel: Now Playing
                    Box(
                        modifier = Modifier
                            .weight(0.4f)
                            .fillMaxHeight()
                            .padding(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        TvNowPlaying(controller, isPlaying)
                    }
                    
                    // Right Panel: Station Grid
                    Column(
                        modifier = Modifier
                            .weight(0.6f)
                            .fillMaxHeight()
                            .padding(top = 48.dp, end = 48.dp, bottom = 48.dp)
                    ) {
                        // TV Navigation Tabs
                        TabRow(
                            selectedTabIndex = selectedTabIndex,
                            indicator = { tabPositions, _ ->
                                TabRowDefaults.PillIndicator(
                                    currentTabPosition = tabPositions[selectedTabIndex],
                                    doesTabRowHaveFocus = true, // Required in alpha10
                                    activeColor = MaterialTheme.colorScheme.primaryContainer,
                                    inactiveColor = androidx.compose.ui.graphics.Color.Transparent
                                )
                            },
                            modifier = Modifier.padding(bottom = 24.dp)
                        ) {
                            tabs.forEachIndexed { index, title ->
                                Tab(
                                    selected = selectedTabIndex == index,
                                    onFocus = { selectedTabIndex = index },
                                    onClick = { selectedTabIndex = index }
                                ) {
                                    Text(
                                        title,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                        }
                        
                        if (stations.isEmpty() && selectedTabIndex == 1) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No favorites added yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            TvLazyVerticalGrid(
                                columns = TvGridCells.Fixed(3),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(stations, key = { it.stationuuid }) { station ->
                                    TvStationCard(
                                        station = station,
                                        isCurrent = station.stationuuid == currentMediaId,
                                        onClick = {
                                            playStation(controller, station, stations)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvStationCard(station: Station, isCurrent: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = ClickableSurfaceDefaults.shape(shape = MaterialTheme.shapes.medium),
        modifier = Modifier.aspectRatio(1f)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AsyncImage(
                model = station.favicon,
                contentDescription = null,
                modifier = Modifier.size(64.dp).padding(bottom = 8.dp)
            )
            Text(
                station.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (isCurrent) {
                Text(
                    "Playing",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvNowPlaying(player: Player?, isPlaying: Boolean) {
    val mediaItem = player?.currentMediaItem
    val metadata = mediaItem?.mediaMetadata
    
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.size(200.dp).padding(bottom = 24.dp)
        ) {
            AsyncImage(
                model = metadata?.artworkUri ?: metadata?.artworkData,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        }
        
        Text(
            metadata?.title?.toString() ?: "Select a Station",
            style = MaterialTheme.typography.headlineLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            metadata?.artist?.toString() ?: "PyPy Radio",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(Modifier.height(32.dp))
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { if (isPlaying) player?.pause() else player?.play() }) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isPlaying) "Pause" else "Play")
            }
        }
    }
}

private fun playStation(player: Player?, st: Station, allStations: List<Station>) {
    val ctrl = player ?: return
    
    // Reuse the same logic as phone but simplified for TV
    val mediaItems = allStations.map { station ->
        MediaItem.Builder()
            .setMediaId(station.stationuuid)
            .setUri(station.urlResolved)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(station.name)
                    .setArtist(station.countryCode ?: "Radio")
                    .setArtworkUri(station.favicon?.let { android.net.Uri.parse(it) })
                    .build()
            )
            .build()
    }
    
    val index = allStations.indexOfFirst { it.stationuuid == st.stationuuid }.coerceAtLeast(0)
    ctrl.setMediaItems(mediaItems, index, 0L)
    ctrl.prepare()
    ctrl.play()
}
