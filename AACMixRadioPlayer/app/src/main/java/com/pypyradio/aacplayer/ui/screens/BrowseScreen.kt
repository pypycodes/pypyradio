package com.pypyradio.aacplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import coil.compose.AsyncImage
import com.pypyradio.aacplayer.data.model.Station
import com.pypyradio.aacplayer.data.prefs.AppPreferences
import com.pypyradio.aacplayer.playback.RadioController
import com.pypyradio.aacplayer.ui.vm.StationFilter
import com.pypyradio.aacplayer.ui.vm.StationsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Main tabs - English and Indian radio only
private enum class MainTab { ENGLISH, INDIA, HINDI, NEWS }

// English genres for browsing
private val ENGLISH_GENRES = listOf(
    "All" to null,
    "Pop" to "pop",
    "Rock" to "rock",
    "Jazz" to "jazz",
    "Classical" to "classical",
    "Talk" to "talk",
    "Country" to "country",
    "Electronic" to "electronic",
    "Hip Hop" to "hip hop"
)

// Indian languages
private val INDIA_LANGUAGES = listOf(
    "All" to null,
    "Hindi" to "hindi",
    "Marathi" to "marathi",
    "Kannada" to "kannada",
    "Tamil" to "tamil",
    "Telugu" to "telugu",
    "Bengali" to "bengali",
    "Gujarati" to "gujarati",
    "Punjabi" to "punjabi",
    "Malayalam" to "malayalam"
)

// News languages - English and Hindi only
private val NEWS_LANGUAGES = listOf(
    "All" to null,
    "English" to "english",
    "Hindi" to "hindi"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    vm: StationsViewModel,
    player: Player,
    onGoFavorites: () -> Unit, 
    onGoAbout: () -> Unit, 
    onGoPodcasts: () -> Unit,
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
        }
        player.addListener(listener)
        currentPlayingId = player.currentMediaItem?.mediaId
        isPlaying = player.isPlaying
        isBuffering = player.playbackState == Player.STATE_BUFFERING
        onDispose { player.removeListener(listener) }
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
                
                // Build small playlist (5 stations) for next/prev with safe handling
                val safeList = stationList.toList()
                val currentIndex = safeList.indexOfFirst { it.stationuuid == st.stationuuid }.coerceAtLeast(0)
                val startIdx = (currentIndex - 2).coerceAtLeast(0)
                val endIdx = (currentIndex + 3).coerceAtMost(safeList.size)
                
                val nearbyStations = if (safeList.size > 1 && endIdx > startIdx) {
                    safeList.subList(startIdx, endIdx).filter { it.urlResolved.isNotBlank() }
                } else {
                    listOf(st)
                }
                
                val playlistIndex = nearbyStations.indexOfFirst { it.stationuuid == st.stationuuid }.coerceAtLeast(0)
                
                val mediaItems = nearbyStations.map { station ->
                    val artworkUri = station.favicon?.takeIf { it.isNotBlank() }?.let {
                        android.net.Uri.parse(it)
                    }
                    androidx.media3.common.MediaItem.Builder()
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
                
                if (mediaItems.isNotEmpty()) {
                    player.setMediaItems(mediaItems, playlistIndex, 0L)
                    player.prepare()
                    player.play()
                }
                
                // Update local state
                currentPlayingId = st.stationuuid
                isPlaying = true
            } catch (e: Exception) {
                // Fallback: try single station
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
                        snackbarHostState.showSnackbar("Error playing: ${st.name}")
                    }
                }
            }
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("No stream URL for: ${st.name}")
            }
        }
    }
    
    // Function to play random station
    fun playRandom() {
        val availableStations = state.stations.filter { 
            !state.failedStationIds.contains(it.stationuuid) 
        }
        if (availableStations.isNotEmpty()) {
            val randomStation = availableStations.random()
            playStation(randomStation)
        }
    }

    // Tab state - default to English
    var selectedTab by remember { mutableStateOf(MainTab.ENGLISH) }
    var selectedSubFilter by remember { mutableStateOf<String?>(null) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Radio,
                            contentDescription = "pypyradio",
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { playRandom() }) {
                        Icon(Icons.Default.Shuffle, contentDescription = "Play Random")
                    }
                    IconButton(onClick = onGoFavorites) {
                        Icon(Icons.Default.Favorite, contentDescription = "Favorites", tint = Color.Red)
                    }
                    
                    // Podcast button - stands out with filled style
                    FilledTonalIconButton(
                        onClick = onGoPodcasts,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Podcasts, 
                            contentDescription = "Podcasts",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    IconButton(onClick = onGoAbout) {
                        Icon(Icons.Default.Info, contentDescription = "About")
                    }
                }
            )
        },
    ) { padding ->
        Column(modifier.padding(padding).fillMaxSize()) {

            // Search bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = state.query,
                    onValueChange = vm::setQuery,
                    label = { Text("Search station name") },
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { vm.search() }) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
            }
            
            // Main tabs - English and Indian radio
            ScrollableTabRow(
                selectedTabIndex = MainTab.entries.indexOf(selectedTab),
                edgePadding = 12.dp
            ) {
                Tab(
                    selected = selectedTab == MainTab.ENGLISH,
                    onClick = { 
                        selectedTab = MainTab.ENGLISH
                        selectedSubFilter = null
                        vm.searchByLanguage("english")
                    },
                    text = { Text("English") }
                )
                Tab(
                    selected = selectedTab == MainTab.INDIA,
                    onClick = { 
                        selectedTab = MainTab.INDIA
                        selectedSubFilter = null
                        vm.searchByCountryAndLanguage("India", null)
                    },
                    text = { Text("India") }
                )
                Tab(
                    selected = selectedTab == MainTab.HINDI,
                    onClick = { 
                        selectedTab = MainTab.HINDI
                        selectedSubFilter = null
                        vm.searchByLanguage("hindi")
                    },
                    text = { Text("Hindi") }
                )
                Tab(
                    selected = selectedTab == MainTab.NEWS,
                    onClick = { 
                        selectedTab = MainTab.NEWS
                        selectedSubFilter = null
                        vm.searchNewsByLanguage("english")
                    },
                    text = { Text("News") }
                )
            }
            
            // Sub-filter chips based on selected tab
            when (selectedTab) {
                MainTab.ENGLISH -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ENGLISH_GENRES.forEach { (label, tag) ->
                            FilterChip(
                                selected = selectedSubFilter == (tag ?: "all_english"),
                                onClick = { 
                                    selectedSubFilter = tag ?: "all_english"
                                    if (tag != null) {
                                        vm.searchByLanguageAndTag("english", tag)
                                    } else {
                                        vm.searchByLanguage("english")
                                    }
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                }
                MainTab.INDIA -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        INDIA_LANGUAGES.forEach { (label, lang) ->
                            FilterChip(
                                selected = selectedSubFilter == (lang ?: "all_india"),
                                onClick = { 
                                    selectedSubFilter = lang ?: "all_india"
                                    vm.searchByCountryAndLanguage("India", lang)
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                }
                MainTab.NEWS -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NEWS_LANGUAGES.forEach { (label, lang) ->
                            FilterChip(
                                selected = selectedSubFilter == (lang ?: "all_news"),
                                onClick = { 
                                    selectedSubFilter = lang ?: "all_news"
                                    vm.searchNewsByLanguage(lang)
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                }
                MainTab.HINDI -> { /* No sub-filters for Hindi */ }
            }
            
            // Filter chips and auto-skip toggle
            val context = LocalContext.current
            val prefs = remember { AppPreferences.get(context) }
            val autoSkipEnabled by prefs.autoSkipEnabled.collectAsState()
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = state.filter == StationFilter.ALL,
                    onClick = { vm.setFilter(StationFilter.ALL) },
                    label = { Text("All") }
                )
                FilterChip(
                    selected = state.filter == StationFilter.HIDE_FAILED,
                    onClick = { vm.setFilter(StationFilter.HIDE_FAILED) },
                    label = { Text("Hide Failed") }
                )
                FilterChip(
                    selected = state.filter == StationFilter.WORKING_ONLY,
                    onClick = { vm.setFilter(StationFilter.WORKING_ONLY) },
                    label = { Text("Working Only") }
                )
                
                Spacer(Modifier.width(8.dp))
                
                // Auto-skip toggle
                FilterChip(
                    selected = autoSkipEnabled,
                    onClick = { prefs.setAutoSkipEnabled(!autoSkipEnabled) },
                    label = { 
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.SkipNext,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Auto-Skip")
                        }
                    }
                )
            }
            
            // Get filtered stations based on current state
            val filteredStations = remember(state.stations, state.filter, state.failedStationIds, state.workingStationIds) {
                when (state.filter) {
                    StationFilter.ALL -> state.stations
                    StationFilter.WORKING_ONLY -> state.stations.filter { state.workingStationIds.contains(it.stationuuid) }
                    StationFilter.HIDE_FAILED -> state.stations.filter { !state.failedStationIds.contains(it.stationuuid) }
                }
            }
            
            // Pagination
            val pageSize = 100
            val totalPages = (filteredStations.size + pageSize - 1) / pageSize
            var currentPage by remember { mutableStateOf(0) }
            
            // Reset page when stations change
            LaunchedEffect(filteredStations.size) {
                currentPage = 0
            }
            
            val paginatedStations = remember(filteredStations, currentPage) {
                val start = currentPage * pageSize
                val end = minOf(start + pageSize, filteredStations.size)
                if (start < filteredStations.size) filteredStations.subList(start, end) else emptyList()
            }
            
            // Station count and pagination info
            if (!state.loading && state.error == null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val countText = when (state.filter) {
                        StationFilter.ALL -> "${filteredStations.size} stations"
                        StationFilter.HIDE_FAILED -> "${filteredStations.size} stations (hiding ${state.failedStationIds.size} failed)"
                        StationFilter.WORKING_ONLY -> "${filteredStations.size} working stations"
                    }
                    Text(
                        countText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (totalPages > 1) {
                        Text(
                            "Page ${currentPage + 1} of $totalPages",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                // Pagination tabs (show when more than 1 page)
                if (totalPages > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (page in 0 until totalPages) {
                            FilterChip(
                                selected = currentPage == page,
                                onClick = { currentPage = page },
                                label = { Text("${page + 1}") },
                                modifier = Modifier.height(32.dp)
                            )
                        }
                    }
                }
            }

            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Error: ${state.error}")
                }
                else -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(paginatedStations, key = { it.stationuuid }) { st ->
                            val hasFailed = state.failedStationIds.contains(st.stationuuid)
                            val isWorking = state.workingStationIds.contains(st.stationuuid)
                            val isFavorite = favoriteIds.contains(st.stationuuid)
                            val isCurrentStation = currentPlayingId == st.stationuuid
                            val isCurrentlyPlaying = isCurrentStation && isPlaying
                            val isCurrentlyBuffering = isCurrentStation && isBuffering
                            
                            StationRow(
                                st = st,
                                hasFailed = hasFailed,
                                isWorking = isWorking,
                                isFavorite = isFavorite,
                                isPlaying = isCurrentlyPlaying,
                                isBuffering = isCurrentlyBuffering,
                                onRowClick = { 
                                    // Use state.stations directly to ensure we have the current list
                                    val currentStations = state.stations.let { stations ->
                                        when (state.filter) {
                                            StationFilter.ALL -> stations
                                            StationFilter.WORKING_ONLY -> stations.filter { state.workingStationIds.contains(it.stationuuid) }
                                            StationFilter.HIDE_FAILED -> stations.filter { !state.failedStationIds.contains(it.stationuuid) }
                                        }
                                    }
                                    playStation(st, currentStations)
                                },
                                onFavorite = { vm.toggleFavorite(st) }
                            )
                            HorizontalDivider()
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
    hasFailed: Boolean,
    isWorking: Boolean = false,
    isFavorite: Boolean,
    isPlaying: Boolean,
    isBuffering: Boolean = false,
    onRowClick: () -> Unit, 
    onFavorite: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onRowClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(40.dp)) {
            AsyncImage(model = st.favicon, contentDescription = null, modifier = Modifier.size(40.dp))
            when {
                hasFailed -> Icon(
                    Icons.Default.Warning,
                    contentDescription = "Playback failed previously",
                    tint = Color(0xFFFFB300),
                    modifier = Modifier.size(20.dp).align(Alignment.BottomEnd)
                )
                isWorking -> Box(
                    modifier = Modifier
                        .size(16.dp)
                        .align(Alignment.BottomEnd)
                        .background(Color(0xFF4CAF50), shape = CircleShape)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    st.name, 
                    style = MaterialTheme.typography.titleMedium, 
                    maxLines = 1, 
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isBuffering) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Loading...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                } else if (isPlaying) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Playing...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            val meta = listOfNotNull(st.countryCode, st.codec, st.bitrate?.let { "${it}kbps" }).joinToString(" • ")
            Text(meta, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onFavorite) { 
            Icon(
                if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, 
                contentDescription = "Favorite",
                tint = if (isFavorite) Color.Red else LocalContentColor.current
            ) 
        }
        // Show pause button when buffering or playing (so user can stop it)
        Icon(
            if (isPlaying || isBuffering) Icons.Default.Pause else Icons.Default.PlayArrow, 
            contentDescription = if (isPlaying || isBuffering) "Pause" else "Play",
            tint = when {
                hasFailed -> Color(0xFFFFB300)
                isBuffering -> MaterialTheme.colorScheme.tertiary
                isPlaying -> MaterialTheme.colorScheme.primary
                else -> LocalContentColor.current
            }
        )
    }
}
