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
import app.headsup.core.photo.LightFx
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * The light effects (Mist, Anamorphic streaks, Star, Prism) for exported video, as the photo export
 * makes them: each frame averaged down on the GPU to a small copy, its light worked out by
 * [LightFx] (the same code as for photos), and laid back over the frame as light adds (screen),
 * drawn up smoothly. Not on the 10-bit route (its frames are not 8-bit to read back).
 */
@OptIn(UnstableApi::class)
class LightFxEffect(private val settings: LightFx.Settings) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(useHdr)

    private inner class Program(useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
        private val down = program(DOWN)
        private val final = program(FINAL)
        private var smallW = SMALL
        private var smallH = SMALL
        private var textures = IntArray(0)
        private var fbo = 0
        private var buffer: ByteBuffer = ByteBuffer.allocateDirect(4)
        private var pixels = IntArray(0)

        private fun program(fragment: String): GlProgram = try {
            GlProgram(VERTEX, fragment).also {
                it.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            val frame = inputWidth.toFloat() / inputHeight.coerceAtLeast(1)
            if (frame >= 1f) {
                smallW = SMALL
                smallH = (SMALL / frame).roundToInt().coerceAtLeast(4)
            } else {
                smallH = SMALL
                smallW = (SMALL * frame).roundToInt().coerceAtLeast(4)
            }
            try {
                release8()
                // 0: the frame small (read back); 1: its light (written from here).
                textures = IntArray(2) { GlUtil.createTexture(smallW, smallH, false) }
                fbo = GlUtil.createFboForTexture(textures[0])
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
            buffer = ByteBuffer.allocateDirect(smallW * smallH * 4).order(ByteOrder.nativeOrder())
            pixels = IntArray(smallW * smallH)
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val bound = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, it, 0) }[0]
                val viewport = IntArray(4).also { GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, it, 0) }

                // 1. The frame, averaged down.
                GlUtil.focusFramebufferUsingCurrentContext(fbo, smallW, smallH)
                down.use()
                down.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                down.setFloatsUniform("uSmall", floatArrayOf(smallW.toFloat(), smallH.toFloat()))
                down.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                // 2. Read back, its light worked out, and given back as a texture.
                buffer.rewind()
                GLES20.glReadPixels(0, 0, smallW, smallH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
                buffer.rewind()
                for (i in pixels.indices) {
                    val r = buffer.get().toInt() and 0xFF
                    val g = buffer.get().toInt() and 0xFF
                    val b = buffer.get().toInt() and 0xFF
                    buffer.get()
                    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                val light = LightFx.light(pixels, smallW, smallH, settings, flipY = true)
                buffer.rewind()
                for (c in light) {
                    buffer.put(((c shr 16) and 0xFF).toByte())
                    buffer.put(((c shr 8) and 0xFF).toByte())
                    buffer.put((c and 0xFF).toByte())
                    buffer.put(0xFF.toByte())
                }
                buffer.rewind()
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[1])
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, smallW, smallH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)

                // 3. The frame with the light over it.
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bound)
                GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
                final.use()
                final.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                final.setSamplerTexIdUniform("uCloud", textures[1], 1)
                final.setFloatsUniform("uSmall", floatArrayOf(smallW.toFloat(), smallH.toFloat()))
                final.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        private fun release8() {
            if (fbo != 0) GlUtil.deleteFbo(fbo)
            for (t in textures) GlUtil.deleteTexture(t)
            fbo = 0
            textures = IntArray(0)
        }

        override fun release() {
            super.release()
            try {
                release8()
                down.delete()
                final.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }

    private companion object {
        /** The small copy's long side: the effects are soft, a thousand pixels is plenty. */
        const val SMALL = 960

        const val VERTEX = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
            }
        """

        // Each small pixel: the average of its cell of the frame (4 × 4 filtered taps).
        val DOWN = """
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform vec2 uSmall;
            varying vec2 vTexSamplingCoord;
            void main() {
                vec2 cell = 1.0 / uSmall;
                vec2 corner = vTexSamplingCoord - cell * 0.5;
                vec3 acc = vec3(0.0);
                for (int j = 0; j < 4; j++) {
                    for (int i = 0; i < 4; i++) {
                        acc += texture2D(uTexSampler, clamp(corner + (vec2(float(i), float(j)) + 0.5) / 4.0 * cell, vec2(0.0), vec2(1.0))).rgb;
                    }
                }
                gl_FragColor = vec4(acc / 16.0, 1.0);
            }
        """

        // The frame with the light over it (screen), the light drawn up with a cubic B-spline.
        val FINAL = """
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform sampler2D uCloud;
            uniform vec2 uSmall;
            varying vec2 vTexSamplingCoord;

            vec3 cloud(vec2 uv) {
                vec2 p = uv * uSmall - 0.5;
                vec2 f = fract(p);
                vec2 i = p - f;
                vec2 f2 = f * f;
                vec2 f3 = f2 * f;
                vec2 w0 = (1.0 - 3.0 * f + 3.0 * f2 - f3) / 6.0;
                vec2 w1 = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0;
                vec2 w2 = (1.0 + 3.0 * f + 3.0 * f2 - 3.0 * f3) / 6.0;
                vec2 w3 = f3 / 6.0;
                vec2 s0 = w0 + w1;
                vec2 s1 = w2 + w3;
                // Between texel centres (k + 0.5) / size, weighted so one filtered read does two taps.
                vec2 t0 = clamp((i - 0.5 + w1 / s0) / uSmall, vec2(0.0), vec2(1.0));
                vec2 t1 = clamp((i + 1.5 + w3 / s1) / uSmall, vec2(0.0), vec2(1.0));
                vec3 a = texture2D(uCloud, vec2(t0.x, t0.y)).rgb;
                vec3 b = texture2D(uCloud, vec2(t1.x, t0.y)).rgb;
                vec3 c = texture2D(uCloud, vec2(t0.x, t1.y)).rgb;
                vec3 d = texture2D(uCloud, vec2(t1.x, t1.y)).rgb;
                return mix(mix(a, b, s1.x / (s0.x + s1.x)), mix(c, d, s1.x / (s0.x + s1.x)), s1.y / (s0.y + s1.y));
            }

            void main() {
                vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
                vec3 g = cloud(vTexSamplingCoord);
                gl_FragColor = vec4(1.0 - (1.0 - src.rgb) * (1.0 - clamp(g, 0.0, 1.0)), src.a);
            }
        """
    }
}
