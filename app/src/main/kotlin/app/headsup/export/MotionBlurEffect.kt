package app.headsup.export

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Motion blur for sped-up footage: each frame of the file is blended into a running average, so
 * the [frames] frames of the file that end up as one frame of the film run together as they
 * would with an open shutter, instead of the film jumping from one sharp frame to the next (a
 * rush at 24x skips 23 frames out of 24 and looks like a slideshow). Put before the speed change
 * and the frame dropping, where every frame of the file still passes. An exponential average with
 * the centre of mass of a [frames]-wide box: about the same blur, one texture of memory.
 */
@OptIn(UnstableApi::class)
class MotionBlurEffect(private val frames: Float) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(useHdr)

    private inner class Program(private val useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
        private val blend = program(BLEND)
        private val copy = program(COPY)
        private var width = 1
        private var height = 1
        // Two accumulations, each frame blended from one into the other.
        private var textures = IntArray(0)
        private var fbos = IntArray(0)
        private var current = 0
        private var started = false
        private val weight = (2f / (frames.coerceAtLeast(1f) + 1f)).coerceIn(0.03f, 1f)

        private fun program(fragment: String): GlProgram = try {
            GlProgram(VERTEX, fragment).also {
                it.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            width = inputWidth
            height = inputHeight
            try {
                release2()
                textures = IntArray(2) { GlUtil.createTexture(width, height, useHdr) }
                fbos = IntArray(2) { GlUtil.createFboForTexture(textures[it]) }
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
            started = false
            return Size(width, height)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val bound = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, it, 0) }[0]
                val viewport = IntArray(4).also { GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, it, 0) }
                val next = 1 - current
                // The running average: this frame mixed into what came before (the first taken as it is).
                GlUtil.focusFramebufferUsingCurrentContext(fbos[next], width, height)
                blend.use()
                blend.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                blend.setSamplerTexIdUniform("uAccum", textures[current], 1)
                blend.setFloatUniform("uWeight", if (started) weight else 1f)
                blend.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                started = true
                current = next
                // Out: the average.
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bound)
                GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
                copy.use()
                copy.setSamplerTexIdUniform("uTexSampler", textures[current], 0)
                copy.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        private fun release2() {
            for (f in fbos) GlUtil.deleteFbo(f)
            for (t in textures) GlUtil.deleteTexture(t)
            fbos = IntArray(0)
            textures = IntArray(0)
        }

        override fun release() {
            super.release()
            try {
                release2()
                blend.delete()
                copy.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }

    companion object {
        /** File frames per film frame from which the blur is worth it. */
        const val FROM_FRAMES = 1.8f

        private const val VERTEX = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
            }
        """

        private const val BLEND = """
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform sampler2D uAccum;
            uniform float uWeight;
            varying vec2 vTexSamplingCoord;
            void main() {
                vec4 now = texture2D(uTexSampler, vTexSamplingCoord);
                vec4 before = texture2D(uAccum, vTexSamplingCoord);
                gl_FragColor = mix(before, now, uWeight);
            }
        """

        private const val COPY = """
            precision highp float;
            uniform sampler2D uTexSampler;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord);
            }
        """
    }
}
