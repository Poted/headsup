package app.headsup.retouch

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.nio.FloatBuffer

/**
 * Fills holes in a picture with what belongs there: LaMa (large-mask inpainting, Apache 2.0),
 * run on the phone. It sees a square of [SIZE] pixels and fills from what is around the hole.
 */
object Inpainter {
    const val SIZE = 512

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null

    /**
     * The model is opened from a file (not read into memory first: it is 200 MB), copied once
     * out of the app's package.
     */
    @Synchronized
    private fun session(context: Context): OrtSession = session ?: run {
        val app = context.applicationContext
        val file = File(File(app.filesDir, "models"), "lama.onnx")
        val size = app.assets.openFd("lama.onnx").use { it.length }
        if (!file.exists() || file.length() != size) {
            file.parentFile?.mkdirs()
            val part = File(file.parentFile, "lama.onnx.part")
            app.assets.open("lama.onnx").use { input -> part.outputStream().use { input.copyTo(it, 1 shl 16) } }
            file.delete()
            check(part.renameTo(file)) { "Could not keep the inpainting model" }
        }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        }
        env.createSession(file.absolutePath, options).also { session = it }
    }

    /**
     * [rgb] ([SIZE]×[SIZE], RGB 0..1 per pixel, row by row) with the pixels where [hole] is true
     * filled; the rest comes back as it was. Takes a few seconds.
     */
    fun fill(context: Context, rgb: FloatArray, hole: BooleanArray): FloatArray {
        val n = SIZE * SIZE
        require(rgb.size == n * 3 && hole.size == n)
        // The model takes planes: all red, then all green, then all blue.
        val image = FloatBuffer.allocate(n * 3)
        for (c in 0 until 3) for (i in 0 until n) image.put(c * n + i, rgb[i * 3 + c])
        val mask = FloatBuffer.allocate(n)
        for (i in 0 until n) mask.put(i, if (hole[i]) 1f else 0f)
        val s = session(context)
        OnnxTensor.createTensor(env, image, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { imageTensor ->
            OnnxTensor.createTensor(env, mask, longArrayOf(1, 1, SIZE.toLong(), SIZE.toLong())).use { maskTensor ->
                s.run(mapOf("image" to imageTensor, "mask" to maskTensor)).use { result ->
                    // 0..255 out, in planes.
                    val out = (result[0] as OnnxTensor).floatBuffer
                    val planes = FloatArray(out.remaining()).also { out.get(it) }
                    return FloatArray(n * 3) { k -> (planes[(k % 3) * n + k / 3] / 255f).coerceIn(0f, 1f) }
                }
            }
        }
    }
}
