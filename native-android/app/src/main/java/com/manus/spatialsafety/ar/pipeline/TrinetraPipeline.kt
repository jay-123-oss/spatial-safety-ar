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

enum class YoloPrecision { AUTO, FP16, INT8 }

data class DetectorTuning(
    val confidenceThreshold: Float = 0.30f,
    val iouThreshold: Float = 0.50f,
) {
    init {
        require(confidenceThreshold in 0f..1f) { "confidenceThreshold must be between 0 and 1" }
        require(iouThreshold in 0f..1f) { "iouThreshold must be between 0 and 1" }
    }

    companion object {
        // Use only when recall is more important than false positives.
        val HIGH_RECALL = DetectorTuning(confidenceThreshold = 0.22f, iouThreshold = 0.45f)
        // Balanced starting point for the current safety detector.
        val BALANCED = DetectorTuning(confidenceThreshold = 0.30f, iouThreshold = 0.50f)
        // Use when duplicate/false detections are the dominant problem.
        val HIGH_PRECISION = DetectorTuning(confidenceThreshold = 0.40f, iouThreshold = 0.55f)
    }
}

class YoloTflite(
    context: Context,
    modelBytes: ByteBuffer,
    private val labels: List<String>,
    private val inputWidth: Int = 640,
    private val inputHeight: Int = 640,
    private val tuning: DetectorTuning = DetectorTuning.BALANCED,
    private val precision: YoloPrecision = YoloPrecision.AUTO,
) : Closeable {
    private val confidenceThreshold = tuning.confidenceThreshold
    private val iouThreshold = tuning.iouThreshold
    private val delegate: AutoCloseable?
    private val interpreter: Interpreter
    private val inputType: DataType
    private val inputScale: Float
    private val inputZeroPoint: Int
    private val outputType: DataType
    private val outputScale: Float
    private val outputZeroPoint: Int
    private val input: ByteBuffer
    private val output: ByteBuffer
    private val outputShape: IntArray

    init {
        val options = Interpreter.Options()
        delegate = try { NnApiDelegate() } catch (_: Throwable) {
            try { if (CompatibilityList().isDelegateSupportedOnThisDevice) GpuDelegate() else null }
            catch (_: Throwable) { null }
        }
        if (delegate != null) options.addDelegate(delegate as org.tensorflow.lite.Delegate)
        else options.setNumThreads(4).setUseXNNPACK(true)
        interpreter = Interpreter(modelBytes, options)
        val inputTensor = interpreter.getInputTensor(0)
        inputType = inputTensor.dataType()
        inputScale = inputTensor.quantizationParams().scale
        inputZeroPoint = inputTensor.quantizationParams().zeroPoint
        require(inputType == DataType.FLOAT32 || inputType == DataType.UINT8 || inputType == DataType.INT8) {
            "Unsupported YOLO input type: $inputType"
        }
        when (precision) {
            YoloPrecision.FP16 -> require(inputType == DataType.FLOAT32) {
                "FP16 TFLite models still expose FLOAT32 input; got $inputType"
            }
            YoloPrecision.INT8 -> require(inputType == DataType.INT8 || inputType == DataType.UINT8) {
                "INT8 mode requires INT8/UINT8 input; got $inputType"
            }
            YoloPrecision.AUTO -> Unit
        }
        val bytesPerChannel = if (inputType == DataType.FLOAT32) 4 else 1
        input = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * bytesPerChannel)
            .order(ByteOrder.nativeOrder())
        val outputTensor = interpreter.getOutputTensor(0)
        outputType = outputTensor.dataType()
        outputScale = outputTensor.quantizationParams().scale
        outputZeroPoint = outputTensor.quantizationParams().zeroPoint
        require(outputType == DataType.FLOAT32 || outputType == DataType.UINT8 || outputType == DataType.INT8) {
            "Unsupported YOLO output type: $outputType"
        }
        outputShape = outputTensor.shape()
        require(outputShape.size == 3 && outputShape[0] == 1) {
            "Expected YOLO output rank 3, got ${outputShape.contentToString()}"
        }
        output = ByteBuffer.allocateDirect(outputTensor.numBytes()).order(ByteOrder.nativeOrder())
    }

    fun detect(bitmap: Bitmap, sourceWidth: Int, sourceHeight: Int, timestampNs: Long): List<Detection> {
        val transform = letterbox(bitmap, inputWidth, inputHeight)
        try {
            input.rewind()
            val pixels = IntArray(inputWidth * inputHeight)
            transform.image.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
            for (p in pixels) putRgb(p)
            output.rewind()
            interpreter.run(input, output)
            val channelsFirst = outputShape[1] <= outputShape[2]
            val count = if (channelsFirst) outputShape[2] else outputShape[1]
            val channels = if (channelsFirst) outputShape[1] else outputShape[2]
            fun value(channel: Int, index: Int): Float {
                val flatIndex = if (channelsFirst) channel * count + index else index * channels + channel
                val byteIndex = flatIndex * if (outputType == DataType.FLOAT32) 4 else 1
                return when (outputType) {
                    DataType.FLOAT32 -> output.getFloat(byteIndex)
                    DataType.UINT8 -> ((output.get(byteIndex).toInt() and 0xff) - outputZeroPoint) * outputScale
                    DataType.INT8 -> (output.get(byteIndex).toInt() - outputZeroPoint) * outputScale
                    else -> error("Unsupported output type")
                }
            }
            val candidates = ArrayList<Detection>()
            for (i in 0 until count) {
                if (channels < 5) continue
                var bestClass = -1; var bestScore = 0f
                for (c in 4 until channels) {
                    val score = value(c, i)
                    if (score > bestScore) { bestScore = score; bestClass = c - 4 }
                }
                if (bestClass < 0 || bestScore < confidenceThreshold) continue
                var cx = value(0, i); var cy = value(1, i); var bw = value(2, i); var bh = value(3, i)
                // YOLO exports differ: some emit pixels in the input tensor space, others emit 0..1.
                // Normalize only when the four box values clearly use the normalized convention.
                if (maxOf(abs(cx), abs(cy), abs(bw), abs(bh)) <= 2f) {
                    cx *= inputWidth; cy *= inputHeight; bw *= inputWidth; bh *= inputHeight
                }
                val box = RectF(
                    ((cx - bw / 2f) - transform.padX) / transform.scale,
                    ((cy - bh / 2f) - transform.padY) / transform.scale,
                    ((cx + bw / 2f) - transform.padX) / transform.scale,
                    ((cy + bh / 2f) - transform.padY) / transform.scale,
                )
                box.intersect(0f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat())
                if (box.width() >= 2f && box.height() >= 2f) {
                    candidates += Detection(labels.getOrElse(bestClass) { "unknown" }, bestScore, box, timestampNs)
                }
            }
            return nms(candidates)
        } finally {
            transform.image.recycle()
        }
    }

    private fun putRgb(pixel: Int) {
        val channels = intArrayOf(pixel shr 16 and 0xff, pixel shr 8 and 0xff, pixel and 0xff)
        for (channel in channels) when (inputType) {
            DataType.FLOAT32 -> input.putFloat(channel / 255f)
            DataType.UINT8 -> input.put(channel.toByte())
            DataType.INT8 -> {
                require(inputScale > 0f) { "INT8 input tensor has no quantization scale" }
                input.put((channel / 255f / inputScale + inputZeroPoint).toInt().coerceIn(-128, 127).toByte())
            }
            else -> error("Unsupported input type")
        }
    }

    private fun nms(input: List<Detection>): List<Detection> {
        val kept = ArrayList<Detection>()
        input.groupBy { it.label }.values.forEach { group ->
            val remaining = group.sortedByDescending { it.confidence }.toMutableList()
            while (remaining.isNotEmpty()) {
                val best = remaining.removeAt(0); kept += best
                remaining.removeAll { iou(best.box, it.box) >= iouThreshold }
            }
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left); val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right); val bottom = minOf(a.bottom, b.bottom)
        val intersection = maxOf(0f, right - left) * maxOf(0f, bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union > 0f) intersection / union else 0f
    }

    private data class Letterbox(val image: Bitmap, val scale: Float, val padX: Float, val padY: Float)
    private fun letterbox(source: Bitmap, width: Int, height: Int): Letterbox {
        val scale = minOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val resizedWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val resizedHeight = (source.height * scale).toInt().coerceAtLeast(1)
        val resized = Bitmap.createScaledBitmap(source, resizedWidth, resizedHeight, true)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        canvas.drawColor(android.graphics.Color.rgb(114, 114, 114))
        val left = (width - resizedWidth) / 2f; val top = (height - resizedHeight) / 2f
        canvas.drawBitmap(resized, left, top, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        resized.recycle()
        return Letterbox(output, scale, left, top)
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
