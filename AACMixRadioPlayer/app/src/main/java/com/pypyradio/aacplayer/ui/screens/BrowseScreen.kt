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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.pypyradio.aacplayer.data.model.Station
import com.pypyradio.aacplayer.ui.vm.StationsViewModel
import kotlinx.coroutines.delay
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
    var bufferingStartTime by remember { mutableStateOf(0L) }
    var isSlowConnection by remember { mutableStateOf(false) }
    var hasPlaybackError by remember { mutableStateOf(false) }
    var playbackErrorMessage by remember { mutableStateOf<String?>(null) }
    
    // Selected category
    var selectedCategory by remember { mutableStateOf("popular") }
    
    // Show ALL stations (don't hide failed ones anymore - Requirement 2)
    // This prevents stations from 'disappearing' from the list during scans.
    val displayStations = remember(state.stations) {
        state.stations.filter { it.urlResolved.isNotBlank() }
    }
    
    // Listen to player state — only handle actual errors, not STATE_ENDED
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                currentPlayingId = p.currentMediaItem?.mediaId
                isPlaying = p.isPlaying
                val wasBuffering = isBuffering
                // Only show buffering if we aren't actually playing audio
                isBuffering = p.playbackState == Player.STATE_BUFFERING && !p.isPlaying
                // Track when buffering started for "slow connection" message
                if (isBuffering && !wasBuffering) {
                    bufferingStartTime = System.currentTimeMillis()
                    isSlowConnection = false
                } else if (!isBuffering) {
                    isSlowConnection = false
                }
            }
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        // Station is playing successfully
                        currentPlayingId?.let { vm.markStationWorking(it) }
                        hasPlaybackError = false
                        playbackErrorMessage = null
                    }
                    // STATE_ENDED is normal for live streams — do NOT mark as failed
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val failedId = player.currentMediaItem?.mediaId ?: return
                vm.markStationFailed(failedId, "Playback failed")
                hasPlaybackError = true
                playbackErrorMessage = when {
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                        "Network error"
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "Connection timed out"
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "Stream unavailable"
                    else -> "Station offline"
                }
            }
            
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Clear error state when moving to a new station
                hasPlaybackError = false
                playbackErrorMessage = null
            }
        }
        player.addListener(listener)
        currentPlayingId = player.currentMediaItem?.mediaId
        isPlaying = player.isPlaying
        isBuffering = player.playbackState == Player.STATE_BUFFERING && !player.isPlaying
        hasPlaybackError = player.playerError != null
        onDispose { player.removeListener(listener) }
    }
    
    // Slow connection detection — show "Taking longer than usual..." after 8 seconds
    LaunchedEffect(isBuffering, bufferingStartTime) {
        if (isBuffering && bufferingStartTime > 0) {
            delay(8000L)
            if (isBuffering) {
                isSlowConnection = true
            }
        }
    }
    
    // Play a station with a windowed playlist for proper next/prev support.
    // Mirrors the EXACT approach FavoritesScreen uses (which already works):
    // build MediaItems locally, send via setMediaItems(list, startIndex).
    // Window of 25 items stays safely within the Binder IPC size limit.
    fun playStation(st: Station) {
        // Clear failed status specifically for this station so user sees a "fresh" attempt
        vm.clearFailedStatus(st.stationuuid)
        hasPlaybackError = false
        playbackErrorMessage = null
        val now = System.currentTimeMillis()
        // 500ms debounce: prevent accidental double-tap (consistent with Favorites)
        if (now - lastPlayTime < 500) return
        lastPlayTime = now
        
        // Immediate visual feedback
        isBuffering = false
        hasPlaybackError = false
        playbackErrorMessage = null

        val url = st.urlResolved
        if (url.isBlank()) {
            vm.markStationFailed(st.stationuuid, "No stream URL")
            scope.launch { snackbarHostState.showSnackbar("Station unavailable", duration = SnackbarDuration.Short) }
            return
        }

        // Toggle play/pause if same station AND player is healthy.
        // If there's an error, we bypass this to force a full re-set of the media items.
        if (currentPlayingId == st.stationuuid && !hasPlaybackError && player.playerError == null) {
            if (player.isPlaying) {
                player.pause()
            } else {
                // Only re-prepare if player is idle/ended, NOT if just paused
                if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                    player.prepare()
                }
                player.play()
            }
            return
        }

        try {
            val selectedIndex = displayStations.indexOfFirst { it.stationuuid == st.stationuuid }

            // Optimized Windowing Strategy: Send the selected station + 10 nearby stations each way.
            // A smaller window (21 items total) is significantly faster, prevents Binder IPC
            // timeouts on slow devices, and still provides a great Next/Prev navigation experience.
            val windowedStations: List<Station>
            val startIndexInWindow: Int
            if (selectedIndex >= 0) {
                val window = 10 
                val fromIndex = maxOf(0, selectedIndex - window)
                val toIndex = minOf(displayStations.size, selectedIndex + window + 1)
                windowedStations = displayStations.subList(fromIndex, toIndex)
                startIndexInWindow = selectedIndex - fromIndex
            } else {
                // Standalone playback for race conditions
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

            // CRITICAL: CLEAN SLATE ARCHITECTURE
            // We stop and clear everything before setting new items. 
            // This prevents old errors or "stuck" states from bleeding into the new request.
            player.stop()
            player.clearMediaItems()
            player.setMediaItems(mediaItems, startIndexInWindow, 0L)
            player.prepare()
            player.play()
            
            // Note: We do NOT set currentPlayingId here manually.
            // We wait for the player to transition so the UI state stays in sync 
            // with the actual background service.
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

    // No more UI-side auto-advance — the service handles playlist navigation.
    // The UI just shows the error state and lets the user retry or pick another station.
    
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
                            val isCurrentError = isCurrentStation && hasPlaybackError
                            val currentErrorMsg = if (isCurrentStation) playbackErrorMessage else null
                            val showSlowConnection = isCurrentStation && isSlowConnection
                            
                            StationRow(
                                st = st,
                                isFailed = isFailed,
                                isFavorite = isFavorite,
                                isPlaying = isCurrentlyPlaying,
                                isBuffering = isCurrentlyBuffering,
                                hasError = isCurrentError,
                                errorMessage = currentErrorMsg,
                                isSlowConnection = showSlowConnection,
                                onRowClick = { playStation(st) },
                                onRetry = { playStation(st) },
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
    hasError: Boolean = false,
    errorMessage: String? = null,
    isSlowConnection: Boolean = false,
    onRowClick: () -> Unit, 
    onRetry: () -> Unit = onRowClick,
    onFavorite: () -> Unit
) {
    val isActive = isPlaying || isBuffering
    
    // Pulsing animation for buffering
    val infiniteTransition = rememberInfiniteTransition(label = "buffering")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    // Animated container color
    val containerColor by animateColorAsState(
        targetValue = when {
            hasError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
            isActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            isFailed -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(300),
        label = "containerColor"
    )
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
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
                    modifier = Modifier
                        .size(48.dp)
                        .then(if (isBuffering) Modifier.alpha(pulseAlpha) else Modifier),
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
                when {
                    hasError -> {
                        Surface(
                            modifier = Modifier.size(18.dp).align(Alignment.BottomEnd),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.error
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Error",
                                modifier = Modifier.padding(2.dp),
                                tint = MaterialTheme.colorScheme.onError
                            )
                        }
                    }
                    isBuffering -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp).align(Alignment.BottomEnd),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    isPlaying -> {
                        Surface(
                            modifier = Modifier.size(18.dp).align(Alignment.BottomEnd),
                            shape = CircleShape,
                            color = Color(0xFF4CAF50)
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.padding(2.dp),
                                tint = Color.White
                            )
                        }
                    }
                    isFailed -> {
                        Surface(
                            modifier = Modifier.size(18.dp).align(Alignment.BottomEnd),
                            shape = CircleShape,
                            color = Color(0xFFFFF3E0)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = "Previously failed",
                                modifier = Modifier.padding(2.dp),
                                tint = Color(0xFFFF9800)
                            )
                        }
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
                    color = when {
                        hasError -> MaterialTheme.colorScheme.error
                        isActive -> MaterialTheme.colorScheme.primary
                        isFailed -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
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
                // Status text — contextual and informative
                when {
                    hasError -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    errorMessage ?: "Station offline",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Text(
                                "· Tap to retry",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    isBuffering && isSlowConnection -> {
                        Text(
                            "Taking longer than usual...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.alpha(pulseAlpha)
                        )
                    }
                    isBuffering -> {
                        Text(
                            "Connecting...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.alpha(pulseAlpha)
                        )
                    }
                    isPlaying -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(Color(0xFF4CAF50), CircleShape)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Live",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF4CAF50)
                            )
                        }
                    }
                    isFailed -> {
                        Text(
                            "May be offline · Tap to retry",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            
            // Retry button for error/failed state
            if (hasError || (isFailed && !isActive)) {
                FilledTonalIconButton(
                    onClick = onRetry,
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (hasError)
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Retry",
                        modifier = Modifier.size(18.dp),
                        tint = if (hasError)
                            MaterialTheme.colorScheme.onErrorContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(4.dp))
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
