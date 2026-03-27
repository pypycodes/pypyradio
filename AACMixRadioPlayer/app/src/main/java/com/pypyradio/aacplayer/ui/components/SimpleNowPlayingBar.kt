package com.pypyradio.aacplayer.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

@Composable
fun SimpleNowPlayingBar(
    player: ExoPlayer,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onStationFailed: (String) -> Unit = {}
) {
    var title by remember { mutableStateOf<String?>(null) }
    var mediaId by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var isStopped by remember { mutableStateOf(false) }
    var hasError by remember { mutableStateOf(false) }
    var isReconnecting by remember { mutableStateOf(false) }
    var errorCount by remember { mutableStateOf(0) }
    var hasNext by remember { mutableStateOf(false) }
    var hasPrevious by remember { mutableStateOf(false) }
    val maxAutoRetries = 5

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                title = p.currentMediaItem?.mediaMetadata?.title?.toString()
                    ?: p.currentMediaItem?.mediaId
                mediaId = p.currentMediaItem?.mediaId
                isPlaying = p.isPlaying
                isBuffering = p.playbackState == Player.STATE_BUFFERING
                isStopped = p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED
                hasNext = p.hasNextMediaItem()
                hasPrevious = p.hasPreviousMediaItem()
            }
            
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                errorCount++
                if (errorCount <= maxAutoRetries) {
                    // Show reconnecting state, not error (auto-retry in progress)
                    isReconnecting = true
                    hasError = false
                    isStopped = false
                } else {
                    // Max retries exceeded, show error
                    hasError = true
                    isReconnecting = false
                    isStopped = false
                    mediaId?.let { onStationFailed(it) }
                }
            }
            
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                // Clear error state when transitioning to a new track
                hasError = false
                isReconnecting = false
                errorCount = 0
                title = mediaItem?.mediaMetadata?.title?.toString() ?: mediaItem?.mediaId
                mediaId = mediaItem?.mediaId
            }
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                isStopped = playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED
                if (playbackState == Player.STATE_READY) {
                    // Playback recovered
                    hasError = false
                    isReconnecting = false
                    isStopped = false
                    errorCount = 0
                }
            }
        }
        player.addListener(listener)
        // Initial state
        title = player.currentMediaItem?.mediaMetadata?.title?.toString()
            ?: player.currentMediaItem?.mediaId
        mediaId = player.currentMediaItem?.mediaId
        isPlaying = player.isPlaying
        isBuffering = player.playbackState == Player.STATE_BUFFERING
        isStopped = player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED
        hasError = player.playerError != null
        hasNext = player.hasNextMediaItem()
        hasPrevious = player.hasPreviousMediaItem()
        onDispose { player.removeListener(listener) }
    }

    // Don't show if nothing is loaded
    if (title == null) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Favorite button
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else LocalContentColor.current
                )
            }
            
            // Title and status
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title ?: "",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        hasError -> "Failed - tap to retry"
                        isReconnecting -> "Reconnecting..."
                        isBuffering -> "Loading..."
                        isPlaying -> "Playing"
                        isStopped -> "Stopped - tap to play"
                        else -> "Paused"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        hasError -> MaterialTheme.colorScheme.error
                        isReconnecting -> MaterialTheme.colorScheme.tertiary
                        isPlaying -> MaterialTheme.colorScheme.primary
                        isStopped -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            // Controls
            Row(
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Previous
                IconButton(
                    onClick = { player.seekToPreviousMediaItem() },
                    enabled = hasPrevious
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous")
                }

                // Play/Pause
                IconButton(
                    onClick = {
                        if (hasError) {
                            // If there's an error, try to prepare and play again
                            player.prepare()
                            player.play()
                        } else if (isPlaying) {
                            player.pause()
                        } else {
                            // If stopped/idle, prepare first then play
                            if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                                player.prepare()
                            }
                            player.play()
                        }
                    }
                ) {
                    if (isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play"
                        )
                    }
                }

                // Next
                IconButton(
                    onClick = { player.seekToNextMediaItem() },
                    enabled = hasNext
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next")
                }

                // Stop
                IconButton(
                    onClick = { 
                        player.stop()
                        player.clearMediaItems()
                    }
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }
            }
        }
    }
}
