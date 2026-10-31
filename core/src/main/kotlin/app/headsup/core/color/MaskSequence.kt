package app.headsup.core.color

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A mask found again on frames of a clip: one coverage map ([width]×[height], 0..255 per pixel,
 * row by row) at each of [timesMs] (file time, rising). Between two maps it fades from one to the
 * next; before the first and after the last it holds.
 */
class MaskSequence(val width: Int, val height: Int, val timesMs: LongArray, val frames: List<ByteArray>) {
    init {
        // Only the count: the maps may be kept packed, and are not opened to be checked.
        require(timesMs.size == frames.size)
    }

    val isEmpty: Boolean get() = timesMs.isEmpty()

    /** Coverage 0..1 at [fileMs], [outWidth]×[outHeight] (sampled with linear filtering). */
    fun at(fileMs: Long, outWidth: Int = width, outHeight: Int = height): FloatArray {
        val out = FloatArray(outWidth * outHeight)
        if (isEmpty) return out
        val i = timesMs.indexOfLast { it <= fileMs }
        val (a, b, t) = when {
            i < 0 -> Triple(0, 0, 0f)
            i >= timesMs.size - 1 -> Triple(timesMs.size - 1, timesMs.size - 1, 0f)
            else -> Triple(i, i + 1, (fileMs - timesMs[i]).toFloat() / (timesMs[i + 1] - timesMs[i]).coerceAtLeast(1))
        }
        val fa = frames[a]
        val fb = frames[b]
        for (y in 0 until outHeight) {
            val sy = ((y + 0.5f) * height / outHeight - 0.5f).coerceIn(0f, height - 1f)
            val y0 = sy.toInt()
            val y1 = min(y0 + 1, height - 1)
            val wy = sy - y0
            for (x in 0 until outWidth) {
                val sx = ((x + 0.5f) * width / outWidth - 0.5f).coerceIn(0f, width - 1f)
                val x0 = sx.toInt()
                val x1 = min(x0 + 1, width - 1)
                val wx = sx - x0
                fun v(f: ByteArray): Float {
                    val top = (f[y0 * width + x0].toInt() and 0xFF) * (1 - wx) + (f[y0 * width + x1].toInt() and 0xFF) * wx
                    val bottom = (f[y1 * width + x0].toInt() and 0xFF) * (1 - wx) + (f[y1 * width + x1].toInt() and 0xFF) * wx
                    return (top * (1 - wy) + bottom * wy) / 255f
                }
                out[y * outWidth + x] = if (a == b || t <= 0f) v(fa) else v(fa) * (1 - t) + v(fb) * t
            }
        }
        return out
    }

    /**
     * This sequence with [newer]'s maps in place of its own between [newer]'s first and last time
     * (a part tracked again); the rest kept. Both must be the same size. No map is opened: the
     * result reads them from the two sequences as it is read.
     */
    fun merged(newer: MaskSequence): MaskSequence {
        if (newer.isEmpty) return this
        if (isEmpty || newer.width != width || newer.height != height) return newer
        val from = newer.timesMs.first()
        val to = newer.timesMs.last()
        val kept = timesMs.indices.filter { timesMs[it] < from || timesMs[it] > to }
        // (time, which sequence, index in it), in time order.
        val all = (kept.map { Triple(timesMs[it], 0, it) } + newer.timesMs.indices.map { Triple(newer.timesMs[it], 1, it) }).sortedBy { it.first }
        val frames = object : AbstractList<ByteArray>() {
            override val size: Int get() = all.size
            override fun get(index: Int): ByteArray = all[index].let { (_, which, i) -> if (which == 0) this@MaskSequence.frames[i] else newer.frames[i] }
        }
        return MaskSequence(width, height, LongArray(all.size) { all[it].first }, frames)
    }

    companion object {
        /** [coverage] (0..1) as a map. */
        fun bytes(coverage: FloatArray): ByteArray = ByteArray(coverage.size) { (coverage[it] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte() }
    }
}

/**
 * Maps kept compressed (a mask's map is mostly all in or all out: a few kB instead of a few
 * hundred), each opened when it is read; the last few opened are kept open. A long clip tracked
 * twelve times a second holds thousands of maps: open, they would not fit in memory.
 */
class PackedFrames(private val packed: List<ByteArray>, private val frameSize: Int) : AbstractList<ByteArray>() {
    private val recent = LinkedHashMap<Int, ByteArray>(8, 0.75f, true)

    override val size: Int get() = packed.size

    /** Map [index] as kept (compressed). */
    fun packedAt(index: Int): ByteArray = packed[index]

    override fun get(index: Int): ByteArray {
        synchronized(recent) { recent[index]?.let { return it } }
        val open = unpack(packed[index], frameSize)
        synchronized(recent) {
            recent[index] = open
            while (recent.size > 4) recent.remove(recent.keys.first())
        }
        return open
    }

    companion object {
        fun pack(map: ByteArray): ByteArray {
            val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED)
            try {
                deflater.setInput(map)
                deflater.finish()
                val out = java.io.ByteArrayOutputStream(map.size / 16 + 64)
                val buffer = ByteArray(16 * 1024)
                while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
                return out.toByteArray()
            } finally {
                deflater.end()
            }
        }

        fun unpack(packed: ByteArray, size: Int): ByteArray {
            val inflater = java.util.zip.Inflater()
            try {
                inflater.setInput(packed)
                val out = ByteArray(size)
                var n = 0
                while (n < size && !inflater.finished()) {
                    val got = inflater.inflate(out, n, size - n)
                    if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    n += got
                }
                return out
            } finally {
                inflater.end()
            }
        }
    }
}

/** What carrying a mask from one frame to the next needs to know about it. */
object MaskFollow {
    /** Pixels counted as in the mask. */
    private const val IN = 0.5f

    /** How much of the frame the mask covers, 0..1. */
    fun area(map: FloatArray): Float = if (map.isEmpty()) 0f else map.count { it > IN }.toFloat() / map.size

    /** Overlap of two masks (intersection over union), 0..1; 0 when both are empty. */
    fun iou(a: FloatArray, b: FloatArray): Float {
        var both = 0
        var any = 0
        for (i in a.indices) {
            val x = a[i] > IN
            val y = b[i] > IN
            if (x && y) both++
            if (x || y) any++
        }
        return if (any == 0) 0f else both.toFloat() / any
    }

    /**
     * The mask's box as fractions (x0, y0, x1, y1), grown by [grow] of its size on each side and
     * kept in the frame; null when the mask is empty.
     */
    fun box(map: FloatArray, width: Int, height: Int, grow: Float = 0.1f): FloatArray? {
        var x0 = width
        var y0 = height
        var x1 = -1
        var y1 = -1
        for (y in 0 until height) for (x in 0 until width) if (map[y * width + x] > IN) {
            x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
        }
        if (x1 < 0) return null
        val gx = (x1 - x0 + 1) * grow
        val gy = (y1 - y0 + 1) * grow
        return floatArrayOf(
            ((x0 - gx) / width).coerceIn(0f, 1f), ((y0 - gy) / height).coerceIn(0f, 1f),
            ((x1 + 1 + gx) / width).coerceIn(0f, 1f), ((y1 + 1 + gy) / height).coerceIn(0f, 1f),
        )
    }

    /**
     * A point well inside the mask (fractions): the pixel farthest from its edge, found on a
     * coarse grid (city-block distance). Null when the mask is empty.
     */
    fun inside(map: FloatArray, width: Int, height: Int): Pair<Float, Float>? {
        val step = max(1, max(width, height) / 96)
        val w = (width + step - 1) / step
        val h = (height + step - 1) / step
        val far = IntArray(w * h) { i ->
            val x = min(width - 1, (i % w) * step + step / 2)
            val y = min(height - 1, (i / w) * step + step / 2)
            if (map[y * width + x] > IN) Int.MAX_VALUE / 2 else 0
        }
        // Two passes of the city-block distance to the nearest outside cell (the frame's edge counts as outside).
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (far[i] == 0) continue
            val up = if (y > 0) far[i - w] else 0
            val left = if (x > 0) far[i - 1] else 0
            far[i] = min(far[i], min(up, left) + 1)
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            if (far[i] == 0) continue
            val down = if (y < h - 1) far[i + w] else 0
            val right = if (x < w - 1) far[i + 1] else 0
            far[i] = min(far[i], min(down, right) + 1)
        }
        var best = -1
        for (i in far.indices) if (far[i] > 0 && (best < 0 || far[i] > far[best])) best = i
        if (best < 0) return null
        return ((best % w) * step + step / 2f + 0.5f) / width to ((best / w) * step + step / 2f + 0.5f) / height
    }

    /** True when the mask runs along the frame's edge (an object partly out of the picture). */
    fun touchesEdge(map: FloatArray, width: Int, height: Int): Boolean {
        for (x in 0 until width) if (map[x] > IN || map[(height - 1) * width + x] > IN) return true
        for (y in 0 until height) if (map[y * width] > IN || map[y * width + width - 1] > IN) return true
        return false
    }

    /**
     * Of [candidates] (maps the model offered for this frame, with its scores), the one that is the
     * same object as [previous] (the mask on the frame before): the most overlap, the score
     * breaking near ties. Null when none overlaps it enough to be the same thing.
     */
    fun pick(previous: FloatArray, candidates: List<Pair<FloatArray, Float>>, minOverlap: Float = 0.3f): Int? {
        var best = -1
        var bestValue = 0f
        for ((i, c) in candidates.withIndex()) {
            val overlap = iou(previous, c.first)
            val value = overlap + 0.05f * c.second
            if (overlap >= minOverlap && value > bestValue) {
                best = i
                bestValue = value
            }
        }
        return best.takeIf { it >= 0 }
    }

    /** The relative change of the mask's size from [before] to [after] (0 = the same). */
    fun growth(before: Float, after: Float): Float = if (before <= 0f) 1f else abs(after - before) / before
}
