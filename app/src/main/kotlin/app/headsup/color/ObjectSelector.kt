package app.headsup.color

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import java.nio.ByteOrder

/**
 * The object at a tapped point of a picture, found by MediaPipe's Interactive Segmenter (the
 * "magic touch" model, run on the phone): how sure it is, 0..1, that each pixel is that object.
 */
object ObjectSelector {
    private var segmenter: InteractiveSegmenter? = null

    private fun segmenter(context: Context): InteractiveSegmenter = segmenter ?: InteractiveSegmenter.createFromOptions(
        context.applicationContext,
        InteractiveSegmenter.InteractiveSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("magic_touch.tflite").build())
            .setOutputCategoryMask(false)
            .setOutputConfidenceMasks(true)
            .build(),
    ).also { segmenter = it }

    /** The object at ([x], [y]) (fractions of [picture]), as coverage the size of the picture, row by row; null when none was found. */
    @Synchronized
    fun select(context: Context, picture: Bitmap, x: Float, y: Float): FloatArray? {
        val argb = if (picture.config == Bitmap.Config.ARGB_8888) picture else picture.copy(Bitmap.Config.ARGB_8888, false)
        val roi = InteractiveSegmenter.RegionOfInterest.create(NormalizedKeypoint.create(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)))
        val result = segmenter(context).segment(BitmapImageBuilder(argb).build(), roi)
        val mask = result.confidenceMasks().orElse(null)?.firstOrNull() ?: return null
        if (mask.width != argb.width || mask.height != argb.height) return null
        val floats = ByteBufferExtractor.extract(mask).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val out = FloatArray(mask.width * mask.height)
        floats.get(out, 0, minOf(out.size, floats.remaining()))
        return out.takeIf { values -> values.any { it > 0.5f } }
    }
}
