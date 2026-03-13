package com.jakarta.mirror

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jakarta.mirror.network.NetworkMonitor
import com.jakarta.mirror.network.NetworkState
import com.jakarta.mirror.service.MirrorForegroundService
import com.jakarta.mirror.ui.QrCodeGenerator
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var isStreaming by mutableStateOf(false)
    private var serverUrl by mutableStateOf("")
    private var currentIp by mutableStateOf("0.0.0.0")
    private var sessionToken by mutableStateOf("")

    private lateinit var networkMonitor: NetworkMonitor
    private var mirrorService: MirrorForegroundService? = null
    private var serviceBound = false
    private var bindRequested = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as MirrorForegroundService.LocalBinder
            mirrorService = localBinder.service
            sessionToken = localBinder.service.sessionToken ?: ""
            isStreaming = localBinder.service.isRunning
            updateServerUrl()
            serviceBound = true
            bindRequested = false
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mirrorService = null
            serviceBound = false
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startMirrorService(result.resultCode, result.data!!)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        networkMonitor = NetworkMonitor(this)
        networkMonitor.startMonitoring()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                networkMonitor.state.collect { state ->
                    when (state) {
                        is NetworkState.Connected -> {
                            currentIp = state.ip
                            updateServerUrl()
                        }
                        is NetworkState.Disconnected -> {
                            currentIp = "0.0.0.0"
                            updateServerUrl()
                        }
                    }
                }
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CastlaScreen(
                    isStreaming = isStreaming,
                    serverUrl = serverUrl,
                    currentIp = currentIp,
                    onStartClick = { requestScreenCapture() },
                    onStopClick = { stopMirrorService() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!serviceBound && !bindRequested) {
            val intent = Intent(this, MirrorForegroundService::class.java)
            bindRequested = bindService(intent, serviceConnection, 0)
        }
    }

    override fun onStop() {
        if (serviceBound || bindRequested) {
            try { unbindService(serviceConnection) } catch (_: IllegalArgumentException) {}
            serviceBound = false
            bindRequested = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        networkMonitor.stopMonitoring()
        super.onDestroy()
    }

    private fun updateServerUrl() {
        serverUrl = if (sessionToken.isNotEmpty()) {
            "http://${currentIp}:8080/?token=${sessionToken}"
        } else {
            "http://${currentIp}:8080"
        }
    }

    private fun requestScreenCapture() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startMirrorService(resultCode: Int, data: Intent) {
        val intent = Intent(this, MirrorForegroundService::class.java).apply {
            putExtra(MirrorForegroundService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MirrorForegroundService.EXTRA_DATA, data)
        }
        startForegroundService(intent)
        if (serviceBound || bindRequested) {
            try { unbindService(serviceConnection) } catch (_: IllegalArgumentException) {}
            serviceBound = false
            bindRequested = false
        }
        bindRequested = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        isStreaming = true
    }

    private fun stopMirrorService() {
        if (serviceBound || bindRequested) {
            try { unbindService(serviceConnection) } catch (_: IllegalArgumentException) {}
            serviceBound = false
            bindRequested = false
        }
        stopService(Intent(this, MirrorForegroundService::class.java))
        mirrorService = null
        isStreaming = false
        sessionToken = ""
        updateServerUrl()
    }
}

@Composable
fun CastlaScreen(
    isStreaming: Boolean,
    serverUrl: String,
    currentIp: String,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "Castla",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Tesla Screen Mirroring",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Status indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (isStreaming) Color(0xFF4CAF50) else Color(0xFF757575))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isStreaming) "Streaming Active" else "Ready to Stream",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isStreaming) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (isStreaming) {
                // QR Code
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Scan with Tesla Browser",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color(0xFF424242)
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val qrBitmap = remember(serverUrl) {
                            try { QrCodeGenerator.generate(serverUrl, 400) }
                            catch (_: Exception) { null }
                        }

                        qrBitmap?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "QR Code",
                                modifier = Modifier.size(200.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Connection info
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Connection Details",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        InfoRow("URL", serverUrl)
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoRow("IP", currentIp)
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoRow("Port", "8080")
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Main action button
            Button(
                onClick = if (isStreaming) onStopClick else onStartClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = if (isStreaming)
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    text = if (isStreaming) "Stop Mirroring" else "Start Mirroring",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (!isStreaming) {
                Spacer(modifier = Modifier.height(24.dp))

                // Instructions
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "How to use",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "1. Connect phone and Tesla to the same WiFi\n" +
                                "2. Tap \"Start Mirroring\" above\n" +
                                "3. Scan the QR code with Tesla's browser\n" +
                                "4. Your phone screen will appear on Tesla",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 20.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 16.dp)
        )
    }
}
