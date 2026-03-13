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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jakarta.mirror.network.NetworkMonitor
import com.jakarta.mirror.network.NetworkState
import com.jakarta.mirror.service.MirrorForegroundService
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
            // Read token directly from the bound service — no timing dependency
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
                MainScreen(
                    isStreaming = isStreaming,
                    serverUrl = serverUrl,
                    onStartClick = { requestScreenCapture() },
                    onStopClick = { stopMirrorService() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Rebind to an already-running service (e.g. after activity recreation)
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
        // Start as foreground service, then bind to read token
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
fun MainScreen(
    isStreaming: Boolean,
    serverUrl: String,
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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Jakarta Mirror",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (isStreaming) "Streaming Active" else "Ready",
                style = MaterialTheme.typography.bodyLarge,
                color = if (isStreaming)
                    MaterialTheme.colorScheme.tertiary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (isStreaming) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Tesla Browser URL",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = serverUrl,
                            style = MaterialTheme.typography.titleLarge,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = if (isStreaming) onStopClick else onStartClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = if (isStreaming)
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else
                    ButtonDefaults.buttonColors()
            ) {
                Text(
                    text = if (isStreaming) "Stop Mirroring" else "Start Mirroring",
                    fontSize = 18.sp
                )
            }
        }
    }
}
