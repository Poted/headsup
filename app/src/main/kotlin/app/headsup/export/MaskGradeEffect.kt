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
import app.headsup.core.color.GradientMask

/**
 * Colour for a clip with a graduated mask: the frame goes through [cube] outside the mask and
 * [maskCube] inside it, blended by [GradientMask.weight]. Replaces Media3's single colour table
 * for such clips; the maths matches the preview shader.
 */
@OptIn(UnstableApi::class)
class MaskGradeEffect(
    private val cube: Array<Array<IntArray>>,
    private val maskCube: Array<Array<IntArray>>,
    private val mask: GradientMask,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(useHdr)

    private inner class Program(useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
        private val program: GlProgram = try {
            GlProgram(VERTEX, FRAGMENT)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        private val tables = IntArray(2)
        private var width = 1
        private var height = 1

        init {
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            GLES20.glGenTextures(2, tables, 0)
            upload(tables[0], cube)
            upload(tables[1], maskCube)
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
                val angle = Math.toRadians(mask.angleDegrees.toDouble())
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setSamplerTexIdUniform("uLut", tables[0], 1)
                program.setSamplerTexIdUniform("uLut2", tables[1], 2)
                program.setFloatUniform("uN", cube.size.toFloat())
                program.setFloatsUniform("uSize", floatArrayOf(width.toFloat(), height.toFloat()))
                program.setFloatsUniform(
                    "uMask",
                    floatArrayOf(mask.position, mask.softness, kotlin.math.sin(angle).toFloat(), kotlin.math.cos(angle).toFloat()),
                )
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
                GLES20.glDeleteTextures(2, tables, 0)
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
        // interpolated here. Texture y grows upwards, the mask measures from the top.
        const val FRAGMENT = """
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform sampler2D uLut;
            uniform sampler2D uLut2;
            uniform float uN;
            uniform vec2 uSize;
            uniform vec4 uMask;
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

            void main() {
                vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
                vec2 px = vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y) * uSize;
                vec2 p = vec2((px.x - uSize.x * 0.5) / uSize.y, px.y / uSize.y - uMask.x);
                float d = -p.x * uMask.z + p.y * uMask.w;
                float halfWidth = max(uMask.y * 0.5, 0.001);
                float t = clamp((d + halfWidth) / (2.0 * halfWidth), 0.0, 1.0);
                float w = 1.0 - t * t * (3.0 - 2.0 * t);
                vec3 c = grade(uLut, src.rgb);
                if (w > 0.0) c = mix(c, grade(uLut2, src.rgb), w);
                gl_FragColor = vec4(c, src.a);
            }
        """
    }
}
