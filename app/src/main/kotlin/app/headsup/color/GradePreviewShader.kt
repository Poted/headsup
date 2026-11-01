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
import app.headsup.render.EffectLook
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
        warp: DoubleArray? = null,
    ): androidx.compose.ui.graphics.RenderEffect = nativeEffect(effects, zebra, frame, width, height, masks, fromWeight, detail, presence, warp).asComposeRenderEffect()

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
        warp: DoubleArray? = null,
    ): RenderEffect {
        setUniforms(effects, zebra, frame, width, height, masks, fromWeight, detail, presence, warp)
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
        warp: DoubleArray? = null,
    ): Shader {
        setUniforms(effects, false, 0f, width, height, masks, 0f, detail, presence, warp)
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
        warp: DoubleArray?,
    ) {
        // Perspective correction: the 3×3 (row by row) given to the shader column by column.
        shader.setFloatUniform("hasWarp", if (warp != null) 1f else 0f)
        val m = warp ?: doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        shader.setFloatUniform("warp", floatArrayOf(m[0].toFloat(), m[3].toFloat(), m[6].toFloat(), m[1].toFloat(), m[4].toFloat(), m[7].toFloat(), m[2].toFloat(), m[5].toFloat(), m[8].toFloat()))
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
            val g = masks.getOrNull(i)?.grade
            shader.setFloatUniform("mg$i", g?.glow ?: 0f, g?.halation ?: 0f)
            val (q, k) = (masks.getOrNull(i) ?: NO_MASK).rangeParams()
            shader.setFloatUniform("mq$i", q)
            shader.setFloatUniform("mc$i", k)
        }
        shader.setFloatUniform("maskGlow", if (masks.take(ColourMask.MAX_PER_CLIP).any { it.grade.glow > 0f || it.grade.halation > 0f }) 1f else 0f)
        shader.setFloatUniform("fromWeight", fromWeight)
        shader.setFloatUniform("grain", effects.grain)
        shader.setFloatUniform("grainSize", effects.grainSize)
        shader.setFloatUniform("glow", effects.glow)
        shader.setFloatUniform("threshold", effects.glowThreshold)
        shader.setFloatUniform("halation", effects.halation)
        shader.setFloatUniform("grainColour", effects.grainColour)
        shader.setFloatUniform("glowSpread", effects.glowSpread)
        shader.setFloatUniform("glowTint", EffectLook.glowTint(effects.glowWarmth))
        shader.setFloatUniform("lensCA", effects.lensAberration)
        shader.setFloatUniform("lensSoft", effects.lensSoftness)
    }

    private companion object {
        /** Stands in for a mask slot not used: no range, its own layer. */
        val NO_MASK = ColourMask()

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
            uniform float grainColour;
            uniform float glowSpread;
            uniform float3 glowTint;
            uniform float lensCA;
            uniform float lensSoft;
            uniform float sharpen;
            uniform float chromaNR;
            uniform float lcTexture;
            uniform float lcClarity;
            uniform float lcDehaze;
            ${List(ColourMask.MAX_PER_CLIP) { "uniform float3 mp$it;" }.joinToString("\n")}
            // Glow and halation of each mask, and whether any mask has them.
            ${List(ColourMask.MAX_PER_CLIP) { "uniform float2 mg$it;" }.joinToString("\n")}
            uniform float maskGlow;
            // Each mask's luminance range and join, and its colour range (ColourMask.rangeParams).
            ${List(ColourMask.MAX_PER_CLIP) { "uniform float4 mq$it;\nuniform float4 mc$it;" }.joinToString("\n")}

            uniform float hasWarp;
            uniform float3x3 warp;

            // The picture, through the perspective correction (Perspective.sourceOf) when there is one.
            half4 img(float2 p) {
                // Never outside the picture (empty there): the edges would glow.
                p = clamp(p, float2(0.5), size - 0.5);
                if (hasWarp < 0.5) return image.eval(p);
                float3 q = warp * float3(p / size, 1.0);
                return image.eval(clamp(q.xy / q.z, float2(0.0), float2(1.0)) * size);
            }

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
            // The atlas at coord: the mask's coverage (r) and where its edge was made gradual (g).
            float2 brushRG(float4 a, float4 b, float2 coord, float slot) {
                float aspect = size.x / size.y;
                float2 d = float2((coord.x / size.x - a.y) * aspect, coord.y / size.y - a.z);
                float c = cos(a.w);
                float s = sin(a.w);
                float2 u = float2(d.x * c + d.y * s, -d.x * s + d.y * c) / max(b.xy, float2(0.001));
                float2 q = float2(0.5 + u.x / aspect, 0.5 + u.y);
                if (q.x >= 0.0 && q.x <= 1.0 && q.y >= 0.0 && q.y <= 1.0) {
                    float2 p = float2(
                        slot * brushSlot.x + clamp(q.x * brushSlot.x, 0.5, brushSlot.x - 0.5),
                        clamp(q.y * brushSlot.y, 0.5, brushSlot.y - 0.5));
                    return float2(brush.eval(p).rg);
                }
                return float2(0.0);
            }

            float brushWeight(float4 a, float4 b, float2 coord, float slot) {
                float w = brushRG(a, b, coord, slot).x;
                return b.w > 0.5 ? 1.0 - w : w;
            }

            half3 gradeNearest(half3 c) {
                float3 v = floor(clamp(float3(c), 0.0, 1.0) * (n - 1.0) + 0.5);
                return cell_lut(v.r, v.g, v.b);
            }

            float luma(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }

            // Oklab of an encoded colour (MaskRange.oklab).
            float3 oklab(float3 c) {
                c = clamp(c, 0.0, 1.0);
                float3 lin = mix(c / 12.92, pow((c + 0.055) / 1.055, float3(2.4)), step(float3(0.04045), c));
                float l = pow(max(dot(lin, float3(0.4122214708, 0.5363325363, 0.0514459929)), 0.0), 1.0 / 3.0);
                float m = pow(max(dot(lin, float3(0.2119034982, 0.6806995451, 0.1073969566)), 0.0), 1.0 / 3.0);
                float s = pow(max(dot(lin, float3(0.0883024619, 0.2817188376, 0.6299787005)), 0.0), 1.0 / 3.0);
                return float3(
                    0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                    1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                    0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s);
            }

            // MaskRange.cover: the shape narrowed by the mask's ranges at a pixel of colour c; a range
            // mask (kind 3) is its ranges alone, inverted when the mask is.
            float cover(float4 a, float4 b, float4 q, float4 k, float shape, float3 c) {
                float w = 1.0;
                if (q.x > -0.5) {
                    float l = luma(c);
                    w *= smooth01(q.x - q.z, q.x, l) * (1.0 - smooth01(q.y, q.y + q.z, l));
                }
                if (k.w > 0.0) {
                    float3 d = (oklab(c) - k.xyz) * float3(0.35, 1.0, 1.0);
                    w *= 1.0 - smooth01(k.w, k.w * 2.0, length(d));
                }
                if (a.x > 2.5) return b.w > 0.5 ? 1.0 - w : w;
                return shape * w;
            }

            // A painted (or found) mask is kept small (640 px); its edge is laid onto the full picture
            // here: where it is soft, each neighbouring mask value counts as much as the picture there
            // looks like this pixel (a joint bilateral upsample), so the edge follows the picture's.
            float brushFine(float4 a, float4 b, float2 p, float slot, float3 c) {
                float w = brushWeight(a, b, p, slot);
                // Feathered (b.z) or softened here (g): its soft band is wanted, not laid onto the picture's edges.
                if (w <= 0.02 || w >= 0.98 || b.z > 0.001) return w;
                float soft = brushRG(a, b, p, slot).y;
                if (soft >= 0.98) return w;
                float2 st = size / brushSlot * max(b.xy, float2(0.001));
                float acc = 0.0;
                float sum = 0.0;
                for (int j = -1; j <= 1; j++) {
                    for (int i = -1; i <= 1; i++) {
                        float2 q = p + float2(float(i), float(j)) * st;
                        float3 d = float3(img(q).rgb) - c;
                        float k = exp(-dot(d, d) * 60.0) * ((i == 0 && j == 0) ? 1.0 : 0.6);
                        acc += brushWeight(a, b, q, slot) * k;
                        sum += k;
                    }
                }
                return mix(acc / max(sum, 0.0001), w, soft);
            }

            float shapeAt(float4 a, float4 b, float2 p, float slot, float3 c, bool fine) {
                if (a.x > 2.5) return 1.0;
                if (a.x > 1.5) return fine ? brushFine(a, b, p, slot, c) : brushWeight(a, b, p, slot);
                return maskWeight(a, b, p);
            }

            // MaskRange.combine (j: 1 add, 2 subtract, 3 intersect).
            float joinOf(float a, float b, float j) {
                if (j < 1.5) return max(a, b);
                if (j < 2.5) return a * (1.0 - b);
                return a * b;
            }

            float4 putAt(float4 f, float h, float v) {
                if (h < 0.5) f.x = v; else if (h < 1.5) f.y = v; else if (h < 2.5) f.z = v; else f.w = v;
                return f;
            }

            // Each mask's coverage at p (colour c there), joined masks combined into the layer they
            // join (MaskRange.joined).
            float4 weightsAt(float2 p, float3 c, bool fine) {
                ${List(ColourMask.MAX_PER_CLIP) { i -> """
                float w$i = 0.0;
                if (maskCount > $i.5) w$i = cover(m${i}a, m${i}b, mq$i, mc$i, shapeAt(m${i}a, m${i}b, p, $i.0, c, fine), c);""" }.joinToString("")}
                float acc = w0;
                float h = 0.0;
                float4 f = float4(0.0);
                ${(1 until ColourMask.MAX_PER_CLIP).joinToString("") { i -> """
                if (mq$i.w < 0.5) { f = putAt(f, h, acc); acc = w$i; h = $i.0; } else { acc = joinOf(acc, w$i, mq$i.w); }""" }}
                return putAt(f, h, acc);
            }

            float3 bright(float2 p) {
                float3 c = float3(gradeNearest(img(p).rgb));
                float k = max(luma(c) - threshold, 0.0) / max(1.0 - threshold, 0.01);
                return c * k;
            }

            // Film grain (as FinishEffect): smooth noise in two sizes (clumps, not square cells).
            float hash21(float2 p) {
                float3 p3 = fract(float3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }
            float vnoise(float2 p) {
                float2 i = floor(p);
                float2 f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                return mix(mix(hash21(i), hash21(i + float2(1.0, 0.0)), f.x), mix(hash21(i + float2(0.0, 1.0)), hash21(i + float2(1.0, 1.0)), f.x), f.y) - 0.5;
            }
            float grainAt(float2 p, float cell, float seed) {
                float2 o = float2(seed * 7.31, seed * 3.17);
                return (vnoise(p / cell + o) * 0.75 + vnoise(p / (cell * 0.5) + o * 1.93 + 11.0) * 0.45) * 1.9;
            }
            float3 filmGrain(float2 p, float3 c) {
                float cellSize = max(0.8, shortSide / 1000.0 * 1.5 * grainSize);
                float seed = mod(time, 61.0);
                float l = luma(c);
                // Strongest in the midtones, as on film; less in deep shadows and bright highlights.
                float resp = 0.3 + 2.8 * l * (1.0 - l);
                float m = grainAt(p, cellSize, seed);
                float3 n = float3(m);
                if (grainColour > 0.0) n = mix(n, float3(m, grainAt(p, cellSize, seed + 17.0), grainAt(p, cellSize, seed + 33.0)), grainColour);
                return clamp(c + n * grain * 0.16 * resp, 0.0, 1.0);
            }

            // Turns the glow's taps a little per pixel when it reaches far, so its rings do not show.
            float glowTurn(float2 p) {
                return fract(52.9829189 * fract(dot(p, float2(0.06711056, 0.00583715)))) * 6.2832 * smooth01(1.1, 2.0, glowSpread);
            }

            // A lens (as FinishEffect): colour fringes and softness growing towards the corners.
            float3 fringed(float2 q, float2 shift) {
                if (lensCA <= 0.0) return float3(img(q).rgb);
                return float3(float(img(q - shift).r), float(img(q).g), float(img(q + shift).b));
            }
            half4 lensed(float2 p) {
                float2 c = size * 0.5;
                float2 d = (p - c) / length(c);
                float r2 = dot(d, d);
                float2 shift = d * r2 * lensCA * shortSide * 0.005;
                float3 col = fringed(p, shift);
                float rad = lensSoft * shortSide * 0.006 * smooth01(0.15, 1.0, r2);
                if (rad > 0.3) {
                    float3 acc = col;
                    for (int i = 0; i < 8; i++) {
                        float a = float(i) * 0.7854 + 0.39;
                        acc += fringed(p + float2(cos(a), sin(a)) * rad, shift);
                    }
                    for (int i = 0; i < 4; i++) {
                        float a = float(i) * 1.5708;
                        acc += fringed(p + float2(cos(a), sin(a)) * rad * 0.5, shift);
                    }
                    col = acc / 13.0;
                }
                return half4(half3(col), img(p).a);
            }

            // Where each mask is at p, for masks that glow: their glow and halation there, added up.
            float2 maskGlowAt(float2 p) {
                float4 w = weightsAt(p, float3(img(p).rgb), false);
                float2 g = float2(0.0);
                ${List(ColourMask.MAX_PER_CLIP) { i -> """
                if (maskCount > ${i}.5 && (mg$i.x > 0.0 || mg$i.y > 0.0)) g += mg$i * w.${"xyzw"[i]};""" }.joinToString("")}
                return g;
            }

            // Colour noise: the colour averaged over a small ring, the brightness kept; then the
            // brightness sharpened against its four neighbours (small differences, i.e. noise, left out).
            float3 detailed(float2 p, float3 c) {
                if (chromaNR > 0.0) {
                    float r = 1.5 + 3.0 * chromaNR;
                    float3 acc = c;
                    for (int i = 0; i < 8; i++) {
                        float a = float(i) * 0.7854;
                        acc += float3(img(p + float2(cos(a), sin(a)) * r).rgb);
                    }
                    float3 avg = acc / 9.0;
                    c = mix(c, avg + (luma(c) - luma(avg)), min(1.0, chromaNR * 1.5));
                }
                if (sharpen > 0.0) {
                    float3 n = (float3(img(p + float2(1.0, 0.0)).rgb) + float3(img(p - float2(1.0, 0.0)).rgb) +
                        float3(img(p + float2(0.0, 1.0)).rgb) + float3(img(p - float2(0.0, 1.0)).rgb)) * 0.25;
                    float d = luma(c) - luma(n);
                    d = sign(d) * max(abs(d) - 0.004, 0.0);
                    c += d * sharpen * 2.0;
                }
                return clamp(c, 0.0, 1.0);
            }

            // The brightness around p over a disc of radius r, counting mostly pixels of a brightness
            // like this one's (an edge-aware mean), so a bright sky does not glow round a dark tree.
            float localMean(float2 p, float r, float l) {
                float acc = l;
                float sum = 1.0;
                for (int k = 1; k <= 2; k++) {
                    float rr = r * float(k) * 0.5;
                    for (int i = 0; i < 12; i++) {
                        float a = float(i) * 0.5236 + float(k) * 0.2618;
                        float s = luma(float3(img(p + float2(cos(a), sin(a)) * rr).rgb));
                        float d = s - l;
                        float w = exp(-d * d * 22.0);
                        acc += s * w;
                        sum += w;
                    }
                }
                return acc / sum;
            }

            // Presence: texture and clarity add the difference from the surroundings' brightness
            // (small and wider), softly limited so edges do not ring; clarity mostly in the midtones.
            // Dehaze is in the colour table (LutBaker), per pixel, so it never leaves halos.
            float3 presence(float2 p, float3 c, float3 pr) {
                float l = luma(c);
                if (pr.x != 0.0) {
                    float d = l - localMean(p, max(2.0, shortSide * 0.004), l);
                    d = d / (1.0 + abs(d) * 4.0);
                    c += d * pr.x * 1.6;
                }
                if (pr.y != 0.0) {
                    float d = l - localMean(p, shortSide * 0.03, l);
                    d = d / (1.0 + abs(d) * 3.0);
                    c += d * pr.y * 6.0 * l * (1.0 - l);
                }
                return clamp(c, 0.0, 1.0);
            }

            half4 main(float2 coord) {
                // Android draws an effect beyond the layer it is on: outside the picture nothing is
                // drawn (the picture's edge, repeated there, showed as streaks over blurred bars).
                if (coord.x < 0.0 || coord.y < 0.0 || coord.x > size.x || coord.y > size.y) return half4(0.0);
                half4 src = (lensCA > 0.0 || lensSoft > 0.0) ? lensed(coord) : img(coord);
                if (sharpen > 0.0 || chromaNR > 0.0) src = half4(half3(detailed(coord, float3(src.rgb))), src.a);
                // Masks first: inside one, its texture, clarity and dehaze add to the clip's.
                float3 pr = float3(lcTexture, lcClarity, lcDehaze);
                float4 mw = maskCount > 0.5 ? weightsAt(coord, float3(src.rgb), true) : float4(0.0);
                ${List(ColourMask.MAX_PER_CLIP) { i -> """
                float w$i = mw.${"xyzw"[i]};
                pr += mp$i * w$i;""" }.joinToString("")}
                if (pr.x != 0.0 || pr.y != 0.0) src = half4(half3(presence(coord, float3(src.rgb), pr)), src.a);
                float3 c = float3(grade_lut(src.rgb));
                ${List(ColourMask.MAX_PER_CLIP) { i -> """
                if (w$i > 0.0) c = mix(c, float3(grade_lutM$i(src.rgb)), w$i);""" }.joinToString("")}
                if (fromWeight > 0.0) c = mix(c, float3(grade_lutFrom(src.rgb)), fromWeight);
                if (glow > 0.0 || halation > 0.0) {
                    float r = shortSide * 0.012 * glowSpread;
                    float turn = glowTurn(coord);
                    float3 acc = float3(0.0);
                    for (int i = 0; i < 12; i++) {
                        float a = float(i) * 0.5236 + turn;
                        acc += bright(coord + float2(cos(a), sin(a)) * r);
                    }
                    for (int i = 0; i < 6; i++) {
                        float a = float(i) * 1.0472 + 0.26 + turn;
                        acc += bright(coord + float2(cos(a), sin(a)) * 2.2 * r);
                    }
                    float3 g = clamp(acc / 18.0 * glow * 1.5 * glowTint + dot(acc / 18.0, float3(0.33)) * halation * 2.0 * float3(1.0, 0.3, 0.1), 0.0, 1.0);
                    c = 1.0 - (1.0 - c) * (1.0 - g);
                }
                // A mask's glow: only what is bright inside the mask gives light, which spreads past
                // its edge as light does (the mask is read where the light comes from).
                if (maskGlow > 0.5) {
                    float r = shortSide * 0.012 * glowSpread;
                    float3 accG = float3(0.0);
                    float3 accH = float3(0.0);
                    for (int i = 0; i < 18; i++) {
                        float a = i < 12 ? float(i) * 0.5236 : float(i - 12) * 1.0472 + 0.26;
                        float2 q = coord + float2(cos(a), sin(a)) * (i < 12 ? r : 2.2 * r);
                        float2 k = maskGlowAt(q);
                        if (k.x <= 0.0 && k.y <= 0.0) continue;
                        float3 s = float3(gradeNearest(img(q).rgb));
                        float3 b = s * (max(luma(s) - 0.6, 0.0) / 0.4);
                        accG += b * k.x;
                        accH += b * k.y;
                    }
                    float3 g = clamp(accG / 18.0 * 1.5 * glowTint + dot(accH / 18.0, float3(0.33)) * 2.0 * float3(1.0, 0.3, 0.1), 0.0, 1.0);
                    c = 1.0 - (1.0 - c) * (1.0 - g);
                }
                if (grain > 0.0) c = filmGrain(coord, c);
                // Zebra: stripes where the picture is at or near clipping.
                if (zebra > 0.5 && luma(c) > 0.95 && mod(coord.x + coord.y, 12.0) < 6.0) {
                    c = float3(0.15);
                }
                return half4(half3(c), src.a);
            }
        """
    }
}
