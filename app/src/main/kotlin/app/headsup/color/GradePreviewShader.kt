package app.headsup.color

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asComposeRenderEffect
import app.headsup.core.color.ColorGrade
import app.headsup.core.color.ColourMask
import app.headsup.core.color.LutBaker
import app.headsup.core.color.Presence
import app.headsup.core.raw.RawDevelop
import app.headsup.render.EffectSettings

/** Colour tables for one clip: outside any mask, one per mask, and the previous clip's for a transition. */
class GradeTables(
    val base: Array<Array<IntArray>>?,
    val masks: List<Array<Array<IntArray>>> = emptyList(),
    val from: Array<Array<IntArray>>? = null,
)

/**
 * GPU shader for the preview's TextureView: applies the baked colour table, blends in one table
 * per mask and, at the start of a clip with a colour transition, the previous clip's table; then
 * glow, grain and zebra stripes. The maths matches the export's `GradeEffect` and `FinishEffect`.
 * Tables are tiny bitmaps swapped in place, so slider moves show up on the next frame.
 */
@RequiresApi(33)
class GradePreviewShader {
    private val shader = RuntimeShader(SOURCE)
    private var tableSize = LutBaker.DEFAULT_SIZE

    init {
        setTables(GradeTables(null))
        setBrush(null)
    }

    /** The clip's painted masks ([BrushAtlas]); null when it has none. */
    fun setBrush(atlas: Bitmap?) {
        val bitmap = atlas ?: BrushAtlas.EMPTY
        shader.setInputShader("brush", BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        })
        val slots = if (atlas == null) 1 else ColourMask.MAX_PER_CLIP
        shader.setFloatUniform("brushSlot", bitmap.width.toFloat() / slots, bitmap.height.toFloat())
    }

    fun setTables(tables: GradeTables) {
        val base = tables.base ?: identity
        val baseShader = tableShader(base)
        shader.setInputShader("lut", baseShader)
        for (i in 0 until ColourMask.MAX_PER_CLIP) {
            shader.setInputShader("lutM$i", tables.masks.getOrNull(i)?.let(::tableShader) ?: baseShader)
        }
        shader.setInputShader("lutFrom", tables.from?.let(::tableShader) ?: baseShader)
        tableSize = base.size
    }

    private fun tableShader(table: Array<Array<IntArray>>) =
        BitmapShader(LiveColorLut.toBitmap(table), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_NEAREST
        }

    fun effect(
        effects: EffectSettings,
        zebra: Boolean,
        frame: Float,
        width: Float,
        height: Float,
        masks: List<ColourMask>,
        fromWeight: Float,
        detail: RawDevelop? = null,
        presence: Presence = Presence.NONE,
    ): androidx.compose.ui.graphics.RenderEffect = nativeEffect(effects, zebra, frame, width, height, masks, fromWeight, detail, presence).asComposeRenderEffect()

    /** The same, for drawing outside Compose (a photo export). */
    fun nativeEffect(
        effects: EffectSettings,
        zebra: Boolean,
        frame: Float,
        width: Float,
        height: Float,
        masks: List<ColourMask>,
        fromWeight: Float,
        detail: RawDevelop? = null,
        presence: Presence = Presence.NONE,
    ): RenderEffect {
        setUniforms(effects, zebra, frame, width, height, masks, fromWeight, detail, presence)
        return RenderEffect.createRuntimeShaderEffect(shader, "image")
    }

    /**
     * The same as a shader drawing [image] directly (a photo export): the picture is sampled at
     * its own precision, not through an 8-bit layer.
     */
    fun paintShader(
        effects: EffectSettings,
        width: Float,
        height: Float,
        masks: List<ColourMask>,
        image: Shader,
        detail: RawDevelop? = null,
        presence: Presence = Presence.NONE,
    ): Shader {
        setUniforms(effects, false, 0f, width, height, masks, 0f, detail, presence)
        shader.setInputShader("image", image)
        return shader
    }

    private fun setUniforms(
        effects: EffectSettings,
        zebra: Boolean,
        frame: Float,
        width: Float,
        height: Float,
        masks: List<ColourMask>,
        fromWeight: Float,
        detail: RawDevelop?,
        presence: Presence,
    ) {
        // A raw's sharpening and colour noise removal, on the picture before its colours.
        shader.setFloatUniform("sharpen", detail?.sharpen ?: 0f)
        shader.setFloatUniform("chromaNR", detail?.colourNoise ?: 0f)
        // Texture, clarity and dehaze: also before the colours, as they look at the neighbourhood.
        shader.setFloatUniform("lcTexture", presence.texture)
        shader.setFloatUniform("lcClarity", presence.clarity)
        shader.setFloatUniform("lcDehaze", presence.dehaze)
        shader.setFloatUniform("n", tableSize.toFloat())
        shader.setFloatUniform("zebra", if (zebra) 1f else 0f)
        shader.setFloatUniform("time", frame)
        shader.setFloatUniform("shortSide", minOf(width, height))
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("maskCount", masks.size.coerceAtMost(ColourMask.MAX_PER_CLIP).toFloat())
        for (i in 0 until ColourMask.MAX_PER_CLIP) {
            val (a, b) = masks.getOrNull(i)?.shaderParams() ?: (FloatArray(4) to FloatArray(4))
            shader.setFloatUniform("m${i}a", a)
            shader.setFloatUniform("m${i}b", b)
            val p = masks.getOrNull(i)?.grade?.presence ?: Presence.NONE
            shader.setFloatUniform("mp$i", p.texture, p.clarity, p.dehaze)
        }
        shader.setFloatUniform("fromWeight", fromWeight)
        shader.setFloatUniform("grain", effects.grain)
        shader.setFloatUniform("grainSize", effects.grainSize)
        shader.setFloatUniform("glow", effects.glow)
        shader.setFloatUniform("threshold", effects.glowThreshold)
        shader.setFloatUniform("halation", effects.halation)
    }

    private companion object {
        val identity by lazy { LutBaker.bake(null, ColorGrade()) }

        /** Trilinear lookup in table [name]; AGSL cannot pass shaders to functions, so one per table. */
        fun lookup(name: String) = """
            half3 cell_$name(float r, float g, float b) {
                return $name.eval(float2(b + 0.5, g + n * r + 0.5)).rgb;
            }
            half3 grade_$name(half3 c) {
                float3 v = clamp(float3(c), 0.0, 1.0) * (n - 1.0);
                float3 i0 = floor(v);
                float3 i1 = min(i0 + 1.0, n - 1.0);
                float3 f = v - i0;
                half3 c00 = mix(cell_$name(i0.r, i0.g, i0.b), cell_$name(i1.r, i0.g, i0.b), f.r);
                half3 c10 = mix(cell_$name(i0.r, i1.g, i0.b), cell_$name(i1.r, i1.g, i0.b), f.r);
                half3 c01 = mix(cell_$name(i0.r, i0.g, i1.b), cell_$name(i1.r, i0.g, i1.b), f.r);
                half3 c11 = mix(cell_$name(i0.r, i1.g, i1.b), cell_$name(i1.r, i1.g, i1.b), f.r);
                return mix(mix(c00, c10, f.g), mix(c01, c11, f.g), f.b);
            }
        """

        val TABLES = listOf("lut") + List(ColourMask.MAX_PER_CLIP) { "lutM$it" } + "lutFrom"

        // The tables are laid out like Media3's SingleColorLut: N wide, N² high, x = b, y = g + N·r.
        // They are sampled without filtering and interpolated here (trilinear); glow taps use the
        // nearest entry of the base table, which is plenty for a blurred highlight.
        val SOURCE = """
            uniform shader image;
            uniform shader brush;
            uniform float2 brushSlot;
            ${TABLES.joinToString("\n") { "uniform shader $it;" }}
            uniform float n;
            uniform float2 size;
            uniform float maskCount;
            ${List(ColourMask.MAX_PER_CLIP) { "uniform float4 m${it}a;\nuniform float4 m${it}b;" }.joinToString("\n")}
            uniform float fromWeight;
            uniform float zebra;
            uniform float time;
            uniform float shortSide;
            uniform float grain;
            uniform float grainSize;
            uniform float glow;
            uniform float threshold;
            uniform float halation;
            uniform float sharpen;
            uniform float chromaNR;
            uniform float lcTexture;
            uniform float lcClarity;
            uniform float lcDehaze;
            ${List(ColourMask.MAX_PER_CLIP) { "uniform float3 mp$it;" }.joinToString("\n")}

            ${TABLES.joinToString("\n") { lookup(it) }}

            float smooth01(float e0, float e1, float x) {
                float t = clamp((x - e0) / max(e1 - e0, 0.0001), 0.0, 1.0);
                return t * t * (3.0 - 2.0 * t);
            }

            // ColourMask.weight: a = (kind, centerX, centerY, angle), b = (sizeX, sizeY, softness, inverted).
            float maskWeight(float4 a, float4 b, float2 coord) {
                float2 d = float2(coord.x - a.y * size.x, coord.y - a.z * size.y) / size.y;
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

            // A brush mask (BrushMask): the pixel taken back to where its strokes were painted
            // (the mask at BrushMask.BASE: centred, upright, scale 1), then read from its atlas slot.
            float brushWeight(float4 a, float4 b, float2 coord, float slot) {
                float aspect = size.x / size.y;
                float2 d = float2((coord.x / size.x - a.y) * aspect, coord.y / size.y - a.z);
                float c = cos(a.w);
                float s = sin(a.w);
                float2 u = float2(d.x * c + d.y * s, -d.x * s + d.y * c) / max(b.xy, float2(0.001));
                float2 q = float2(0.5 + u.x / aspect, 0.5 + u.y);
                float w = 0.0;
                if (q.x >= 0.0 && q.x <= 1.0 && q.y >= 0.0 && q.y <= 1.0) {
                    float2 p = float2(
                        slot * brushSlot.x + clamp(q.x * brushSlot.x, 0.5, brushSlot.x - 0.5),
                        clamp(q.y * brushSlot.y, 0.5, brushSlot.y - 0.5));
                    w = float(brush.eval(p).r);
                }
                return b.w > 0.5 ? 1.0 - w : w;
            }

            half3 gradeNearest(half3 c) {
                float3 v = floor(clamp(float3(c), 0.0, 1.0) * (n - 1.0) + 0.5);
                return cell_lut(v.r, v.g, v.b);
            }

            float luma(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }

            float3 bright(float2 p) {
                float3 c = float3(gradeNearest(image.eval(p).rgb));
                float k = max(luma(c) - threshold, 0.0) / max(1.0 - threshold, 0.01);
                return c * k;
            }

            // Colour noise: the colour averaged over a small ring, the brightness kept; then the
            // brightness sharpened against its four neighbours (small differences, i.e. noise, left out).
            float3 detailed(float2 p, float3 c) {
                if (chromaNR > 0.0) {
                    float r = 1.5 + 3.0 * chromaNR;
                    float3 acc = c;
                    for (int i = 0; i < 8; i++) {
                        float a = float(i) * 0.7854;
                        acc += float3(image.eval(p + float2(cos(a), sin(a)) * r).rgb);
                    }
                    float3 avg = acc / 9.0;
                    c = mix(c, avg + (luma(c) - luma(avg)), min(1.0, chromaNR * 1.5));
                }
                if (sharpen > 0.0) {
                    float3 n = (float3(image.eval(p + float2(1.0, 0.0)).rgb) + float3(image.eval(p - float2(1.0, 0.0)).rgb) +
                        float3(image.eval(p + float2(0.0, 1.0)).rgb) + float3(image.eval(p - float2(0.0, 1.0)).rgb)) * 0.25;
                    float d = luma(c) - luma(n);
                    d = sign(d) * max(abs(d) - 0.004, 0.0);
                    c += d * sharpen * 2.0;
                }
                return clamp(c, 0.0, 1.0);
            }

            // The brightness averaged over a ring of radius r around p.
            float ringLuma(float2 p, float r) {
                float acc = 0.0;
                for (int i = 0; i < 8; i++) {
                    float a = float(i) * 0.7854 + 0.3927;
                    acc += luma(float3(image.eval(p + float2(cos(a), sin(a)) * r).rgb));
                }
                return acc / 8.0;
            }

            // Presence: texture and clarity add the difference from the surroundings' brightness
            // (small and wider), clarity mostly in the midtones; dehaze takes out the haze, i.e. how
            // far the darkest channel is lifted over a wide area (negative adds haze).
            float3 presence(float2 p, float3 c, float3 pr) {
                float l = luma(c);
                if (pr.x != 0.0) {
                    float m = ringLuma(p, max(1.5, shortSide * 0.003));
                    c += (l - m) * pr.x * 1.5;
                }
                if (pr.y != 0.0) {
                    float r = shortSide * 0.025;
                    float m = (ringLuma(p, r) * 2.0 + ringLuma(p, r * 0.5)) / 3.0;
                    c += (l - m) * pr.y * 6.0 * l * (1.0 - l);
                }
                if (pr.z > 0.0) {
                    float r = shortSide * 0.05;
                    float dark = min(c.r, min(c.g, c.b));
                    for (int i = 0; i < 8; i++) {
                        float a = float(i) * 0.7854;
                        float3 s = float3(image.eval(p + float2(cos(a), sin(a)) * r).rgb);
                        dark += min(s.r, min(s.g, s.b));
                    }
                    float h = clamp(dark / 9.0 * pr.z * 0.95, 0.0, 0.9);
                    c = (c - h) / (1.0 - h);
                } else if (pr.z < 0.0) {
                    c = mix(c, float3(0.82), -pr.z * 0.45);
                }
                return clamp(c, 0.0, 1.0);
            }

            half4 main(float2 coord) {
                half4 src = image.eval(coord);
                if (sharpen > 0.0 || chromaNR > 0.0) src = half4(half3(detailed(coord, float3(src.rgb))), src.a);
                // Masks first: inside one, its texture, clarity and dehaze add to the clip's.
                float3 pr = float3(lcTexture, lcClarity, lcDehaze);
                ${List(ColourMask.MAX_PER_CLIP) { i -> """
                float w$i = 0.0;
                if (maskCount > ${i}.5) {
                    w$i = m${i}a.x > 1.5 ? brushWeight(m${i}a, m${i}b, coord, $i.0) : maskWeight(m${i}a, m${i}b, coord);
                    pr += mp$i * w$i;
                }""" }.joinToString("")}
                if (pr != float3(0.0)) src = half4(half3(presence(coord, float3(src.rgb), pr)), src.a);
                float3 c = float3(grade_lut(src.rgb));
                ${List(ColourMask.MAX_PER_CLIP) { i -> """
                if (w$i > 0.0) c = mix(c, float3(grade_lutM$i(src.rgb)), w$i);""" }.joinToString("")}
                if (fromWeight > 0.0) c = mix(c, float3(grade_lutFrom(src.rgb)), fromWeight);
                if (glow > 0.0 || halation > 0.0) {
                    float r = shortSide * 0.012;
                    float3 acc = float3(0.0);
                    for (int i = 0; i < 12; i++) {
                        float a = float(i) * 0.5236;
                        acc += bright(coord + float2(cos(a), sin(a)) * r);
                    }
                    for (int i = 0; i < 6; i++) {
                        float a = float(i) * 1.0472 + 0.26;
                        acc += bright(coord + float2(cos(a), sin(a)) * 2.2 * r);
                    }
                    float3 g = clamp(acc / 18.0 * glow * 1.5 + dot(acc / 18.0, float3(0.33)) * halation * 2.0 * float3(1.0, 0.3, 0.1), 0.0, 1.0);
                    c = 1.0 - (1.0 - c) * (1.0 - g);
                }
                if (grain > 0.0) {
                    float cellSize = max(1.0, shortSide / 1000.0 * 1.5 * grainSize);
                    float2 p = floor(coord / cellSize);
                    float noise = fract(sin(dot(p + float2(time * 37.0, time * 17.0), float2(12.9898, 78.233))) * 43758.5453) - 0.5;
                    c += noise * grain * 0.25 * (1.0 - 0.6 * luma(c));
                }
                // Zebra: stripes where the picture is at or near clipping.
                if (zebra > 0.5 && luma(c) > 0.95 && mod(coord.x + coord.y, 12.0) < 6.0) {
                    c = float3(0.15);
                }
                return half4(half3(c), src.a);
            }
        """
    }
}
