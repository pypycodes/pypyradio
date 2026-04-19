package com.pypyradio.aacplayer.ui

import android.app.Activity
import android.content.ComponentName
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Color
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
import coil.request.ImageRequest
import com.pypyradio.aacplayer.R
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
import androidx.compose.material3.IconButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator

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
    var playbackState by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    DisposableEffect(controller) {
        val ctrl = controller ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                currentMediaId = player.currentMediaItem?.mediaId
                isPlaying = player.isPlaying
                playbackState = player.playbackState
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                errorMessage = error.localizedMessage
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
    
    val TvPremiumPalette = darkColorScheme(
        primary = Color(0xFF42A5F5), // Vibrant Blue Focus
        surface = Color(0xFF121212), // Deep Ebony background
        onSurface = Color.White,     // High contrast text
        onSurfaceVariant = Color(0xFFCFD8DC), // Gray text
        secondaryContainer = Color(0xFF263238), // Focused surface
        onSecondaryContainer = Color.White,     // White text on focus
        primaryContainer = Color(0xFF1976D2),
        onPrimaryContainer = Color.White
    )

    MaterialTheme(
        colorScheme = TvPremiumPalette
    ) {
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RectangleShape
            ) {
                if (isConnecting) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Connecting to Radio Service...", color = Color.White)
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
                            playbackState,
                            errorMessage,
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
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selectedTabIndex == index) Color.White else Color.White.copy(alpha = 0.6f)
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
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvStationCard(station: Station, isCurrent: Boolean, isFavorite: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContentColor = MaterialTheme.colorScheme.onSecondaryContainer
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
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(station.favicon)
                        .crossfade(true)
                        .error(R.drawable.pypyradio_fallback_cover_art)
                        .fallback(R.drawable.pypyradio_fallback_cover_art)
                        .build(),
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
                color = Color.White,
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
    playbackState: Int,
    errorMessage: String?,
    vm: StationsViewModel,
    onToggleFavorite: (Station) -> Unit
) {
    val mediaItem = player?.currentMediaItem
    val metadata = mediaItem?.mediaMetadata
    val state by vm.browse.collectAsState()
    val favorites by vm.favorites.collectAsState()
    
    val currentStation = remember(mediaItem, state.stations, favorites) {
        val id = mediaItem?.mediaId
        state.stations.find { it.stationuuid == id } ?: favorites.find { it.stationuuid == id }
    }
    val isFavorite = favorites.any { it.stationuuid == currentStation?.stationuuid }
    
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.size(200.dp).padding(bottom = 24.dp)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(metadata?.artworkUri ?: metadata?.artworkData)
                    .crossfade(true)
                    .error(R.drawable.pypyradio_fallback_cover_art)
                    .fallback(R.drawable.pypyradio_fallback_cover_art)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        }
        
        Text(
            metadata?.title?.toString() ?: "Select a Station",
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            metadata?.artist?.toString() ?: "PyPy Radio",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.7f)
        )
        
        Spacer(Modifier.height(16.dp))
        
        if (playbackState == Player.STATE_BUFFERING) {
            Text("Buffering...", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
        if (errorMessage != null) {
            Text("Error: $errorMessage", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
        
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
        TvSearchBar(
            query = state.query,
            onQueryChange = { vm.setQuery(it); vm.search() },
            placeholder = "Search Radio Stations...",
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        )
        
        if (state.loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (state.stations.isEmpty() && state.error == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No stations found. Try a different search.", color = Color.White.copy(alpha = 0.5f))
            }
        }
        
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
                    onClick = { playStation(controller, station) }
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
            TvSearchBar(
                query = state.query,
                onQueryChange = { pvm.setQuery(it); pvm.search() },
                placeholder = "Search Podcasts...",
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            )
            
            if (state.loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.podcasts.isEmpty() && state.error == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No podcasts found. Check your connection.", color = Color.White.copy(alpha = 0.5f))
                }
            }
            
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
                    Text("Back to Search", color = Color.White)
                }
                Spacer(Modifier.height(16.dp))
                Text(state.selectedPodcast?.title ?: "Episodes", style = MaterialTheme.typography.titleLarge, color = Color.White)
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
                    Text("Favorite Radio Stations", style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(bottom = 8.dp, top = 16.dp))
                }
                items(favorites, key = { it.stationuuid }) { station ->
                    TvStationCard(
                        station = station,
                        isCurrent = station.stationuuid == currentMediaId,
                        isFavorite = true,
                        onClick = { playStation(controller, station) }
                    )
                }
            }
            
            if (pFavorites.isNotEmpty()) {
                item(span = { TvGridItemSpan(3) }) {
                    Text("Favorite Podcasts", style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(bottom = 8.dp, top = 24.dp))
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
                model = ImageRequest.Builder(LocalContext.current)
                    .data(podcast.imageUrl)
                    .crossfade(true)
                    .error(R.drawable.pypyradio_fallback_cover_art)
                    .fallback(R.drawable.pypyradio_fallback_cover_art)
                    .build(),
                contentDescription = null,
                modifier = Modifier.size(64.dp).padding(bottom = 8.dp)
            )
            Text(
                podcast.title,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}


@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        if (!expanded) {
            Surface(
                onClick = { expanded = true },
                shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Default.Search, 
                        contentDescription = "Open Search",
                        tint = Color.White
                    )
                }
            }
            if (query.isNotEmpty()) {
                Spacer(Modifier.width(16.dp))
                Text(
                    "Searching: $query", 
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        } else {
            val focusRequester = remember { FocusRequester() }
            
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(placeholder) },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                leadingIcon = { 
                    IconButton(onClick = { expanded = false }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Close Search")
                    }
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { expanded = false })
            )
            
            LaunchedEffect(expanded) {
                if (expanded) focusRequester.requestFocus()
            }
        }
    }
}

private fun playStation(player: Player?, st: Station) {
    val ctrl = player ?: return
    
    // ATOMIC IPC: Send only ONE item to avoid Binder limitations on real TV hardware.
    val mediaItem = MediaItem.Builder()
        .setMediaId(st.stationuuid)
        .setUri(st.urlResolved)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(st.name)
                .setArtist(st.countryCode ?: "Radio")
                .setArtworkUri(st.favicon?.let { android.net.Uri.parse(it) })
                .build()
        )
        .build()

    ctrl.setMediaItem(mediaItem)
    ctrl.volume = 1.0f 
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
    ctrl.volume = 1.0f // Force volume on TV
    ctrl.prepare()
    ctrl.play()
}
