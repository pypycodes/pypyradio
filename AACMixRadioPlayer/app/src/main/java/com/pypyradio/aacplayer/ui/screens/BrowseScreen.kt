package com.pypyradio.aacplayer.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.pypyradio.aacplayer.R
import com.pypyradio.aacplayer.data.model.Station
import com.pypyradio.aacplayer.ui.vm.StationsViewModel
import kotlinx.coroutines.launch


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    vm: StationsViewModel,
    player: Player,
    onGoAbout: () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    modifier: Modifier = Modifier
) {
    val state by vm.browse.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val favoriteIds = remember(favorites) { favorites.map { it.stationuuid }.toSet() }
    val scope = rememberCoroutineScope()
    
    // Track current playing station and state
    var currentPlayingId by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var lastPlayTime by remember { mutableStateOf(0L) }
    
    // Listen to player state
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                currentPlayingId = p.currentMediaItem?.mediaId
                isPlaying = p.isPlaying
                isBuffering = p.playbackState == Player.STATE_BUFFERING
            }
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    // Station is working - mark it
                    currentPlayingId?.let { vm.markStationWorking(it) }
                }
            }
        }
        player.addListener(listener)
        currentPlayingId = player.currentMediaItem?.mediaId
        isPlaying = player.isPlaying
        isBuffering = player.playbackState == Player.STATE_BUFFERING
        onDispose { player.removeListener(listener) }
    }
    
    // Helper function to create a MediaItem from a Station
    fun createMediaItem(station: Station): androidx.media3.common.MediaItem {
        val artworkUri = station.favicon?.takeIf { it.isNotBlank() }?.let {
            android.net.Uri.parse(it)
        }
        return androidx.media3.common.MediaItem.Builder()
            .setMediaId(station.stationuuid)
            .setUri(station.urlResolved)
            .setRequestMetadata(
                androidx.media3.common.MediaItem.RequestMetadata.Builder()
                    .setMediaUri(android.net.Uri.parse(station.urlResolved))
                    .build()
            )
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(station.name)
                    .setArtist(station.countryCode ?: "Radio")
                    .setAlbumTitle(station.tags?.split(",")?.firstOrNull()?.trim() ?: "Internet Radio")
                    .setArtworkUri(artworkUri)
                    .setMediaType(androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }
    
    // Play station with playlist for next/prev support
    fun playStation(st: Station) {
        val now = System.currentTimeMillis()
        if (now - lastPlayTime < 500) return
        lastPlayTime = now
        
        val url = st.urlResolved
        if (url.isBlank()) {
            vm.markStationFailed(st.stationuuid, "No stream URL")
            scope.launch { snackbarHostState.showSnackbar("Station unavailable", duration = SnackbarDuration.Short) }
            return
        }
        
        // Toggle play/pause if same station
        if (currentPlayingId == st.stationuuid) {
            if (player.isPlaying) {
                player.pause()
            } else {
                if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                    player.prepare()
                }
                player.play()
            }
            return
        }
        
        // Use the full station list (not the filtered displayStations) for building
        // the playlist. The filtered list is unstable because the background health
        // checker continuously changes failedStationIds, causing the list to shift.
        // Using the snapshot from state.stations ensures the index lookup is reliable.
        val allStations = state.stations.filter { it.urlResolved.isNotBlank() }
        
        try {
            player.stop()
            player.clearMediaItems()
            
            val mediaItems = allStations.map { createMediaItem(it) }
            val startIndex = allStations.indexOfFirst { it.stationuuid == st.stationuuid }
            
            if (startIndex >= 0) {
                // Found in the list — set full playlist with correct start index
                player.setMediaItems(mediaItems, startIndex, 0L)
            } else {
                // Station not found in the list (edge case) — play it directly
                player.setMediaItem(createMediaItem(st))
            }
            
            player.prepare()
            player.play()
            currentPlayingId = st.stationuuid
        } catch (e: Exception) {
            vm.markStationFailed(st.stationuuid, "Playback error")
            scope.launch { snackbarHostState.showSnackbar("Station unavailable", duration = SnackbarDuration.Short) }
        }
    }
    
    // Load top stations on first launch
    LaunchedEffect(Unit) {
        vm.loadTop()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = "pypyradio",
                        modifier = Modifier
                            .size(40.dp)
                            .clickable { onGoAbout() }
                    )
                },
                actions = {
                    IconButton(onClick = onGoAbout) {
                        Icon(Icons.Default.Info, contentDescription = "About")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
    ) { padding ->
        Column(modifier.padding(padding).fillMaxSize()) {
            
            // Search bar
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextField(
                        modifier = Modifier.weight(1f),
                        value = state.query,
                        onValueChange = vm::setQuery,
                        placeholder = { Text("Search stations...") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { 
                            vm.setQuery("")
                            vm.loadTop()  // Reload top stations when cleared
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(20.dp))
                        }
                        FilledTonalIconButton(
                            onClick = { vm.search() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Search", modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                }
            }
            
            // Filter out failed stations
            val displayStations = remember(state.stations, state.failedStationIds) {
                state.stations.filter { 
                    !state.failedStationIds.contains(it.stationuuid) && it.urlResolved.isNotBlank()
                }
            }
            
            // Station count
            if (!state.loading && state.error == null && displayStations.isNotEmpty()) {
                Text(
                    "${displayStations.size} stations • Search to find more",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            when {
                state.loading && state.error == null -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(10) { SkeletonStationRow() }
                    }
                }
                state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Failed to load", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { vm.loadTop() }) { Text("Retry") }
                    }
                }
                displayStations.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No stations found", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("Try a different search", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
                else -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(displayStations, key = { it.stationuuid }) { st ->
                            val isFavorite = favoriteIds.contains(st.stationuuid)
                            val isCurrentStation = currentPlayingId == st.stationuuid
                            val isCurrentlyPlaying = isCurrentStation && isPlaying
                            val isCurrentlyBuffering = isCurrentStation && isBuffering
                            
                            StationRow(
                                st = st,
                                isFavorite = isFavorite,
                                isPlaying = isCurrentlyPlaying,
                                isBuffering = isCurrentlyBuffering,
                                onRowClick = { playStation(st) },
                                onFavorite = { vm.toggleFavorite(st) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StationRow(
    st: Station, 
    isFavorite: Boolean,
    isPlaying: Boolean,
    isBuffering: Boolean = false,
    onRowClick: () -> Unit, 
    onFavorite: () -> Unit
) {
    val isActive = isPlaying || isBuffering
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) 
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) 
            else 
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isActive) 4.dp else 1.dp),
        onClick = onRowClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Station artwork with status indicator
            Box(modifier = Modifier.size(48.dp)) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    AsyncImage(
                        model = st.favicon, 
                        contentDescription = null, 
                        modifier = Modifier.fillMaxSize().padding(4.dp)
                    )
                }
                // Status badge
                if (isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp).align(Alignment.BottomEnd),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (isPlaying) {
                    Surface(
                        modifier = Modifier.size(16.dp).align(Alignment.BottomEnd),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.padding(2.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
            
            Spacer(Modifier.width(12.dp))
            
            // Station info
            Column(Modifier.weight(1f)) {
                Text(
                    st.name, 
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, 
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Country badge
                    st.countryCode?.let { country ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                country,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    // Codec & bitrate
                    val techInfo = listOfNotNull(
                        st.codec?.uppercase(),
                        st.bitrate?.let { "${it}k" }
                    ).joinToString(" • ")
                    if (techInfo.isNotBlank()) {
                        Text(
                            techInfo, 
                            style = MaterialTheme.typography.labelSmall, 
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // Status text for active station
                if (isBuffering) {
                    Text(
                        "Connecting...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                } else if (isPlaying) {
                    Text(
                        "Now Playing",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            // Favorite button
            IconButton(onClick = onFavorite) { 
                Icon(
                    if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, 
                    contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (isFavorite) Color(0xFFE91E63) else MaterialTheme.colorScheme.onSurfaceVariant
                ) 
            }
        }
    }
}

@Composable
fun SkeletonStationRow() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {}
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(0.6f).height(20.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {}
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(0.3f).height(14.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {}
            }
            Spacer(Modifier.width(12.dp))
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {}
        }
    }
}
