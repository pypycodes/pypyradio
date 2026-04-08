package com.pypyradio.aacplayer.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import coil.compose.AsyncImage
import com.pypyradio.aacplayer.data.model.Podcast
import com.pypyradio.aacplayer.data.model.PodcastEpisode
import com.pypyradio.aacplayer.data.repo.PodcastRepository
import com.pypyradio.aacplayer.playback.RadioController
import com.pypyradio.aacplayer.ui.vm.PodcastViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastScreen(
    vm: PodcastViewModel = viewModel(),
    player: androidx.media3.exoplayer.ExoPlayer,
    onBack: () -> Unit,
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
    
    fun playEpisode(episode: PodcastEpisode, allEpisodes: List<PodcastEpisode>) {
        // Toggle play/pause if same episode
        if (currentPlayingId == episode.id) {
            if (player.isPlaying) player.pause() else player.play()
            return
        }
        
        // Stop and load all episodes as playlist
        player.stop()
        player.clearMediaItems()
        
        val mediaItems = allEpisodes.map { ep ->
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
        val startIndex = allEpisodes.indexOfFirst { it.id == episode.id }
        player.setMediaItems(mediaItems, startIndex.coerceAtLeast(0), 0L)
        player.prepare()
        player.play()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        when {
                            state.showingEpisodes && state.selectedPodcast != null -> state.selectedPodcast!!.title
                            state.showingFavorites -> "Favorite Podcasts"
                            else -> "Podcasts"
                        }
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            state.showingEpisodes -> vm.backToPodcasts()
                            state.showingFavorites -> vm.backToPodcasts()
                            else -> onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!state.showingEpisodes && !state.showingFavorites) {
                        IconButton(onClick = { vm.showFavorites() }) {
                            Icon(Icons.Default.Favorite, contentDescription = "Favorites", tint = Color.Red)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Search bar (only show when not viewing episodes or favorites)
            if (!state.showingEpisodes && !state.showingFavorites) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = state.query,
                        onValueChange = vm::setQuery,
                        label = { Text("Search podcasts") },
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { vm.search() }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
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
                state.loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Error: ${state.error}")
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { vm.loadTrending() }) {
                                Text("Retry")
                            }
                        }
                    }
                }
                state.showingEpisodes -> {
                    // Episodes list
                    if (state.episodes.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No episodes found")
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
                                HorizontalDivider()
                            }
                        }
                    }
                }
                state.showingFavorites -> {
                    // Favorites list
                    if (favorites.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.FavoriteBorder,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                Text("No favorite podcasts yet")
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Tap the heart icon on a podcast to add it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(favorites, key = { it.id }) { podcast ->
                                PodcastRow(
                                    podcast = podcast,
                                    isFavorite = true,
                                    onFavoriteClick = { vm.toggleFavorite(podcast) },
                                    onClick = { vm.loadFavoriteEpisodes(podcast) }
                                )
                                HorizontalDivider()
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
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                Text("No podcasts found")
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
                                HorizontalDivider()
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = podcast.imageUrl,
            contentDescription = null,
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(8.dp))
        )
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
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                podcast.genre?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                podcast.episodeCount?.let {
                    Text(
                        "$it episodes",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        IconButton(onClick = onFavoriteClick) {
            Icon(
                if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                tint = if (isFavorite) Color.Red else LocalContentColor.current
            )
        }
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = "View episodes",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EpisodeRow(
    episode: PodcastEpisode,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = episode.imageUrl,
            contentDescription = null,
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(6.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    episode.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isPlaying) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Playing...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            episode.podcastTitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                episode.publishedDate?.let {
                    Text(
                        it.take(10), // Just the date part
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                }
                episode.durationMs?.let { ms ->
                    val minutes = ms / 60000
                    Text(
                        "${minutes}min",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Icon(
            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = if (isPlaying) MaterialTheme.colorScheme.primary else LocalContentColor.current
        )
    }
}
