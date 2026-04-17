package com.pypyradio.aacplayer.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.pypyradio.aacplayer.data.model.Podcast
import com.pypyradio.aacplayer.data.model.PodcastEpisode
import com.pypyradio.aacplayer.data.model.Station
import com.pypyradio.aacplayer.ui.vm.PodcastViewModel
import com.pypyradio.aacplayer.ui.vm.StationsViewModel

private enum class FavoritesTab { RADIO, PODCASTS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    vm: StationsViewModel,
    podcastVm: PodcastViewModel = viewModel(),
    player: Player,
    modifier: Modifier = Modifier
) {
    val radioFavs by vm.favorites.collectAsState()
    val browseState by vm.browse.collectAsState()
    val failedStationIds = browseState.failedStationIds
    val podcastFavs by podcastVm.favorites.collectAsState()
    val podcastState by podcastVm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Stations already marked as yellow in browse screen should still be visible in favorites
    // but we can filter out those with absolutely no URL.
    val validRadioFavs = remember(radioFavs) {
        radioFavs.filter { it.urlResolved.isNotBlank() }
    }
    
    var selectedTab by remember { mutableStateOf(FavoritesTab.RADIO) }
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
                isBuffering = p.playbackState == Player.STATE_BUFFERING && !p.isPlaying
            }
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    currentPlayingId?.let { vm.markStationWorking(it) }
                }
            }
            
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val failedId = player.currentMediaItem?.mediaId ?: return
                vm.markStationFailed(failedId, "Playback failed")
            }
        }
        player.addListener(listener)
        currentPlayingId = player.currentMediaItem?.mediaId
        isPlaying = player.isPlaying
        isBuffering = player.playbackState == Player.STATE_BUFFERING && !player.isPlaying
        onDispose { player.removeListener(listener) }
    }
    
    // Helper function to create a MediaItem from a Station
    fun createStationMediaItem(station: Station): MediaItem {
        val cleanFavicon = station.favicon?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        val artworkUri = cleanFavicon?.let {
            android.net.Uri.parse(it)
        }
        return MediaItem.Builder()
            .setMediaId(station.stationuuid)
            .setUri(station.urlResolved)
            .setRequestMetadata(
                androidx.media3.common.MediaItem.RequestMetadata.Builder()
                    .setMediaUri(android.net.Uri.parse(station.urlResolved))
                    .build()
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(station.name)
                    .setArtist(station.countryCode ?: "Radio")
                    .setAlbumTitle("Favorites")
                    .setArtworkUri(artworkUri)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }
    
    fun playStation(st: Station) {
        // Clear failed status specifically for this station so user sees a "fresh" attempt
        vm.clearFailedStatus(st.stationuuid)
        val now = System.currentTimeMillis()
        if (now - lastPlayTime < 500) return
        lastPlayTime = now
        
        val url = st.urlResolved
        if (url.isBlank()) {
            vm.markStationFailed(st.stationuuid, "No stream URL")
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
        
        // Build playlist from all valid favorites for next/prev support
        try {
            player.stop()
            player.clearMediaItems()
            
            val mediaItems = validRadioFavs.map { createStationMediaItem(it) }
            val startIndex = validRadioFavs.indexOfFirst { it.stationuuid == st.stationuuid }
                .coerceAtLeast(0)
            
            player.setMediaItems(mediaItems, startIndex, androidx.media3.common.C.TIME_UNSET)
            if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                player.prepare()
            }
            player.play()
            currentPlayingId = st.stationuuid
        } catch (e: Exception) {
            vm.markStationFailed(st.stationuuid, "Playback error")
        }
    }
    
    fun playEpisode(episode: PodcastEpisode) {
        // Toggle play/pause if same episode
        if (currentPlayingId == episode.id) {
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
        
        // Play single episode
        player.stop()
        player.clearMediaItems()
        val mediaItem = MediaItem.Builder()
            .setMediaId(episode.id)
            .setUri(episode.audioUrl)
            .setRequestMetadata(
                androidx.media3.common.MediaItem.RequestMetadata.Builder()
                    .setMediaUri(android.net.Uri.parse(episode.audioUrl))
                    .build()
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setArtist(episode.podcastTitle ?: episode.author)
                    .build()
            )
            .build()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
        currentPlayingId = episode.id
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
                            "Favorites",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
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
            // Tabs for Radio and Podcasts
            TabRow(
                selectedTabIndex = if (selectedTab == FavoritesTab.RADIO) 0 else 1,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Tab(
                    selected = selectedTab == FavoritesTab.RADIO,
                    onClick = { selectedTab = FavoritesTab.RADIO },
                    text = { 
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Radio, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Radio (${validRadioFavs.size})")
                        }
                    }
                )
                Tab(
                    selected = selectedTab == FavoritesTab.PODCASTS,
                    onClick = { selectedTab = FavoritesTab.PODCASTS },
                    text = { 
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Podcasts, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Podcasts (${podcastFavs.size})")
                        }
                    }
                )
            }
            
            // Content based on selected tab
            when (selectedTab) {
                FavoritesTab.RADIO -> {
                    if (validRadioFavs.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.FavoriteBorder,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "No favorites yet",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            itemsIndexed(validRadioFavs, key = { _, it -> it.stationuuid }) { index, st ->
                                val isFailed = failedStationIds.contains(st.stationuuid)
                                val isCurrentStation = currentPlayingId == st.stationuuid
                                val isCurrentPlaying = isCurrentStation && isPlaying
                                val isCurrentBuffering = isCurrentStation && isBuffering
                                FavStationRow(
                                    st = st,
                                    isFailed = isFailed,
                                    isPlaying = isCurrentPlaying,
                                    isBuffering = isCurrentBuffering,
                                    isFirst = index == 0,
                                    isLast = index == validRadioFavs.lastIndex,
                                    onRowClick = { playStation(st) },
                                    onRemove = { vm.toggleFavorite(st) },
                                    onMoveUp = { vm.moveFavorite(st.stationuuid, -1) },
                                    onMoveDown = { vm.moveFavorite(st.stationuuid, 1) }
                                )
                            }
                        }
                    }
                }
                FavoritesTab.PODCASTS -> {
                    when {
                        podcastState.showingEpisodes -> {
                            // Show episodes of selected podcast
                            Column {
                                // Back to podcasts button
                                TextButton(
                                    onClick = { podcastVm.backToPodcasts() },
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Back to Podcasts")
                                }
                                
                                if (podcastState.episodes.isEmpty()) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("No episodes found")
                                    }
                                } else {
                                    LazyColumn(Modifier.fillMaxSize()) {
                                        items(podcastState.episodes, key = { it.id }) { episode ->
                                            val isCurrentPlaying = currentPlayingId == episode.id && isPlaying
                                            FavEpisodeRow(
                                                episode = episode,
                                                isPlaying = isCurrentPlaying,
                                                onClick = { playEpisode(episode) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        podcastFavs.isEmpty() -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.FavoriteBorder,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        "No favorites yet",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        else -> {
                            LazyColumn(Modifier.fillMaxSize()) {
                                itemsIndexed(podcastFavs, key = { _, it -> it.id }) { index, podcast ->
                                    FavPodcastRow(
                                        podcast = podcast,
                                        isFirst = index == 0,
                                        isLast = index == podcastFavs.lastIndex,
                                        onRemove = { podcastVm.toggleFavorite(podcast) },
                                        onMoveUp = { podcastVm.moveFavorite(podcast.id, -1) },
                                        onMoveDown = { podcastVm.moveFavorite(podcast.id, 1) },
                                        onClick = { podcastVm.loadFavoriteEpisodes(podcast) }
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

@Composable
private fun FavStationRow(
    st: Station, 
    isFailed: Boolean = false,
    isPlaying: Boolean, 
    isBuffering: Boolean = false,
    isFirst: Boolean,
    isLast: Boolean,
    onRowClick: () -> Unit, 
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val isActive = isPlaying || isBuffering
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                isFailed -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isActive) 4.dp else 1.dp),
        onClick = onRowClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                AsyncImage(model = st.favicon, contentDescription = null, modifier = Modifier.fillMaxSize().padding(4.dp))
                
                if (isFailed && !isActive) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                        Surface(
                            modifier = Modifier.size(18.dp),
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = Color(0xFFFFF3E0)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = "May be offline",
                                modifier = Modifier.padding(2.dp),
                                tint = Color(0xFFFF9800)
                            )
                        }
                    }
                } else if (isBuffering) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    st.name, 
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1, 
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                val meta = listOfNotNull(st.countryCode, st.codec, st.bitrate?.let { "${it}kbps" }).joinToString(" • ")
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                when {
                    isBuffering -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Connecting...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    isPlaying -> {
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                "Playing",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    isFailed -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "May be offline · Tap to retry",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            Column {
                if (!isFirst) {
                    IconButton(onClick = onMoveUp, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up", modifier = Modifier.size(18.dp))
                    }
                } else {
                    Spacer(modifier = Modifier.size(24.dp))
                }
                if (!isLast) {
                    IconButton(onClick = onMoveDown, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down", modifier = Modifier.size(18.dp))
                    }
                } else {
                    Spacer(modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(
                onClick = onRemove,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Remove", modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(4.dp))
            FilledTonalIconButton(
                onClick = onRowClick,
                modifier = Modifier.size(40.dp),
                colors = if (isActive) {
                    IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)
                } else {
                    IconButtonDefaults.filledTonalIconButtonColors()
                }
            ) {
                if (isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FavPodcastRow(
    podcast: Podcast,
    isFirst: Boolean,
    isLast: Boolean,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                AsyncImage(model = podcast.imageUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    podcast.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                podcast.author?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tap to view episodes",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Column {
                if (!isFirst) {
                    IconButton(onClick = onMoveUp, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up", modifier = Modifier.size(18.dp))
                    }
                } else {
                    Spacer(modifier = Modifier.size(24.dp))
                }
                if (!isLast) {
                    IconButton(onClick = onMoveDown, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down", modifier = Modifier.size(18.dp))
                    }
                } else {
                    Spacer(modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(
                onClick = onRemove,
                modifier = Modifier.size(36.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0xFFFFE0E0))
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color(0xFFE91E63), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun FavEpisodeRow(
    episode: PodcastEpisode,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isPlaying) 4.dp else 1.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                AsyncImage(model = episode.imageUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    episode.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                episode.podcastTitle?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    episode.durationMs?.let { ms ->
                        Text("${ms / 60000} min", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    if (isPlaying) {
                        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primary) {
                            Text("Playing", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
            }
            FilledTonalIconButton(
                onClick = onClick,
                modifier = Modifier.size(40.dp),
                colors = if (isPlaying) {
                    IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)
                } else {
                    IconButtonDefaults.filledTonalIconButtonColors()
                }
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = if (isPlaying) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
