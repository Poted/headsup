package app.headsup.export

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import app.headsup.color.LiveColorLut
import app.headsup.core.color.ColourMask
import app.headsup.core.color.ColourTransition

/**
 * Colour for a clip with masks or a colour transition: the frame goes through [base], then each
 * mask's table is blended in by [ColourMask.weight], and during the first [transitionMs] the
 * previous clip's table [from] fades out ([ColourTransition.fromWeight]). Replaces Media3's single
 * colour table for such clips (and every clip of a 10-bit export); the maths matches the preview shader.
 */
@OptIn(UnstableApi::class)
class GradeEffect(
    private val base: Array<Array<IntArray>>,
    private val masks: List<ColourMask>,
    private val maskTables: List<Array<Array<IntArray>>>,
    private val from: Array<Array<IntArray>>?,
    private val transitionMs: Long,
    /** Input arrives through [HdrRoute]: how to get back to the values the colour tables expect. */
    private val hdrInput: HdrInput? = null,
    /** Where the exported part starts in the file, for masks that move ([ColourMask.keys]). */
    private val fileStartMs: Long = 0L,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(useHdr)

    private inner class Program(useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
        private val program: GlProgram = try {
            GlProgram(VERTEX, FRAGMENT)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        // Base, one per mask slot, previous clip; unused slots repeat the base table.
        private val textures = IntArray(2 + ColourMask.MAX_PER_CLIP)
        private var width = 1
        private var height = 1
        private var firstUs = -1L

        init {
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            GLES20.glGenTextures(textures.size, textures, 0)
            upload(textures[0], base)
            for (i in 0 until ColourMask.MAX_PER_CLIP) upload(textures[1 + i], maskTables.getOrNull(i) ?: base)
            upload(textures.last(), from ?: base)
        }

        private fun upload(texture: Int, table: Array<Array<IntArray>>) {
            val bitmap = LiveColorLut.toBitmap(table)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            width = inputWidth
            height = inputHeight
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                // Time counts from the first frame, however Media3 timestamps a clipped item.
                if (firstUs < 0) firstUs = presentationTimeUs
                val positionMs = (presentationTimeUs - firstUs) / 1000
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setSamplerTexIdUniform("uLut", textures[0], 1)
                for (i in 0 until ColourMask.MAX_PER_CLIP) program.setSamplerTexIdUniform("uLutM$i", textures[1 + i], 2 + i)
                program.setSamplerTexIdUniform("uLutFrom", textures.last(), 2 + ColourMask.MAX_PER_CLIP)
                program.setFloatUniform("uN", base.size.toFloat())
                program.setFloatsUniform("uSize", floatArrayOf(width.toFloat(), height.toFloat()))
                program.setFloatUniform("uMaskCount", masks.size.coerceAtMost(ColourMask.MAX_PER_CLIP).toFloat())
                for (i in 0 until ColourMask.MAX_PER_CLIP) {
                    val (a, b) = masks.getOrNull(i)?.at(fileStartMs + positionMs)?.shaderParams() ?: (FloatArray(4) to FloatArray(4))
                    program.setFloatsUniform("uM${i}a", a)
                    program.setFloatsUniform("uM${i}b", b)
                }
                val fromWeight = if (from != null) ColourTransition.fromWeight(positionMs, transitionMs) else 0f
                program.setFloatUniform("uFromWeight", fromWeight)
                program.setIntUniform("uHdrIn", if (hdrInput == HdrInput.HLG) 2 else if (hdrInput != null) 1 else 0)
                program.setFloatsUniform("uHlgFix", if (hdrInput == HdrInput.RELABELLED_BT709) HdrRoute.HLG_FIX else HdrRoute.IDENTITY)
                program.setFloatUniform("uSdrWhite", HdrRoute.SDR_WHITE)
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
                GLES20.glDeleteTextures(textures.size, textures, 0)
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

        // Tables are N wide and N² high (x = b, y = g + N·r), sampled without filtering and
        // interpolated here. Texture y grows upwards, masks measure from the top.
        val FRAGMENT = """
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform sampler2D uLut;
            ${List(ColourMask.MAX_PER_CLIP) { "uniform sampler2D uLutM$it;\nuniform vec4 uM${it}a;\nuniform vec4 uM${it}b;" }.joinToString("\n")}
            uniform sampler2D uLutFrom;
            uniform float uN;
            uniform vec2 uSize;
            uniform float uMaskCount;
            uniform float uFromWeight;
            uniform int uHdrIn;
            uniform mat3 uHlgFix;
            uniform float uSdrWhite;
            const mat3 BT2020_TO_BT709 = mat3(
                1.6605, -0.1246, -0.0182,
                -0.5876, 1.1329, -0.1006,
                -0.0728, -0.0083, 1.1187);
            varying vec2 vTexSamplingCoord;

            vec3 cell(sampler2D t, float r, float g, float b) {
                return texture2D(t, vec2((b + 0.5) / uN, (g + uN * r + 0.5) / (uN * uN))).rgb;
            }

            vec3 grade(sampler2D t, vec3 c) {
                vec3 v = clamp(c, 0.0, 1.0) * (uN - 1.0);
                vec3 i0 = floor(v);
                vec3 i1 = min(i0 + 1.0, uN - 1.0);
                vec3 f = v - i0;
                vec3 c00 = mix(cell(t, i0.r, i0.g, i0.b), cell(t, i1.r, i0.g, i0.b), f.r);
                vec3 c10 = mix(cell(t, i0.r, i1.g, i0.b), cell(t, i1.r, i1.g, i0.b), f.r);
                vec3 c01 = mix(cell(t, i0.r, i0.g, i1.b), cell(t, i1.r, i0.g, i1.b), f.r);
                vec3 c11 = mix(cell(t, i0.r, i1.g, i1.b), cell(t, i1.r, i1.g, i1.b), f.r);
                return mix(mix(c00, c10, f.g), mix(c01, c11, f.g), f.b);
            }

            float smooth01(float e0, float e1, float x) {
                float t = clamp((x - e0) / max(e1 - e0, 0.0001), 0.0, 1.0);
                return t * t * (3.0 - 2.0 * t);
            }

            // ColourMask.weight: a = (kind, centerX, centerY, angle), b = (sizeX, sizeY, softness, inverted).
            float maskWeight(vec4 a, vec4 b, vec2 px) {
                vec2 d = vec2(px.x - a.y * uSize.x, px.y - a.z * uSize.y) / uSize.y;
                float s = sin(a.w);
                float c = cos(a.w);
                float w;
                if (a.x < 0.5) {
                    w = 1.0 - smooth01(-b.z * 0.5, b.z * 0.5, -d.x * s + d.y * c);
                } else {
                    float u = (d.x * c + d.y * s) / max(b.x * 0.5, 0.001);
                    float v = (-d.x * s + d.y * c) / max(b.y * 0.5, 0.001);
                    w = 1.0 - smooth01(1.0 - b.z * 0.5, 1.0 + b.z * 0.5, sqrt(u * u + v * v));
                }
                return b.w > 0.5 ? 1.0 - w : w;
            }

            // Inverse of Media3's HLG EOTF, back to the signal values.
            float hlgOetf(float x) {
                return x <= 1.0 / 12.0 ? sqrt(3.0 * x) : 0.17883277 * log(12.0 * x - 0.28466892) + 0.55991073;
            }

            void main() {
                vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
                vec3 srcRgb = src.rgb;
                if (uHdrIn == 1) {
                    // Relabelled SDR/log: back to the file's own code values.
                    vec3 lin = max(src.rgb, 0.0);
                    srcRgb = clamp(uHlgFix * vec3(hlgOetf(lin.r), hlgOetf(lin.g), hlgOetf(lin.b)), 0.0, 1.0);
                } else if (uHdrIn == 2) {
                    // Real HLG: SDR white (75 % HLG) to 1.0, BT.709, highlights rolled off, display gamma.
                    vec3 x = max(BT2020_TO_BT709 * (src.rgb / uSdrWhite), 0.0);
                    vec3 knee = vec3(0.7);
                    vec3 rolled = mix(x, knee + 0.3 * (1.0 - exp(-(x - knee) / 0.3)), step(knee, x));
                    srcRgb = pow(clamp(rolled, 0.0, 1.0), vec3(1.0 / 2.4));
                }
                vec2 px = vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y) * uSize;
                vec3 c = grade(uLut, srcRgb);
                ${List(ColourMask.MAX_PER_CLIP) { i -> """
                if (uMaskCount > $i.5) {
                    float w$i = maskWeight(uM${i}a, uM${i}b, px);
                    if (w$i > 0.0) c = mix(c, grade(uLutM$i, srcRgb), w$i);
                }""" }.joinToString("")}
                if (uFromWeight > 0.0) c = mix(c, grade(uLutFrom, srcRgb), uFromWeight);
                gl_FragColor = vec4(c, src.a);
            }
        """
    }
}
