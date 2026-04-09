package com.pypyradio.aacplayer.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

data class SleepTimerOption(val label: String, val minutes: Int)

private val sleepTimerOptions = listOf(
    SleepTimerOption("15 minutes", 15),
    SleepTimerOption("30 minutes", 30),
    SleepTimerOption("45 minutes", 45),
    SleepTimerOption("1 hour", 60),
    SleepTimerOption("1.5 hours", 90),
    SleepTimerOption("2 hours", 120)
)

@Composable
fun SleepTimerButton(
    remainingMinutes: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier.size(36.dp),
        colors = if (remainingMinutes != null) {
            IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        } else {
            IconButtonDefaults.filledTonalIconButtonColors()
        }
    ) {
        if (remainingMinutes != null) {
            Text(
                "${remainingMinutes}m",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(
                Icons.Default.Bedtime,
                contentDescription = "Sleep Timer",
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun SleepTimerDialog(
    isVisible: Boolean,
    currentMinutes: Int?,
    onDismiss: () -> Unit,
    onSetTimer: (Int?) -> Unit
) {
    if (!isVisible) return
    
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Bedtime,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                if (currentMinutes != null) "Sleep Timer Active" else "Set Sleep Timer",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                if (currentMinutes != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "$currentMinutes",
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "minutes remaining",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Playback will stop automatically when the timer ends.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "Select how long you want to listen before playback stops automatically.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(16.dp))
                    sleepTimerOptions.forEach { option ->
                        Surface(
                            onClick = { onSetTimer(option.minutes) },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                option.label,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (currentMinutes != null) {
                TextButton(onClick = { onSetTimer(null) }) {
                    Text("Cancel Timer")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (currentMinutes != null) "Close" else "Cancel")
            }
        }
    )
}

@Composable
fun rememberSleepTimerState(
    onTimerEnd: () -> Unit
): SleepTimerState {
    var remainingMinutes by remember { mutableStateOf<Int?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    
    // Countdown effect
    LaunchedEffect(remainingMinutes) {
        if (remainingMinutes != null && remainingMinutes!! > 0) {
            delay(60_000L) // 1 minute
            remainingMinutes = remainingMinutes!! - 1
        } else if (remainingMinutes == 0) {
            onTimerEnd()
            remainingMinutes = null
        }
    }
    
    return remember(remainingMinutes, showDialog) {
        SleepTimerState(
            remainingMinutes = remainingMinutes,
            showDialog = showDialog,
            onShowDialog = { showDialog = true },
            onDismissDialog = { showDialog = false },
            onSetTimer = { minutes ->
                remainingMinutes = minutes
                showDialog = false
            }
        )
    }
}

data class SleepTimerState(
    val remainingMinutes: Int?,
    val showDialog: Boolean,
    val onShowDialog: () -> Unit,
    val onDismissDialog: () -> Unit,
    val onSetTimer: (Int?) -> Unit
)
