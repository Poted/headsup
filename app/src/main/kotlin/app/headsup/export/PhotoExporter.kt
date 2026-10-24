package app.headsup.export

import android.content.Context
import android.graphics.Bitmap
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
    /** Framing in the timeline's frame (its shape in [Reframe.aspect]). */
    val reframe: Reframe,
    val style: DashboardStyle,
    /** Watch data at the photo's moment, or null. */
    val dashboard: DashboardData?,
    /** Texts as they stand at [textMs] on the timeline. */
    val texts: List<TextItem>,
    val textMs: Long,
)

/**
 * Exports a photo as a JPEG at its own resolution (in the timeline's frame shape), drawn the way
 * the preview draws it: the colour shader on the GPU, then framing, vignette, dashboard and
 * texts. Android 13+ (the colour shader).
 */
@RequiresApi(33)
object PhotoExporter {
    /** Longest side read from the file (larger photos are read at half, quarter… size). */
    private const val MAX_SIDE = 6000

    fun export(context: Context, request: PhotoRequest): Uri {
        val photo = decodeUpright(context, request.uri) ?: error("Could not read ${request.name}")
        val pw = photo.width.toFloat()
        val ph = photo.height.toFloat()
        // The largest frame of the timeline's shape inside the photo.
        val (fwF, fhF) = request.reframe.outputSize(pw, ph)
        val fw = (fwF.toInt() / 2 * 2).coerceAtLeast(2)
        val fh = (fhF.toInt() / 2 * 2).coerceAtLeast(2)
        // As in the preview: the picture covers the frame, the reframe moves it.
        val (picW, picH) = if (pw / ph > fw.toFloat() / fh) fh * pw / ph to fh.toFloat() else fw.toFloat() to fw * ph / pw
        val geometry = FrameGeometry(fw.toFloat(), fh.toFloat(), picW, picH)
        val t = reframeTransform(request.reframe, geometry)

        val shader = GradePreviewShader().apply { setTables(GradeTables(request.cube, request.maskCubes)) }
        val picture = RenderNode("picture").apply {
            setPosition(0, 0, picW.toInt(), picH.toInt())
            setRenderEffect(shader.nativeEffect(request.effects, false, 0f, picW, picH, request.masks, 0f))
            val c = beginRecording()
            c.drawBitmap(photo, null, android.graphics.RectF(0f, 0f, picW, picH), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            endRecording()
        }
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
            c.drawRenderNode(picture)
            c.restore()
            renderer.drawVignette(c, fw.toFloat(), fh.toFloat(), request.vignette)
            val data = request.dashboard
            if (data != null) renderer.draw(c, fw.toFloat(), fh.toFloat(), request.style, data, request.effects) else renderer.drawBars(c, fw.toFloat(), fh.toFloat(), request.effects)
            TextRenderer.draw(c, fw.toFloat(), fh.toFloat(), request.texts, request.textMs)
            endRecording()
        }
        val bitmap = render(root, fw, fh)
        photo.recycle()
        val name = request.name.substringBeforeLast('.') + "_edit.jpg"
        return MediaStoreOutput.savePhoto(context, name) { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out) }.also { bitmap.recycle() }
    }

    /** Draws [root] off screen on the GPU and reads it back. */
    private fun render(root: RenderNode, w: Int, h: Int): Bitmap {
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val hw = HardwareRenderer()
        try {
            hw.setSurface(reader.surface)
            hw.setContentRoot(root)
            hw.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader.acquireNextImage() ?: error("Nothing was drawn")
            image.use {
                val buffer = it.hardwareBuffer ?: error("No picture buffer")
                buffer.use { b ->
                    val wrapped = Bitmap.wrapHardwareBuffer(b, ColorSpace.get(ColorSpace.Named.SRGB)) ?: error("Could not read the picture")
                    return wrapped.copy(Bitmap.Config.ARGB_8888, false)
                }
            }
        } finally {
            hw.destroy()
            reader.close()
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
        val orientation = context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
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
