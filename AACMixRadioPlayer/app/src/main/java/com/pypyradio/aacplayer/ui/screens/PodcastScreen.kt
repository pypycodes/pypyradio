package com.pypyradio.aacplayer.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Search
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
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.pypyradio.aacplayer.R
import com.pypyradio.aacplayer.data.model.Podcast
import com.pypyradio.aacplayer.data.model.PodcastEpisode
import com.pypyradio.aacplayer.data.repo.PodcastRepository
import com.pypyradio.aacplayer.ui.vm.PodcastViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastScreen(
    vm: PodcastViewModel = viewModel(),
    player: Player,
    modifier: Modifier = Modifier
) {
    val state by vm.state.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val favoriteIds = remember(favorites) { favorites.map { it.id }.toSet() }
    
    var currentPlayingId by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    
    // Listen to player state
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                currentPlayingId = p.currentMediaItem?.mediaId
                isPlaying = p.isPlaying
            }
        }
        player.addListener(listener)
        currentPlayingId = player.currentMediaItem?.mediaId
        isPlaying = player.isPlaying
        onDispose { player.removeListener(listener) }
    }
    
    fun playEpisode(episode: PodcastEpisode, episodeList: List<PodcastEpisode>) {
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
        
        // Build playlist from episode list for next/prev support
        player.stop()
        player.clearMediaItems()
        
        if (episodeList.isNotEmpty()) {
            val mediaItems = episodeList.map { ep ->
                MediaItem.Builder()
                    .setMediaId(ep.id)
                    .setUri(ep.audioUrl)
                    .setRequestMetadata(
                        androidx.media3.common.MediaItem.RequestMetadata.Builder()
                            .setMediaUri(android.net.Uri.parse(ep.audioUrl))
                            .build()
                    )
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(ep.title)
                            .setArtist(ep.podcastTitle ?: ep.author)
                            .setArtworkUri(ep.imageUrl?.let { android.net.Uri.parse(it) }
                                ?: android.net.Uri.parse("android.resource://com.pypyradio.aacplayer/drawable/pypyradio_fallback_cover_art"))
                            .build()
                    )
                    .build()
            }
            val startIndex = episodeList.indexOf(episode).coerceAtLeast(0)
            player.setMediaItems(mediaItems, startIndex, 0L)
        } else {
            // Fallback to single episode
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
                            .setArtworkUri(episode.imageUrl?.let { android.net.Uri.parse(it) }
                                ?: android.net.Uri.parse("android.resource://com.pypyradio.aacplayer/drawable/pypyradio_fallback_cover_art"))
                            .build()
                )
                .build()
            player.setMediaItem(mediaItem)
        }
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Back button - only show when viewing episodes
                            if (state.showingEpisodes) {
                                IconButton(onClick = { vm.backToPodcasts() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            }
                            
                            Text(
                                if (state.showingEpisodes && state.selectedPodcast != null) 
                                    state.selectedPodcast!!.title 
                                else 
                                    "Podcasts",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = { },
                    actions = { },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            }
        }
    ) { padding ->
        Column(
            modifier = modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Search bar - modern design (only show when not viewing episodes)
            if (!state.showingEpisodes) {
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
                            placeholder = { Text("Search podcasts...") },
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
                
                // Category chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PodcastRepository.CATEGORIES.forEach { (label, category) ->
                        FilterChip(
                            selected = selectedCategory == (category ?: "trending"),
                            onClick = {
                                selectedCategory = category ?: "trending"
                                if (category != null) {
                                    vm.searchByCategory(category)
                                } else {
                                    vm.loadTrending()
                                }
                            },
                            label = { Text(label) }
                        )
                    }
                }
                
                // Podcast count
                if (!state.loading && state.error == null) {
                    Text(
                        "${state.podcasts.size} podcasts",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
            
            // Content
            when {
                state.loading && state.podcasts.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Failed to load",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = { vm.loadTrending() }) {
                                Text("Retry")
                            }
                        }
                    }
                }
                state.showingEpisodes -> {
                    // Episodes list
                    if (state.episodes.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Podcasts,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "No episodes",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(state.episodes, key = { it.id }) { episode ->
                                val isCurrentPlaying = currentPlayingId == episode.id && isPlaying
                                EpisodeRow(
                                    episode = episode,
                                    isPlaying = isCurrentPlaying,
                                    onClick = { playEpisode(episode, state.episodes) }
                                )
                            }
                        }
                    }
                }
                else -> {
                    // Podcasts list
                    if (state.podcasts.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Podcasts,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "No podcasts found",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(state.podcasts, key = { it.id }) { podcast ->
                                val isFav = favoriteIds.contains(podcast.id)
                                PodcastRow(
                                    podcast = podcast,
                                    isFavorite = isFav,
                                    onFavoriteClick = { vm.toggleFavorite(podcast) },
                                    onClick = { vm.loadEpisodes(podcast) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PodcastRow(
    podcast: Podcast,
    isFavorite: Boolean = false,
    onFavoriteClick: () -> Unit = {},
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Podcast artwork
            Surface(
                modifier = Modifier.size(64.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(podcast.imageUrl)
                        .crossfade(true)
                        .error(R.drawable.pypyradio_fallback_cover_art)
                        .fallback(R.drawable.pypyradio_fallback_cover_art)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.width(12.dp))
            
            // Podcast info
            Column(Modifier.weight(1f)) {
                Text(
                    podcast.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                podcast.author?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    podcast.genre?.let {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    podcast.episodeCount?.let {
                        Text(
                            "$it episodes",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
            
            // Favorite button
            FilledTonalIconButton(
                onClick = onFavoriteClick,
                modifier = Modifier.size(36.dp),
                colors = if (isFavorite) {
                    IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color(0xFFFFE0E0)
                    )
                } else {
                    IconButtonDefaults.filledTonalIconButtonColors()
                }
            ) {
                Icon(
                    if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (isFavorite) Color(0xFFE91E63) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(
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
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isPlaying) 4.dp else 1.dp
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Episode artwork
            Surface(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(episode.imageUrl)
                        .crossfade(true)
                        .error(R.drawable.pypyradio_fallback_cover_art)
                        .fallback(R.drawable.pypyradio_fallback_cover_art)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.width(12.dp))
            
            // Episode info
            Column(Modifier.weight(1f)) {
                Text(
                    episode.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                episode.podcastTitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    episode.publishedDate?.let {
                        Text(
                            it.take(10),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    episode.durationMs?.let { ms ->
                        val minutes = ms / 60000
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                "${minutes} min",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (isPlaying) {
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
            }
            
            // Play button
            FilledTonalIconButton(
                onClick = onClick,
                modifier = Modifier.size(40.dp),
                colors = if (isPlaying) {
                    IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
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
