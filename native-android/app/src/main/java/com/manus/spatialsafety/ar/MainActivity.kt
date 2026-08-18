package com.manus.spatialsafety.ar

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
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
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.manus.spatialsafety.ar.ar.ArSafetyRenderer
import com.manus.spatialsafety.ar.pipeline.SmolVlmConfig
import com.manus.spatialsafety.ar.pipeline.DepthSensorSnapshot
import com.manus.spatialsafety.ar.pipeline.PerceptionContext
import com.manus.spatialsafety.ar.pipeline.SmolVlmNavigationPipeline
import com.manus.spatialsafety.ar.safety.AlertController
import com.manus.spatialsafety.ar.safety.ThreatZone
import com.manus.spatialsafety.ar.ui.SafetyUiState
import com.manus.spatialsafety.ar.ui.UIOverlayScreen
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.lifecycleScope

/**
 * Hosts the AR experience only after the device, ARCore service, camera permission, and renderer
 * are ready. Every startup failure becomes a visible Compose fallback state instead of a process
 * crash, so the app remains open even on unsupported or partially configured devices.
 */
open class MainActivity : ComponentActivity() {
    private val state = MutableStateFlow(SafetyUiState())
    private var glSurfaceView by mutableStateOf<GLSurfaceView?>(null)
    private var renderer: ArSafetyRenderer? = null
    private lateinit var alertController: AlertController
    private var vlmPipeline: SmolVlmNavigationPipeline? = null
    private var session: Session? = null
    private var installRequested = false

    private val cameraPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startArIfReady() else showStartupError("Camera permission is required to scan obstacles.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        alertController = AlertController(applicationContext)
        Log.w(TAG, "SmolVLM2 local engine boundary is configured, but no bundled .litertlm model artifact is present; depth safety fallback remains active")
        vlmPipeline = SmolVlmNavigationPipeline(
            context = applicationContext,
            config = SmolVlmConfig(),
            scope = lifecycleScope,
            perceptionContext = {
                val current = state.value
                PerceptionContext(
                    yoloConfidence = current.reading.confidence.takeIf { it > 0f },
                    unknownObject = current.highestZone == ThreatZone.UNKNOWN,
                    detectionsUnstable = !current.reading.isStable,
                    safetyCritical = current.highestZone == ThreatZone.TURANT_RUKE,
                    depthDistanceMeters = current.reading.distanceMeters,
                )
            },
            depthSnapshot = {
                DepthSensorSnapshot(
                    distanceMeters = state.value.reading.distanceMeters,
                    confidence = state.value.reading.confidence,
                )
            },
        )

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
                    glSurfaceView?.let { surfaceView ->
                        AndroidView(
                            factory = { surfaceView },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    UIOverlayScreen(state = uiState)
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

        val arCore = ArCoreApk.getInstance()
        val availability = arCore.checkAvailability(this)
        when {
            availability.isUnsupported() -> {
                showStartupError("This device does not support ARCore. The safety scanner cannot start here.")
                return
            }
            availability.isTransient() -> {
                state.value = SafetyUiState(statusText = "Checking ARCore availability")
                arCore.checkAvailabilityAsync(this) { result ->
                    runOnUiThread {
                        if (result.isSupported()) startArIfReady()
                        else if (!result.isTransient()) showStartupError("ARCore availability could not be verified.")
                    }
                }
                return
            }
            !availability.isSupported() -> {
                showStartupError("ARCore availability could not be verified on this device.")
                return
            }
        }

        try {
            when (arCore.requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    state.value = SafetyUiState(statusText = "Installing Google Play Services for AR")
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }

            ensureRenderer()
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
                    renderer?.setSession(arSession)
                }
            }
            renderer?.setSession(session ?: error("ARCore session was not created"))
            session?.resume()
            glSurfaceView?.onResume()
        } catch (_: UnavailableArcoreNotInstalledException) {
            showStartupError("Google Play Services for AR is not installed.")
        } catch (_: UnavailableApkTooOldException) {
            showStartupError("Google Play Services for AR needs an update.")
        } catch (_: UnavailableSdkTooOldException) {
            showStartupError("This app needs a newer ARCore SDK implementation.")
        } catch (_: UnavailableDeviceNotCompatibleException) {
            showStartupError("This device does not support ARCore.")
        } catch (_: UnavailableUserDeclinedInstallationException) {
            showStartupError("Google Play Services for AR installation was declined.")
        } catch (error: Exception) {
            showStartupError(error.message ?: "Unable to start the AR session.")
        }
    }

    private fun ensureRenderer() {
        if (renderer != null && glSurfaceView != null) return
        runCatching {
            ArSafetyRenderer(
                applicationContext,
                onStateChanged = { state.value = it },
                onAlert = alertController::playFeedback,
                onFeedbackReset = alertController::cancelAllFeedback,
                onFrameForVlm = { frame -> vlmPipeline?.submitArCoreFrame(frame) },
            )
        }.onSuccess { newRenderer ->
            renderer = newRenderer
            glSurfaceView = GLSurfaceView(this).apply {
                setEGLContextClientVersion(2)
                setRenderer(newRenderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                preserveEGLContextOnPause = true
            }
        }.onFailure { error ->
            showStartupError("AR renderer initialization failed: ${error.message ?: "unknown error"}")
        }
    }

    private fun showStartupError(message: String) {
        state.value = SafetyUiState.error(message)
    }

    override fun onPause() {
        // Stop GL rendering first. ARCore session calls must not race an active onDrawFrame().
        glSurfaceView?.onPause()
        session?.pause()
        renderer?.detachSession()
        super.onPause()
    }

    override fun onDestroy() {
        // onPause() normally ran first, but keep destruction safe when the activity is finished
        // directly by the system or a test harness.
        glSurfaceView?.onPause()
        renderer?.detachSession()
        renderer?.close()
        vlmPipeline?.close()
        vlmPipeline = null
        alertController.close()
        session?.close()
        session = null
        super.onDestroy()
    }

    private companion object { const val TAG = "TrinetraMainActivity" }
}
