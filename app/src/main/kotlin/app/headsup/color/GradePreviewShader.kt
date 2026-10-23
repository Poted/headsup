package app.headsup.color

import android.graphics.BitmapShader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asComposeRenderEffect
import app.headsup.core.color.ColorGrade
import app.headsup.core.color.ColourMask
import app.headsup.core.color.LutBaker
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
    ): androidx.compose.ui.graphics.RenderEffect {
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
        }
        shader.setFloatUniform("fromWeight", fromWeight)
        shader.setFloatUniform("grain", effects.grain)
        shader.setFloatUniform("grainSize", effects.grainSize)
        shader.setFloatUniform("glow", effects.glow)
        shader.setFloatUniform("threshold", effects.glowThreshold)
        return RenderEffect.createRuntimeShaderEffect(shader, "image").asComposeRenderEffect()
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

            half4 main(float2 coord) {
                half4 src = image.eval(coord);
                float3 c = float3(grade_lut(src.rgb));
                ${List(ColourMask.MAX_PER_CLIP) { i -> """
                if (maskCount > ${i}.5) {
                    float w$i = maskWeight(m${i}a, m${i}b, coord);
                    if (w$i > 0.0) c = mix(c, float3(grade_lutM$i(src.rgb)), w$i);
                }""" }.joinToString("")}
                if (fromWeight > 0.0) c = mix(c, float3(grade_lutFrom(src.rgb)), fromWeight);
                if (glow > 0.0) {
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
                    float3 g = clamp(acc / 18.0 * glow * 1.5, 0.0, 1.0);
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
