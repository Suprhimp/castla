package com.castla.mirror.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.castla.mirror.R
import androidx.compose.ui.res.stringResource

@Composable
fun MeshGradientBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)) // Base dark
    ) {
        // Top-Left Coral
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFFFF5252).copy(alpha = 0.45f), Color.Transparent),
                        center = Offset(0f, 0f),
                        radius = 1500f
                    )
                )
        )
        // Center Blue
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF2979FF).copy(alpha = 0.45f), Color.Transparent),
                        center = Offset(600f, 1000f),
                        radius = 2000f
                    )
                )
        )
        content()
    }
}

fun Modifier.glassCard() = this
    .clip(RoundedCornerShape(24.dp))
    .background(Color.White.copy(alpha = 0.05f))
    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(24.dp))

@Composable
fun ModernOptionChip(text: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.05f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = text,
            color = if (selected) Color.Black else Color.White.copy(alpha = if (enabled) 1f else 0.4f),
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: StreamSettings,
    isStreaming: Boolean,
    isPremium: Boolean = false,
    onSettingsChanged: (StreamSettings) -> Unit,
    onBackClick: () -> Unit,
    onUpgradeClick: (() -> Unit)? = null
) {
    MeshGradientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 48.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 32.dp)
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.1f))
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Settings",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
            }

            AnimatedVisibility(visible = isStreaming) {
                Box(modifier = Modifier.padding(bottom = 24.dp)) {
                    Text(
                        text = "Stop streaming to change settings",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFF5252),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Resolution
            SettingSection(title = "Max Resolution") {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StreamSettings.Resolution.entries.forEach { res ->
                        val isResLocked = !isPremium && res == StreamSettings.Resolution.RES_1080
                        ModernOptionChip(
                            text = if (isResLocked) "${res.label} PRO" else res.label,
                            selected = settings.maxResolution == res,
                            onClick = {
                                if (!isStreaming && !isResLocked) onSettingsChanged(settings.copy(maxResolution = res))
                            },
                            enabled = !isStreaming && !isResLocked
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // FPS
            SettingSection(title = "Frame Rate") {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StreamSettings.FPS_OPTIONS.forEach { fps ->
                        val isFpsLocked = !isPremium && fps > 30
                        ModernOptionChip(
                            text = if (isFpsLocked) "${fps}fps PRO" else "${fps}fps",
                            selected = settings.fps == fps,
                            onClick = {
                                if (!isStreaming && !isFpsLocked) onSettingsChanged(settings.copy(fps = fps))
                            },
                            enabled = !isStreaming && !isFpsLocked
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Audio
            SettingSection(title = "Audio (Experimental)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Stream device audio",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Requires Android 10+. Captures system output.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = settings.audioEnabled,
                        onCheckedChange = { enabled ->
                            if (!isStreaming) onSettingsChanged(settings.copy(audioEnabled = enabled))
                        },
                        enabled = !isStreaming,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF2979FF),
                            uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                            uncheckedTrackColor = Color.White.copy(alpha = 0.2f),
                            uncheckedBorderColor = Color.Transparent
                        )
                    )
                }
            }

            // PRO Upgrade
            if (!isPremium && onUpgradeClick != null) {
                Spacer(modifier = Modifier.height(20.dp))
                SettingSection(title = "Castla PRO") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Unlock all features",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color(0xFFFFD700),
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "1080p, 60fps, and more",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                        Button(
                            onClick = onUpgradeClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFFD700)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Upgrade", color = Color.Black, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Current config summary
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassCard()
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        text = "Current Configuration",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.9f),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val modeText = if (settings.mirroringMode == MirroringMode.APP && settings.targetAppLabel.isNotEmpty()) {
                        "App: ${settings.targetAppLabel}"
                    } else {
                        "Full Screen"
                    }
                    Text(
                        text = "$modeText, Max ${settings.maxResolution.label} @ " +
                            "${settings.fps}fps, Auto Bitrate" +
                            if (settings.audioEnabled) ", audio on" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
fun SettingSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glassCard()
                .padding(20.dp)
        ) {
            content()
        }
    }
}