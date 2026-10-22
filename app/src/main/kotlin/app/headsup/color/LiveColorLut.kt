package app.headsup.color

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ColorLut
import app.headsup.core.color.ColorGrade
import app.headsup.core.color.LutBaker
import java.util.concurrent.atomic.AtomicReference

/**
 * Colour lookup for the preview player whose table can be swapped while the video plays.
 * Media3's `SingleColorLut` is immutable, so changing it means re-preparing the player, which
 * blanks the picture; here the new table is simply uploaded before the next frame is drawn.
 *
 * The table always has [LutBaker.DEFAULT_SIZE] entries per side (an identity table when colours
 * are untouched), so its length never changes between frames.
 */
@OptIn(UnstableApi::class)
class LiveColorLut : ColorLut {
    private val pending = AtomicReference<Bitmap?>(identity)
    private var current: Bitmap = identity
    private var textureId = C.INDEX_UNSET

    /** Called from the UI thread; takes effect on the next rendered frame. */
    fun update(cube: Array<Array<IntArray>>?) {
        pending.set(cube?.let(::toBitmap) ?: identity)
    }

    override fun getLutTextureId(presentationTimeUs: Long): Int {
        pending.getAndSet(null)?.let { bitmap ->
            if (textureId != C.INDEX_UNSET) GlUtil.deleteTexture(textureId)
            textureId = GlUtil.createTexture(bitmap)
            current = bitmap
        }
        return textureId
    }

    override fun getLength(presentationTimeUs: Long): Int = LutBaker.DEFAULT_SIZE

    override fun release() {
        if (textureId != C.INDEX_UNSET) GlUtil.deleteTexture(textureId)
        textureId = C.INDEX_UNSET
        // The player may build a new pipeline (e.g. for the next clip); upload again then.
        pending.compareAndSet(null, current)
    }

    companion object {
        private val identity: Bitmap by lazy { toBitmap(LutBaker.bake(null, ColorGrade())) }

        /** Same layout as Media3's SingleColorLut: N wide, N² high, row = g + N·r, column = b. */
        fun toBitmap(cube: Array<Array<IntArray>>): Bitmap {
            val n = cube.size
            val pixels = IntArray(n * n * n)
            for (r in 0 until n) for (g in 0 until n) for (b in 0 until n) pixels[b + n * (g + n * r)] = cube[r][g][b]
            return Bitmap.createBitmap(pixels, n, n * n, Bitmap.Config.ARGB_8888)
        }
    }
}
