package com.pypyradio.aacplayer.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material3.*
import androidx.compose.ui.res.painterResource
import com.pypyradio.aacplayer.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.pypyradio.aacplayer.data.prefs.AppPreferences
import com.pypyradio.aacplayer.data.prefs.SoundMode
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pypyradio.aacplayer.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        val uriHandler = LocalUriHandler.current
        
        Column(
            modifier = modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Playback Settings Section
            Text(
                "Playback Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(Modifier.height(24.dp))

            val prefs = AppPreferences.get(LocalContext.current)
            val autoSkipEnabled by prefs.autoSkipEnabled.collectAsState()
            val currentSoundMode by prefs.soundMode.collectAsState()

            // Subheading: Sound Modes
            Text(
                "Sound Modes",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Optimize volume & boost for your activity:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SoundModeChip(
                            selected = currentSoundMode == SoundMode.DEFAULT,
                            onClick = { prefs.setSoundMode(SoundMode.DEFAULT) },
                            icon = Icons.Default.AutoFixHigh,
                            label = "Default",
                            modifier = Modifier.weight(1f)
                        )
                        SoundModeChip(
                            selected = currentSoundMode == SoundMode.STUDY,
                            onClick = { prefs.setSoundMode(SoundMode.STUDY) },
                            icon = Icons.AutoMirrored.Filled.MenuBook,
                            label = "Study",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SoundModeChip(
                            selected = currentSoundMode == SoundMode.NIGHT,
                            onClick = { prefs.setSoundMode(SoundMode.NIGHT) },
                            icon = Icons.Default.Nightlight,
                            label = "Night",
                            modifier = Modifier.weight(1f)
                        )
                        SoundModeChip(
                            selected = currentSoundMode == SoundMode.LOUD,
                            onClick = { prefs.setSoundMode(SoundMode.LOUD) },
                            icon = Icons.AutoMirrored.Filled.VolumeUp,
                            label = "Loud",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    
                    Spacer(Modifier.height(12.dp))
                    
                    val description = when(currentSoundMode) {
                        SoundMode.DEFAULT -> "Resets volume to 50% for standard listening."
                        SoundMode.STUDY -> "Focus mode: Volume capped at 30% with subtle boost."
                        SoundMode.NIGHT -> "Sleep mode: Volume capped at 20% with zero boost."
                        SoundMode.LOUD -> "Party mode: Boosted audio (+4dB) and 100% volume."
                    }
                    
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            
            // Subheading: Auto-Skip
            Text(
                "Auto-Skip",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(
                            "Enabled",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Automatically skips to the next station instead of stopping if a stream stays buffering for over 35 seconds.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoSkipEnabled,
                        onCheckedChange = { prefs.setAutoSkipEnabled(it) }
                    )
                }
            }
            
            Spacer(Modifier.height(32.dp))
            
            // About App Section
            Text(
                "About This App",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                "pypyradio",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Version ${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    Spacer(Modifier.height(16.dp))
                    
                    Text(
                        "pypyradio is a free, open-source radio player designed for simplicity. " +
                        "It provides access to thousands of internet radio stations and podcasts. " +
                        "We believe in privacy: the app contains absolutely no ads, no trackers, " +
                        "and does not collect your personal data.",
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.2
                    )
                }
            }
            
            Spacer(Modifier.height(24.dp))
            
            // Permissions Detail Section
            Text(
                "Why we need permissions",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(Modifier.height(16.dp))
            
            PermissionItem(
                icon = Icons.Default.Settings,
                title = "Internet Access",
                description = "Required to stream radio stations and download station metadata from the Radio Browser API."
            )
            
            PermissionItem(
                icon = Icons.Default.Notifications,
                title = "Foreground Service & Notifications",
                description = "Allows the radio to continue playing smoothly in the background while you use other apps or turn off your screen. The notification provides quick playback controls."
            )
            
            PermissionItem(
                icon = Icons.Default.Warning,
                title = "Wake Lock",
                description = "Prevents the system from pausing the radio stream when your device goes to sleep, ensuring uninterrupted playback."
            )
            
            PermissionItem(
                icon = Icons.Default.Info,
                title = "Android Auto",
                description = "Connects the app's media session to your car's display so you can safely browse and play favorite stations while driving."
            )
            
            Spacer(Modifier.height(32.dp))
            
            // Links
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                OutlinedButton(
                    onClick = { uriHandler.openUri("https://github.com/pypycodes/pypyradio") },
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text("Source Code")
                }
                Button(
                    onClick = { uriHandler.openUri("https://github.com/pypycodes/pypyradio/blob/main/privacypolicy.html") }
                ) {
                    Text("Privacy Policy")
                }
            }
            
            Spacer(Modifier.height(32.dp))
            
            // Footer
            Text(
                "Open Source Project • 2026",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SoundModeChip(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier
) {
    FilterChip(
        modifier = modifier,
        selected = selected,
        onClick = onClick,
        label = { Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        shape = RoundedCornerShape(12.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
        )
    )
}

@Composable
private fun PermissionItem(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(8.dp)
            )
        }
        
        Spacer(Modifier.width(16.dp))
        
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.2
            )
        }
    }
}
