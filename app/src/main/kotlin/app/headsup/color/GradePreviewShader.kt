package app.headsup.color

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asComposeRenderEffect
import app.headsup.core.color.ColorGrade
import app.headsup.core.color.LutBaker

/**
 * Applies the baked colour table to whatever is drawn below it (the preview's TextureView) on the
 * GPU. Changing the table only swaps a tiny bitmap, so slider moves show up on the next frame,
 * while playing or paused, without touching the player. Export still goes through Media3 with the
 * same table, so the two match.
 */
@RequiresApi(33)
object GradePreviewShader {
    private val identity by lazy { LutBaker.bake(null, ColorGrade()) }

    // The table is laid out like Media3's SingleColorLut: N wide, N² high, x = b, y = g + N·r.
    // It is sampled without filtering and interpolated here (trilinear).
    private const val SOURCE = """
        uniform shader image;
        uniform shader lut;
        uniform float n;
        uniform float zebra;

        half3 cell(float r, float g, float b) {
            return lut.eval(float2(b + 0.5, g + n * r + 0.5)).rgb;
        }

        half4 main(float2 coord) {
            half4 c = image.eval(coord);
            float3 v = clamp(float3(c.rgb), 0.0, 1.0) * (n - 1.0);
            float3 i0 = floor(v);
            float3 i1 = min(i0 + 1.0, n - 1.0);
            float3 f = v - i0;
            half3 c00 = mix(cell(i0.r, i0.g, i0.b), cell(i1.r, i0.g, i0.b), f.r);
            half3 c10 = mix(cell(i0.r, i1.g, i0.b), cell(i1.r, i1.g, i0.b), f.r);
            half3 c01 = mix(cell(i0.r, i0.g, i1.b), cell(i1.r, i0.g, i1.b), f.r);
            half3 c11 = mix(cell(i0.r, i1.g, i1.b), cell(i1.r, i1.g, i1.b), f.r);
            half3 c0 = mix(c00, c10, f.g);
            half3 c1 = mix(c01, c11, f.g);
            half3 graded = mix(c0, c1, f.b);
            // Zebra: stripes where the graded picture is at or near clipping.
            float y = dot(float3(graded), float3(0.2126, 0.7152, 0.0722));
            if (zebra > 0.5 && y > 0.95 && mod(coord.x + coord.y, 12.0) < 6.0) {
                graded = half3(0.15, 0.15, 0.15);
            }
            return half4(graded, c.a);
        }
    """

    /** [cube] null means untouched colours (identity table), e.g. when only zebra is on. */
    fun effect(cube: Array<Array<IntArray>>?, zebra: Boolean = false): androidx.compose.ui.graphics.RenderEffect {
        val table: Bitmap = LiveColorLut.toBitmap(cube ?: identity)
        val lut = BitmapShader(table, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_NEAREST
        }
        val shader = RuntimeShader(SOURCE).apply {
            setFloatUniform("n", (cube ?: identity).size.toFloat())
            setFloatUniform("zebra", if (zebra) 1f else 0f)
            setInputShader("lut", lut)
        }
        return RenderEffect.createRuntimeShaderEffect(shader, "image").asComposeRenderEffect()
    }
}
