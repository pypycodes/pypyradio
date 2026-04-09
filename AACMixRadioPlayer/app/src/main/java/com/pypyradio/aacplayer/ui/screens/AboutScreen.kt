package com.pypyradio.aacplayer.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.ui.res.painterResource
import com.pypyradio.aacplayer.R
import androidx.compose.runtime.Composable
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
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About & Permissions") },
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
            // App Header
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = "pypyradio",
                modifier = Modifier.size(100.dp)
            )
            
            Text(
                "pypyradio",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )
            
            Text(
                "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(Modifier.height(32.dp))
            
            // About App Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "About This App",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
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
private fun PermissionItem(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        crossAxisAlignment = Alignment.Top
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
