package com.manus.spatialsafety.ar.ar

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.manus.spatialsafety.ar.safety.ARCoreObstacleEngine
import com.manus.spatialsafety.ar.safety.AlertDecision
import com.manus.spatialsafety.ar.safety.ObstacleReading
import com.manus.spatialsafety.ar.safety.ThreatZone
import com.manus.spatialsafety.ar.ui.SafetyUiState
import com.manus.spatialsafety.ar.util.PerformanceMonitor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** OpenGL camera preview plus ARCore-only central-depth obstacle analysis. */
class ArSafetyRenderer(
    context: Context,
    private val onStateChanged: (SafetyUiState) -> Unit,
    private val onAlert: (AlertDecision) -> Unit,
) : GLSurfaceView.Renderer, AutoCloseable {
    private val obstacleEngine = ARCoreObstacleEngine()
    private val performance = PerformanceMonitor(context)
    private val backgroundRenderer = CameraBackgroundRenderer()
    private var session: Session? = null
    @Volatile private var scanningEnabled = true
    private var lastUiUpdateMs = 0L

    fun setSession(arSession: Session) {
        session = arSession
    }

    fun setScanningEnabled(enabled: Boolean) {
        scanningEnabled = enabled
        obstacleEngine.reset()
        if (!enabled) onStateChanged(SafetyUiState.paused())
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.027f, 0.063f, 0.098f, 1f)
        backgroundRenderer.createOnGlThread()
        session?.setCameraTextureName(backgroundRenderer.textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val activeSession = session ?: return
        val performanceStats = performance.onFrame()
        try {
            val frame = activeSession.update()
            backgroundRenderer.draw(frame)
            val tracking = frame.camera.trackingState == TrackingState.TRACKING
            if (!tracking) {
                emitUiState(ObstacleReading(), tracking = false, performanceStats = performanceStats)
                return
            }
            if (!scanningEnabled) return

            val reading = obstacleEngine.analyze(frame)
            obstacleEngine.nextAlert(reading)?.let(onAlert)
            emitUiState(reading, tracking = true, performanceStats = performanceStats)
        } catch (_: CameraNotAvailableException) {
            onStateChanged(SafetyUiState.error("Camera unavailable. Reopen the safety view."))
        } catch (error: Exception) {
            onStateChanged(SafetyUiState.error(error.message ?: "ARCore frame processing failed."))
        }
    }

    private fun emitUiState(
        reading: ObstacleReading,
        tracking: Boolean,
        performanceStats: com.manus.spatialsafety.ar.util.PerformanceStats,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUiUpdateMs < 100L) return
        lastUiUpdateMs = now
        onStateChanged(
            SafetyUiState(
                tracking = tracking,
                paused = !scanningEnabled,
                statusText = if (tracking) "Depth scanning" else "Finding tracking",
                highestZone = reading.zone.takeUnless { it == ThreatZone.UNKNOWN } ?: ThreatZone.UNKNOWN,
                reading = reading,
                performance = performanceStats,
            ),
        )
    }

    override fun close() = Unit
}

/** Minimal OpenGL ES 2.0 renderer for ARCore's camera external texture. */
private class CameraBackgroundRenderer {
    var textureId: Int = 0
        private set
    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var transformHandle = 0
    private val positionBuffer = floatBufferOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val baseTexCoords = floatBufferOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
    private val transformedTexBuffer: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val transform = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    fun createOnGlThread() {
        val texture = IntArray(1)
        GLES20.glGenTextures(1, texture, 0)
        textureId = texture[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordHandle = GLES20.glGetAttribLocation(program, "a_TexCoord")
        transformHandle = GLES20.glGetUniformLocation(program, "u_TexTransform")
    }

    fun draw(frame: Frame) {
        baseTexCoords.position(0)
        transformedTexBuffer.position(0)
        frame.transformDisplayUvCoords(baseTexCoords, transformedTexBuffer)
        transformedTexBuffer.position(0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniformMatrix4fv(transformHandle, 1, false, transform, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, transformedTexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES20.glCreateProgram().also { output ->
            GLES20.glAttachShader(output, vertex)
            GLES20.glAttachShader(output, fragment)
            GLES20.glLinkProgram(output)
        }
    }

    private fun compileShader(type: Int, source: String): Int = GLES20.glCreateShader(type).also { shader ->
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
    }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            uniform mat4 u_TexTransform;
            varying vec2 v_TexCoord;
            void main() { gl_Position = a_Position; v_TexCoord = (u_TexTransform * vec4(a_TexCoord, 0.0, 1.0)).xy; }
        """
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES sTexture;
            void main() { gl_FragColor = texture2D(sTexture, v_TexCoord); }
        """

        fun floatBufferOf(vararg values: Float): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
    }
}
