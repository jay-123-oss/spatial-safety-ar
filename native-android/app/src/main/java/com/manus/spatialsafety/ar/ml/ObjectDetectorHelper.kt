package com.manus.spatialsafety.ar.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.media.Image
import android.os.Build
import com.manus.spatialsafety.ar.safety.Detection
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.BufferedReader
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Serial, on-device SSD-style detector. It accepts ARCore YUV_420_888 camera images, performs
 * conversion before the image is closed, and returns detection boxes in source-image pixels.
 * The bundled model uses the common SSD four-output tensor contract; YOLO exports need a decoder.
 */
class ObjectDetectorHelper(
    context: Context,
    modelAsset: String = "ssd_mobilenet_v1.tflite",
    labelsAsset: String = "labels.txt",
    private val minimumConfidence: Float = 0.55f,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val inferenceRunning = AtomicBoolean(false)
    private val labels = context.assets.open(labelsAsset).bufferedReader().use(BufferedReader::readLines)
    private val nnApiDelegate: NnApiDelegate?
    private val interpreter: Interpreter
    private val inputWidth: Int
    private val inputHeight: Int
    private val inputType: DataType

    init {
        val options = Interpreter.Options().setNumThreads(4)
        nnApiDelegate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            runCatching { NnApiDelegate() }.getOrNull()
        } else {
            null
        }
        nnApiDelegate?.let(options::addDelegate)
        interpreter = Interpreter(loadModelFile(context, modelAsset), options)
        val shape = interpreter.getInputTensor(0).shape()
        require(shape.size == 4 && shape[0] == 1 && shape[3] == 3) {
            "Expected an RGB input tensor shaped [1,height,width,3]."
        }
        inputHeight = shape[1]
        inputWidth = shape[2]
        inputType = interpreter.getInputTensor(0).dataType()
    }

    /** Returns false when a newer frame is intentionally dropped to keep camera latency bounded. */
    fun submit(image: Image, onResult: (List<Detection>) -> Unit): Boolean {
        if (!inferenceRunning.compareAndSet(false, true)) return false
        val sourceBitmap = try {
            yuv420ToBitmap(image)
        } catch (exception: Exception) {
            inferenceRunning.set(false)
            throw exception
        }

        executor.execute {
            try {
                onResult(detect(sourceBitmap))
            } finally {
                sourceBitmap.recycle()
                inferenceRunning.set(false)
            }
        }
        return true
    }

    private fun detect(source: Bitmap): List<Detection> {
        val resized = Bitmap.createScaledBitmap(source, inputWidth, inputHeight, true)
        val input = bitmapToInputBuffer(resized)
        if (resized !== source) resized.recycle()

        val locationOutput = Array(1) { Array(MAX_DETECTIONS) { FloatArray(4) } }
        val classOutput = Array(1) { FloatArray(MAX_DETECTIONS) }
        val scoreOutput = Array(1) { FloatArray(MAX_DETECTIONS) }
        val countOutput = FloatArray(1)
        val outputs = hashMapOf<Int, Any>(
            0 to locationOutput,
            1 to classOutput,
            2 to scoreOutput,
            3 to countOutput,
        )
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)

        val count = min(MAX_DETECTIONS, countOutput[0].toInt())
        return buildList {
            for (index in 0 until count) {
                val score = scoreOutput[0][index]
                if (score < minimumConfidence) continue
                val rawBox = locationOutput[0][index]
                val top = (rawBox[0] * source.height).coerceIn(0f, source.height.toFloat())
                val left = (rawBox[1] * source.width).coerceIn(0f, source.width.toFloat())
                val bottom = (rawBox[2] * source.height).coerceIn(0f, source.height.toFloat())
                val right = (rawBox[3] * source.width).coerceIn(0f, source.width.toFloat())
                if (right <= left || bottom <= top) continue
                val classIndex = classOutput[0][index].toInt()
                add(
                    Detection(
                        label = labels.getOrElse(classIndex) { "Object $classIndex" },
                        confidence = score,
                        box = RectF(left, top, right, bottom),
                    ),
                )
            }
        }
    }

    private fun bitmapToInputBuffer(bitmap: Bitmap): ByteBuffer {
        val bytesPerChannel = if (inputType == DataType.FLOAT32) 4 else 1
        val buffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * bytesPerChannel)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        for (pixel in pixels) {
            val red = pixel shr 16 and 0xFF
            val green = pixel shr 8 and 0xFF
            val blue = pixel and 0xFF
            if (inputType == DataType.FLOAT32) {
                buffer.putFloat(red / 255f)
                buffer.putFloat(green / 255f)
                buffer.putFloat(blue / 255f)
            } else {
                buffer.put(red.toByte())
                buffer.put(green.toByte())
                buffer.put(blue.toByte())
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun yuv420ToBitmap(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        val output = IntArray(width * height)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()

        for (y in 0 until height) {
            val yRow = y * yPlane.rowStride
            val uvRow = (y shr 1) * uPlane.rowStride
            for (x in 0 until width) {
                val luminance = yBuffer.get(yRow + x * yPlane.pixelStride).toInt() and 0xFF
                val uvOffset = uvRow + (x shr 1) * uPlane.pixelStride
                val u = (uBuffer.get(uvOffset).toInt() and 0xFF) - 128
                val v = (vBuffer.get(uvOffset).toInt() and 0xFF) - 128
                val r = (luminance + 1.370705f * v).toInt().coerceIn(0, 255)
                val g = (luminance - 0.337633f * u - 0.698001f * v).toInt().coerceIn(0, 255)
                val b = (luminance + 1.732446f * u).toInt().coerceIn(0, 255)
                output[y * width + x] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun loadModelFile(context: Context, assetName: String): MappedByteBuffer {
        context.assets.openFd(assetName).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                return channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
            }
        }
    }

    override fun close() {
        executor.shutdownNow()
        interpreter.close()
        nnApiDelegate?.close()
    }

    private companion object {
        const val MAX_DETECTIONS = 10
    }
}
