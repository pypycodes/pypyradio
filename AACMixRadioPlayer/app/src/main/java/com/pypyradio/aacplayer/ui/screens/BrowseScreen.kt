package com.pypyradio.aacplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.pypyradio.aacplayer.data.model.Station
import com.pypyradio.aacplayer.ui.vm.StationsViewModel
import kotlinx.coroutines.launch

// Country groups
private enum class CountryGroup { INDIA, ENGLISH, WORLD }

private val COUNTRY_GROUPS = mapOf(
    CountryGroup.INDIA to listOf("India" to "IN"),
    CountryGroup.ENGLISH to listOf(
        "USA" to "US",
        "UK" to "GB",
        "Canada" to "CA",
        "Australia" to "AU",
        "New Zealand" to "NZ",
        "Ireland" to "IE"
    ),
    CountryGroup.WORLD to listOf(
        "Germany" to "DE",
        "France" to "FR",
        "Spain" to "ES",
        "Italy" to "IT",
        "Netherlands" to "NL",
        "Brazil" to "BR",
        "Japan" to "JP",
        "Russia" to "RU"
    )
)

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
    
    // Listen to player state with auto-skip on error
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                currentPlayingId = p.currentMediaItem?.mediaId
                isPlaying = p.isPlaying
                isBuffering = p.playbackState == Player.STATE_BUFFERING
            }
            
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val failedId = player.currentMediaItem?.mediaId
                if (failedId != null) {
                    // Mark station as failed
                    vm.markStationFailed(failedId, error.message ?: "Playback error")
                    
                    // Auto-skip to next station
                    if (player.hasNextMediaItem()) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                "Skipping unavailable station...",
                                duration = SnackbarDuration.Short
                            )
                        }
                        player.seekToNextMediaItem()
                        player.prepare()
                        player.play()
                    } else {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                "Station unavailable",
                                duration = SnackbarDuration.Short
                            )
                        }
                    }
                }
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
    
    // Function to play a station with debouncing
    fun playStation(st: Station, stationList: List<Station> = state.stations) {
        val now = System.currentTimeMillis()
        // Debounce: ignore rapid taps within 500ms
        if (now - lastPlayTime < 500) {
            return
        }
        lastPlayTime = now
        
        val url = st.urlResolved
        if (url.isNotBlank()) {
            // If same station is playing, toggle play/pause
            if (currentPlayingId == st.stationuuid) {
                try {
                    if (player.isPlaying) {
                        player.pause()
                        isPlaying = false
                    } else {
                        // Prepare if needed (player might be in IDLE or ENDED state)
                        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                            player.prepare()
                        }
                        player.play()
                        isPlaying = true
                    }
                } catch (e: Exception) {
                    // Ignore errors on toggle
                }
                return
            }
            
            try {
                // Stop current playback and clear playlist
                player.stop()
                player.clearMediaItems()
                
                // Build playlist from all valid stations in original order
                val validStations = stationList.filter { it.urlResolved.isNotBlank() }
                val mediaItems = validStations.map { createMediaItem(it) }
                
                // Find the index of the tapped station
                val startIndex = validStations.indexOfFirst { it.stationuuid == st.stationuuid }
                    .coerceAtLeast(0)
                
                // Play starting at the tapped station's position
                player.setMediaItems(mediaItems, startIndex, 0L)
                player.prepare()
                player.play()
                
                // Update local state
                currentPlayingId = st.stationuuid
                isPlaying = true
            } catch (e: Exception) {
                // Fallback: try single station directly
                try {
                    player.stop()
                    player.clearMediaItems()
                    val artworkUri = st.favicon?.takeIf { it.isNotBlank() }?.let {
                        android.net.Uri.parse(it)
                    }
                    val mediaItem = androidx.media3.common.MediaItem.Builder()
                        .setMediaId(st.stationuuid)
                        .setUri(url)
                        .setMediaMetadata(
                            androidx.media3.common.MediaMetadata.Builder()
                                .setTitle(st.name)
                                .setArtist(st.countryCode ?: "Radio")
                                .setAlbumTitle(st.tags?.split(",")?.firstOrNull()?.trim() ?: "Internet Radio")
                                .setArtworkUri(artworkUri)
                                .setMediaType(androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC)
                                .setIsPlayable(true)
                                .build()
                        )
                        .build()
                    player.setMediaItem(mediaItem)
                    player.prepare()
                    player.play()
                    currentPlayingId = st.stationuuid
                    isPlaying = true
                } catch (e2: Exception) {
                    vm.markStationFailed(st.stationuuid, "Playback error")
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            "Station unavailable - try another one",
                            duration = SnackbarDuration.Short
                        )
                    }
                }
            }
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(
                    "Station offline - free stations may change",
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    // Selected group and country
    var selectedGroup by remember { mutableStateOf(CountryGroup.INDIA) }
    var selectedCountry by remember { mutableStateOf("IN") }
    
    // Load India stations on first launch
    LaunchedEffect(Unit) {
        vm.searchByCountry("IN")
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onGoAbout() }
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Icon(
                                Icons.Default.Radio,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp).padding(6.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "pypyradio",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
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
                        IconButton(onClick = { vm.setQuery("") }) {
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
            
            // Group tabs: India | English | World
            TabRow(
                selectedTabIndex = CountryGroup.entries.indexOf(selectedGroup),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Tab(
                    selected = selectedGroup == CountryGroup.INDIA,
                    onClick = { 
                        selectedGroup = CountryGroup.INDIA
                        selectedCountry = "IN"
                        vm.searchByCountry("IN")
                    },
                    text = { Text("India") }
                )
                Tab(
                    selected = selectedGroup == CountryGroup.ENGLISH,
                    onClick = { 
                        selectedGroup = CountryGroup.ENGLISH
                        val firstCountry = COUNTRY_GROUPS[CountryGroup.ENGLISH]?.firstOrNull()
                        if (firstCountry != null) {
                            selectedCountry = firstCountry.second
                            vm.searchByCountry(firstCountry.second)
                        }
                    },
                    text = { Text("English") }
                )
                Tab(
                    selected = selectedGroup == CountryGroup.WORLD,
                    onClick = { 
                        selectedGroup = CountryGroup.WORLD
                        val firstCountry = COUNTRY_GROUPS[CountryGroup.WORLD]?.firstOrNull()
                        if (firstCountry != null) {
                            selectedCountry = firstCountry.second
                            vm.searchByCountry(firstCountry.second)
                        }
                    },
                    text = { Text("World") }
                )
            }
            
            // Country chips for selected group (only show if more than 1 country)
            val countriesInGroup = COUNTRY_GROUPS[selectedGroup] ?: emptyList()
            if (countriesInGroup.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    countriesInGroup.forEach { (name, code) ->
                        FilterChip(
                            selected = selectedCountry == code,
                            onClick = { 
                                selectedCountry = code
                                vm.searchByCountry(code)
                            },
                            label = { Text(name) }
                        )
                    }
                }
            }
            
            // Filter out failed stations from display AND playlist
            val displayStations = remember(state.stations, state.failedStationIds) {
                state.stations.filter { 
                    !state.failedStationIds.contains(it.stationuuid) && it.urlResolved.isNotBlank()
                }
            }
            
            // Station count
            if (!state.loading && state.error == null && displayStations.isNotEmpty()) {
                Text(
                    "${displayStations.size} stations",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            when {
                state.loading && state.stations.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Failed to load", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { vm.searchByCountry(selectedCountry) }) { Text("Retry") }
                    }
                }
                displayStations.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No stations found", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                onRowClick = { playStation(st, displayStations) },
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
