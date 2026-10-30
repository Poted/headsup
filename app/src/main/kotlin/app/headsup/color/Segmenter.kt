package app.headsup.color

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import app.headsup.core.color.MaskRefine
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Masks found by small models on the phone (ONNX Runtime): objects with MobileSAM (Segment
 * Anything: the picture is read once, then each tap, outline or box is answered in a moment) and
 * the sky with DeepLab (trained on ADE20K). What they find is rough (they see the picture small),
 * so it is laid onto the picture's own edges ([MaskRefine]).
 */
object Segmenter {
    /** The picture as MobileSAM reads it: its long side. */
    const val INPUT = 1024

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var skyModel: OrtSession? = null

    private fun session(context: Context, asset: String): OrtSession {
        val bytes = context.applicationContext.assets.open(asset).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        }
        return env.createSession(bytes, options)
    }

    @Synchronized private fun encoder(context: Context) = encoder ?: session(context, "sam_encoder.onnx").also { encoder = it }
    @Synchronized private fun decoder(context: Context) = decoder ?: session(context, "sam_decoder.onnx").also { decoder = it }
    @Synchronized private fun skyModel(context: Context) = skyModel ?: session(context, "sky.onnx").also { skyModel = it }

    /** [bitmap] scaled so its long side is [side] (never up). */
    fun scaled(bitmap: Bitmap, side: Int): Bitmap {
        val s = side.toFloat() / max(bitmap.width, bitmap.height)
        if (s >= 1f && bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        val w = (bitmap.width * min(1f, s)).roundToInt().coerceAtLeast(1)
        val h = (bitmap.height * min(1f, s)).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createScaledBitmap(bitmap, w, h, true)
        return if (out.config == Bitmap.Config.ARGB_8888) out else out.copy(Bitmap.Config.ARGB_8888, false)
    }

    /** RGB 0..1 per pixel, row by row. */
    fun rgb(bitmap: Bitmap): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = FloatArray(w * h * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            out[i * 3] = ((p shr 16) and 0xFF) / 255f
            out[i * 3 + 1] = ((p shr 8) and 0xFF) / 255f
            out[i * 3 + 2] = (p and 0xFF) / 255f
        }
        return out
    }

    /** A picture read by MobileSAM: what it saw ([embedding]) at [width]×[height] (long side [INPUT]). */
    class Reading(val embedding: FloatArray, val width: Int, val height: Int)

    /** Reads [picture] (any size; scaled to [INPUT] on its long side). Takes about half a second. */
    fun read(context: Context, picture: Bitmap): Reading {
        val bmp = scaled(picture, INPUT)
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        // Height × width × RGB, 0..255: the model scales and pads it itself.
        val input = FloatBuffer.allocate(w * h * 3)
        for (p in pixels) {
            input.put(((p shr 16) and 0xFF).toFloat())
            input.put(((p shr 8) and 0xFF).toFloat())
            input.put((p and 0xFF).toFloat())
        }
        input.rewind()
        val session = encoder(context)
        OnnxTensor.createTensor(env, input, longArrayOf(h.toLong(), w.toLong(), 3)).use { tensor ->
            session.run(mapOf("input_image" to tensor)).use { result ->
                val out = (result[0] as OnnxTensor).floatBuffer
                val embedding = FloatArray(out.remaining())
                out.get(embedding)
                return Reading(embedding, w, h)
            }
        }
    }

    /** A point that tells MobileSAM what is wanted ([x], [y] fractions of the picture): in, out, or a box corner. */
    data class Prompt(val x: Float, val y: Float, val label: Label) {
        enum class Label(val value: Float) { IN(1f), OUT(0f), BOX_START(2f), BOX_END(3f) }
    }

    /**
     * What [prompts] point at in [reading]: the candidates (how sure, 0..1 per pixel, [width]×[height]
     * with the picture's shape), each with the model's own score, best first. They differ in how
     * much they take: a part, the object, or the object with what it stands in.
     */
    fun objects(context: Context, reading: Reading, prompts: List<Prompt>, width: Int, height: Int): List<Pair<FloatArray, Float>> {
        if (prompts.isEmpty()) return emptyList()
        val n = prompts.size + 1
        val coords = FloatBuffer.allocate(n * 2)
        val labels = FloatBuffer.allocate(n)
        for (p in prompts) {
            coords.put(p.x * reading.width)
            coords.put(p.y * reading.height)
            labels.put(p.label.value)
        }
        // The padding point SAM's export expects when no box is given (and harmless with one).
        coords.put(0f); coords.put(0f); labels.put(-1f)
        coords.rewind(); labels.rewind()
        // The embedding comes out as 1 × 256 × 64 × 64.
        val embedding = FloatBuffer.wrap(reading.embedding)
        val session = decoder(context)
        val tensors = listOf(
            "image_embeddings" to OnnxTensor.createTensor(env, embedding, longArrayOf(1, 256, 64, 64)),
            "point_coords" to OnnxTensor.createTensor(env, coords, longArrayOf(1, n.toLong(), 2)),
            "point_labels" to OnnxTensor.createTensor(env, labels, longArrayOf(1, n.toLong())),
            "mask_input" to OnnxTensor.createTensor(env, FloatBuffer.allocate(256 * 256), longArrayOf(1, 1, 256, 256)),
            "has_mask_input" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0f)), longArrayOf(1)),
            "orig_im_size" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(height.toFloat(), width.toFloat())), longArrayOf(2)),
        )
        try {
            session.run(tensors.toMap()).use { result ->
                val masks = (result[0] as OnnxTensor).floatBuffer
                val scores = (result[1] as OnnxTensor).floatBuffer
                val count = scores.remaining()
                val size = width * height
                val found = (0 until count).map { k ->
                    val logits = FloatArray(size)
                    masks.position(k * size)
                    masks.get(logits)
                    // The model's logits as how sure it is.
                    FloatArray(size) { 1f / (1f + exp(-logits[it])) } to scores.get(k)
                }
                return found.filter { (m, _) -> m.any { it > 0.5f } }.sortedByDescending { it.second }
            }
        } finally {
            tensors.forEach { it.second.close() }
        }
    }

    /** How sure the sky model is that each pixel of [picture] is sky (0..1, [width]×[height], row by row). */
    fun sky(context: Context, picture: Bitmap, width: Int, height: Int): FloatArray {
        val size = 513
        val s = size.toFloat() / max(picture.width, picture.height)
        val rw = (picture.width * s).roundToInt().coerceIn(1, size)
        val rh = (picture.height * s).roundToInt().coerceIn(1, size)
        val small = Bitmap.createScaledBitmap(picture, rw, rh, true)
        val pixels = IntArray(rw * rh)
        small.getPixels(pixels, 0, rw, 0, 0, rw, rh)
        // 513 × 513, the picture in its top-left corner, -1..1; the rest is 0 (mid grey, as trained).
        val input = FloatBuffer.allocate(size * size * 3)
        for (y in 0 until rh) {
            for (x in 0 until rw) {
                val p = pixels[y * rw + x]
                val o = (y * size + x) * 3
                input.put(o, ((p shr 16) and 0xFF) / 127.5f - 1f)
                input.put(o + 1, ((p shr 8) and 0xFF) / 127.5f - 1f)
                input.put(o + 2, (p and 0xFF) / 127.5f - 1f)
            }
        }
        val session = skyModel(context)
        val grid = OnnxTensor.createTensor(env, input, longArrayOf(1, size.toLong(), size.toLong(), 3)).use { tensor ->
            session.run(mapOf("image" to tensor)).use { result ->
                val out = (result[0] as OnnxTensor).floatBuffer
                FloatArray(out.remaining()).also { out.get(it) }
            }
        }
        // A 129 × 129 grid over the 513 square: grid point k sits at pixel k × 4.
        val g = 129
        val out = FloatArray(width * height)
        for (y in 0 until height) {
            val gy = ((y + 0.5f) / height * rh - 0.5f) / 4f
            val y0 = gy.toInt().coerceIn(0, g - 1)
            val y1 = (y0 + 1).coerceAtMost(g - 1)
            val fy = (gy - y0).coerceIn(0f, 1f)
            for (x in 0 until width) {
                val gx = ((x + 0.5f) / width * rw - 0.5f) / 4f
                val x0 = gx.toInt().coerceIn(0, g - 1)
                val x1 = (x0 + 1).coerceAtMost(g - 1)
                val fx = (gx - x0).coerceIn(0f, 1f)
                val top = grid[y0 * g + x0] * (1 - fx) + grid[y0 * g + x1] * fx
                val bottom = grid[y1 * g + x0] * (1 - fx) + grid[y1 * g + x1] * fx
                out[y * width + x] = top * (1 - fy) + bottom * fy
            }
        }
        return out
    }

    /** [mask] laid onto the edges of [guide] (RGB 0..1 of the same size). */
    fun refine(guide: FloatArray, mask: FloatArray, width: Int, height: Int): FloatArray =
        MaskRefine.guided(guide, mask, width, height, radius = (max(width, height) / 80).coerceAtLeast(2), eps = 1e-4f)
}
