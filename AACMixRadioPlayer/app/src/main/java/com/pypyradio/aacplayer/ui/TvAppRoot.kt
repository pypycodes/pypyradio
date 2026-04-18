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
import androidx.tv.foundation.lazy.grid.TvGridItemSpan
import androidx.tv.material3.*
import com.google.common.util.concurrent.MoreExecutors
import com.pypyradio.aacplayer.playback.RadioPlaybackService
import com.pypyradio.aacplayer.ui.vm.StationsViewModel
import com.pypyradio.aacplayer.ui.vm.PodcastViewModel
import com.pypyradio.aacplayer.data.model.Station
import com.pypyradio.aacplayer.data.model.Podcast
import com.pypyradio.aacplayer.data.model.PodcastEpisode
import coil.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Star
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.text.KeyboardOptions

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvAppRoot(
    vm: StationsViewModel = viewModel(),
    pvm: PodcastViewModel = viewModel()
) {
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
    
    // State and Tab variables
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("Radio", "Podcasts", "Favorites")
    
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
                        TvNowPlaying(
                            controller, 
                            isPlaying, 
                            vm,
                            onToggleFavorite = { vm.toggleFavorite(it) }
                        )
                    }
                    
                    // Right Panel: Content Area
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
                        
                        when (selectedTabIndex) {
                            0 -> TvRadioSection(vm, currentMediaId, controller)
                            1 -> TvPodcastSection(pvm, controller)
                            2 -> TvFavoritesSection(vm, pvm, currentMediaId, controller)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvStationCard(station: Station, isCurrent: Boolean, isFavorite: Boolean, onClick: () -> Unit) {
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
            Box(contentAlignment = Alignment.TopEnd) {
                AsyncImage(
                    model = station.favicon,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp).padding(bottom = 8.dp)
                )
                if (isFavorite) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp).offset(x = 4.dp, y = (-4).dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
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
fun TvNowPlaying(
    player: Player?, 
    isPlaying: Boolean, 
    vm: StationsViewModel,
    onToggleFavorite: (Station) -> Unit
) {
    val mediaItem = player?.currentMediaItem
    val metadata = mediaItem?.mediaMetadata
    val state by vm.browse.collectAsState()
    val favorites by vm.favorites.collectAsState()
    
    val currentStation = remember(mediaItem, state.stations) {
        state.stations.find { it.stationuuid == mediaItem?.mediaId }
    }
    val isFavorite = favorites.any { it.stationuuid == currentStation?.stationuuid }
    
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
            
            if (currentStation != null) {
                Spacer(Modifier.width(16.dp))
                OutlinedButton(onClick = { onToggleFavorite(currentStation) }) {
                    Icon(
                        if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (isFavorite) "Unfavorite" else "Favorite")
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvRadioSection(vm: StationsViewModel, currentMediaId: String?, controller: Player?) {
    val state by vm.browse.collectAsState()
    val favorites by vm.favorites.collectAsState()
    
    Column {
        TextField(
            value = state.query,
            onValueChange = { vm.setQuery(it); vm.search() },
            placeholder = { Text("Search Radio Stations...") },
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )
        
        val stations = state.stations.filter { it.urlResolved.isNotBlank() }
        
        TvLazyVerticalGrid(
            columns = TvGridCells.Fixed(3),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(stations, key = { it.stationuuid }) { station ->
                val isFavorite = favorites.any { it.stationuuid == station.stationuuid }
                TvStationCard(
                    station = station,
                    isCurrent = station.stationuuid == currentMediaId,
                    isFavorite = isFavorite,
                    onClick = { playStation(controller, station, stations) }
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvPodcastSection(pvm: PodcastViewModel, controller: Player?) {
    val state by pvm.state.collectAsState()
    
    Column {
        if (!state.showingEpisodes) {
            TextField(
                value = state.query,
                onValueChange = { pvm.setQuery(it); pvm.search() },
                placeholder = { Text("Search Podcasts...") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
            )
            
            TvLazyVerticalGrid(
                columns = TvGridCells.Fixed(3),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(state.podcasts, key = { it.id }) { podcast ->
                    TvPodcastCard(podcast = podcast, onClick = { pvm.loadEpisodes(podcast) })
                }
            }
        } else {
            // Show episodes
            Column {
                Button(onClick = { pvm.backToPodcasts() }) {
                    Text("Back to Search")
                }
                Spacer(Modifier.height(16.dp))
                Text(state.selectedPodcast?.title ?: "Episodes", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                
                TvLazyVerticalGrid(
                    columns = TvGridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.episodes) { episode ->
                        Surface(
                            onClick = { playPodcast(controller, episode) },
                            modifier = Modifier.padding(8.dp).fillMaxWidth()
                        ) {
                            Text(episode.title, modifier = Modifier.padding(8.dp), maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvFavoritesSection(vm: StationsViewModel, pvm: PodcastViewModel, currentMediaId: String?, controller: Player?) {
    val favorites by vm.favorites.collectAsState()
    val pFavorites by pvm.favorites.collectAsState()
    
    if (favorites.isEmpty() && pFavorites.isEmpty()) {
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
            if (favorites.isNotEmpty()) {
                item(span = { TvGridItemSpan(3) }) {
                    Text("Favorite Radio Stations", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp, top = 16.dp))
                }
                items(favorites, key = { it.stationuuid }) { station ->
                    TvStationCard(
                        station = station,
                        isCurrent = station.stationuuid == currentMediaId,
                        isFavorite = true,
                        onClick = { playStation(controller, station, favorites) }
                    )
                }
            }
            
            if (pFavorites.isNotEmpty()) {
                item(span = { TvGridItemSpan(3) }) {
                    Text("Favorite Podcasts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp, top = 24.dp))
                }
                items(pFavorites, key = { it.id }) { podcast ->
                    TvPodcastCard(podcast = podcast, onClick = { pvm.loadEpisodes(podcast) })
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvPodcastCard(podcast: Podcast, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        modifier = Modifier.aspectRatio(1f)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AsyncImage(
                model = podcast.imageUrl,
                contentDescription = null,
                modifier = Modifier.size(64.dp).padding(bottom = 8.dp)
            )
            Text(
                podcast.title,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun playStation(player: Player?, st: Station, allStations: List<Station>) {
    val ctrl = player ?: return
    
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

private fun playPodcast(player: Player?, episode: PodcastEpisode) {
    val ctrl = player ?: return
    val mediaItem = MediaItem.Builder()
        .setMediaId(episode.id)
        .setUri(episode.audioUrl)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(episode.title)
                .setArtist("Podcast")
                .setArtworkUri(episode.imageUrl?.let { android.net.Uri.parse(it) })
                .build()
        )
        .build()
    
    ctrl.setMediaItem(mediaItem)
    ctrl.prepare()
    ctrl.play()
}
