package com.castla.mirror.ui

import android.content.Intent
import android.content.pm.ResolveInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: StreamSettings,
    isStreaming: Boolean,
    isPremium: Boolean = false,
    onSettingsChanged: (StreamSettings) -> Unit,
    onBackClick: () -> Unit,
    onDebugTogglePremium: (() -> Unit)? = null
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

            // App picker removed — DesktopActivity on VD provides app selection via browser touch
            var showAppPicker by remember { mutableStateOf(false) }

            if (showAppPicker) {
                AppPickerDialog(
                    onAppSelected = { packageName, label ->
                        onSettingsChanged(settings.copy(
                            targetAppPackage = packageName,
                            targetAppLabel = label
                        ))
                        showAppPicker = false
                    },
                    onDismiss = { showAppPicker = false }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Resolution
            SettingSection(title = "Resolution") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StreamSettings.Resolution.entries.forEach { res ->
                        val isResLocked = !isPremium && (res == StreamSettings.Resolution.FHD_1080 || res == StreamSettings.Resolution.AUTO)
                        FilterChip(
                            selected = settings.resolution == res,
                            onClick = {
                                if (!isStreaming && !isResLocked) onSettingsChanged(settings.copy(resolution = res))
                            },
                            label = { Text(if (isResLocked) "${res.label} PRO" else res.label) },
                            enabled = !isStreaming && !isResLocked
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
                        val isFpsLocked = !isPremium && fps > 30
                        FilterChip(
                            selected = settings.fps == fps,
                            onClick = {
                                if (!isStreaming && !isFpsLocked) onSettingsChanged(settings.copy(fps = fps))
                            },
                            label = { Text(if (isFpsLocked) "${fps} fps PRO" else "${fps} fps") },
                            enabled = !isStreaming && !isFpsLocked
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

            // Debug: PRO toggle (only in debug builds)
            if (onDebugTogglePremium != null) {
                Spacer(modifier = Modifier.height(16.dp))
                SettingSection(title = "Debug") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isPremium) "PRO Active" else "Free",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Button(
                            onClick = onDebugTogglePremium,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPremium)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(if (isPremium) "Downgrade to Free" else "Activate PRO")
                        }
                    }
                }
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
                    val modeText = if (settings.mirroringMode == MirroringMode.APP && settings.targetAppLabel.isNotEmpty()) {
                        "App: ${settings.targetAppLabel}"
                    } else {
                        "Full Screen"
                    }
                    Text(
                        text = "$modeText, ${settings.resolution.width}x${settings.resolution.height} @ " +
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
fun AppPickerDialog(
    onAppSelected: (packageName: String, label: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val apps = remember {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = context.packageManager
        pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select App") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                items(apps) { app ->
                    val pm = context.packageManager
                    val label = app.loadLabel(pm).toString()
                    val packageName = app.activityInfo.packageName
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAppSelected(packageName, label) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
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
