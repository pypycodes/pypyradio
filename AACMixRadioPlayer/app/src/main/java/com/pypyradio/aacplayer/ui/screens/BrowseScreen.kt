package com.pypyradio.aacplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.pypyradio.aacplayer.R
import com.pypyradio.aacplayer.data.model.Station
import com.pypyradio.aacplayer.ui.vm.StationsViewModel
import kotlinx.coroutines.launch

// Category definition for browse chips
private data class BrowseCategory(
    val id: String,
    val label: String,
    val emoji: String
)

private val BROWSE_CATEGORIES = listOf(
    BrowseCategory("popular", "Popular", "🔥"),
    BrowseCategory("music", "Music", "🎵"),
    BrowseCategory("news", "News", "📰"),
    BrowseCategory("talk", "Talk", "🗣️"),
    BrowseCategory("sports", "Sports", "⚽"),
    BrowseCategory("hindi", "Hindi", "🇮🇳"),
    BrowseCategory("english", "English", "🇬🇧"),
    BrowseCategory("tamil", "Tamil", "🎶"),
    BrowseCategory("telugu", "Telugu", "🎤"),
    BrowseCategory("classical", "Classical", "🎻"),
    BrowseCategory("rock", "Rock", "🎸"),
    BrowseCategory("jazz", "Jazz", "🎷"),
    BrowseCategory("pop", "Pop", "🎧"),
    BrowseCategory("lofi", "Lo-Fi", "🌙"),
    BrowseCategory("ambient", "Ambient", "🌊")
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
    // Triggers UI-side auto-advance when service can't skip (single-item queue)
    var autoAdvanceFromId by remember { mutableStateOf<String?>(null) }
    
    // Selected category
    var selectedCategory by remember { mutableStateOf("popular") }
    
    // Show ALL stations (don't hide failed ones anymore - Requirement 2)
    // This prevents stations from 'disappearing' from the list during scans.
    val displayStations = remember(state.stations) {
        state.stations.filter { it.urlResolved.isNotBlank() }
    }
    
    // Listen to player state
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                currentPlayingId = p.currentMediaItem?.mediaId
                isPlaying = p.isPlaying
                // Only show buffering if we aren't actually playing audio
                isBuffering = p.playbackState == Player.STATE_BUFFERING && !p.isPlaying
            }
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        currentPlayingId?.let { vm.markStationWorking(it) }
                    }
                    Player.STATE_ENDED -> {
                        val failedId = player.currentMediaItem?.mediaId ?: return
                        vm.markStationFailed(failedId, "Stream ended unexpectedly")
                        if (player.mediaItemCount <= 1) {
                            autoAdvanceFromId = failedId
                        }
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val failedId = player.currentMediaItem?.mediaId ?: return
                vm.markStationFailed(failedId, "Playback failed")
                if (player.mediaItemCount <= 1) {
                    autoAdvanceFromId = failedId
                }
            }
        }
        player.addListener(listener)
        currentPlayingId = player.currentMediaItem?.mediaId
        isPlaying = player.isPlaying
        isBuffering = player.playbackState == Player.STATE_BUFFERING && !player.isPlaying
        onDispose { player.removeListener(listener) }
    }
    
    // Play a station with a windowed playlist for proper next/prev support.
    // Mirrors the EXACT approach FavoritesScreen uses (which already works):
    // build MediaItems locally, send via setMediaItems(list, startIndex).
    // Window of 25 items stays safely within the Binder IPC size limit.
    fun playStation(st: Station) {
        // Clear failed status specifically for this station so user sees a "fresh" attempt
        vm.clearFailedStatus(st.stationuuid)
        // Cancel any pending auto-advance so it doesn't override the user's manual choice
        autoAdvanceFromId = null
        val now = System.currentTimeMillis()
        // 100ms debounce: prevent accidental double-tap, but don't block quick station switching
        if (now - lastPlayTime < 100) return
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
                player.prepare()
                player.play()
            }
            return
        }

        try {
            val selectedIndex = displayStations.indexOfFirst { it.stationuuid == st.stationuuid }

            // Build windowed slice centred on the tapped station.
            // If selectedIndex == -1 (health check removed station just as user tapped it),
            // play the station standalone — never default to index 0 which plays the wrong station.
            // Build larger window slice (100 items each side) for better Next/Prev support.
            // A window of 200 items stays safely within the 1MB Binder IPC size limit.
            val windowedStations: List<Station>
            val startIndexInWindow: Int
            if (selectedIndex >= 0) {
                val window = 25
                val fromIndex = maxOf(0, selectedIndex - window)
                val toIndex = minOf(displayStations.size, selectedIndex + window + 1)
                windowedStations = displayStations.subList(fromIndex, toIndex)
                startIndexInWindow = selectedIndex - fromIndex
            } else {
                // Station was just removed from displayStations (timing race with health check).
                // Play it as a standalone item so the mini player shows the correct station.
                windowedStations = listOf(st)
                startIndexInWindow = 0
            }

            val mediaItems = windowedStations.map { displaySt ->
                val artUri = displaySt.favicon?.takeIf { it.isNotBlank() }?.let { android.net.Uri.parse(it) }
                MediaItem.Builder()
                    .setMediaId(displaySt.stationuuid)
                    .setUri(displaySt.urlResolved)
                    .setRequestMetadata(
                        androidx.media3.common.MediaItem.RequestMetadata.Builder()
                            .setMediaUri(android.net.Uri.parse(displaySt.urlResolved))
                            .build()
                    )
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(displaySt.name)
                            .setArtist(displaySt.countryCode ?: "Radio")
                            .setAlbumTitle(displaySt.tags?.split(",")?.firstOrNull()?.trim() ?: "Internet Radio")
                            .setArtworkUri(artUri)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                            .setIsPlayable(true)
                            .build()
                    )
                    .build()
            }

            player.stop()
            player.clearMediaItems()
            player.setMediaItems(mediaItems, startIndexInWindow, 0L)
            player.prepare()
            player.play()
            // Do NOT set currentPlayingId here — let onEvents update it from the actual player state.
            // Setting it here races with async IPC error events from the PREVIOUS playlist,
            // causing those stale errors to mark the new station as failed.
        } catch (e: Exception) {
            vm.markStationFailed(st.stationuuid, "Playback error")
            scope.launch { snackbarHostState.showSnackbar("Station unavailable", duration = SnackbarDuration.Short) }
        }
    }
    
    // Load category based on selection
    fun loadCategory(categoryId: String) {
        selectedCategory = categoryId
        when (categoryId) {
            "popular" -> vm.loadTop()
            "music" -> vm.searchByTag("music")
            "news" -> vm.searchByLanguageAndTag("english", "news")
            "talk" -> vm.searchByTag("talk")
            "sports" -> vm.searchByTag("sports")
            "hindi" -> vm.searchByLanguage("hindi")
            "english" -> vm.searchByLanguage("english")
            "tamil" -> vm.searchByLanguage("tamil")
            "telugu" -> vm.searchByLanguage("telugu")
            "classical" -> vm.searchByTag("classical")
            "rock" -> vm.searchByTag("rock")
            "jazz" -> vm.searchByTag("jazz")
            "pop" -> vm.searchByTag("pop")
            "lofi" -> vm.searchByTag("lofi")
            "ambient" -> vm.searchByTag("ambient")
            else -> vm.loadTop()
        }
    }
    
    // Load initial stations
    LaunchedEffect(Unit) {
        vm.loadTop()
    }

    // Auto-advance when a tapped station fails and the queue has only 1 item
    LaunchedEffect(autoAdvanceFromId) {
        val fromId = autoAdvanceFromId ?: return@LaunchedEffect
        kotlinx.coroutines.delay(400L)
        val failedIdx = displayStations.indexOfFirst { it.stationuuid == fromId }
        val nextStation = displayStations.getOrNull(failedIdx + 1)
            ?: displayStations.getOrNull(failedIdx - 1)
        nextStation?.let { playStation(it) }
        autoAdvanceFromId = null
    }
    
    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                TopAppBar(
                    title = {
                        Text(
                            "Radio",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    actions = {
                        IconButton(onClick = onGoAbout) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = "About",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier.padding(padding).fillMaxSize()) {
            
            // Search bar - aligned with Podcast style
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
                            loadCategory(selectedCategory)
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(20.dp))
                        }
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
            
            // Category chips - horizontal scrollable row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BROWSE_CATEGORIES.forEach { category ->
                    val isSelected = selectedCategory == category.id && state.query.isEmpty()
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            vm.setQuery("") // Clear search when switching categories
                            loadCategory(category.id)
                        },
                        label = {
                            Text(
                                "${category.emoji} ${category.label}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }
            
            LaunchedEffect(displayStations) {
                com.pypyradio.aacplayer.playback.ActivePlaylistCache.currentBrowseItems = displayStations
            }



            // Station count and current category label
            if (!state.loading && state.error == null && displayStations.isNotEmpty()) {
                val categoryLabel = if (state.query.isNotEmpty()) {
                    "\"${state.query}\""
                } else {
                    BROWSE_CATEGORIES.find { it.id == selectedCategory }?.let { 
                        "${it.emoji} ${it.label}" 
                    } ?: "Popular"
                }
                Text(
                    "${displayStations.size} stations in $categoryLabel",
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
                        TextButton(onClick = { loadCategory(selectedCategory) }) { Text("Retry") }
                    }
                }
                displayStations.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No stations found", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("Try a different category or search", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
                else -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(displayStations, key = { it.stationuuid }) { st ->
                            val isFailed = state.failedStationIds.contains(st.stationuuid)
                            val isFavorite = favoriteIds.contains(st.stationuuid)
                            val isCurrentStation = currentPlayingId == st.stationuuid
                            val isCurrentlyPlaying = isCurrentStation && isPlaying
                            val isCurrentlyBuffering = isCurrentStation && isBuffering
                            
                            StationRow(
                                st = st,
                                isFailed = isFailed,
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
    isFailed: Boolean = false,
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
            else if (isFailed)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
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
                } else if (isFailed) {
                    Surface(
                        modifier = Modifier.size(16.dp).align(Alignment.BottomEnd),
                        shape = CircleShape,
                        color = Color.White
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Failed",
                            modifier = Modifier.padding(1.dp),
                            tint = Color(0xFFFFB300) // Yellow/Amber warning mark
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
                    color = if (isActive) MaterialTheme.colorScheme.primary 
                            else if (isFailed) MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                            else MaterialTheme.colorScheme.onSurface,
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
