package app.headsup.retouch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import app.headsup.core.retouch.PixelBox
import app.headsup.core.retouch.Retouch
import app.headsup.core.retouch.RetouchPatch
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Removing things from photos (Clean up): what is to go is filled by [Inpainter], area by area,
 * each seen with what is around it at the model's size, grown a little past its edge (soft edges,
 * halos), given back the photo's grain and faded into the photo. The result is kept as patches
 * over the photo ([RetouchPatch]), never changing the file: they are laid over it in the preview
 * and in export, and can be undone.
 */
object Cleaner {
    /** The photo is cleaned at up to this long side: the patches are kept at that detail. */
    const val WORK_SIDE = 4096

    private val cache = LinkedHashMap<String, Bitmap>()

    private fun file(context: Context, id: String) = File(File(context.filesDir, "retouch"), "$id.png")

    /** A patch's pixels (with alpha), or null when they are gone. */
    fun pixels(context: Context, id: String): Bitmap? {
        synchronized(cache) { cache[id]?.let { return it } }
        val bmp = runCatching { BitmapFactory.decodeFile(file(context, id).absolutePath) }.getOrNull() ?: return null
        synchronized(cache) {
            cache[id] = bmp
            while (cache.size > 12) cache.remove(cache.keys.first())
        }
        return bmp
    }

    private fun keep(context: Context, bitmap: Bitmap): String {
        val id = UUID.randomUUID().toString()
        val target = file(context.applicationContext, id)
        target.parentFile?.mkdirs()
        target.outputStream().buffered().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        synchronized(cache) { cache[id] = bitmap }
        return id
    }

    /**
     * Removes what [hole] covers from [photo] (upright, as it is shown, with earlier patches
     * already on it): [hole] is 0..1 over the whole picture ([holeWidth]×[holeHeight], any size of
     * the same shape). Returns the new patches (their [RetouchPatch.hole] and [RetouchPatch.develop]
     * as given). [onProgress] gets 0..1.
     */
    fun remove(
        context: Context,
        photo: Bitmap,
        hole: FloatArray,
        holeWidth: Int,
        holeHeight: Int,
        holeId: String?,
        develop: String?,
        onProgress: (Float) -> Unit = {},
    ): List<RetouchPatch> {
        val w = photo.width
        val h = photo.height
        // The hole at the photo's size, grown a little past the object's edge (its soft rim, halo, shadow line).
        val sharp = BooleanArray(w * h)
        for (y in 0 until h) {
            val hy = ((y + 0.5f) * holeHeight / h).toInt().coerceIn(0, holeHeight - 1)
            for (x in 0 until w) {
                val hx = ((x + 0.5f) * holeWidth / w).toInt().coerceIn(0, holeWidth - 1)
                sharp[y * w + x] = hole[hy * holeWidth + hx] > 0.5f
            }
        }
        // The hole map is coarser than the photo: grown by at least its own step.
        val grow = max(max(3, (max(w, h) * 0.004f).roundToInt()), (max(w, h).toFloat() / max(holeWidth, holeHeight)).roundToInt() + 1)
        val grown = Retouch.dilate(sharp, w, h, grow)
        // Areas close together share what is around them: one fill.
        val areas = Retouch.areas(grown, w, h, join = grow * 4)
        val patches = ArrayList<RetouchPatch>()
        val random = Random(holeId?.hashCode() ?: 1)
        for ((k, area) in areas.withIndex()) {
            val box = Retouch.context(area, w, h, minSide = Inpainter.SIZE / 2)
            patch(context, photo, grown, box, grow, random)?.let { (bmp, at) ->
                patches += RetouchPatch(keep(context, bmp), at.x0.toFloat() / w, at.y0.toFloat() / h, at.x1.toFloat() / w, at.y1.toFloat() / h, holeId, develop)
            }
            onProgress((k + 1f) / areas.size)
        }
        return patches
    }

    /** One area: the fill over [box] (pixels of [photo]), cut to where it shows. */
    private fun patch(context: Context, photo: Bitmap, hole: BooleanArray, box: PixelBox, feather: Int, random: Random): Pair<Bitmap, PixelBox>? {
        val w = photo.width
        val cw = box.width
        val ch = box.height
        val crop = Bitmap.createBitmap(photo, box.x0, box.y0, cw, ch).let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
        val cropHole = BooleanArray(cw * ch) { i -> hole[(box.y0 + i / cw) * w + box.x0 + i % cw] }
        if (cropHole.none { it }) return null
        // At the model's size: the picture smoothly, the hole never shrinking (any pixel of it marks).
        val n = Inpainter.SIZE
        val small = Bitmap.createScaledBitmap(crop, n, n, true)
        val smallRgb = rgb(small)
        val smallHole = BooleanArray(n * n)
        for (sy in 0 until n) {
            val ya = sy * ch / n
            val yb = max(ya + 1, (sy + 1) * ch / n)
            for (sx in 0 until n) {
                val xa = sx * cw / n
                val xb = max(xa + 1, (sx + 1) * cw / n)
                var any = false
                var y = ya
                while (!any && y < yb) {
                    var x = xa
                    while (!any && x < xb) { any = cropHole[y * cw + x]; x++ }
                    y++
                }
                smallHole[sy * n + sx] = any
            }
        }
        val filled = Inpainter.fill(context, smallRgb, Retouch.dilate(smallHole, n, n, 1))
        // Back at the crop's size.
        val fillBmp = Bitmap.createScaledBitmap(bitmap(filled, n, n), cw, ch, true)
        val fill = rgb(fillBmp)
        val photoRgb = rgb(crop)
        // A fill made smaller than the photo is smoother: the photo's grain is added back.
        val photoGrain = Retouch.grain(photoRgb, cropHole, cw, ch, ring = feather * 3)
        val fillGrain = grainInside(fill, cropHole, cw, ch)
        val add = sqrt(max(0f, photoGrain * photoGrain - fillGrain * fillGrain))
        val alpha = Retouch.alpha(cropHole, cw, ch, feather)
        // Only where it shows.
        var x0 = cw; var y0 = ch; var x1 = -1; var y1 = -1
        for (i in alpha.indices) if (alpha[i] > 0f) {
            val x = i % cw
            val y = i / cw
            x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
        }
        if (x1 < 0) return null
        val pw = x1 - x0 + 1
        val ph = y1 - y0 + 1
        val pixels = IntArray(pw * ph)
        for (y in 0 until ph) for (x in 0 until pw) {
            val i = (y0 + y) * cw + x0 + x
            val a = alpha[i]
            if (a <= 0f) continue
            // The same grain in all three colours mostly (as a sensor's), a little in each.
            val common = if (add > 0f) add * gaussian(random) * 0.8f else 0f
            fun c(k: Int): Int {
                val v = fill[i * 3 + k] + common + (if (add > 0f) add * 0.45f * gaussian(random) else 0f)
                return (v * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
            pixels[y * pw + x] = ((a * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or (c(0) shl 16) or (c(1) shl 8) or c(2)
        }
        val out = Bitmap.createBitmap(pixels, pw, ph, Bitmap.Config.ARGB_8888)
        return out to PixelBox(box.x0 + x0, box.y0 + y0, box.x0 + x1 + 1, box.y0 + y1 + 1)
    }

    /** The fill's own grain inside the hole (as [Retouch.grain] measures the photo's around it). */
    private fun grainInside(rgb: FloatArray, hole: BooleanArray, w: Int, h: Int): Float {
        var sum = 0.0
        var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (!hole[i] || !hole[i - 1] || !hole[i + 1] || !hole[i - w] || !hole[i + w]) continue
            for (c in 0 until 3) {
                var mean = 0f
                for (dy in -1..1) for (dx in -1..1) mean += rgb[((y + dy) * w + x + dx) * 3 + c]
                val d = rgb[i * 3 + c] - mean / 9f
                sum += d * d
                n++
            }
        }
        return if (n == 0) 0f else sqrt(sum / n * 9.0 / 8.0).toFloat()
    }

    private fun gaussian(random: Random): Float {
        val u = random.nextDouble().coerceAtLeast(1e-12)
        val v = random.nextDouble()
        return (sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2.0 * Math.PI * v)).toFloat()
    }

    private fun rgb(bitmap: Bitmap): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val out = FloatArray(w * h * 3)
        for (i in px.indices) {
            out[i * 3] = ((px[i] shr 16) and 0xFF) / 255f
            out[i * 3 + 1] = ((px[i] shr 8) and 0xFF) / 255f
            out[i * 3 + 2] = (px[i] and 0xFF) / 255f
        }
        return out
    }

    private fun bitmap(rgb: FloatArray, w: Int, h: Int): Bitmap {
        val px = IntArray(w * h) { i ->
            fun c(k: Int) = (rgb[i * 3 + k] * 255f + 0.5f).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (c(0) shl 16) or (c(1) shl 8) or c(2)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * The photo the way it is cleaned: upright, up to [WORK_SIDE] on its long side; a raw one
     * developed as [develop] says.
     */
    fun source(context: Context, uri: android.net.Uri, raw: Boolean, develop: app.headsup.core.raw.RawDevelop): Bitmap? {
        if (raw) app.headsup.media.RawPhotos.decode(context, uri, develop, WORK_SIDE)?.let { return it }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= WORK_SIDE) sample *= 2
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            } ?: return@runCatching null
            val degrees = when (app.headsup.media.Exif.orientation(context, uri)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, android.graphics.Matrix().apply { postRotate(degrees) }, true)
        }.getOrNull()
    }

    /** What a raw photo's patches were made for: its development, as far as it changes pixels Clean up sees. */
    fun developKey(develop: app.headsup.core.raw.RawDevelop): String = develop.copy(sharpen = 0f, colourNoise = 0f).toString()

    /** [photo] with [patches] laid over it: the same bitmap when it can be drawn on, else a copy. */
    fun applyTo(context: Context, photo: Bitmap, patches: List<RetouchPatch>): Bitmap {
        if (patches.isEmpty()) return photo
        val config = photo.config?.takeIf { it != Bitmap.Config.HARDWARE } ?: Bitmap.Config.ARGB_8888
        val target = if (photo.isMutable && photo.config == config) photo else photo.copy(config, true) ?: return photo
        val canvas = Canvas(target)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (p in patches) {
            val bmp = pixels(context, p.id) ?: continue
            canvas.drawBitmap(bmp, null, RectF(p.left * target.width, p.top * target.height, p.right * target.width, p.bottom * target.height), paint)
        }
        return target
    }

    /**
     * The patches alone, on a transparent picture of [width]×[height] (the photo's shape), for
     * laying over a photo drawn some other way (export). With [colourOf], each patch is drawn in
     * that one colour (its alpha kept): for maps that go with the photo, e.g. its headroom.
     */
    fun layer(context: Context, patches: List<RetouchPatch>, width: Int, height: Int, colourOf: ((RetouchPatch) -> Int)? = null): Bitmap? {
        if (patches.isEmpty()) return null
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        for (p in patches) {
            val bmp = pixels(context, p.id) ?: continue
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colourOf?.let { colorFilter = PorterDuffColorFilter(it(p), PorterDuff.Mode.SRC_IN) }
            }
            canvas.drawBitmap(bmp, null, RectF(p.left * width, p.top * height, p.right * width, p.bottom * height), paint)
        }
        return out
    }
}
