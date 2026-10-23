package app.headsup.export

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * The dashboard (and vignette) drawn over the finished picture by our own shader, for the 10-bit
 * route: there Media3's overlays expect HDR light, while this blends the bitmap on display colours
 * exactly as the 8-bit export does. The texture is uploaded only when the overlay hands over a
 * new bitmap.
 */
@OptIn(UnstableApi::class)
class DashboardEffect(private val overlay: BitmapOverlay) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(useHdr)

    private inner class Program(useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
        private val program = try {
            GlProgram(VERTEX, FRAGMENT)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        private val texture = IntArray(1)
        private var uploaded: Bitmap? = null

        init {
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            GLES20.glGenTextures(1, texture, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            val size = Size(inputWidth, inputHeight)
            overlay.configure(size)
            return size
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val bitmap = overlay.getBitmap(presentationTimeUs)
                if (bitmap !== uploaded) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                    uploaded = bitmap
                }
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setSamplerTexIdUniform("uOverlay", texture[0], 1)
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                program.delete()
                GLES20.glDeleteTextures(1, texture, 0)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }

    private companion object {
        const val VERTEX = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
            }
        """

        // Bitmaps are premultiplied and stored top row first; texture y grows upwards.
        const val FRAGMENT = """
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform sampler2D uOverlay;
            varying vec2 vTexSamplingCoord;
            void main() {
                vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
                vec4 o = texture2D(uOverlay, vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y));
                gl_FragColor = vec4(src.rgb * (1.0 - o.a) + o.rgb, src.a);
            }
        """
    }
}
