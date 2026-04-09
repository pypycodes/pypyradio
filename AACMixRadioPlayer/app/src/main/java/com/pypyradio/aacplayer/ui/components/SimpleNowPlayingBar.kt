package com.pypyradio.aacplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player

@Composable
fun SimpleNowPlayingBar(
    player: Player,
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
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 12.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column {
            // Progress indicator for buffering/playing
            if (isBuffering || isReconnecting) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (isPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Favorite button with animation
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                        tint = if (isFavorite) Color(0xFFE91E63) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                Spacer(Modifier.width(8.dp))
                
                // Title and status
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = title ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Status dot
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = when {
                                        hasError -> MaterialTheme.colorScheme.error
                                        isReconnecting || isBuffering -> MaterialTheme.colorScheme.tertiary
                                        isPlaying -> Color(0xFF4CAF50)
                                        else -> MaterialTheme.colorScheme.outline
                                    },
                                    shape = CircleShape
                                )
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = when {
                                hasError -> "Connection failed"
                                isReconnecting -> "Reconnecting..."
                                isBuffering -> "Buffering..."
                                isPlaying -> "Live"
                                isStopped -> "Stopped"
                                else -> "Paused"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Controls - more compact and modern
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Previous
                    FilledTonalIconButton(
                        onClick = { player.seekToPreviousMediaItem() },
                        enabled = hasPrevious,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious, 
                            contentDescription = "Previous",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Play/Pause - larger and prominent
                    FilledIconButton(
                        onClick = {
                            if (hasError) {
                                player.prepare()
                                player.play()
                            } else if (isPlaying) {
                                player.pause()
                            } else {
                                if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                                    player.prepare()
                                }
                                player.play()
                            }
                        },
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        if (isBuffering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }

                    // Next
                    FilledTonalIconButton(
                        onClick = { player.seekToNextMediaItem() },
                        enabled = hasNext,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.SkipNext, 
                            contentDescription = "Next",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Stop
                    IconButton(
                        onClick = { 
                            player.stop()
                            player.clearMediaItems()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Stop, 
                            contentDescription = "Stop",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
