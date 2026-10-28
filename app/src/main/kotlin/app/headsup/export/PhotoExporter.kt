package app.headsup.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ExifInterface
import android.media.ImageReader
import android.net.Uri
import androidx.annotation.RequiresApi
import app.headsup.color.GradePreviewShader
import app.headsup.color.GradeTables
import app.headsup.core.color.ColourMask
import app.headsup.core.edit.TextItem
import app.headsup.core.frame.Reframe
import app.headsup.core.raw.RawDevelop
import app.headsup.media.RawPhotos
import app.headsup.render.DashboardData
import app.headsup.render.DashboardRenderer
import app.headsup.render.DashboardStyle
import app.headsup.render.EffectSettings
import app.headsup.render.TextRenderer
import app.headsup.ui.FrameGeometry
import app.headsup.ui.reframeTransform

/** One photo to export, with everything the preview applies to it. */
class PhotoRequest(
    val uri: Uri,
    val name: String,
    val cube: Array<Array<IntArray>>?,
    val masks: List<ColourMask>,
    val maskCubes: List<Array<Array<IntArray>>>,
    val effects: EffectSettings,
    val vignette: Float,
    /** Texture, clarity and dehaze. */
    val presence: app.headsup.core.color.Presence = app.headsup.core.color.Presence.NONE,
    /** Framing in the timeline's frame (its shape in [Reframe.aspect]). */
    val reframe: Reframe,
    val style: DashboardStyle,
    /** Watch data at the photo's moment, or null. */
    val dashboard: DashboardData?,
    /** Texts as they stand at [textMs] on the timeline. */
    val texts: List<TextItem>,
    val textMs: Long,
    /** The route map, the activity it shows and where the photo is on it. */
    val routeMap: app.headsup.render.RouteMap = app.headsup.render.RouteMap(),
    val mapTrack: app.headsup.core.track.ActivityTrack? = null,
    val mapMarker: Pair<Double, Double>? = null,
    /** A raw (DNG) photo and its development. */
    val raw: Boolean = false,
    val rawDevelop: RawDevelop = RawDevelop(),
    /** Long side of the file (null = the photo's own), and JPEG quality 0..100. */
    val longSide: Int? = null,
    val quality: Int = 92,
    val format: PhotoFormat = PhotoFormat.JPEG,
)

/**
 * Exports a photo as a JPEG at its own resolution (in the timeline's frame shape), drawn the way
 * the preview draws it: the colour shader on the GPU, then framing, vignette, dashboard and
 * texts. A raw photo is developed at full size in half floats and graded at that precision, drawn
 * at 10 bits and dithered to the JPEG's 8. Android 13+ (the colour shader).
 */
@RequiresApi(33)
object PhotoExporter {
    /** Longest side read from the file (larger photos are read at half, quarter… size). */
    private const val MAX_SIDE = 10000

    /** How many stops an Ultra HDR photo's brightest parts are lifted on an HDR screen. */
    private const val HDR_STOPS = 2f

    /** [onProgress] gets 0..1 as reading, developing, drawing and saving go. */
    fun export(context: Context, request: PhotoRequest, onProgress: (Float) -> Unit = {}): Uri {
        // Raw photos are developed by the app at full size in half floats; others decoded by Android.
        val full = if (request.raw) RawPhotos.developFull(context, request.uri, request.rawDevelop, onProgress) else null
        onProgress(0.8f)
        val photo = full?.bitmap
            ?: (if (request.raw) RawPhotos.decode(context, request.uri, request.rawDevelop, MAX_SIDE) else null)
            ?: decodeUpright(context, request.uri) ?: error("Could not read ${request.name}")
        val degrees = full?.degrees ?: 0
        val turned = degrees == 90 || degrees == 270
        val pw = (if (turned) photo.height else photo.width).toFloat()
        val ph = (if (turned) photo.width else photo.height).toFloat()
        // The largest frame of the timeline's shape inside the photo.
        val (fwF, fhF) = request.reframe.outputSize(pw, ph)
        val fw = (fwF.toInt() / 2 * 2).coerceAtLeast(2)
        val fh = (fhF.toInt() / 2 * 2).coerceAtLeast(2)
        // As in the preview: the picture covers the frame, the reframe moves it.
        val (picW, picH) = if (pw / ph > fw.toFloat() / fh) fh * pw / ph to fh.toFloat() else fw.toFloat() to fw * ph / pw
        val geometry = FrameGeometry(fw.toFloat(), fh.toFloat(), picW, picH)
        val t = reframeTransform(request.reframe, geometry)

        // The colour shader samples the photo directly (no 8-bit layer in between); the photo is
        // turned upright and scaled to the picture by the sampling itself.
        val bitmapShader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }
        // The lens distortion (and lateral colour fringes) the camera describes, corrected as it is sampled.
        val image: Shader = full?.warp?.let { warpShader(bitmapShader, it, photo.width, photo.height) } ?: bitmapShader
        image.apply {
            setLocalMatrix(Matrix().apply {
                postRotate(degrees.toFloat())
                when (degrees) {
                    90 -> postTranslate(photo.height.toFloat(), 0f)
                    180 -> postTranslate(photo.width.toFloat(), photo.height.toFloat())
                    270 -> postTranslate(0f, photo.width.toFloat())
                }
                postScale(picW / pw, picH / ph)
            })
        }
        val brush = app.headsup.color.BrushAtlas.build(context, request.masks, picW / picH)
        val shader = GradePreviewShader().apply {
            setTables(GradeTables(request.cube, request.maskCubes))
            setBrush(brush)
        }
        val graded = android.graphics.Paint().apply { this.shader = shader.paintShader(request.effects, picW, picH, request.masks, image, request.rawDevelop.takeIf { request.raw }, request.presence, request.reframe.warp) }
        val renderer = DashboardRenderer(context.assets)
        val root = RenderNode("frame").apply {
            setPosition(0, 0, fw, fh)
            val c = beginRecording()
            c.drawColor(android.graphics.Color.BLACK)
            c.save()
            c.translate(fw / 2f + t.translation.x, fh / 2f + t.translation.y)
            c.rotate(request.reframe.angleDegrees)
            c.scale(t.zoom, t.zoom)
            c.translate(-picW / 2f, -picH / 2f)
            c.drawRect(0f, 0f, picW, picH, graded)
            c.restore()
            renderer.drawVignette(c, fw.toFloat(), fh.toFloat(), request.vignette)
            val data = request.dashboard
            if (data != null) renderer.draw(c, fw.toFloat(), fh.toFloat(), request.style, data, request.effects) else renderer.drawBars(c, fw.toFloat(), fh.toFloat(), request.effects)
            app.headsup.render.RouteMapRenderer.draw(c, fw.toFloat(), fh.toFloat(), request.routeMap, request.mapTrack, request.mapMarker?.first, request.mapMarker?.second)
            TextRenderer.draw(c, fw.toFloat(), fh.toFloat(), request.texts, request.textMs)
            endRecording()
        }
        if (request.format == PhotoFormat.TIFF16) {
            // 16 bits per colour from the drawing's half floats, no dithering needed.
            val deep = runCatching { draw(root, fw, fh, PixelFormat.RGBA_1010102) }.getOrNull()
                ?: draw(root, fw, fh, PixelFormat.RGBA_8888) ?: error("Nothing was drawn")
            photo.recycle()
            onProgress(0.9f)
            val target = request.longSide?.takeIf { it < maxOf(fw, fh) }
            val sized = if (target == null) deep else {
                val k = target.toFloat() / maxOf(fw, fh)
                Bitmap.createScaledBitmap(deep, (fw * k).toInt().coerceAtLeast(2), (fh * k).toInt().coerceAtLeast(2), true).also { deep.recycle() }
            }
            val name = request.name.substringBeforeLast('.') + "_edit.tif"
            return MediaStoreOutput.savePhoto(context, name, "image/tiff") { out -> writeTiff(sized, out.buffered(1 shl 20)) }.also { sized.recycle() }
        }
        val rendered = render(root, fw, fh)
        // A raw's real lights above white, framed as the photo, for an Ultra HDR gain map.
        val headroom = if (request.format == PhotoFormat.ULTRA_HDR) full?.headroom?.let { map ->
            runCatching { renderHeadroom(map, degrees, picW, picH, fw, fh, t, request.reframe) }.getOrNull().also { map.recycle() }
        } else null
        photo.recycle()
        onProgress(0.92f)
        // Smaller on request (never larger than the photo).
        val target = request.longSide?.takeIf { it < maxOf(fw, fh) }
        val bitmap = if (target == null) rendered else {
            val k = target.toFloat() / maxOf(fw, fh)
            Bitmap.createScaledBitmap(rendered, (fw * k).toInt().coerceAtLeast(2), (fh * k).toInt().coerceAtLeast(2), true).also { rendered.recycle() }
        }
        val name = request.name.substringBeforeLast('.') + "_edit.jpg"
        val saved = if (request.format == PhotoFormat.ULTRA_HDR && android.os.Build.VERSION.SDK_INT >= 34) withGainmap(bitmap, headroom) else bitmap
        return MediaStoreOutput.savePhoto(context, name) { out -> saved.compress(Bitmap.CompressFormat.JPEG, request.quality.coerceIn(1, 100), out) }
            .also { saved.recycle(); if (saved !== bitmap) bitmap.recycle() }
    }

    /** [photo] ([w] × [h] pixels) sampled through the DNG's WarpRectilinear: each colour from where its lens put it. */
    private fun warpShader(photo: Shader, warp: app.headsup.core.raw.Opcode.WarpRectilinear, w: Int, h: Int): Shader {
        val radial = app.headsup.core.raw.Opcode.Radial(warp.cx, warp.cy, w, h)
        return android.graphics.RuntimeShader(WARP).apply {
            setInputShader("photo", photo)
            setFloatUniform("center", radial.centerX.toFloat(), radial.centerY.toFloat())
            setFloatUniform("norm", radial.norm.toFloat())
            for (c in 0 until 3) {
                val k = warp.coefficients[minOf(c, warp.planes - 1)]
                setFloatUniform("r$c", k[0].toFloat(), k[1].toFloat(), k[2].toFloat(), k[3].toFloat())
                setFloatUniform("t$c", k[4].toFloat(), k[5].toFloat())
            }
        }
    }

    // Opcode.WarpRectilinear.source on the GPU (pixel centres at +0.5).
    private val WARP = """
        uniform shader photo;
        uniform float2 center;
        uniform float norm;
        uniform float4 r0; uniform float4 r1; uniform float4 r2;
        uniform float2 t0; uniform float2 t1; uniform float2 t2;

        float2 source(float2 p, float4 r, float2 t) {
            float2 d = (p - center) / norm;
            float q = dot(d, d);
            float f = r.x + q * (r.y + q * (r.z + q * r.w));
            float2 tg = float2(t.x * 2.0 * d.x * d.y + t.y * (q + 2.0 * d.x * d.x), t.y * 2.0 * d.x * d.y + t.x * (q + 2.0 * d.y * d.y));
            return center + (d * f + tg) * norm;
        }

        half4 main(float2 coord) {
            float2 p = coord - 0.5;
            half red = photo.eval(source(p, r0, t0) + 0.5).r;
            half green = photo.eval(source(p, r1, t1) + 0.5).g;
            half blue = photo.eval(source(p, r2, t2) + 0.5).b;
            return half4(red, green, blue, 1.0);
        }
    """

    /**
     * Draws [root] off screen on the GPU and reads it back as 8 bits per colour: drawn at 10 bits
     * and dithered down (smooth skies stay smooth), or at 8 bits where 10 cannot be drawn.
     */
    private fun render(root: RenderNode, w: Int, h: Int): Bitmap =
        runCatching { draw(root, w, h, PixelFormat.RGBA_1010102)?.let { deep -> dithered(deep).also { deep.recycle() } } }.getOrNull()
            ?: draw(root, w, h, PixelFormat.RGBA_8888) ?: error("Nothing was drawn")

    /**
     * Draws [root] into a buffer of [format] and reads it back: a 10-bit picture as half floats
     * (a layout Android defines, unlike the packed 10-bit one), an 8-bit one as it is.
     */
    private fun draw(root: RenderNode, w: Int, h: Int, format: Int): Bitmap? {
        val reader = ImageReader.newInstance(w, h, format, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val hw = HardwareRenderer()
        try {
            hw.setSurface(reader.surface)
            hw.setContentRoot(root)
            hw.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader.acquireNextImage() ?: return null
            image.use {
                val buffer = it.hardwareBuffer ?: return null
                buffer.use { b ->
                    val wrapped = Bitmap.wrapHardwareBuffer(b, ColorSpace.get(ColorSpace.Named.SRGB)) ?: return null
                    val config = if (format == PixelFormat.RGBA_1010102) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
                    return wrapped.copy(config, false)
                }
            }
        } finally {
            hw.destroy()
            reader.close()
        }
    }

    /**
     * A deep (half-float) picture to 8 bits with a little noise below the last bit, in strips.
     * Checked against Android's own conversion at a grid of points; if they disagree, that one is
     * used (a picture is never saved wrong because of an unexpected pixel layout).
     */
    private fun dithered(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rows = 32
        val bytes = java.nio.ByteBuffer.allocate(w * rows * 8).order(java.nio.ByteOrder.nativeOrder())
        val ints = IntArray(w * rows)
        val linear = src.colorSpace.let { it == ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB) || it == ColorSpace.get(ColorSpace.Named.LINEAR_SRGB) }
        var y0 = 0
        while (y0 < h) {
            val n = minOf(rows, h - y0)
            val part = Bitmap.createBitmap(src, 0, y0, w, n)
            bytes.clear()
            part.copyPixelsToBuffer(bytes)
            if (part !== src) part.recycle()
            bytes.rewind()
            val halves = bytes.asShortBuffer()
            for (i in 0 until w * n) {
                var seed = (y0 + i / w) * 7919 + (i % w) * 104729
                var argb = 0xFF shl 24
                for (c in 0 until 3) {
                    seed = seed * 1103515245 + 12345
                    val noise = ((seed ushr 8) and 0xFFFF) / 65536f
                    var v = android.util.Half.toFloat(halves.get(i * 4 + c))
                    // A linear half-float picture is encoded to sRGB first.
                    if (linear) v = if (v <= 0.0031308f) 12.92f * v else 1.055f * Math.pow(v.toDouble(), 1 / 2.4).toFloat() - 0.055f
                    argb = argb or ((v.coerceIn(0f, 1f) * 255f + noise).toInt().coerceIn(0, 255) shl (16 - 8 * c))
                }
                ints[i] = argb
            }
            out.setPixels(ints, 0, w, 0, y0, w, n)
            y0 += n
        }
        // The check: at most one step apart (the noise) on a grid of points.
        var worst = 0
        for (gy in 1..9) for (gx in 1..9) {
            val x = w * gx / 10
            val y = h * gy / 10
            val a = src.getPixel(x, y)
            val b = out.getPixel(x, y)
            for (shift in intArrayOf(16, 8, 0)) worst = maxOf(worst, kotlin.math.abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)))
        }
        if (worst > 3) {
            out.recycle()
            return src.copy(Bitmap.Config.ARGB_8888, false)
        }
        return out
    }

    /**
     * [sdr] with a gain map (Ultra HDR): on an HDR screen the brightest parts are lifted up to
     * [HDR_STOPS] stops, fading in from near white, so lights, sky and reflections shine; ordinary
     * screens show the JPEG as it is. The map is a quarter of the size, grey, log-encoded.
     */
    @androidx.annotation.RequiresApi(34)
    private fun withGainmap(sdr: Bitmap, headroom: Bitmap? = null): Bitmap {
        val out = if (sdr.isMutable) sdr else sdr.copy(Bitmap.Config.ARGB_8888, true)
        if (headroom != null) {
            // A raw: the scene's own light above white, up to RawPhotos.HEADROOM_STOPS.
            val maxGain = Math.pow(2.0, app.headsup.media.RawPhotos.HEADROOM_STOPS.toDouble()).toFloat()
            out.gainmap = android.graphics.Gainmap(headroom).apply {
                setRatioMin(1f, 1f, 1f)
                setRatioMax(maxGain, maxGain, maxGain)
                setGamma(1f, 1f, 1f)
                setEpsilonSdr(0f, 0f, 0f)
                setEpsilonHdr(0f, 0f, 0f)
                setMinDisplayRatioForHdrTransition(1f)
                setDisplayRatioForFullHdr(maxGain)
            }
            return out
        }
        val gw = (out.width / 4).coerceAtLeast(1)
        val gh = (out.height / 4).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(out, gw, gh, true)
        val pixels = IntArray(gw * gh)
        small.getPixels(pixels, 0, gw, 0, 0, gw, gh)
        if (small !== out) small.recycle()
        val maxGain = Math.pow(2.0, HDR_STOPS.toDouble()).toFloat()
        for (i in pixels.indices) {
            val p = pixels[i]
            val m = maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF) / 255f
            val t = ((m - 0.72f) / 0.28f).coerceIn(0f, 1f)
            val gain = 1f + (maxGain - 1f) * t * t * (3f - 2f * t)
            // log2(gain) / log2(maxGain), as the map stores it (ratio min 1, gamma 1).
            val e = ((kotlin.math.ln(gain) / kotlin.math.ln(maxGain)) * 255f + 0.5f).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (e shl 16) or (e shl 8) or e
        }
        val map = Bitmap.createBitmap(pixels, gw, gh, Bitmap.Config.ARGB_8888)
        out.gainmap = android.graphics.Gainmap(map).apply {
            setRatioMin(1f, 1f, 1f)
            setRatioMax(maxGain, maxGain, maxGain)
            setGamma(1f, 1f, 1f)
            setEpsilonSdr(0f, 0f, 0f)
            setEpsilonHdr(0f, 0f, 0f)
            setMinDisplayRatioForHdrTransition(1f)
            setDisplayRatioForFullHdr(maxGain)
        }
        return out
    }

    /**
     * A raw's headroom map drawn as the photo is (upright, framed, straightened, perspective
     * corrected), at a quarter of the frame's size: the Ultra HDR gain map's contents.
     */
    private fun renderHeadroom(map: Bitmap, degrees: Int, picW: Float, picH: Float, fw: Int, fh: Int, t: app.headsup.ui.ReframeTransform, reframe: app.headsup.core.frame.Reframe): Bitmap? {
        val turned = degrees == 90 || degrees == 270
        val mw = (if (turned) map.height else map.width).toFloat()
        val mh = (if (turned) map.width else map.height).toFloat()
        val mapShader = BitmapShader(map, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
            setLocalMatrix(Matrix().apply {
                postRotate(degrees.toFloat())
                when (degrees) {
                    90 -> postTranslate(map.height.toFloat(), 0f)
                    180 -> postTranslate(map.width.toFloat(), map.height.toFloat())
                    270 -> postTranslate(0f, map.width.toFloat())
                }
                postScale(picW / mw, picH / mh)
            })
        }
        val shader: Shader = reframe.warp?.let { m ->
            android.graphics.RuntimeShader(HEADROOM_WARP).apply {
                setInputShader("map", mapShader)
                setFloatUniform("warp", floatArrayOf(m[0].toFloat(), m[3].toFloat(), m[6].toFloat(), m[1].toFloat(), m[4].toFloat(), m[7].toFloat(), m[2].toFloat(), m[5].toFloat(), m[8].toFloat()))
                setFloatUniform("size", picW, picH)
            }
        } ?: mapShader
        val gw = (fw / 4).coerceAtLeast(1)
        val gh = (fh / 4).coerceAtLeast(1)
        val paint = android.graphics.Paint().apply { this.shader = shader }
        val root = RenderNode("headroom").apply {
            setPosition(0, 0, gw, gh)
            val c = beginRecording()
            c.drawColor(android.graphics.Color.BLACK)
            c.scale(gw.toFloat() / fw, gh.toFloat() / fh)
            c.translate(fw / 2f + t.translation.x, fh / 2f + t.translation.y)
            c.rotate(reframe.angleDegrees)
            c.scale(t.zoom, t.zoom)
            c.translate(-picW / 2f, -picH / 2f)
            c.drawRect(0f, 0f, picW, picH, paint)
            endRecording()
        }
        return draw(root, gw, gh, PixelFormat.RGBA_8888)
    }

    // The perspective correction (as the grading shader's img()), for the headroom map.
    private val HEADROOM_WARP = """
        uniform shader map;
        uniform float3x3 warp;
        uniform float2 size;
        half4 main(float2 p) {
            float3 q = warp * float3(p / size, 1.0);
            return map.eval(clamp(q.xy / q.z, float2(0.0), float2(1.0)) * size);
        }
    """

    /**
     * [src] as a 16-bit TIFF: a half-float picture's values as they are (sRGB-encoded first if
     * linear), an 8-bit one widened. Checked against Android's own conversion at a grid of points,
     * as [dithered] is; if they disagree, the 8-bit values are used.
     */
    private fun writeTiff(src: Bitmap, out: java.io.OutputStream) {
        val w = src.width
        val h = src.height
        val half = src.config == Bitmap.Config.RGBA_F16
        val linear = src.colorSpace.let { it == ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB) || it == ColorSpace.get(ColorSpace.Named.LINEAR_SRGB) }
        fun encode(v: Float): Int {
            val e = if (linear) (if (v <= 0.0031308f) 12.92f * v else 1.055f * Math.pow(v.toDouble(), 1 / 2.4).toFloat() - 0.055f) else v
            return (e.coerceIn(0f, 1f) * 65535f + 0.5f).toInt()
        }
        val rows = 32
        val bytes = java.nio.ByteBuffer.allocate(w * rows * 8).order(java.nio.ByteOrder.nativeOrder())
        val ints = IntArray(w * rows)
        // One block of rows read at a time, as the writer asks for them.
        var block = -1
        fun load(y: Int) {
            val b = y / rows
            if (b == block) return
            block = b
            val y0 = b * rows
            val n = minOf(rows, h - y0)
            if (half) {
                val part = Bitmap.createBitmap(src, 0, y0, w, n)
                bytes.clear()
                part.copyPixelsToBuffer(bytes)
                if (part !== src) part.recycle()
            } else {
                src.getPixels(ints, 0, w, 0, y0, w, n)
            }
        }
        var useHalf = half
        if (half) {
            // The check: half-float reading against Android's 8-bit view.
            var worst = 0
            for (gy in 1..9) for (gx in 1..9) {
                val x = w * gx / 10
                val y = h * gy / 10
                load(y)
                val i = ((y % rows) * w + x) * 4
                val a = src.getPixel(x, y)
                for (c in 0 until 3) {
                    val v = encode(android.util.Half.toFloat(bytes.getShort(i * 2 + c * 2))) / 257
                    worst = maxOf(worst, kotlin.math.abs(((a shr (16 - 8 * c)) and 0xFF) - v))
                }
            }
            if (worst > 3) useHalf = false
            block = -1
        }
        val eight = if (!useHalf && half) src.copy(Bitmap.Config.ARGB_8888, false) else null
        try {
            app.headsup.core.image.Tiff16.write(out, w, h) { y, rgb ->
                if (useHalf) {
                    load(y)
                    val base = (y % rows) * w * 4
                    for (x in 0 until w) for (c in 0 until 3) rgb[x * 3 + c] = encode(android.util.Half.toFloat(bytes.getShort((base + x * 4 + c) * 2)))
                } else {
                    val from = eight ?: src
                    from.getPixels(ints, 0, w, 0, y, w, 1)
                    for (x in 0 until w) for (c in 0 until 3) rgb[x * 3 + c] = ((ints[x] shr (16 - 8 * c)) and 0xFF) * 257
                }
            }
            out.flush()
        } finally {
            eight?.recycle()
        }
    }

    /** The photo turned upright by its EXIF orientation, at most [MAX_SIDE] on its long side. */
    private fun decodeUpright(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val raw = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val orientation = app.headsup.media.Exif.orientation(context, uri)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return raw
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees) }, true).also { if (it !== raw) raw.recycle() }
    }
}
