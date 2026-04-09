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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Warning
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
    var errorRetryCount by remember { mutableStateOf(0) }
    val maxAutoRetries = 3
    
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
                    errorRetryCount = 0
                }
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
                
                // Build playlist: tapped station FIRST, then others for next/prev
                // This ensures the tapped station always plays immediately
                val safeList = stationList.toList()
                val currentIndex = safeList.indexOfFirst { it.stationuuid == st.stationuuid }
                
                // Build playlist with tapped station at correct position
                val playlistStations: List<Station>
                val playlistIndex: Int
                
                if (currentIndex >= 0) {
                    // Station found in list - build playlist around it
                    val startIdx = (currentIndex - 2).coerceAtLeast(0)
                    val endIdx = (currentIndex + 3).coerceAtMost(safeList.size)
                    playlistStations = safeList.subList(startIdx, endIdx).filter { it.urlResolved.isNotBlank() }
                    playlistIndex = playlistStations.indexOfFirst { it.stationuuid == st.stationuuid }.coerceAtLeast(0)
                } else {
                    // Station not in list (edge case) - play just this station
                    playlistStations = listOf(st)
                    playlistIndex = 0
                }
                
                val mediaItems = playlistStations.map { station ->
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
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                Column {
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
                                        contentDescription = "About pypyradio",
                                        modifier = Modifier.size(36.dp).padding(6.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        "pypyradio",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        "Free Internet Radio",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        actions = {
                            // Shuffle button
                            FilledTonalIconButton(
                                onClick = { playRandom() },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    Icons.Default.Shuffle, 
                                    contentDescription = "Play Random",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            // Favorites button
                            FilledTonalIconButton(
                                onClick = onGoFavorites,
                                modifier = Modifier.size(40.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Color(0xFFFFE0E0)
                                )
                            ) {
                                Icon(
                                    Icons.Default.Favorite, 
                                    contentDescription = "Favorites", 
                                    tint = Color(0xFFE91E63),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            // Podcast button
                            FilledTonalIconButton(
                                onClick = onGoPodcasts,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    Icons.Default.Podcasts, 
                                    contentDescription = "Podcasts",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent
                        )
                    )
                }
            }
        },
    ) { padding ->
        Column(modifier.padding(padding).fillMaxSize()) {

            // Search bar - modern design
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
                        FilledTonalIconButton(
                            onClick = { vm.search() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "Search",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
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
            
            // Filtered stations - auto-hide failed stations
            val filteredStations = remember(state.stations, state.failedStationIds) {
                state.stations.filter { !state.failedStationIds.contains(it.stationuuid) }
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
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Station count with failed info
                        val failedCount = state.failedStationIds.size
                        val countText = if (failedCount > 0) {
                            "${filteredStations.size} stations (${failedCount} unavailable hidden)"
                        } else {
                            "${filteredStations.size} stations"
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
                    // Simple hint
                    Text(
                        "Auto-skips unavailable stations",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
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
                                    // Pass filteredStations for next/prev navigation
                                    // The tapped station (st) is already the correct one
                                    playStation(st, filteredStations)
                                },
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
    hasFailed: Boolean,
    isWorking: Boolean = false,
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
            containerColor = when {
                isActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                hasFailed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isActive) 4.dp else 1.dp
        ),
        onClick = onRowClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Station artwork with status indicator
            Box(modifier = Modifier.size(52.dp)) {
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    AsyncImage(
                        model = st.favicon, 
                        contentDescription = null, 
                        modifier = Modifier.fillMaxSize().padding(4.dp)
                    )
                }
                // Status badge
                when {
                    isBuffering -> {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(18.dp)
                                .align(Alignment.BottomEnd),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    isPlaying -> {
                        Surface(
                            modifier = Modifier
                                .size(18.dp)
                                .align(Alignment.BottomEnd),
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
                    hasFailed -> {
                        Surface(
                            modifier = Modifier
                                .size(18.dp)
                                .align(Alignment.BottomEnd),
                            shape = CircleShape,
                            color = Color(0xFFFFB300)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = "Failed",
                                modifier = Modifier.padding(2.dp),
                                tint = Color.White
                            )
                        }
                    }
                    isWorking -> {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .align(Alignment.BottomEnd)
                                .background(Color(0xFF4CAF50), shape = CircleShape)
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
