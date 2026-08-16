package com.manus.spatialsafety.ar

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.manus.spatialsafety.ar.ar.ArSafetyRenderer
import com.manus.spatialsafety.ar.safety.AlertController
import com.manus.spatialsafety.ar.ui.SafetyUiState
import com.manus.spatialsafety.ar.ui.UIOverlayScreen
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val state = MutableStateFlow(SafetyUiState())
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: ArSafetyRenderer
    private lateinit var alertController: AlertController
    private var session: Session? = null
    private var installRequested = false
    private var voiceEnabled by mutableStateOf(true)

    private val cameraPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startArIfReady() else state.value = SafetyUiState.error("Camera permission is required to scan obstacles.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        alertController = AlertController(applicationContext)
        renderer = ArSafetyRenderer(
            applicationContext,
            onStateChanged = { state.value = it },
            onAlert = { obstacle ->
                if (voiceEnabled) {
                    obstacle.distanceMeters?.let { distance ->
                        alertController.speakAlert(obstacle.zone.priority, obstacle.detection.label, distance)
                    }
                }
            },
        )
        glSurfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            preserveEGLContextOnPause = true
        }

        setContent {
            val uiState by state.collectAsState()
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFF36D399),
                    background = androidx.compose.ui.graphics.Color(0xFF071019),
                    surface = androidx.compose.ui.graphics.Color(0xFF071019),
                ),
            ) {
                Box {
                    AndroidView(
                        factory = { glSurfaceView },
                        modifier = Modifier.fillMaxSize(),
                    )
                    UIOverlayScreen(
                        state = uiState,
                        voiceEnabled = voiceEnabled,
                        onToggleScanning = { renderer.setScanningEnabled(uiState.paused) },
                        onToggleVoice = { voiceEnabled = !voiceEnabled },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startArIfReady()
    }

    private fun startArIfReady() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionRequest.launch(Manifest.permission.CAMERA)
            return
        }
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    state.value = SafetyUiState(statusText = "Installing Google Play Services for AR")
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }
            if (session == null) {
                session = Session(this).also { arSession ->
                    val config = Config(arSession).apply {
                        focusMode = Config.FocusMode.AUTO
                        depthMode = if (arSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                            Config.DepthMode.AUTOMATIC
                        } else {
                            Config.DepthMode.DISABLED
                        }
                    }
                    arSession.configure(config)
                    renderer.setSession(arSession)
                }
            }
            session?.resume()
            glSurfaceView.onResume()
        } catch (_: UnavailableArcoreNotInstalledException) {
            state.value = SafetyUiState.error("Google Play Services for AR is not installed.")
        } catch (_: UnavailableApkTooOldException) {
            state.value = SafetyUiState.error("Google Play Services for AR needs an update.")
        } catch (_: UnavailableSdkTooOldException) {
            state.value = SafetyUiState.error("This app needs a newer ARCore SDK implementation.")
        } catch (_: UnavailableDeviceNotCompatibleException) {
            state.value = SafetyUiState.error("This device does not support ARCore.")
        } catch (error: Exception) {
            state.value = SafetyUiState.error(error.message ?: "Unable to start the AR session.")
        }
    }

    override fun onPause() {
        glSurfaceView.onPause()
        session?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        renderer.close()
        alertController.close()
        session?.close()
        super.onDestroy()
    }
}
