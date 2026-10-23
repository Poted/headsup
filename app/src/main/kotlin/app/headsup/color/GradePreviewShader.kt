package app.headsup.color

import android.graphics.BitmapShader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asComposeRenderEffect
import app.headsup.core.color.ColorGrade
import app.headsup.core.color.GradientMask
import app.headsup.core.color.LutBaker
import app.headsup.render.EffectSettings

/**
 * GPU shader for the preview's TextureView: applies the baked colour table (and, inside a
 * graduated mask, a second one), then glow and grain
 * (the same maths as the export's `FinishEffect`), and zebra stripes. The colour table is a tiny
 * bitmap swapped in place, so slider moves show up on the next frame without touching the player.
 */
@RequiresApi(33)
class GradePreviewShader {
    private val shader = RuntimeShader(SOURCE)
    private var tableSize = LutBaker.DEFAULT_SIZE

    init {
        setTables(null, null)
    }

    /** [cube] null means untouched colours; [maskCube] is used inside the mask (null = same as outside). */
    fun setTables(cube: Array<Array<IntArray>>?, maskCube: Array<Array<IntArray>>?) {
        val table = cube ?: identity
        shader.setInputShader("lut", tableShader(table))
        shader.setInputShader("lut2", tableShader(maskCube ?: table))
        tableSize = table.size
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
        mask: GradientMask?,
    ): androidx.compose.ui.graphics.RenderEffect {
        shader.setFloatUniform("n", tableSize.toFloat())
        shader.setFloatUniform("zebra", if (zebra) 1f else 0f)
        shader.setFloatUniform("time", frame)
        shader.setFloatUniform("shortSide", minOf(width, height))
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("maskOn", if (mask != null) 1f else 0f)
        val angle = Math.toRadians((mask?.angleDegrees ?: 0f).toDouble())
        shader.setFloatUniform("maskLine", mask?.position ?: 0f, mask?.softness ?: 0f, kotlin.math.sin(angle).toFloat(), kotlin.math.cos(angle).toFloat())
        shader.setFloatUniform("grain", effects.grain)
        shader.setFloatUniform("grainSize", effects.grainSize)
        shader.setFloatUniform("glow", effects.glow)
        shader.setFloatUniform("threshold", effects.glowThreshold)
        return RenderEffect.createRuntimeShaderEffect(shader, "image").asComposeRenderEffect()
    }

    private companion object {
        val identity by lazy { LutBaker.bake(null, ColorGrade()) }

        // The table is laid out like Media3's SingleColorLut: N wide, N² high, x = b, y = g + N·r.
        // It is sampled without filtering and interpolated here (trilinear); glow taps use the
        // nearest entry, which is plenty for a blurred highlight.
        const val SOURCE = """
            uniform shader image;
            uniform shader lut;
            uniform shader lut2;
            uniform float n;
            uniform float2 size;
            uniform float maskOn;
            // position, softness, sin(angle), cos(angle); see GradientMask.weight.
            uniform float4 maskLine;
            uniform float zebra;
            uniform float time;
            uniform float shortSide;
            uniform float grain;
            uniform float grainSize;
            uniform float glow;
            uniform float threshold;

            half3 cell(float r, float g, float b) {
                return lut.eval(float2(b + 0.5, g + n * r + 0.5)).rgb;
            }

            half3 grade(half3 c) {
                float3 v = clamp(float3(c), 0.0, 1.0) * (n - 1.0);
                float3 i0 = floor(v);
                float3 i1 = min(i0 + 1.0, n - 1.0);
                float3 f = v - i0;
                half3 c00 = mix(cell(i0.r, i0.g, i0.b), cell(i1.r, i0.g, i0.b), f.r);
                half3 c10 = mix(cell(i0.r, i1.g, i0.b), cell(i1.r, i1.g, i0.b), f.r);
                half3 c01 = mix(cell(i0.r, i0.g, i1.b), cell(i1.r, i0.g, i1.b), f.r);
                half3 c11 = mix(cell(i0.r, i1.g, i1.b), cell(i1.r, i1.g, i1.b), f.r);
                return mix(mix(c00, c10, f.g), mix(c01, c11, f.g), f.b);
            }

            half3 cell2(float r, float g, float b) {
                return lut2.eval(float2(b + 0.5, g + n * r + 0.5)).rgb;
            }

            half3 grade2(half3 c) {
                float3 v = clamp(float3(c), 0.0, 1.0) * (n - 1.0);
                float3 i0 = floor(v);
                float3 i1 = min(i0 + 1.0, n - 1.0);
                float3 f = v - i0;
                half3 c00 = mix(cell2(i0.r, i0.g, i0.b), cell2(i1.r, i0.g, i0.b), f.r);
                half3 c10 = mix(cell2(i0.r, i1.g, i0.b), cell2(i1.r, i1.g, i0.b), f.r);
                half3 c01 = mix(cell2(i0.r, i0.g, i1.b), cell2(i1.r, i0.g, i1.b), f.r);
                half3 c11 = mix(cell2(i0.r, i1.g, i1.b), cell2(i1.r, i1.g, i1.b), f.r);
                return mix(mix(c00, c10, f.g), mix(c01, c11, f.g), f.b);
            }

            float maskWeight(float2 coord) {
                float2 p = float2((coord.x - size.x * 0.5) / size.y, coord.y / size.y - maskLine.x);
                float d = -p.x * maskLine.z + p.y * maskLine.w;
                float halfWidth = max(maskLine.y * 0.5, 0.001);
                float t = clamp((d + halfWidth) / (2.0 * halfWidth), 0.0, 1.0);
                return 1.0 - t * t * (3.0 - 2.0 * t);
            }

            half3 gradeNearest(half3 c) {
                float3 v = floor(clamp(float3(c), 0.0, 1.0) * (n - 1.0) + 0.5);
                return cell(v.r, v.g, v.b);
            }

            float luma(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }

            float3 bright(float2 p) {
                float3 c = float3(gradeNearest(image.eval(p).rgb));
                float k = max(luma(c) - threshold, 0.0) / max(1.0 - threshold, 0.01);
                return c * k;
            }

            half4 main(float2 coord) {
                half4 src = image.eval(coord);
                float3 c = float3(grade(src.rgb));
                if (maskOn > 0.5) {
                    float w = maskWeight(coord);
                    if (w > 0.0) c = mix(c, float3(grade2(src.rgb)), w);
                }
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
