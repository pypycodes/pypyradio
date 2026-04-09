package com.pypyradio.aacplayer.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Radio
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
import kotlinx.coroutines.launch

private enum class FavoritesTab { RADIO, PODCASTS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    vm: StationsViewModel,
    podcastVm: PodcastViewModel = viewModel(),
    player: Player,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val radioFavs by vm.favorites.collectAsState()
    val podcastFavs by podcastVm.favorites.collectAsState()
    val podcastState by podcastVm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    
    var selectedTab by remember { mutableStateOf(FavoritesTab.RADIO) }
    var currentPlayingId by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var lastPlayTime by remember { mutableStateOf(0L) }
    
    // Listen to player state with auto-skip on error
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                currentPlayingId = p.currentMediaItem?.mediaId
                isPlaying = p.isPlaying
            }
            
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val failedId = player.currentMediaItem?.mediaId
                if (failedId != null) {
                    vm.markStationFailed(failedId, error.message ?: "Playback error")
                    if (player.hasNextMediaItem()) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                "Skipping unavailable...",
                                duration = SnackbarDuration.Short
                            )
                        }
                        player.seekToNextMediaItem()
                        player.prepare()
                        player.play()
                    }
                }
            }
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    currentPlayingId?.let { vm.markStationWorking(it) }
                }
            }
        }
        player.addListener(listener)
        currentPlayingId = player.currentMediaItem?.mediaId
        isPlaying = player.isPlaying
        onDispose { player.removeListener(listener) }
    }
    
    // Helper function to create a MediaItem from a Station
    fun createStationMediaItem(station: Station): MediaItem {
        val artworkUri = station.favicon?.takeIf { it.isNotBlank() }?.let {
            android.net.Uri.parse(it)
        }
        return MediaItem.Builder()
            .setMediaId(station.stationuuid)
            .setUri(station.urlResolved)
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
        val now = System.currentTimeMillis()
        if (now - lastPlayTime < 500) return
        lastPlayTime = now
        
        val url = st.urlResolved
        if (url.isNotBlank()) {
            if (currentPlayingId == st.stationuuid) {
                try { if (player.isPlaying) player.pause() else player.play() } catch (e: Exception) {}
                return
            }
            
            try {
                player.stop()
                player.clearMediaItems()
                val tappedMediaItem = createStationMediaItem(st)
                val otherStations = radioFavs.filter { it.urlResolved.isNotBlank() && it.stationuuid != st.stationuuid }
                val allMediaItems = mutableListOf(tappedMediaItem)
                allMediaItems.addAll(otherStations.map { createStationMediaItem(it) })
                player.setMediaItems(allMediaItems, 0, 0L)
                player.prepare()
                player.play()
            } catch (e: Exception) {
                // Fallback
                try {
                    player.stop()
                    player.clearMediaItems()
                    player.setMediaItem(createStationMediaItem(st))
                    player.prepare()
                    player.play()
                } catch (e2: Exception) {}
            }
        }
    }
    
    fun playEpisode(episode: PodcastEpisode, allEpisodes: List<PodcastEpisode>) {
        if (currentPlayingId == episode.id) {
            if (player.isPlaying) player.pause() else player.play()
            return
        }
        
        player.stop()
        player.clearMediaItems()
        
        val tappedItem = MediaItem.Builder()
            .setMediaId(episode.id)
            .setUri(episode.audioUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setArtist(episode.podcastTitle ?: episode.author)
                    .build()
            )
            .build()
        
        val otherItems = allEpisodes.filter { it.id != episode.id }.map { ep ->
            MediaItem.Builder()
                .setMediaId(ep.id)
                .setUri(ep.audioUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(ep.title)
                        .setArtist(ep.podcastTitle ?: ep.author)
                        .build()
                )
                .build()
        }
        
        val allItems = mutableListOf(tappedItem)
        allItems.addAll(otherItems)
        player.setMediaItems(allItems, 0, 0L)
        player.prepare()
        player.play()
    }

    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                TopAppBar(
                    title = { 
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFFFE0E0)
                            ) {
                                Icon(
                                    Icons.Default.Favorite,
                                    contentDescription = "Favorites",
                                    modifier = Modifier.size(36.dp).padding(6.dp),
                                    tint = Color(0xFFE91E63)
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Favorites",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    navigationIcon = { },
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
                            Text("Radio (${radioFavs.size})")
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
                    if (radioFavs.isEmpty()) {
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
                            items(radioFavs, key = { it.stationuuid }) { st ->
                                val isCurrentPlaying = currentPlayingId == st.stationuuid && isPlaying
                                FavStationRow(
                                    st = st,
                                    isPlaying = isCurrentPlaying,
                                    onRowClick = { playStation(st) },
                                    onRemove = { vm.toggleFavorite(st) }
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
                                                onClick = { playEpisode(episode, podcastState.episodes) }
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
                                items(podcastFavs, key = { it.id }) { podcast ->
                                    FavPodcastRow(
                                        podcast = podcast,
                                        onRemove = { podcastVm.toggleFavorite(podcast) },
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
    isPlaying: Boolean, 
    onRowClick: () -> Unit, 
    onRemove: () -> Unit
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
                if (isPlaying) {
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
            }
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

@Composable
private fun FavPodcastRow(
    podcast: Podcast,
    onRemove: () -> Unit,
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
