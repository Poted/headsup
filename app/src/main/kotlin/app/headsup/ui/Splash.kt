package app.headsup.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawscope.Stroke as TextStroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val LOGO_SIZE = 52.sp
private val RECORD_RED = Color(0xFFFF3B30)

/**
 * Start-up animation, about two seconds: "sweetSpot" unrolls from the left, the "o" being a
 * record button that is pressed once the word is complete; then the overlay fades out.
 */
@Composable
fun SplashOverlay(onFinished: () -> Unit) {
    val reveal = remember { Animatable(0f) }
    val press = remember { Animatable(1f) }
    val glow = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        reveal.animateTo(1f, tween(750, easing = FastOutSlowInEasing))
        // Press: the dot sinks, then springs back with a red glow, like starting a recording.
        launch { glow.animateTo(1f, tween(250)); glow.animateTo(0.35f, tween(400)) }
        press.animateTo(0.72f, tween(110, easing = LinearEasing))
        press.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        delay(350)
        fade.animateTo(0f, tween(350))
        onFinished()
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .background(Brush.radialGradient(listOf(Color(0xFF2A2522), Color(0xFF121110), Color(0xFF060606)), radius = 1400f)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .graphicsLayer { translationX = (1f - reveal.value) * -60f }
                .drawWithContent {
                    // Unrolls from the left with a soft leading edge.
                    clipRect(right = size.width * reveal.value + 1f) { this@drawWithContent.drawContent() }
                },
        ) {
            LogoText("sweetSp", Modifier.alignByBaseline())
            RecordButton(press.value, glow.value, Modifier.alignBy { it.measuredHeight })
            LogoText("t", Modifier.alignByBaseline())
        }
    }
}

/** White letters with a dark outline and a short extruded shadow for a slight 3D look. */
@Composable
private fun LogoText(text: String, modifier: Modifier) {
    val base = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = LOGO_SIZE, letterSpacing = (-1).sp)
    val density = LocalDensity.current
    Box(modifier) {
        // Extrusion: a few dark copies stepping down-right.
        for (i in 4 downTo 1) {
            Text(
                text,
                Modifier.graphicsLayer { with(density) { translationX = i.dp.toPx() * 0.6f; translationY = i.dp.toPx() } },
                style = base.copy(color = Color(0xFF1B1A19).copy(alpha = 0.9f - i * 0.12f)),
            )
        }
        Text(text, style = base.copy(color = Color(0xFF0E0E0E), drawStyle = TextStroke(width = with(density) { 3.dp.toPx() })))
        Text(text, style = base.copy(brush = Brush.verticalGradient(listOf(Color.White, Color(0xFFD9D6D2)))))
    }
}

/** The "o": a white ring with a red dot, sized like a lowercase letter and sitting on the baseline. */
@Composable
private fun RecordButton(press: Float, glow: Float, modifier: Modifier, size: Dp = 30.dp) {
    Canvas(modifier.size(size + 6.dp, size)) {
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val r = this.size.height / 2f
        // Extrusion shadow, ring outline and ring, matching the letters.
        drawCircle(Color(0xFF1B1A19), radius = r - 2.dp.toPx(), center = c + Offset(2.4f.dp.toPx(), 4.dp.toPx()), style = Stroke(4.dp.toPx()))
        drawCircle(Color(0xFF0E0E0E), radius = r - 2.dp.toPx(), center = c, style = Stroke(6.dp.toPx()))
        drawCircle(Color.White, radius = r - 2.dp.toPx(), center = c, style = Stroke(3.dp.toPx()))
        if (glow > 0f) drawCircle(RECORD_RED.copy(alpha = 0.35f * glow), radius = r * (0.9f + 0.6f * glow), center = c)
        scale(press, pivot = c) {
            drawCircle(Color(0xFF7A0F0A), radius = r * 0.56f, center = c + Offset(0f, 1.dp.toPx()))
            drawCircle(RECORD_RED, radius = r * 0.52f, center = c)
            // Small highlight for depth.
            drawCircle(Color.White.copy(alpha = 0.35f), radius = r * 0.14f, center = c + Offset(-r * 0.18f, -r * 0.2f))
        }
    }
}
