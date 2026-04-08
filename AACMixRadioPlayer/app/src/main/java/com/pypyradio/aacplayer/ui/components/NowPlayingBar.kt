package com.pypyradio.aacplayer.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.pypyradio.aacplayer.playback.RadioController

enum class PlaybackState {
    IDLE, LOADING, PLAYING, PAUSED, ERROR
}

@Composable
fun NowPlayingBar() {
    val context = LocalContext.current

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var title by remember { mutableStateOf<String?>(null) }
    var playbackState by remember { mutableStateOf(PlaybackState.IDLE) }
    var isMuted by remember { mutableStateOf(false) }
    var hasNext by remember { mutableStateOf(false) }
    var hasPrevious by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableStateOf(0) }
    var totalCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        controller = RadioController.get(context)
    }

    DisposableEffect(controller) {
        val c = controller ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                title = player.currentMediaItem?.mediaMetadata?.title?.toString()
                isMuted = player.volume == 0f
                hasNext = player.hasNextMediaItem()
                hasPrevious = player.hasPreviousMediaItem()
                currentIndex = player.currentMediaItemIndex + 1
                totalCount = player.mediaItemCount
                
                // Determine playback state
                playbackState = when {
                    player.playerError != null -> PlaybackState.ERROR
                    player.playbackState == Player.STATE_BUFFERING -> PlaybackState.LOADING
                    player.isPlaying -> PlaybackState.PLAYING
                    player.playbackState == Player.STATE_READY && !player.isPlaying -> PlaybackState.PAUSED
                    player.playbackState == Player.STATE_IDLE -> PlaybackState.IDLE
                    else -> PlaybackState.IDLE
                }
            }
        }
        c.addListener(listener)
        // Initial state
        title = c.currentMediaItem?.mediaMetadata?.title?.toString()
        isMuted = c.volume == 0f
        hasNext = c.hasNextMediaItem()
        hasPrevious = c.hasPreviousMediaItem()
        currentIndex = c.currentMediaItemIndex + 1
        totalCount = c.mediaItemCount
        playbackState = when {
            c.playerError != null -> PlaybackState.ERROR
            c.playbackState == Player.STATE_BUFFERING -> PlaybackState.LOADING
            c.isPlaying -> PlaybackState.PLAYING
            c.playbackState == Player.STATE_READY && !c.isPlaying -> PlaybackState.PAUSED
            else -> PlaybackState.IDLE
        }
        onDispose { c.removeListener(listener) }
    }

    // Don't show if nothing is loaded
    if (title == null) return
    val shownTitle = title!!

    // Status text and color based on playback state
    val (statusText, statusColor) = when (playbackState) {
        PlaybackState.LOADING -> "Loading..." to MaterialTheme.colorScheme.tertiary
        PlaybackState.PLAYING -> "Playing" to MaterialTheme.colorScheme.primary
        PlaybackState.PAUSED -> "Paused" to MaterialTheme.colorScheme.onSurfaceVariant
        PlaybackState.ERROR -> "Error" to MaterialTheme.colorScheme.error
        PlaybackState.IDLE -> "Stopped" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Top row: Status indicator + Station name
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Animated status indicator
                StatusIndicator(playbackState = playbackState, color = statusColor)
                
                Spacer(Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    // Status text
                    Text(
                        statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.Medium
                    )
                    // Station name
                    Text(
                        shownTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Track position indicator
                    if (totalCount > 1) {
                        Text(
                            "$currentIndex of $totalCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                // Mute button
                IconButton(onClick = {
                    controller?.let {
                        if (isMuted) {
                            it.volume = 1f
                        } else {
                            it.volume = 0f
                        }
                    }
                }) {
                    Icon(
                        if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (isMuted) "Unmute" else "Mute",
                        tint = if (isMuted) MaterialTheme.colorScheme.error else LocalContentColor.current
                    )
                }
            }
            
            Spacer(Modifier.height(8.dp))
            
            // Controls row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Previous button
                FilledTonalIconButton(
                    onClick = { controller?.seekToPreviousMediaItem() },
                    enabled = hasPrevious,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                // Main Play/Pause/Stop button - larger and prominent
                FilledIconButton(
                    onClick = {
                        controller?.let { c ->
                            when (playbackState) {
                                PlaybackState.PLAYING, PlaybackState.LOADING -> c.pause()
                                PlaybackState.PAUSED -> c.play()
                                PlaybackState.ERROR, PlaybackState.IDLE -> { c.prepare(); c.play() }
                            }
                        }
                    },
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = when (playbackState) {
                            PlaybackState.LOADING -> MaterialTheme.colorScheme.tertiary
                            PlaybackState.PLAYING -> MaterialTheme.colorScheme.primary
                            PlaybackState.ERROR -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primaryContainer
                        }
                    )
                ) {
                    when (playbackState) {
                        PlaybackState.LOADING -> {
                            // Show loading spinner inside button
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = MaterialTheme.colorScheme.onTertiary,
                                strokeWidth = 3.dp
                            )
                        }
                        PlaybackState.PLAYING -> {
                            Icon(
                                Icons.Default.Pause,
                                contentDescription = "Pause",
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        PlaybackState.ERROR -> {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Retry",
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onError
                            )
                        }
                        else -> {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                
                // Next button
                FilledTonalIconButton(
                    onClick = { controller?.seekToNextMediaItem() },
                    enabled = hasNext,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Next",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusIndicator(playbackState: PlaybackState, color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    
    when (playbackState) {
        PlaybackState.LOADING -> {
            // Pulsing dot for loading
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulse"
            )
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha))
            )
        }
        PlaybackState.PLAYING -> {
            // Animated bars for playing
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.height(12.dp)
            ) {
                repeat(3) { index ->
                    val height by infiniteTransition.animateFloat(
                        initialValue = 4f,
                        targetValue = 12f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(
                                durationMillis = 400,
                                delayMillis = index * 100,
                                easing = FastOutSlowInEasing
                            ),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "bar$index"
                    )
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(height.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(color)
                    )
                }
            }
        }
        else -> {
            // Static dot for other states
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.5f))
            )
        }
    }
}
