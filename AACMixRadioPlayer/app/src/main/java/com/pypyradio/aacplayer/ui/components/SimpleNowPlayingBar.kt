package com.pypyradio.aacplayer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@Composable
fun SimpleNowPlayingBar(
    player: Player,
    onStationFailed: (String) -> Unit = {},
    onClick: () -> Unit = {}
) {
    var title by remember { mutableStateOf<String?>(null) }
    var mediaId by remember { mutableStateOf<String?>(null) }
    var artworkUrl by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var isStopped by remember { mutableStateOf(false) }
    var hasError by remember { mutableStateOf(false) }
    var isReconnecting by remember { mutableStateOf(false) }
    var errorCount by remember { mutableStateOf(0) }
    var hasNext by remember { mutableStateOf(false) }
    var hasPrevious by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSlowConnection by remember { mutableStateOf(false) }
    var bufferingStartTime by remember { mutableStateOf(0L) }
    val maxAutoRetries = 1  // Service handles real retries; UI just shows one reconnecting pulse

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                title = p.currentMediaItem?.mediaMetadata?.title?.toString()
                    ?: p.currentMediaItem?.mediaId
                mediaId = p.currentMediaItem?.mediaId
                artworkUrl = p.currentMediaItem?.mediaMetadata?.artworkUri?.toString()
                isPlaying = p.isPlaying
                val wasBuffering = isBuffering
                // Only show buffering UI if we aren't actually playing audio yet
                isBuffering = p.playbackState == Player.STATE_BUFFERING && !p.isPlaying
                isStopped = p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED
                hasNext = p.hasNextMediaItem()
                hasPrevious = p.hasPreviousMediaItem()
                // Track buffering start for slow connection message
                if (isBuffering && !wasBuffering) {
                    bufferingStartTime = System.currentTimeMillis()
                    isSlowConnection = false
                } else if (!isBuffering) {
                    isSlowConnection = false
                }
            }
            
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                errorCount++
                // Determine contextual error message
                val msg = when {
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                        "Network error"
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "Connection timed out"
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "Stream unavailable"
                    else -> "Station not responding"
                }
                if (errorCount <= maxAutoRetries) {
                    // Show reconnecting state, not error (auto-retry in progress)
                    isReconnecting = true
                    hasError = false
                    isStopped = false
                    errorMessage = null
                } else {
                    // Max retries exceeded - mark as failed
                    errorMessage = msg
                    mediaId?.let { id ->
                        onStationFailed(id)
                        if (!hasNext) {
                            // No next station, show error
                            hasError = true
                            isReconnecting = false
                            isStopped = false
                        }
                    }
                    // Reset error count for next station
                    errorCount = 0
                }
            }
            
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                // Clear error state when transitioning to a new track
                hasError = false
                isReconnecting = false
                errorCount = 0
                errorMessage = null
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
                    errorMessage = null
                }
            }
        }
        player.addListener(listener)
        // Initial state
        title = player.currentMediaItem?.mediaMetadata?.title?.toString()
            ?: player.currentMediaItem?.mediaId
        mediaId = player.currentMediaItem?.mediaId
        artworkUrl = player.currentMediaItem?.mediaMetadata?.artworkUri?.toString()
        isPlaying = player.isPlaying
        isBuffering = player.playbackState == Player.STATE_BUFFERING && !player.isPlaying
        isStopped = player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED
        hasError = player.playerError != null
        hasNext = player.hasNextMediaItem()
        hasPrevious = player.hasPreviousMediaItem()
        onDispose { player.removeListener(listener) }
    }

    // Don't show if nothing is loaded
    if (title == null) return
    
    // Slow connection detection
    LaunchedEffect(isBuffering, bufferingStartTime) {
        if (isBuffering && bufferingStartTime > 0) {
            delay(5000L)
            if (isBuffering) {
                isSlowConnection = true
            }
        }
    }
    
    // Pulsing animation
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

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 12.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        onClick = onClick
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
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Artwork thumbnail with favorite overlay
                Box(modifier = Modifier.size(48.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        if (artworkUrl != null) {
                            AsyncImage(
                                model = artworkUrl,
                                contentDescription = "Now playing artwork",
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Radio,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
                
                Spacer(Modifier.width(10.dp))
                
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
                                hasError -> errorMessage ?: "Station offline"
                                isReconnecting -> "Reconnecting..."
                                isBuffering && isSlowConnection -> "Slow connection..."
                                isBuffering -> "Connecting..."
                                isPlaying -> "Live"
                                isStopped -> "Stopped"
                                else -> "Paused"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                hasError -> MaterialTheme.colorScheme.error
                                isBuffering || isReconnecting -> MaterialTheme.colorScheme.tertiary
                                isPlaying -> Color(0xFF4CAF50)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = if (isBuffering || isReconnecting) Modifier.alpha(pulseAlpha) else Modifier
                        )
                    }
                }

                // Controls - simplified for mini player
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Previous
                    IconButton(
                        onClick = { 
                            player.seekToPreviousMediaItem()
                            player.prepare() // Ensure ready
                            player.play()    // Forced play
                        },
                        enabled = hasPrevious,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious, 
                            contentDescription = "Previous",
                            modifier = Modifier.size(28.dp),
                            tint = if (hasPrevious) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }

                    // Play/Pause/Retry - large and prominent
                    FilledIconButton(
                        onClick = {
                            if (hasError) {
                                // Retry: clear error state and re-prepare
                                hasError = false
                                errorMessage = null
                                errorCount = 0
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
                            containerColor = if (hasError)
                                MaterialTheme.colorScheme.errorContainer
                            else
                                MaterialTheme.colorScheme.primaryContainer,
                            contentColor = if (hasError)
                                MaterialTheme.colorScheme.onErrorContainer
                            else
                                MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        if (isBuffering || isReconnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else if (hasError) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Retry",
                                modifier = Modifier.size(28.dp)
                            )
                        } else {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    // Next
                    IconButton(
                        onClick = { 
                            player.seekToNextMediaItem()
                            player.prepare() // Ensure ready
                            player.play()    // Forced play
                        },
                        enabled = hasNext,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.SkipNext, 
                            contentDescription = "Next",
                            modifier = Modifier.size(28.dp),
                            tint = if (hasNext) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                }
            }
            
            // Error info banner — expandable, tap to retry
            AnimatedVisibility(
                visible = hasError,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    onClick = {
                        hasError = false
                        errorMessage = null
                        errorCount = 0
                        player.prepare()
                        player.play()
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${errorMessage ?: "Station offline"} · Tap to retry",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                hasError = false
                                errorMessage = null
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Dismiss",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }
    }
}
