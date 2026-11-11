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
 * The lens (colour fringes and soft corners), glow, halation and film grain for exported video,
 * applied after the colour table. The maths matches the
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
                program.setFloatUniform("uHalation", settings.halation)
                program.setFloatUniform("uGrainColour", settings.grainColour)
                program.setFloatUniform("uGlowSpread", settings.glowSpread)
                program.setFloatsUniform("uGlowTint", app.headsup.render.EffectLook.glowTint(settings.glowWarmth))
                program.setFloatUniform("uLensCA", settings.lensAberration)
                program.setFloatUniform("uLensSoft", settings.lensSoftness)
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

        // Lens: colour fringes (red and blue pushed apart) and softness, growing towards the corners.
        // Glow: thresholded highlights from 18 taps on two rings, screen-blended on top.
        // Grain: smooth noise in two sizes, new each frame, strongest in the midtones.
        // All as the preview shader (GradePreviewShader).
        const val FRAGMENT = """
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform vec2 uSize;
            uniform float uTime;
            uniform float uGrain;
            uniform float uGrainSize;
            uniform float uGlow;
            uniform float uThreshold;
            uniform float uHalation;
            uniform float uGrainColour;
            uniform float uGlowSpread;
            uniform vec3 uGlowTint;
            uniform float uLensCA;
            uniform float uLensSoft;
            varying vec2 vTexSamplingCoord;

            float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

            float smooth01(float e0, float e1, float x) {
                float t = clamp((x - e0) / max(e1 - e0, 0.0001), 0.0, 1.0);
                return t * t * (3.0 - 2.0 * t);
            }

            vec3 bright(vec2 uv) {
                vec3 c = texture2D(uTexSampler, uv).rgb;
                float k = max(luma(c) - uThreshold, 0.0) / max(1.0 - uThreshold, 0.01);
                return c * k;
            }

            float hash21(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }
            float vnoise(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                return mix(mix(hash21(i), hash21(i + vec2(1.0, 0.0)), f.x), mix(hash21(i + vec2(0.0, 1.0)), hash21(i + vec2(1.0, 1.0)), f.x), f.y) - 0.5;
            }
            float grainAt(vec2 p, float cell, float seed) {
                vec2 o = vec2(seed * 7.31, seed * 3.17);
                return (vnoise(p / cell + o) * 0.75 + vnoise(p / (cell * 0.5) + o * 1.93 + 11.0) * 0.45) * 1.9;
            }

            vec3 fringed(vec2 uv, vec2 shift) {
                if (uLensCA <= 0.0) return texture2D(uTexSampler, uv).rgb;
                return vec3(texture2D(uTexSampler, uv - shift).r, texture2D(uTexSampler, uv).g, texture2D(uTexSampler, uv + shift).b);
            }

            vec3 lensed(vec2 uv, float shortSide) {
                vec2 mid = uSize * 0.5;
                vec2 d = (uv - 0.5) * uSize / length(mid);
                float r2 = dot(d, d);
                vec2 shift = d * r2 * uLensCA * shortSide * 0.005 / uSize;
                vec3 col = fringed(uv, shift);
                float rad = uLensSoft * shortSide * 0.006 * smooth01(0.15, 1.0, r2);
                if (rad > 0.3) {
                    vec3 acc = col;
                    for (int i = 0; i < 8; i++) {
                        float a = float(i) * 0.7854 + 0.39;
                        acc += fringed(uv + vec2(cos(a), sin(a)) * rad / uSize, shift);
                    }
                    for (int i = 0; i < 4; i++) {
                        float a = float(i) * 1.5708;
                        acc += fringed(uv + vec2(cos(a), sin(a)) * rad * 0.5 / uSize, shift);
                    }
                    col = acc / 13.0;
                }
                return col;
            }

            void main() {
                vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
                float shortSide = min(uSize.x, uSize.y);
                vec3 c = (uLensCA > 0.0 || uLensSoft > 0.0) ? lensed(vTexSamplingCoord, shortSide) : src.rgb;
                vec2 px = vTexSamplingCoord * uSize;
                if (uGlow > 0.0 || uHalation > 0.0) {
                    float r = shortSide * 0.012 * uGlowSpread;
                    // Over the whole disc (a golden-angle spiral), turned differently at each pixel, as the
                    // preview: two fixed rings repeated a small bright thing as copies a ring away.
                    float turn = fract(52.9829189 * fract(dot(px, vec2(0.06711056, 0.00583715)))) * 6.2832;
                    vec3 acc = vec3(0.0);
                    for (int i = 0; i < 28; i++) {
                        float a = float(i) * 2.39996 + turn;
                        float d = (0.25 + 1.95 * sqrt((float(i) + 0.5) / 28.0)) * r;
                        acc += bright(vTexSamplingCoord + vec2(cos(a), sin(a)) * d / uSize);
                    }
                    // Halation: film's red-orange bleed around highlights, from the same blurred taps.
                    vec3 g = clamp(acc / 28.0 * uGlow * 1.5 * uGlowTint + dot(acc / 28.0, vec3(0.33)) * uHalation * 2.0 * vec3(1.0, 0.3, 0.1), 0.0, 1.0);
                    c = 1.0 - (1.0 - c) * (1.0 - g);
                }
                if (uGrain > 0.0) {
                    float cell = max(0.8, shortSide / 1000.0 * 1.5 * uGrainSize);
                    float seed = mod(uTime, 61.0);
                    float l = luma(c);
                    float resp = 0.3 + 2.8 * l * (1.0 - l);
                    float m = grainAt(px, cell, seed);
                    vec3 n = vec3(m);
                    if (uGrainColour > 0.0) n = mix(n, vec3(m, grainAt(px, cell, seed + 17.0), grainAt(px, cell, seed + 33.0)), uGrainColour);
                    c = clamp(c + n * uGrain * 0.16 * resp, 0.0, 1.0);
                }
                gl_FragColor = vec4(c, src.a);
            }
        """
    }
}
