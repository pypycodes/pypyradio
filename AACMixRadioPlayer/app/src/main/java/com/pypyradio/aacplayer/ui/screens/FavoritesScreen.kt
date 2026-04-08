package com.pypyradio.aacplayer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.pypyradio.aacplayer.data.model.Station
import com.pypyradio.aacplayer.ui.vm.StationsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    vm: StationsViewModel,
    player: ExoPlayer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val favs by vm.favorites.collectAsState()
    
    var currentPlayingId by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var lastPlayTime by remember { mutableStateOf(0L) }
    
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
    
    fun playStation(st: Station) {
        val now = System.currentTimeMillis()
        // Debounce: ignore rapid taps within 500ms
        if (now - lastPlayTime < 500) {
            return
        }
        lastPlayTime = now
        
        val url = st.urlResolved
        if (url.isNotBlank()) {
            // Toggle play/pause if same station
            if (currentPlayingId == st.stationuuid) {
                try {
                    if (player.isPlaying) player.pause() else player.play()
                } catch (e: Exception) {
                    // Ignore errors
                }
                return
            }
            
            try {
                // Stop and build small playlist for next/prev
                player.stop()
                player.clearMediaItems()
                
                val safeList = favs.toList()
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
                    androidx.media3.common.MediaItem.Builder()
                        .setMediaId(station.stationuuid)
                        .setUri(station.urlResolved)
                        .setMediaMetadata(
                            androidx.media3.common.MediaMetadata.Builder()
                                .setTitle(station.name)
                                .build()
                        )
                        .build()
                }
                
                if (mediaItems.isNotEmpty()) {
                    player.setMediaItems(mediaItems, playlistIndex, 0L)
                    player.prepare()
                    player.play()
                }
            } catch (e: Exception) {
                // Fallback: try single station
                try {
                    player.stop()
                    player.clearMediaItems()
                    val mediaItem = androidx.media3.common.MediaItem.Builder()
                        .setMediaId(st.stationuuid)
                        .setUri(url)
                        .setMediaMetadata(
                            androidx.media3.common.MediaMetadata.Builder()
                                .setTitle(st.name)
                                .build()
                        )
                        .build()
                    player.setMediaItem(mediaItem)
                    player.prepare()
                    player.play()
                } catch (e2: Exception) {
                    // Ignore errors
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Favorites (${favs.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        if (favs.isEmpty()) {
            Box(modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No favorites yet. Add favorites from the Browse tab.")
            }
        } else {
            LazyColumn(modifier.padding(padding).fillMaxSize()) {
                items(favs, key = { it.stationuuid }) { st ->
                    val isCurrentPlaying = currentPlayingId == st.stationuuid && isPlaying
                    FavRow(
                        st = st,
                        isPlaying = isCurrentPlaying,
                        onRowClick = { playStation(st) },
                        onRemove = { vm.toggleFavorite(st) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun FavRow(st: Station, isPlaying: Boolean, onRowClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onRowClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(model = st.favicon, contentDescription = null, modifier = Modifier.size(40.dp))
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
                if (isPlaying) {
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
        IconButton(onClick = onRemove) { 
            Icon(Icons.Default.Delete, contentDescription = "Remove from favorites") 
        }
        Icon(
            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = if (isPlaying) MaterialTheme.colorScheme.primary else LocalContentColor.current
        )
    }
}
