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
import app.headsup.color.BrushAtlas
import app.headsup.color.LiveColorLut
import app.headsup.core.color.ColourMask
import app.headsup.core.color.ColourTransition
import app.headsup.core.color.Presence

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
    /** Texture, clarity and dehaze, drawn on the frame before its colour tables. */
    private val presence: Presence = Presence.NONE,
    /** Perspective correction ([app.headsup.core.frame.Perspective.sourceOf]), row by row; null for none. */
    private val warp: DoubleArray? = null,
    /** How far into the clip this part starts, for the blend from the previous clip (a ramped clip's piece). */
    private val timeOffsetMs: Long = 0L,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(context.applicationContext, useHdr)

    private inner class Program(private val context: Context, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
        private val program: GlProgram = try {
            GlProgram(VERTEX, FRAGMENT)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        // Base, one per mask slot, previous clip; unused slots repeat the base table.
        private val textures = IntArray(2 + ColourMask.MAX_PER_CLIP)
        // The painted masks ([BrushAtlas]), drawn when the frame size is known.
        private val brushTexture = IntArray(1)
        private var brushSlot = floatArrayOf(1f, 1f)
        private var brushSlots = 1f
        private var width = 1
        private var height = 1
        private var firstUs = -1L
        private val changing = BrushAtlas.changesOverTime(masks)

        init {
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            GLES20.glGenTextures(textures.size, textures, 0)
            upload(textures[0], base)
            for (i in 0 until ColourMask.MAX_PER_CLIP) upload(textures[1 + i], maskTables.getOrNull(i) ?: base)
            upload(textures.last(), from ?: base)
            GLES20.glGenTextures(1, brushTexture, 0)
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
            uploadBrush(fileStartMs)
            return Size(inputWidth, inputHeight)
        }

        /** Masks tracked with AI: the moment the brush atlas was last drawn for (file time), or -1. */
        private var brushMs = -1L

        private fun uploadBrush(timeMs: Long) {
            brushMs = timeMs
            val atlas = BrushAtlas.build(context, masks, width.toFloat() / height.coerceAtLeast(1), timeMs)
            val bitmap = atlas ?: BrushAtlas.EMPTY
            brushSlots = if (atlas == null) 1f else ColourMask.MAX_PER_CLIP.toFloat()
            brushSlot = floatArrayOf(bitmap.width / brushSlots, bitmap.height.toFloat())
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, brushTexture[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            atlas?.recycle()
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                // Time counts from the first frame, however Media3 timestamps a clipped item.
                if (firstUs < 0) firstUs = presentationTimeUs
                val positionMs = (presentationTimeUs - firstUs) / 1000
                // A mask tracked with AI has its own map for each moment.
                if (changing && fileStartMs + positionMs != brushMs) uploadBrush(fileStartMs + positionMs)
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setSamplerTexIdUniform("uLut", textures[0], 1)
                for (i in 0 until ColourMask.MAX_PER_CLIP) program.setSamplerTexIdUniform("uLutM$i", textures[1 + i], 2 + i)
                program.setSamplerTexIdUniform("uLutFrom", textures.last(), 2 + ColourMask.MAX_PER_CLIP)
                program.setSamplerTexIdUniform("uBrush", brushTexture[0], 3 + ColourMask.MAX_PER_CLIP)
                program.setFloatsUniform("uBrushSlot", brushSlot)
                program.setFloatUniform("uBrushSlots", brushSlots)
                program.setFloatUniform("uN", base.size.toFloat())
                program.setFloatsUniform("uSize", floatArrayOf(width.toFloat(), height.toFloat()))
                program.setFloatUniform("uMaskCount", masks.size.coerceAtMost(ColourMask.MAX_PER_CLIP).toFloat())
                for (i in 0 until ColourMask.MAX_PER_CLIP) {
                    val (a, b) = masks.getOrNull(i)?.at(fileStartMs + positionMs)?.shaderParams() ?: (FloatArray(4) to FloatArray(4))
                    program.setFloatsUniform("uM${i}a", a)
                    program.setFloatsUniform("uM${i}b", b)
                    val p = masks.getOrNull(i)?.grade?.presence ?: Presence.NONE
                    program.setFloatsUniform("uMp$i", floatArrayOf(p.texture, p.clarity, p.dehaze))
                    val g = masks.getOrNull(i)?.grade
                    program.setFloatsUniform("uMg$i", floatArrayOf(g?.glow ?: 0f, g?.halation ?: 0f))
                }
                val fromWeight = if (from != null) ColourTransition.fromWeight(positionMs + timeOffsetMs, transitionMs) else 0f
                program.setFloatUniform("uFromWeight", fromWeight)
                program.setFloatUniform("uMaskGlow", if (masks.take(ColourMask.MAX_PER_CLIP).any { it.grade.glow > 0f || it.grade.halation > 0f }) 1f else 0f)
                program.setIntUniform("uHdrIn", if (hdrInput == HdrInput.HLG) 2 else if (hdrInput != null) 1 else 0)
                program.setFloatsUniform("uHlgFix", if (hdrInput == HdrInput.RELABELLED_BT709) HdrRoute.HLG_FIX else HdrRoute.IDENTITY)
                program.setFloatUniform("uSdrWhite", HdrRoute.SDR_WHITE)
                program.setFloatsUniform("uPresence", floatArrayOf(presence.texture, presence.clarity, presence.dehaze))
                program.setFloatUniform("uHasWarp", if (warp != null) 1f else 0f)
                val m = warp ?: doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
                // Given column by column, as GLSL reads a mat3.
                program.setFloatsUniform("uWarp", floatArrayOf(m[0].toFloat(), m[3].toFloat(), m[6].toFloat(), m[1].toFloat(), m[4].toFloat(), m[7].toFloat(), m[2].toFloat(), m[5].toFloat(), m[8].toFloat()))
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
                GLES20.glDeleteTextures(1, brushTexture, 0)
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
            uniform sampler2D uBrush;
            uniform vec2 uBrushSlot;
            uniform float uBrushSlots;
            uniform float uN;
            uniform vec2 uSize;
            uniform float uMaskCount;
            uniform float uFromWeight;
            uniform int uHdrIn;
            uniform mat3 uHlgFix;
            uniform float uSdrWhite;
            uniform vec3 uPresence;
            uniform float uHasWarp;
            uniform mat3 uWarp;
            ${List(ColourMask.MAX_PER_CLIP) { "uniform vec3 uMp$it;" }.joinToString("\n")}
            ${List(ColourMask.MAX_PER_CLIP) { "uniform vec2 uMg$it;" }.joinToString("\n")}
            uniform float uMaskGlow;
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

            // As the preview shader's brushWeight(): back to where the strokes were painted, then the atlas slot.
            float brushWeight(vec4 a, vec4 b, vec2 px, float slot) {
                float aspect = uSize.x / uSize.y;
                vec2 d = vec2((px.x / uSize.x - a.y) * aspect, px.y / uSize.y - a.z);
                float c = cos(a.w);
                float s = sin(a.w);
                vec2 u = vec2(d.x * c + d.y * s, -d.x * s + d.y * c) / max(b.xy, vec2(0.001));
                vec2 q = vec2(0.5 + u.x / aspect, 0.5 + u.y);
                float w = 0.0;
                if (q.x >= 0.0 && q.x <= 1.0 && q.y >= 0.0 && q.y <= 1.0) {
                    vec2 t = vec2(
                        (slot * uBrushSlot.x + clamp(q.x * uBrushSlot.x, 0.5, uBrushSlot.x - 0.5)) / (uBrushSlot.x * uBrushSlots),
                        clamp(q.y * uBrushSlot.y, 0.5, uBrushSlot.y - 0.5) / uBrushSlot.y);
                    w = texture2D(uBrush, t).r;
                }
                return b.w > 0.5 ? 1.0 - w : w;
            }

            // Inverse of Media3's HLG EOTF, back to the signal values.
            float hlgOetf(float x) {
                return x <= 1.0 / 12.0 ? sqrt(3.0 * x) : 0.17883277 * log(12.0 * x - 0.28466892) + 0.55991073;
            }

            // The file's values as the colour tables expect them.
            vec3 source(vec2 uv) {
                // Never outside the frame: the edges would glow.
                uv = clamp(uv, 0.5 / uSize, 1.0 - 0.5 / uSize);
                // Perspective correction, on fractions measured from the top (as the preview's).
                if (uHasWarp > 0.5) {
                    vec3 q = uWarp * vec3(uv.x, 1.0 - uv.y, 1.0);
                    vec2 s = clamp(q.xy / q.z, 0.0, 1.0);
                    uv = vec2(s.x, 1.0 - s.y);
                }
                vec4 src = texture2D(uTexSampler, uv);
                if (uHdrIn == 1) {
                    // Relabelled SDR/log: back to the file's own code values.
                    vec3 lin = max(src.rgb, 0.0);
                    return clamp(uHlgFix * vec3(hlgOetf(lin.r), hlgOetf(lin.g), hlgOetf(lin.b)), 0.0, 1.0);
                } else if (uHdrIn == 2) {
                    // Real HLG: SDR white (75 % HLG) to 1.0, BT.709, highlights rolled off, display gamma.
                    vec3 x = max(BT2020_TO_BT709 * (src.rgb / uSdrWhite), 0.0);
                    vec3 knee = vec3(0.7);
                    vec3 rolled = mix(x, knee + 0.3 * (1.0 - exp(-(x - knee) / 0.3)), step(knee, x));
                    return pow(clamp(rolled, 0.0, 1.0), vec3(1.0 / 2.4));
                }
                return src.rgb;
            }

            float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

            // As the preview shader's localMean(): an edge-aware mean brightness over a disc of r pixels.
            float localMean(vec2 uv, float r, float l) {
                float acc = l;
                float sum = 1.0;
                for (int k = 1; k <= 2; k++) {
                    float rr = r * float(k) * 0.5;
                    for (int i = 0; i < 12; i++) {
                        float a = float(i) * 0.5236 + float(k) * 0.2618;
                        float s = luma(source(uv + vec2(cos(a), sin(a)) * rr / uSize));
                        float d = s - l;
                        float w = exp(-d * d * 22.0);
                        acc += s * w;
                        sum += w;
                    }
                }
                return acc / sum;
            }

            // As the preview shader's presence(): texture and clarity (dehaze is in the colour table).
            vec3 presence(vec2 uv, vec3 c, vec3 pr) {
                float shortSide = min(uSize.x, uSize.y);
                float l = luma(c);
                if (pr.x != 0.0) {
                    float d = l - localMean(uv, max(2.0, shortSide * 0.004), l);
                    d = d / (1.0 + abs(d) * 4.0);
                    c += d * pr.x * 1.6;
                }
                if (pr.y != 0.0) {
                    float d = l - localMean(uv, shortSide * 0.03, l);
                    d = d / (1.0 + abs(d) * 3.0);
                    c += d * pr.y * 6.0 * l * (1.0 - l);
                }
                return clamp(c, 0.0, 1.0);
            }

            // As the preview shader's maskGlowAt(): each glowing mask's glow and halation at px, added up.
            vec2 maskGlowAt(vec2 px) {
                vec2 g = vec2(0.0);
                ${List(ColourMask.MAX_PER_CLIP) { i -> """
                if (uMaskCount > $i.5 && (uMg$i.x > 0.0 || uMg$i.y > 0.0)) {
                    g += uMg$i * (uM${i}a.x > 1.5 ? brushWeight(uM${i}a, uM${i}b, px, $i.0) : maskWeight(uM${i}a, uM${i}b, px));
                }""" }.joinToString("")}
                return g;
            }

            void main() {
                vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
                vec3 srcRgb = source(vTexSamplingCoord);
                vec2 px = vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y) * uSize;
                // Masks first: inside one, its texture, clarity and dehaze add to the clip's.
                vec3 pr = uPresence;
                ${List(ColourMask.MAX_PER_CLIP) { i -> """
                float w$i = 0.0;
                if (uMaskCount > $i.5) {
                    w$i = uM${i}a.x > 1.5 ? brushWeight(uM${i}a, uM${i}b, px, $i.0) : maskWeight(uM${i}a, uM${i}b, px);
                    pr += uMp$i * w$i;
                }""" }.joinToString("")}
                if (pr.x != 0.0 || pr.y != 0.0) srcRgb = presence(vTexSamplingCoord, srcRgb, pr);
                vec3 c = grade(uLut, srcRgb);
                ${List(ColourMask.MAX_PER_CLIP) { i -> """
                if (w$i > 0.0) c = mix(c, grade(uLutM$i, srcRgb), w$i);""" }.joinToString("")}
                if (uFromWeight > 0.0) c = mix(c, grade(uLutFrom, srcRgb), uFromWeight);
                // As the preview: a mask's glow comes only from what is bright inside it and spreads past its edge.
                if (uMaskGlow > 0.5) {
                    float r = min(uSize.x, uSize.y) * 0.012;
                    vec3 accG = vec3(0.0);
                    vec3 accH = vec3(0.0);
                    for (int i = 0; i < 18; i++) {
                        float a = i < 12 ? float(i) * 0.5236 : float(i - 12) * 1.0472 + 0.26;
                        vec2 q = px + vec2(cos(a), sin(a)) * (i < 12 ? r : 2.2 * r);
                        vec2 k = maskGlowAt(q);
                        if (k.x <= 0.0 && k.y <= 0.0) continue;
                        vec3 s = grade(uLut, source(vec2(q.x / uSize.x, 1.0 - q.y / uSize.y)));
                        vec3 b = s * (max(luma(s) - 0.6, 0.0) / 0.4);
                        accG += b * k.x;
                        accH += b * k.y;
                    }
                    vec3 g = clamp(accG / 18.0 * 1.5 + dot(accH / 18.0, vec3(0.33)) * 2.0 * vec3(1.0, 0.3, 0.1), 0.0, 1.0);
                    c = 1.0 - (1.0 - c) * (1.0 - g);
                }
                gl_FragColor = vec4(c, src.a);
            }
        """
    }
}
