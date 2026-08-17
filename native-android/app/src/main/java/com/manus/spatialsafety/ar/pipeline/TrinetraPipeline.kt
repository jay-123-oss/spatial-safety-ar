package com.manus.spatialsafety.ar.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.ar.core.Frame
import com.google.ar.core.Session
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

// ---------- Shared data ----------

data class Detection(val label: String, val confidence: Float, val box: RectF, val timestampNs: Long)
data class Track(val id: Int, val label: String, val confidence: Float, val box: RectF, val timestampNs: Long)
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun div(s: Float) = Vec3(x / s, y / s, z / s)
    fun norm() = sqrt(x * x + y * y + z * z)
}
data class FusedObject(
    val id: Int, val classLabel: String, val boundingBox: RectF,
    val position: Vec3, val realDistanceMeters: Float,
    val confidence: Float, val timestampNs: Long
)

// ---------- Camera and dual dispatch ----------

class TrinetraCameraController(
    private val context: Context,
    private val analyzer: ImageAnalysis.Analyzer
) : Closeable {
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null

    fun bind(owner: androidx.lifecycle.LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            provider = future.get()
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { it.setAnalyzer(analysisExecutor, analyzer) }
            provider?.unbindAll()
            provider?.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(context))
    }

    override fun close() {
        provider?.unbindAll()
        analysisExecutor.shutdownNow()
    }
}

class TrinetraImageAnalyzer(
    private val arCore: ArCoreFrameSource,
    private val yolo: YoloTflite,
    private val tracker: ByteTrackAdapter,
    private val fusion: SpatialFusion,
    private val visionDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val onObjects: (List<FusedObject>) -> Unit,
    private val yoloPeriodMs: Long = 100L // hard cap: 10 FPS
) : ImageAnalysis.Analyzer {
    private val yoloMutex = Mutex()
    @Volatile private var lastYoloMs = 0L
    @Volatile private var yoloJob: Job? = null

    override fun analyze(image: ImageProxy) {
        try {
            val nowMs = SystemClock.elapsedRealtime()
            val frame = arCore.updateAndAcquire(image) ?: return

            // ARCore receives every analyzer frame. YOLO is independently throttled and latest-only.
            if (nowMs - lastYoloMs >= yoloPeriodMs && yoloMutex.tryLock()) {
                lastYoloMs = nowMs
                val bitmap = image.toBitmapForModel() // copy is released with image.close()
                yoloJob = scope.launch(visionDispatcher) {
                    try {
                        val detections = yolo.detect(bitmap, frame.cameraImageWidth, frame.cameraImageHeight, frame.timestampNs)
                        val tracks = tracker.update(detections, frame.timestampNs)
                        onObjects(fusion.fuse(tracks, frame))
                    } finally {
                        bitmap.recycle()
                        yoloMutex.unlock()
                    }
                }
            }
            // Never block CameraX waiting for inference; current tracks are emitted by the YOLO job.
        } catch (_: Throwable) {
            // Production code should report telemetry, but must not prevent close() below.
        } finally {
            image.close()
        }
    }
}

interface ArCoreFrameSource {
    fun updateAndAcquire(image: ImageProxy): ArFrame?
}
data class ArFrame(
    val timestampNs: Long,
    val cameraImageWidth: Int,
    val cameraImageHeight: Int,
    val depth: DepthSampler,
    val intrinsics: CameraIntrinsics
)
interface DepthSampler { fun sampleMeters(xPx: Float, yPx: Float): Float? }
data class CameraIntrinsics(val fx: Float, val fy: Float, val cx: Float, val cy: Float)

// ---------- TFLite wrapper ----------

class YoloTflite(
    context: Context,
    modelBytes: ByteBuffer,
    private val labels: List<String>,
    private val inputWidth: Int = 640,
    private val inputHeight: Int = 640
) : Closeable {
    private val delegate: AutoCloseable?
    private val interpreter: Interpreter
    private val input: ByteBuffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3)
        .order(ByteOrder.nativeOrder())

    init {
        val options = Interpreter.Options()
        delegate = try { NnApiDelegate() } catch (_: Throwable) {
            try {
                if (CompatibilityList().isDelegateSupportedOnThisDevice) GpuDelegate() else null
            } catch (_: Throwable) { null }
        }
        if (delegate != null) options.addDelegate(delegate as org.tensorflow.lite.Delegate)
        else options.setNumThreads(4).setUseXNNPACK(true)
        interpreter = Interpreter(modelBytes, options)
    }

    fun detect(bitmap: Bitmap, sourceWidth: Int, sourceHeight: Int, timestampNs: Long): List<Detection> {
        val scaled = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        input.rewind()
        val pixels = IntArray(inputWidth * inputHeight)
        scaled.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        for (p in pixels) {
            input.put((p shr 16 and 0xff).toByte())
            input.put((p shr 8 and 0xff).toByte())
            input.put((p and 0xff).toByte())
        }
        if (scaled !== bitmap) scaled.recycle()

        // Model-specific output decoding must match the exported YOLOv8 TFLite signature.
        // The tensor buffers are allocated once in a real adapter; this example uses output arrays.
        val output = Array(1) { Array(84) { FloatArray(8400) } }
        interpreter.run(input, output)
        return decodeYoloOutput(output[0], sourceWidth, sourceHeight, timestampNs)
    }

    private fun decodeYoloOutput(t: Array<FloatArray>, w: Int, h: Int, ts: Long): List<Detection> {
        val result = ArrayList<Detection>()
        for (i in t[0].indices) {
            var best = 0; var score = 0f
            for (c in 4 until t.size) if (t[c][i] > score) { score = t[c][i]; best = c - 4 }
            if (score < 0.35f) continue
            val cx = t[0][i] * w / inputWidth; val cy = t[1][i] * h / inputHeight
            val bw = t[2][i] * w / inputWidth; val bh = t[3][i] * h / inputHeight
            result += Detection(labels.getOrElse(best) { "unknown" }, score,
                RectF(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2), ts)
        }
        return result
    }
    override fun close() { interpreter.close(); delegate?.close() }
}

// Replace this adapter with the chosen ByteTrack implementation. It owns tracker state and
// must be called only from the serialized YOLO coroutine above.
interface ByteTrackAdapter { fun update(detections: List<Detection>, timestampNs: Long): List<Track> }

// ---------- Spatial fusion ----------

class SpatialFusion(private val medianRadiusPx: Int = 2) {
    fun fuse(tracks: List<Track>, frame: ArFrame): List<FusedObject> = tracks.mapNotNull { track ->
        val x = track.box.centerX(); val y = track.box.centerY()
        val depth = robustDepth(frame.depth, x, y) ?: return@mapNotNull null
        if (depth <= 0f || depth.isNaN() || depth > 30f) return@mapNotNull null
        val k = frame.intrinsics
        val position = Vec3((x - k.cx) * depth / k.fx, (y - k.cy) * depth / k.fy, depth)
        FusedObject(track.id, track.label, track.box, position, position.norm(), track.confidence, track.timestampNs)
    }
    private fun robustDepth(d: DepthSampler, x: Float, y: Float): Float? {
        val samples = ArrayList<Float>()
        for (dy in -medianRadiusPx..medianRadiusPx) for (dx in -medianRadiusPx..medianRadiusPx)
            d.sampleMeters(x + dx, y + dy)?.takeIf { it > 0f && it.isFinite() }?.let(samples::add)
        return samples.sorted().getOrNull(samples.size / 2)
    }
}

// ---------- World model and collision prediction ----------

data class WorldObject(val object: FusedObject, val velocity: Vec3, val ageMs: Long)
enum class RiskState { SAFE, CAUTION, DANGER }
data class Risk(val state: RiskState, val ttcSeconds: Float?, val distanceMeters: Float)

class WorldModel(private val staleAfterMs: Long = 750L) {
    private data class Previous(val position: Vec3, val tsNs: Long)
    private val previous = HashMap<Int, Previous>()
    fun update(objects: List<FusedObject>): List<WorldObject> {
        val now = objects.maxOfOrNull { it.timestampNs } ?: return emptyList()
        return objects.map { o ->
            val p = previous[o.id]
            val dt = p?.let { (o.timestampNs - it.tsNs) / 1_000_000_000f } ?: 0f
            val v = if (p != null && dt > 0.01f) (o.position - p.position) / dt else Vec3(0f, 0f, 0f)
            previous[o.id] = Previous(o.position, o.timestampNs)
            WorldObject(o, v, (now - o.timestampNs) / 1_000_000L)
        }.filter { it.ageMs <= staleAfterMs }
    }
}

class CollisionPredictor(
    private val criticalDistance: Float = 1.5f,
    private val warningDistance: Float = 4f,
    private val criticalTtc: Float = 2f,
    private val warningTtc: Float = 4f
) {
    fun evaluate(o: WorldObject): Risk {
        val d = o.object.realDistanceMeters
        val closingSpeed = -o.velocity.z // camera-forward convention: +Z is forward
        val ttc = if (closingSpeed > 0.05f) d / closingSpeed else null
        val state = when {
            d < criticalDistance || (ttc != null && ttc < criticalTtc) -> RiskState.DANGER
            d < warningDistance || (ttc != null && ttc < warningTtc) -> RiskState.CAUTION
            else -> RiskState.SAFE
        }
        return Risk(state, ttc, d)
    }
}

// ---------- Safety state and non-blocking alerts ----------

class SafetyEngine(
    private val world: WorldModel,
    private val predictor: CollisionPredictor,
    dispatcher: CoroutineDispatcher
) {
    private val scope = CoroutineScope(dispatcher)
    private val _state = MutableStateFlow(RiskState.SAFE)
    val state: StateFlow<RiskState> = _state.asStateFlow()
    fun submit(objects: List<FusedObject>) = scope.launch {
        val risks = world.update(objects).map(predictor::evaluate)
        _state.value = risks.maxByOrNull { it.state.ordinal }?.state ?: RiskState.SAFE
    }
}

class SmartAlertManager(
    context: Context,
    private val dispatcher: CoroutineDispatcher
) : Closeable {
    private val tts = android.speech.tts.TextToSpeech(context) {}
    private val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31)
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
    else context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
    private val scope = CoroutineScope(dispatcher)
    private var previous = RiskState.SAFE
    private var lastDanger = Long.MIN_VALUE
    private var lastCaution = Long.MIN_VALUE

    fun collect(states: StateFlow<RiskState>): Job = scope.launch {
        states.collect { next -> handle(next, SystemClock.elapsedRealtime()) }
    }
    private fun handle(next: RiskState, now: Long) {
        if (next == previous && next == RiskState.SAFE) return
        if (next == RiskState.DANGER && previous == RiskState.CAUTION) tts.stop()
        when (next) {
            RiskState.DANGER -> {
                vibrate(longArrayOf(0, 700), true)
                if (now - lastDanger >= 3000L) { speak("Ruko"); lastDanger = now }
            }
            RiskState.CAUTION -> {
                vibrate(longArrayOf(0, 120, 180, 120), true)
                if (now - lastCaution >= 5000L) { speak("Sabdhan"); lastCaution = now }
            }
            RiskState.SAFE -> {
                vibrator.cancel()
                if (previous == RiskState.DANGER || previous == RiskState.CAUTION) speak("Chlo")
            }
        }
        previous = next
    }
    private fun speak(text: String) { tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, text) }
    private fun vibrate(pattern: LongArray, repeat: Int) {
        vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, repeat))
    }
    override fun close() { vibrator.cancel(); tts.stop(); tts.shutdown() }
}

private fun ImageProxy.toBitmapForModel(): Bitmap = YuvRgbConverter.convert(this)

/** Converts YUV_420_888 while respecting row/pixel strides. The returned bitmap is owned by the
 * caller and must be recycled after inference. For higher throughput, move the reusable output
 * IntArray and Bitmap into a per-analyzer buffer pool. */
object YuvRgbConverter {
    fun convert(image: ImageProxy): Bitmap {
        require(image.format == android.graphics.ImageFormat.YUV_420_888)
        val width = image.width; val height = image.height
        val yPlane = image.planes[0]; val uPlane = image.planes[1]; val vPlane = image.planes[2]
        val y = yPlane.buffer.duplicate(); val u = uPlane.buffer.duplicate(); val v = vPlane.buffer.duplicate()
        val out = IntArray(width * height)
        val yRow = yPlane.rowStride; val uRow = uPlane.rowStride; val vRow = vPlane.rowStride
        val yPixel = yPlane.pixelStride; val uPixel = uPlane.pixelStride; val vPixel = vPlane.pixelStride
        for (row in 0 until height) for (col in 0 until width) {
            val yi = row * yRow + col * yPixel
            val ci = (row / 2) * (uRow) + (col / 2) * uPixel
            val cj = (row / 2) * (vRow) + (col / 2) * vPixel
            val yy = (y.get(yi).toInt() and 0xff) - 16
            val uu = (u.get(ci).toInt() and 0xff) - 128
            val vv = (v.get(cj).toInt() and 0xff) - 128
            val r = (1.164f * yy + 1.596f * vv).toInt().coerceIn(0, 255)
            val g = (1.164f * yy - 0.392f * uu - 0.813f * vv).toInt().coerceIn(0, 255)
            val b = (1.164f * yy + 2.017f * uu).toInt().coerceIn(0, 255)
            out[row * width + col] = android.graphics.Color.rgb(r, g, b)
        }
        return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
    }
}
