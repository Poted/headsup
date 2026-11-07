package app.headsup.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Animations at their written length, whatever the system's animation scale. */
private object RealTime : androidx.compose.ui.MotionDurationScale {
    override val scaleFactor: Float = 1f
}

/** The golden ratio: where the point sits on the curve. */
const val GOLDEN = 0.618034f

/** The S the curve settles into: its control points' x (the y are 0 and 1). */
private const val CURVE_HANDLE = 0.38f

/** One coordinate of the unit cubic from 0 to 1 at [t]; [p1] and [p2] are its control points' coordinate. */
private fun cubic(t: Float, p1: Float, p2: Float): Float {
    val u = 1f - t
    return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t
}

/**
 * The curve between the straight diagonal (bend 0, the untouched picture) and the settled S
 * (bend 1); a bend past 1 over-bends it. Returns its two control points, in unit coordinates with y up.
 */
private fun controls(bend: Float): Pair<Offset, Offset> {
    val p1 = Offset(1f / 3f + bend * (CURVE_HANDLE - 1f / 3f), 1f / 3f + bend * (0f - 1f / 3f))
    val p2 = Offset(2f / 3f + bend * (1f - CURVE_HANDLE - 2f / 3f), 2f / 3f + bend * (1f - 2f / 3f))
    return p1 to p2
}

/** The curve's height at [x] (unit coordinates), found by bisection on the Bézier. */
private fun heightAt(x: Float, p1: Offset, p2: Offset): Float {
    var lo = 0f
    var hi = 1f
    repeat(24) {
        val m = (lo + hi) / 2f
        if (cubic(m, p1.x, p2.x) < x) lo = m else hi = m
    }
    return cubic(lo, p1.y, p2.y)
}

/** A damped spring from 0 to 1 over [p] in 0..1: overshoots about one and a half times, then rests. */
private fun searching(p: Float): Float {
    if (p <= 0f) return 0f
    if (p >= 1f) return 1f
    val zeta = 0.38f
    val omega = 13f
    val damped = omega * sqrt(1f - zeta * zeta)
    val t = p * 0.8f
    return 1f - exp(-zeta * omega * t) * (cos(damped * t) + zeta * omega / damped * sin(damped * t))
}

/**
 * The SweetSpot mark: a tone curve inside a rounded frame, with the orange point at the golden
 * ratio and a ring of [ground] cut round it. [frame] and [line] (0..1) draw the frame and the curve
 * progressively, [bend] shapes the curve, [dot] grows the point, [grid] shows the golden grid and
 * [lock] (0..1) the ring that marks the point settling.
 */
fun DrawScope.sweetSpotMark(
    ink: androidx.compose.ui.graphics.Color,
    ground: androidx.compose.ui.graphics.Color,
    point: androidx.compose.ui.graphics.Color,
    frame: Float = 1f,
    line: Float = 1f,
    bend: Float = 1f,
    dot: Float = 1f,
    grid: Float = 0f,
    lock: Float = 0f,
) {
    val s = size.minDimension
    val unit = s / 100f
    val left = (size.width - s) / 2f
    val top = (size.height - s) / 2f
    // The curve sits inside the frame with 20 % padding; y grows upwards.
    fun at(x: Float, y: Float) = Offset(left + (20f + 60f * x) * unit, top + (80f - 60f * y) * unit)
    val (p1, p2) = controls(bend)
    val px = GOLDEN
    val py = heightAt(px, p1, p2)
    val pt = at(px, py)

    if (grid > 0f) {
        val g = ink.copy(alpha = 0.14f * grid)
        drawLine(g, Offset(pt.x, top + 6f * unit), Offset(pt.x, top + 94f * unit), 0.6f * unit)
        drawLine(g, Offset(left + 6f * unit, pt.y), Offset(left + 94f * unit, pt.y), 0.6f * unit)
    }
    if (frame > 0f) {
        val w = 3f * unit
        val box = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left + w / 2f, top + w / 2f, left + s - w / 2f, top + s - w / 2f,
                    CornerRadius(25f * unit),
                ),
            )
        }
        drawPath(partial(box, frame), ink, style = Stroke(w, cap = StrokeCap.Round))
    }
    if (line > 0f) {
        val a = at(0f, 0f)
        val b = at(1f, 1f)
        val c1 = at(p1.x, p1.y)
        val c2 = at(p2.x, p2.y)
        val curve = Path().apply { moveTo(a.x, a.y); cubicTo(c1.x, c1.y, c2.x, c2.y, b.x, b.y) }
        drawPath(partial(curve, line), ink, style = Stroke(6f * unit, cap = StrokeCap.Round))
    }
    if (lock > 0f && lock < 1f) {
        drawCircle(HeadsupColors.AccentSoft.copy(alpha = 1f - lock), 9.3f * unit * (1f + 1.2f * lock), pt, style = Stroke(1.5f * unit))
    }
    if (dot > 0f) {
        drawCircle(ground, 13.8f * unit * dot, pt)
        drawCircle(point, 9.3f * unit * dot, pt)
    }
}

/** The first [fraction] of [path]. */
private fun partial(path: Path, fraction: Float): Path {
    if (fraction >= 1f) return path
    val measure = PathMeasure().apply { setPath(path, false) }
    return Path().also { measure.getSegment(0f, measure.length * fraction.coerceIn(0f, 1f), it, true) }
}

/** The mark alone, sized like an icon, in the theme's colours. */
@Composable
fun SweetSpotMark(modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val ink = HeadsupColors.Ink
    val ground = HeadsupColors.Background
    val point = HeadsupColors.AccentMark
    Canvas(modifier.size(size)) { sweetSpotMark(ink, ground, point) }
}

/**
 * Start-up animation, "finding the sweet spot", about 2.8 seconds: the frame draws itself, the
 * curve appears as the straight diagonal of the untouched picture, then searches (bends too far,
 * not enough) and settles; the orange point riding it at the golden ratio locks with one soft
 * ring, the name fades in, and the overlay fades out. [onRevealed] comes when the point locks, so
 * heavier work can start without stealing its frames. It plays at its own pace even when the
 * phone's animations are shortened; with them turned off it shows the last frame and fades.
 */
@Composable
fun SplashOverlay(onRevealed: () -> Unit = {}, onFinished: () -> Unit) {
    val frame = remember { Animatable(0f) }
    val line = remember { Animatable(0f) }
    val search = remember { Animatable(0f) }
    val dot = remember { Animatable(0f) }
    val grid = remember { Animatable(0f) }
    val lock = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    val context = LocalContext.current
    val still = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    LaunchedEffect(Unit) {
        withContext(RealTime) {
            if (still) {
                listOf(frame, line, search, dot, word).forEach { it.snapTo(1f) }
                onRevealed()
                delay(600)
                fade.animateTo(0f, tween(300))
                onFinished()
                return@withContext
            }
            val ease = FastOutSlowInEasing
            delay(120)
            launch { frame.animateTo(1f, tween(400, easing = ease)) }
            launch { grid.animateTo(1f, tween(400)) }
            delay(260)
            line.animateTo(1f, tween(520, easing = ease))
            // The search: the bend follows a damped spring (it may pass 1), the point grows on it.
            launch { dot.animateTo(1f, tween(200, easing = ease)) }
            search.animateTo(1f, tween(800, easing = LinearEasing))
            launch { lock.animateTo(1f, tween(420, easing = ease)) }
            onRevealed()
            launch { grid.animateTo(0f, tween(500)) }
            word.animateTo(1f, tween(500, easing = ease))
            delay(200)
            fade.animateTo(0f, tween(450))
            onFinished()
        }
    }
    val ink = HeadsupColors.Ink
    val ground = HeadsupColors.Background
    val point = HeadsupColors.AccentMark
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .background(HeadsupColors.BackgroundGradient),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Canvas(Modifier.size(112.dp)) {
                sweetSpotMark(
                    ink, ground, point,
                    frame = frame.value, line = line.value, bend = searching(search.value),
                    dot = dot.value, grid = grid.value, lock = lock.value,
                )
            }
            Text(
                "SweetSpot",
                Modifier.graphicsLayer {
                    alpha = word.value
                    translationY = 6.dp.toPx() * (1f - word.value)
                },
                style = TextStyle(
                    fontFamily = GeistFamily, fontWeight = FontWeight.Medium, fontSize = 30.sp,
                    letterSpacing = (0.04f - 0.06f * word.value).em, color = ink,
                ),
            )
        }
    }
}
