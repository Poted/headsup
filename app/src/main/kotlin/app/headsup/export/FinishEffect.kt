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
import app.headsup.render.EffectSettings

/**
 * Glow and film grain for exported video, applied after the colour table. The maths matches the
 * preview shader (`GradePreviewShader`) so both look alike.
 */
@OptIn(UnstableApi::class)
class FinishEffect(private val settings: EffectSettings) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(settings, useHdr)

    private class Program(private val settings: EffectSettings, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
        private val program: GlProgram = try {
            GlProgram(VERTEX, FRAGMENT)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        private var width = 1
        private var height = 1

        init {
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            width = inputWidth
            height = inputHeight
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setFloatsUniform("uSize", floatArrayOf(width.toFloat(), height.toFloat()))
                program.setFloatUniform("uTime", (presentationTimeUs / 33_333L % 10_000L).toFloat())
                program.setFloatUniform("uGrain", settings.grain)
                program.setFloatUniform("uGrainSize", settings.grainSize)
                program.setFloatUniform("uGlow", settings.glow)
                program.setFloatUniform("uThreshold", settings.glowThreshold)
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

        // Glow: thresholded highlights from 18 taps on two rings, screen-blended on top.
        // Grain: per-cell hash noise that changes every frame, weaker in highlights.
        const val FRAGMENT = """
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform vec2 uSize;
            uniform float uTime;
            uniform float uGrain;
            uniform float uGrainSize;
            uniform float uGlow;
            uniform float uThreshold;
            varying vec2 vTexSamplingCoord;

            float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

            vec3 bright(vec2 uv) {
                vec3 c = texture2D(uTexSampler, uv).rgb;
                float k = max(luma(c) - uThreshold, 0.0) / max(1.0 - uThreshold, 0.01);
                return c * k;
            }

            void main() {
                vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
                vec3 c = src.rgb;
                float shortSide = min(uSize.x, uSize.y);
                if (uGlow > 0.0) {
                    float r = shortSide * 0.012;
                    vec3 acc = vec3(0.0);
                    for (int i = 0; i < 12; i++) {
                        float a = float(i) * 0.5236;
                        acc += bright(vTexSamplingCoord + vec2(cos(a), sin(a)) * r / uSize);
                    }
                    for (int i = 0; i < 6; i++) {
                        float a = float(i) * 1.0472 + 0.26;
                        acc += bright(vTexSamplingCoord + vec2(cos(a), sin(a)) * 2.2 * r / uSize);
                    }
                    vec3 g = clamp(acc / 18.0 * uGlow * 1.5, 0.0, 1.0);
                    c = 1.0 - (1.0 - c) * (1.0 - g);
                }
                if (uGrain > 0.0) {
                    float cell = max(1.0, shortSide / 1000.0 * 1.5 * uGrainSize);
                    vec2 p = floor(gl_FragCoord.xy / cell);
                    float n = fract(sin(dot(p + vec2(uTime * 37.0, uTime * 17.0), vec2(12.9898, 78.233))) * 43758.5453) - 0.5;
                    c += n * uGrain * 0.25 * (1.0 - 0.6 * luma(c));
                }
                gl_FragColor = vec4(c, src.a);
            }
        """
    }
}
