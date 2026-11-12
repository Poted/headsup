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
import app.headsup.core.edit.CutKind
import app.headsup.core.edit.CutTransition

/**
 * A clip's transitions at its cuts ([CutTransition]): its start coming in through [into] (the
 * whole transition [intoMs] long, half of it here), its end going out through [out] ([outMs]):
 * dipped through black or white, zoomed, or flung sideways, with the blur of that movement. On
 * the finished frame, before the dashboard and texts are laid over it; times as [FadeEffect]'s.
 */
@OptIn(UnstableApi::class)
class CutEffect(
    private val timelineDurationMs: Long,
    private val into: CutKind,
    private val intoMs: Long,
    private val out: CutKind,
    private val outMs: Long,
    private val speed: Float,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(useHdr)

    private inner class Program(useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
        private val program = try {
            GlProgram(VERTEX, FRAGMENT)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        private var firstUs = -1L

        init {
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }

        override fun configure(inputWidth: Int, inputHeight: Int) = Size(inputWidth, inputHeight)

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                if (firstUs < 0) firstUs = presentationTimeUs
                val timelineMs = ((presentationTimeUs - firstUs) / 1000 / speed).toLong()
                val look = CutTransition.at(timelineMs, timelineDurationMs, into, intoMs, out, outMs)
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setFloatUniform("uZoom", look.zoom)
                program.setFloatUniform("uShift", look.shift)
                program.setFloatUniform("uBlur", look.blur)
                program.setFloatUniform("uRadial", if (look.radial) 1f else 0f)
                program.setFloatUniform("uDip", look.dip)
                program.setFloatUniform("uDipWhite", if (look.dipWhite) 1f else 0f)
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

        // Zoomed about the middle and moved sideways; blurred along the movement (24 taps,
        // sideways for a whip, outwards from the middle for a zoom); black beyond the picture.
        const val FRAGMENT = """
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform float uZoom;
            uniform float uShift;
            uniform float uBlur;
            uniform float uRadial;
            uniform float uDip;
            uniform float uDipWhite;
            varying vec2 vTexSamplingCoord;
            vec3 at(vec2 uv) {
                if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) return vec3(0.0);
                return texture2D(uTexSampler, uv).rgb;
            }
            void main() {
                vec2 uv = (vTexSamplingCoord - 0.5) / uZoom + 0.5 - vec2(uShift, 0.0);
                vec3 c;
                if (uBlur <= 0.0005) {
                    c = at(uv);
                } else {
                    vec2 dir = uRadial > 0.5 ? (uv - 0.5) : vec2(1.0, 0.0);
                    vec3 acc = vec3(0.0);
                    for (int i = 0; i < 24; i++) {
                        float t = (float(i) + 0.5) / 24.0 - 0.5;
                        acc += at(uv + dir * t * uBlur * (uRadial > 0.5 ? 4.0 : 1.0));
                    }
                    c = acc / 24.0;
                }
                vec3 dip = uDipWhite > 0.5 ? vec3(1.0) : vec3(0.0);
                gl_FragColor = vec4(mix(c, dip, clamp(uDip, 0.0, 1.0)), 1.0);
            }
        """
    }
}
