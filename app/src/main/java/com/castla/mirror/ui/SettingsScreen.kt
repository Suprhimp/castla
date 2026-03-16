package com.castla.mirror.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: StreamSettings,
    isStreaming: Boolean,
    onSettingsChanged: (StreamSettings) -> Unit,
    onBackClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBackClick) {
                    Text("< Back")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Settings",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (isStreaming) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Stop streaming to change settings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Resolution
            SettingSection(title = "Resolution") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StreamSettings.Resolution.entries.forEach { res ->
                        FilterChip(
                            selected = settings.resolution == res,
                            onClick = {
                                if (!isStreaming) onSettingsChanged(settings.copy(resolution = res))
                            },
                            label = { Text(res.label) },
                            enabled = !isStreaming
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bitrate
            SettingSection(title = "Bitrate") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StreamSettings.BITRATE_OPTIONS.forEach { (value, label) ->
                        FilterChip(
                            selected = settings.bitrate == value,
                            onClick = {
                                if (!isStreaming) onSettingsChanged(settings.copy(bitrate = value))
                            },
                            label = { Text(label) },
                            enabled = !isStreaming
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // FPS
            SettingSection(title = "Frame Rate") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StreamSettings.FPS_OPTIONS.forEach { fps ->
                        FilterChip(
                            selected = settings.fps == fps,
                            onClick = {
                                if (!isStreaming) onSettingsChanged(settings.copy(fps = fps))
                            },
                            label = { Text("${fps} fps") },
                            enabled = !isStreaming
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Audio
            SettingSection(title = "Audio (Experimental)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Stream device audio",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Switch(
                        checked = settings.audioEnabled,
                        onCheckedChange = { enabled ->
                            if (!isStreaming) onSettingsChanged(settings.copy(audioEnabled = enabled))
                        },
                        enabled = !isStreaming
                    )
                }
                Text(
                    text = "Requires Android 10+. Captures system audio output.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Current config summary
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Current Configuration",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${settings.resolution.width}x${settings.resolution.height} @ " +
                            "${settings.fps}fps, ${settings.bitrate / 1_000_000}Mbps" +
                            if (settings.audioEnabled) ", audio on" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun SettingSection(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}
