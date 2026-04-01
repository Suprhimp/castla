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
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import com.castla.mirror.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign

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
    thermalStatus: Int = 0,
    onSettingsChanged: (StreamSettings) -> Unit,
    onBackClick: () -> Unit
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
                        contentDescription = stringResource(R.string.settings_back),
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.settings_title),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
            }

            AnimatedVisibility(visible = isStreaming) {
                Box(modifier = Modifier.padding(bottom = 24.dp)) {
                    Text(
                        text = stringResource(R.string.settings_stop_streaming_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFF5252),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Thermal throttling warning
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val thermalWarning = when (thermalStatus) {
                    PowerManager.THERMAL_STATUS_EMERGENCY ->
                        stringResource(R.string.thermal_warning_emergency)
                    PowerManager.THERMAL_STATUS_CRITICAL,
                    PowerManager.THERMAL_STATUS_SEVERE ->
                        stringResource(R.string.thermal_warning_critical)
                    PowerManager.THERMAL_STATUS_MODERATE ->
                        stringResource(R.string.thermal_warning_moderate)
                    PowerManager.THERMAL_STATUS_LIGHT ->
                        stringResource(R.string.thermal_warning_light)
                    else -> null
                }
                AnimatedVisibility(visible = thermalWarning != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFFFF5252).copy(alpha = 0.15f))
                            .border(1.dp, Color(0xFFFF5252).copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Text(
                            text = thermalWarning ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFFF5252),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Resolution
            SettingSection(title = stringResource(R.string.settings_max_resolution)) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StreamSettings.Resolution.entries.forEach { res ->
                        val localizedLabel = when (res) {
                            StreamSettings.Resolution.RES_720 -> stringResource(R.string.settings_res_720)
                            StreamSettings.Resolution.RES_1080 -> stringResource(R.string.settings_res_1080)
                        }
                        ModernOptionChip(
                            text = localizedLabel,
                            selected = settings.maxResolution == res,
                            onClick = {
                                if (!isStreaming) onSettingsChanged(settings.copy(maxResolution = res))
                            },
                            enabled = !isStreaming
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // FPS
            SettingSection(title = stringResource(R.string.settings_frame_rate)) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StreamSettings.FPS_OPTIONS.forEach { fps ->
                        ModernOptionChip(
                            text = "${fps}fps",
                            selected = settings.fps == fps,
                            onClick = {
                                if (!isStreaming) onSettingsChanged(settings.copy(fps = fps))
                            },
                            enabled = !isStreaming
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Audio
            SettingSection(title = stringResource(R.string.settings_audio_experimental)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_stream_device_audio),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.settings_audio_description),
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

            Spacer(modifier = Modifier.height(20.dp))

            // Auto Hotspot
            SettingSection(title = stringResource(R.string.settings_auto_hotspot)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_auto_hotspot_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.settings_auto_hotspot_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = settings.autoHotspot,
                        onCheckedChange = { enabled ->
                            if (!isStreaming) onSettingsChanged(settings.copy(autoHotspot = enabled))
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

            Spacer(modifier = Modifier.height(32.dp))

            // Support the Developer
            run {
                val context = LocalContext.current
                val isKorean = java.util.Locale.getDefault().language == "ko"
                val donateUrl = if (isKorean) "https://qr.kakaopay.com/Ej8mYEElE" else "https://ko-fi.com/suprhimp"
                val buttonLabel = if (isKorean) stringResource(R.string.settings_donate_kakaopay) else stringResource(R.string.settings_donate_kofi)

                SettingSection(title = stringResource(R.string.settings_support_title)) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.settings_support_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(donateUrl)))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isKorean) Color(0xFFFFEB00) else Color(0xFF72A4F2)
                            )
                        ) {
                            Text(
                                text = buttonLabel,
                                color = if (isKorean) Color.Black else Color.White,
                                fontWeight = FontWeight.Bold
                            )
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
                        text = stringResource(R.string.settings_current_config),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.9f),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val modeText = if (settings.mirroringMode == MirroringMode.APP && settings.targetAppLabel.isNotEmpty()) {
                        stringResource(R.string.settings_app_mode, settings.targetAppLabel)
                    } else {
                        stringResource(R.string.settings_full_screen)
                    }
                    val resLabel = when (settings.maxResolution) {
                        StreamSettings.Resolution.RES_720 -> stringResource(R.string.settings_res_720)
                        StreamSettings.Resolution.RES_1080 -> stringResource(R.string.settings_res_1080)
                    }
                    val audioSuffix = if (settings.audioEnabled) stringResource(R.string.settings_audio_on) else ""
                    Text(
                        text = "$modeText, Max $resLabel @ " +
                            "${settings.fps}fps, ${stringResource(R.string.settings_auto_bitrate)}" +
                            audioSuffix,
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