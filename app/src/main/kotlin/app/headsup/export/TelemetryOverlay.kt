package app.headsup.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import app.headsup.render.DashboardData
import app.headsup.render.DashboardRenderer
import app.headsup.render.DashboardStyle
import app.headsup.render.EffectSettings
import app.headsup.render.TextRenderer
import app.headsup.core.edit.TextItem

/**
 * Media3 overlay that draws the dashboard (and vignette) for each exported frame.
 *
 * Uploading a full 4K bitmap to the GPU for every frame is slow, and most consecutive frames
 * show identical numbers. The overlay therefore keeps two bitmaps and only draws into the other
 * one (handing Media3 a new instance, which triggers an upload) when the visible content changes.
 */
@OptIn(UnstableApi::class)
class TelemetryOverlay(
    private val renderer: DashboardRenderer,
    private val style: DashboardStyle,
    private val effects: EffectSettings,
    private val vignette: Float,
    fallbackWidth: Int,
    fallbackHeight: Int,
    /** Texts drawn over the dashboard, at their timeline time. */
    private val texts: List<TextItem> = emptyList(),
    private val timelineMsOf: (positionMs: Long) -> Long = { it },
    private val dataAt: (positionMs: Long) -> DashboardData?,
) : BitmapOverlay() {
    private var width = fallbackWidth
    private var height = fallbackHeight
    private val buffers = arrayOfNulls<Bitmap>(2)
    private var current = 0
    private var lastKey: String? = null

    /** Timestamp of the first frame, so positions count from the start of the exported part. */
    private var firstUs = Long.MIN_VALUE

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        width = videoSize.width
        height = videoSize.height
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap = try {
        draw(presentationTimeUs)
    } catch (e: Throwable) {
        // Report through the export's error callback instead of crashing the app.
        throw VideoFrameProcessingException(e)
    }

    private fun draw(presentationTimeUs: Long): Bitmap {
        val w = width.toFloat()
        val h = height.toFloat()
        if (firstUs == Long.MIN_VALUE) firstUs = presentationTimeUs
        val positionMs = (presentationTimeUs - firstUs).coerceAtLeast(0L) / 1000
        val data = dataAt(positionMs)
        val timelineMs = timelineMsOf(positionMs)
        val textKey = if (texts.isEmpty()) "" else TextRenderer.key(texts, timelineMs)
        val key = "${width}x$height " + (data?.let { renderer.contentKey(style, it, w, h, effects) } ?: "-") + textKey
        buffers[current]?.let { if (key == lastKey) return it }

        current = 1 - current
        val bitmap = buffers[current]?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { buffers[current] = it }
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        renderer.drawVignette(canvas, w, h, vignette)
        if (data != null) renderer.draw(canvas, w, h, style, data, effects) else renderer.drawBars(canvas, w, h, effects)
        if (texts.isNotEmpty()) TextRenderer.draw(canvas, w, h, texts, timelineMs)
        lastKey = key
        return bitmap
    }
}
