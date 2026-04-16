package com.pypyradio.aacplayer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FullScreenPlayer(
    player: Player,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    sleepTimerMinutes: Int,
    onSleepTimerClick: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var artworkUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var hasNext by remember { mutableStateOf(false) }
    var hasPrev by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSlowConnection by remember { mutableStateOf(false) }
    var bufferingStartTime by remember { mutableStateOf(0L) }

    fun updateState() {
        val mediaItem = player.currentMediaItem
        title = mediaItem?.mediaMetadata?.title?.toString() ?: "No Title"
        artist = mediaItem?.mediaMetadata?.artist?.toString() ?: "No Artist"
        album = mediaItem?.mediaMetadata?.albumTitle?.toString() ?: ""
        artworkUri = mediaItem?.mediaMetadata?.artworkUri
        isPlaying = player.isPlaying
        hasNext = player.hasNextMediaItem()
        hasPrev = player.hasPreviousMediaItem()
        val wasBuffering = isBuffering
        // Only show buffering UI if we aren't actually playing audio yet
        isBuffering = player.playbackState == Player.STATE_BUFFERING && !player.isPlaying
        // Track buffering start
        if (isBuffering && !wasBuffering) {
            bufferingStartTime = System.currentTimeMillis()
            isSlowConnection = false
        } else if (!isBuffering) {
            isSlowConnection = false
        }
        // Check for errors
        hasError = player.playerError != null
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                updateState()
            }
            
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                hasError = true
                errorMessage = when {
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                        "Network error"
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "Connection timed out"
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "Stream unavailable"
                    else -> "Station not responding"
                }
            }
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    hasError = false
                    errorMessage = null
                }
            }
            
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                hasError = false
                errorMessage = null
            }
        }
        player.addListener(listener)
        updateState()
        onDispose { player.removeListener(listener) }
    }
    
    // Slow connection detection
    LaunchedEffect(isBuffering, bufferingStartTime) {
        if (isBuffering && bufferingStartTime > 0) {
            delay(8000L)
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

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Blurred Background
        if (artworkUri != null) {
            AsyncImage(
                model = artworkUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().padding(16.dp),
                alpha = 0.2f
            )
        }
        
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))
            
            // Artwork Wrapper
            Box(contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier
                        .size(280.dp)
                        .clip(RoundedCornerShape(24.dp)),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 8.dp
                ) {
                    AsyncImage(
                        model = artworkUri,
                        contentDescription = "Artwork",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (hasError) Modifier.alpha(0.4f) else Modifier)
                    )
                }
                
                // Error overlay card on artwork
                androidx.compose.animation.AnimatedVisibility(
                    visible = hasError,
                    enter = fadeIn(animationSpec = tween(300)),
                    exit = fadeOut(animationSpec = tween(300))
                ) {
                    Surface(
                        modifier = Modifier
                            .padding(24.dp)
                            .widthIn(max = 240.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                        shadowElevation = 4.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.SignalWifiOff,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                errorMessage ?: "Station offline",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "This station is currently unavailable",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
            
            Spacer(Modifier.height(48.dp))
            
            // Titles
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = artist,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (album.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = album,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            
            // Buffering status text
            AnimatedVisibility(visible = isBuffering) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (isSlowConnection) "Taking longer than usual..." else "Connecting...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.alpha(pulseAlpha)
                )
            }
            
            Spacer(Modifier.weight(1f))
            
            // Interaction Row (Favorite & Timer)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) Color(0xFFE91E63) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp)
                    )
                }
                TextButton(onClick = onSleepTimerClick) {
                    Icon(Icons.Default.Timer, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (sleepTimerMinutes > 0) "${sleepTimerMinutes}m" else "Sleep Timer")
                }
            }
            
            Spacer(Modifier.height(32.dp))
            
            // Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Prev
                IconButton(
                    onClick = { 
                        player.seekToPreviousMediaItem()
                        player.prepare()
                        player.play()
                    },
                    enabled = hasPrev,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(40.dp))
                }
                
                Spacer(Modifier.width(24.dp))
                
                // Play/Pause/Retry
                Surface(
                    modifier = Modifier.size(80.dp),
                    shape = CircleShape,
                    color = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    shadowElevation = 4.dp,
                    onClick = {
                        if (hasError) {
                            hasError = false
                            errorMessage = null
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
                    }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isBuffering) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(40.dp),
                                strokeWidth = 3.dp
                            )
                        } else if (hasError) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Retry",
                                tint = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.size(48.dp)
                            )
                        } else {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }
                }
                
                Spacer(Modifier.width(24.dp))
                
                // Next
                IconButton(
                    onClick = { 
                        hasError = false
                        errorMessage = null
                        player.seekToNextMediaItem()
                        player.prepare()
                        player.play()
                    },
                    enabled = hasNext,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(40.dp))
                }
            }
            
            // "Try Next Station" button when in error state with next available
            AnimatedVisibility(visible = hasError && hasNext) {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = {
                        hasError = false
                        errorMessage = null
                        player.seekToNextMediaItem()
                        player.prepare()
                        player.play()
                    }
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Try Next Station")
                }
            }
            
            Spacer(Modifier.height(48.dp))
        }
    }
}
