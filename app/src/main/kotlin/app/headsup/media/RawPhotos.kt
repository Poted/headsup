package app.headsup.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import app.headsup.core.raw.Dng
import app.headsup.core.raw.RawDevelop
import app.headsup.core.raw.RawImage
import app.headsup.core.raw.RawSource
import app.headsup.core.raw.Developer
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Rect
import android.util.Half
import java.nio.ByteOrder
import app.headsup.core.raw.BytesSource
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Raw (DNG) photos developed by the app itself, from the sensor data, instead of Android's 8-bit
 * decoder: exposure, highlights, shadows and white balance work on the raw values. The last file
 * read stays in memory, so moving a slider only develops it again.
 */
object RawPhotos {
    private var cached: Triple<Uri, Int, RemoteRaw>? = null
    private val lock = Any()

    /** Whether [uri] starts like a TIFF/DNG file (whatever its name says). */
    fun looksRaw(context: Context, uri: Uri): Boolean = runCatching {
        val head = ByteArray(4)
        val n = context.contentResolver.openInputStream(uri)?.use { it.read(head) } ?: 0
        n == 4 && ((head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 42.toByte() && head[3] == 0.toByte()) ||
            (head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte() && head[2] == 0.toByte() && head[3] == 42.toByte()))
    }.getOrDefault(false)

    /** Size of the developed picture (upright), or null when the app cannot develop the file. */
    fun size(context: Context, uri: Uri): Pair<Int, Int>? {
        // From the file's directories only (its first few MB), not by decoding the whole picture.
        val head = runCatching {
            context.contentResolver.openInputStream(uri)?.use { s ->
                val buf = ByteArray(4 shl 20)
                var n = 0
                while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                buf.copyOf(n)
            }
        }.getOrNull() ?: return null
        return Dng.probe(head)
    }

    /**
     * [uri] developed with [develop], at most about [maxSide] on its long side, upright; null when
     * the file is not one the app can develop (the reason in [lastError]), so callers fall back.
     */
    /**
     * One development at a time (slider moves come quickly; each needs its own picture), and one
     * no longer [wanted] by the time its turn comes is skipped.
     */
    fun decode(context: Context, uri: Uri, develop: RawDevelop, maxSide: Int, wanted: () -> Boolean = { true }): Bitmap? = synchronized(lock) {
        if (!wanted()) return null
        try {
            developed(context, uri, develop, maxSide)
        } catch (e: OutOfMemoryError) {
            // Too large for the memory left: the caller falls back to Android's decoder.
            lastError = "Not enough memory to develop this file"
            null
        }
    }

    private fun developed(context: Context, uri: Uri, develop: RawDevelop, maxSide: Int): Bitmap? {
        val remote = read(context, uri, maxSide) ?: return null
        try {
            return developed(remote.image, develop, maxSide)
        } finally {
            if (cached?.third !== remote) remote.release()
        }
    }

    private fun developed(raw: RawImage, develop: RawDevelop, maxSide: Int): Bitmap {
        val full = maxOf(raw.developedWidth, raw.developedHeight)
        val step = ((full + maxSide - 1) / maxSide).coerceAtLeast(1)
        val out = Dng.develop(raw, develop, step)
        // The lens distortion the camera describes, corrected (in the raw's own orientation).
        val pixels = raw.warp?.applyTo(out.pixels, out.width, out.height) ?: out.pixels
        val bitmap = Bitmap.createBitmap(pixels, out.width, out.height, Bitmap.Config.ARGB_8888)
        val degrees = when (raw.orientation) {
            3 -> 180f
            6 -> 90f
            8 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true).also { if (it !== bitmap) bitmap.recycle() }
    }

    /** A raw developed at full size for export, in half floats; [degrees] turns it upright. */
    class FullPicture(val bitmap: Bitmap, val degrees: Int, val warp: app.headsup.core.raw.Opcode.WarpRectilinear?)

    /**
     * [uri] developed at its full size (a mosaic demosaiced, not averaged) into a half-float
     * picture, for an export that keeps the precision to the end. The sensor data is held
     * outside the Java heap while it is developed and let go after; null when the app cannot
     * develop the file.
     */
    fun developFull(context: Context, uri: Uri, develop: RawDevelop, onProgress: (Float) -> Unit = {}): FullPicture? {
        var remote: RemoteRaw? = null
        return try {
            onProgress(0.05f)
            remote = RawReader.read(context, uri, 0, mosaic = true)
            onProgress(0.4f)
            val raw = remote.image
            val dev = Developer(raw, develop)
            val w = dev.width
            val space = ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB)
            val picture = Bitmap.createBitmap(w, dev.height, Bitmap.Config.RGBA_F16, false, space)
            val strip = Bitmap.createBitmap(w, STRIP_ROWS, Bitmap.Config.RGBA_F16, false, space)
            val bytes = ByteBuffer.allocate(w * STRIP_ROWS * 8).order(ByteOrder.nativeOrder())
            val halves = bytes.asShortBuffer()
            val row = FloatArray(w * 3)
            val canvas = Canvas(picture)
            val one = Half.toHalf(1f)
            var y0 = 0
            while (y0 < dev.height) {
                val n = minOf(STRIP_ROWS, dev.height - y0)
                halves.clear()
                for (i in 0 until n) {
                    dev.row(y0 + i, row)
                    for (x in 0 until w) {
                        halves.put(Half.toHalf(row[x * 3]))
                        halves.put(Half.toHalf(row[x * 3 + 1]))
                        halves.put(Half.toHalf(row[x * 3 + 2]))
                        halves.put(one)
                    }
                }
                bytes.rewind()
                strip.copyPixelsFromBuffer(bytes)
                canvas.drawBitmap(strip, Rect(0, 0, w, n), Rect(0, y0, w, y0 + n), null)
                y0 += n
                onProgress(0.4f + 0.4f * y0 / dev.height)
            }
            strip.recycle()
            lastError = null
            val degrees = when (raw.orientation) {
                3 -> 180
                6 -> 90
                8 -> 270
                else -> 0
            }
            FullPicture(picture, degrees, raw.warp)
        } catch (e: Throwable) {
            lastError = if (e is OutOfMemoryError) "Not enough memory to develop this file at full size" else e.message
            null
        } finally {
            remote?.release()
        }
    }

    @Volatile
    var lastError: String? = null
        private set

    /** What the last raw read held (format, baseline, white, lens corrections), for the raw controls. */
    @Volatile
    var lastInfo: String? = null
        private set

    /**
     * The camera's own preview stored in the raw file (a JPEG), at least [minSide] / 2 on its
     * long side when it is that large; null when there is none.
     */
    fun preview(context: Context, uri: Uri, minSide: Int): Bitmap? = runCatching {
        withSource(context, uri) { source ->
            val head = source.read(0, minOf(source.size, HEAD_BYTES.toLong()).toInt())
            val (offset, length) = Dng.preview(head) ?: return@withSource null
            val jpeg = source.read(offset, length)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= minSide) sample *= 2
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    /** The file read in parts through its descriptor; a stream that cannot seek (e.g. from the cloud) is read whole. */
    internal fun <T> withSource(context: Context, uri: Uri, block: (RawSource) -> T): T {
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (pfd != null) {
            val channel = FileInputStream(pfd.fileDescriptor).channel
            if (runCatching { channel.size() > 0 }.getOrDefault(false)) return pfd.use { block(FileSource(channel)) }
            pfd.close()
        }
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot open the file")
        return block(BytesSource(bytes))
    }

    private class FileSource(private val channel: FileChannel) : RawSource {
        override val size: Long = channel.size()

        override fun read(offset: Long, length: Int): ByteArray {
            val n = minOf(length.toLong(), (size - offset).coerceAtLeast(0)).toInt()
            val buf = ByteBuffer.allocate(n)
            var at = offset
            while (buf.hasRemaining()) {
                val r = channel.read(buf, at)
                if (r <= 0) break
                at += r
            }
            return if (buf.position() == n) buf.array() else buf.array().copyOf(buf.position())
        }

        override fun stream(offset: Long, length: Long): InputStream = object : InputStream() {
            private var at = offset
            private val end = minOf(offset + length, size)
            private val one = ByteArray(1)

            override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val n = minOf(len.toLong(), end - at).toInt()
                if (n <= 0) return -1
                val r = channel.read(ByteBuffer.wrap(b, off, n), at)
                if (r <= 0) return -1
                at += r
                return r
            }

            override fun skip(n: Long): Long {
                val k = minOf(n, end - at).coerceAtLeast(0)
                at += k
                return k
            }

            override fun available(): Int = minOf(end - at, Int.MAX_VALUE.toLong()).toInt()
        }
    }

    /**
     * The raw image reduced to the size asked for while it is read, so neither the file nor the
     * full sensor data is held. The preview's size (the last one asked) stays for slider moves;
     * an export's larger read is not kept.
     */
    private fun read(context: Context, uri: Uri, maxSide: Int): RemoteRaw? {
        cached?.takeIf { it.first == uri && it.second == maxSide }?.let { return it.third }
        return runCatching {
            if (maxSide <= KEEP_SIDE) {
                cached?.third?.release()
                cached = null
            }
            RawReader.read(context, uri, maxSide, mosaic = false)
        }.onFailure { lastError = it.message }.getOrNull()?.also {
            lastError = null
            lastInfo = it.image.info
            if (maxSide <= KEEP_SIDE) cached = Triple(uri, maxSide, it)
        }
    }

    private const val HEAD_BYTES = 8 shl 20
    private const val KEEP_SIDE = 2048
    private const val STRIP_ROWS = 64
}
