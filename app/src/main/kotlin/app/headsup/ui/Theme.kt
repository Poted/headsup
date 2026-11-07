package app.headsup.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.headsup.R

/**
 * The SweetSpot palette, one per theme. Values are the design system's tokens (named in the
 * comments); dark is the app's original charcoal and orange.
 */
class Palette(
    val background: Color, // ground
    val backgroundGlow: Color, // ground-glow
    val backgroundMid: Color, // ground-mid
    val surface: Color, // surface
    val surfaceTop: Color, // surface-raised
    val surfaceHigh: Color, // surface-high
    val outline: Color, // hairline: decoration only
    val controlEdge: Color, // control-edge: a control found by its edge (>= 3:1)
    val ink: Color, // ink: text and icons on the surfaces
    val textMuted: Color, // ink-muted
    val highlight: Color, // selected
    val onHighlight: Color, // on-selected
    val accentInk: Color, // accent-ink: orange as text or a thin line
    val accentMark: Color, // accent-mark: the logo point
    val good: Color,
    val warn: Color,
    val bad: Color,
    val badContainer: Color,
    /** Behind graphs that draw light lines (curves, scopes, wheels): dark in both themes, like the picture. */
    val instrument: Color,
    val isDark: Boolean,
)

val DarkPalette = Palette(
    background = Color(0xFF0A0A0A), backgroundGlow = Color(0xFF241F1B), backgroundMid = Color(0xFF141312),
    surface = Color(0xFF161616), surfaceTop = Color(0xFF1F1E1D), surfaceHigh = Color(0xFF242322),
    outline = Color(0xFF2F2E2C), controlEdge = Color(0xFF7A766F),
    ink = Color(0xFFF2EFEB), textMuted = Color(0xFF9A9A9A),
    highlight = Color(0xFFF2EFEB), onHighlight = Color(0xFF141414),
    accentInk = Color(0xFFFF8A3D), accentMark = Color(0xFFFF8A3D),
    good = Color(0xFF7BD389), warn = Color(0xFFFFC14D), bad = Color(0xFFFF6B5B), badContainer = Color(0xFF3A1B18),
    instrument = Color.Black.copy(alpha = 0.35f),
    isDark = true,
)

val LightPalette = Palette(
    background = Color(0xFFF6F4F1), backgroundGlow = Color(0xFFFBF8F4), backgroundMid = Color(0xFFF8F6F3),
    surface = Color(0xFFFFFFFF), surfaceTop = Color(0xFFFBFAF8), surfaceHigh = Color(0xFFEBE8E4),
    outline = Color(0xFFDCD8D2), controlEdge = Color(0xFF878279),
    ink = Color(0xFF141414), textMuted = Color(0xFF66635D),
    highlight = Color(0xFF141414), onHighlight = Color(0xFFF6F4F1),
    accentInk = Color(0xFFA8430A), accentMark = Color(0xFFE5641C),
    good = Color(0xFF2F7A3D), warn = Color(0xFF8A5A00), bad = Color(0xFFC4352A), badContainer = Color(0xFFFBE9E6),
    instrument = Color(0xFF1A1918),
    isDark = false,
)

object HeadsupColors {
    /** The palette in use; [HeadsupTheme] sets it. Kept in snapshot state so drawing code follows a theme change. */
    var palette: Palette by mutableStateOf(DarkPalette)

    val Background get() = palette.background
    val BackgroundGlow get() = palette.backgroundGlow
    val BackgroundMid get() = palette.backgroundMid
    val Surface get() = palette.surface
    val SurfaceTop get() = palette.surfaceTop
    val SurfaceHigh get() = palette.surfaceHigh
    val Outline get() = palette.outline
    val ControlEdge get() = palette.controlEdge
    val Ink get() = palette.ink
    val TextMuted get() = palette.textMuted
    val Highlight get() = palette.highlight
    val OnHighlight get() = palette.onHighlight
    val AccentInk get() = palette.accentInk
    val AccentMark get() = palette.accentMark
    val Instrument get() = palette.instrument
    val Good get() = palette.good
    val Warn get() = palette.warn
    val Bad get() = palette.bad

    // The sweet-spot orange is the same in both themes; text on it is OnAccent.
    val OnAccent = Color(0xFF1E1006)
    val Accent = Color(0xFFFF8A3D)
    val AccentSoft = Color(0xFFFFB574)
    val AccentStrong = Color(0xFFE5641C)

    /** The photo or clip is always judged on black, in both themes. */
    val Picture = Color.Black

    /** A ruler's zero tick: soft orange on dark, the darker orange on light. */
    val ZeroTick get() = if (palette.isDark) AccentSoft else palette.accentInk

    /** Card fill: slightly lighter at the top. */
    val CardGradient get() = Brush.verticalGradient(listOf(SurfaceTop, Surface))

    /** Screen background: a warm glow at the top fading into the ground. */
    val BackgroundGradient get() = Brush.verticalGradient(0f to BackgroundGlow, 0.35f to BackgroundMid, 1f to Background)
}

/** Geist (SIL Open Font License, see assets/licenses/geist_OFL.txt): one variable file, any weight. */
@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, vararg weights: Int) = FontFamily(
    weights.map { Font(res, FontWeight(it), variationSettings = FontVariation.Settings(FontVariation.weight(it))) },
)

val GeistFamily = variable(R.font.geist, 300, 400, 500, 600, 700)
val GeistMonoFamily = variable(R.font.geist_mono, 400, 500)

private fun Typography.inFamily(f: FontFamily): Typography {
    fun TextStyle.f() = copy(fontFamily = f)
    return copy(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
    )
}

private fun scheme(p: Palette): ColorScheme {
    val base = if (p.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.highlight,
        onPrimary = p.onHighlight,
        primaryContainer = p.surfaceHigh,
        onPrimaryContainer = p.ink,
        secondary = p.accentInk,
        onSecondary = if (p.isDark) HeadsupColors.OnAccent else Color.White,
        secondaryContainer = p.surfaceHigh,
        onSecondaryContainer = p.ink,
        tertiary = p.accentInk,
        background = p.background,
        onBackground = p.ink,
        surface = p.surface,
        onSurface = p.ink,
        surfaceVariant = p.surfaceHigh,
        onSurfaceVariant = p.textMuted,
        surfaceContainerLowest = p.surface,
        surfaceContainerLow = p.surface,
        surfaceContainer = p.surface,
        surfaceContainerHigh = p.surfaceHigh,
        surfaceContainerHighest = p.surfaceHigh,
        surfaceBright = p.surfaceTop,
        surfaceDim = p.background,
        inverseSurface = p.ink,
        inverseOnSurface = p.background,
        outline = p.controlEdge,
        outlineVariant = p.outline,
        error = p.bad,
        onError = if (p.isDark) Color.Black else Color.White,
        errorContainer = p.badContainer,
        onErrorContainer = p.ink,
        scrim = Color.Black,
    )
}

private val LocalPalette = staticCompositionLocalOf<Palette?> { null }

/**
 * The app's theme: follows the system's dark or light setting. Nested with [dark] = true it
 * makes a part dark whatever the system says (the full-screen picture, on black), and gives the
 * outer palette back when that part leaves.
 */
@Composable
fun HeadsupTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (dark) DarkPalette else LightPalette
    val outer = LocalPalette.current
    // Set before the content reads it, so the first frame already has the right colours.
    remember(palette) { HeadsupColors.palette = palette; palette }
    if (outer != null) {
        // The outer palette as it is when this part leaves (the system's setting may change meanwhile).
        val outerNow by rememberUpdatedState(outer)
        DisposableEffect(palette) { onDispose { HeadsupColors.palette = outerNow } }
    }
    val base = Typography().inFamily(GeistFamily)
    MaterialTheme(
        colorScheme = scheme(palette),
        // Lighter weights for an elegant look.
        typography = base.copy(
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Light, letterSpacing = 0.5.sp),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Normal),
            headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Light),
        ),
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(28.dp),
        ),
    ) {
        // Text without an explicit colour is ink on the ground.
        CompositionLocalProvider(LocalContentColor provides palette.ink, LocalPalette provides palette, content = content)
    }
}

/** Small line icons drawn here so the app does not need the large Material icon library. */
object HeadsupIcons {
    private fun icon(name: String, build: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(build).build()

    private val white = SolidColor(Color.White)

    private fun ImageVector.Builder.stroke(block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        path(stroke = white, strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)

    val Play = icon("play") { path(fill = white) { moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close() } }

    val Pause = icon("pause") {
        path(fill = white) {
            moveTo(7f, 5f); lineTo(10f, 5f); lineTo(10f, 19f); lineTo(7f, 19f); close()
            moveTo(14f, 5f); lineTo(17f, 5f); lineTo(17f, 19f); lineTo(14f, 19f); close()
        }
    }

    /** A letter T: text. */
    val Text = icon("text") { stroke { moveTo(5f, 6f); lineTo(19f, 6f); moveTo(12f, 6f); lineTo(12f, 19f) } }

    val Add = icon("add") { stroke { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) } }

    /** Chevron up: unfold. */
    val ChevronUp = icon("chevron-up") { stroke { moveTo(6f, 15f); lineTo(12f, 9f); lineTo(18f, 15f) } }

    /** Chevron down: fold. */
    val ChevronDown = icon("chevron-down") { stroke { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) } }

    /** A tick: milestones (moments of the edit marked as done). */
    val Check = icon("check") { stroke { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) } }

    /** Three dots: more actions. */
    val More = icon("more") {
        path(fill = white) {
            for (y in listOf(6f, 12f, 18f)) {
                moveTo(12f + 1.6f, y); arcTo(1.6f, 1.6f, 0f, true, true, 12f - 1.6f, y); arcTo(1.6f, 1.6f, 0f, true, true, 12f + 1.6f, y); close()
            }
        }
    }

    /** Clock: synchronisation. */
    val Sync = icon("sync") {
        stroke {
            moveTo(12f, 3f); arcTo(9f, 9f, 0f, true, true, 12f, 21f); arcTo(9f, 9f, 0f, true, true, 12f, 3f)
            moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15.5f, 14f)
        }
    }

    /** Half-filled circle: colour. */
    val Colour = icon("colour") {
        stroke { moveTo(12f, 3f); arcTo(9f, 9f, 0f, true, true, 12f, 21f); arcTo(9f, 9f, 0f, true, true, 12f, 3f) }
        path(fill = white) { moveTo(12f, 3f); arcTo(9f, 9f, 0f, false, true, 12f, 21f); close() }
    }

    /** Heart-rate line: dashboard. */
    val Dashboard = icon("dashboard") {
        stroke { moveTo(3f, 12f); lineTo(7f, 12f); lineTo(9.5f, 6f); lineTo(13.5f, 18f); lineTo(16f, 12f); lineTo(21f, 12f) }
    }

    /** Arrow out of a tray: export. */
    val Export = icon("export") {
        stroke {
            moveTo(12f, 15f); lineTo(12f, 3.5f); moveTo(7.5f, 8f); lineTo(12f, 3.5f); lineTo(16.5f, 8f)
            moveTo(5f, 13f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 13f)
        }
    }

    /** An eraser with a spark: Clean up (removing things). */
    val Erase = icon("erase") {
        stroke {
            moveTo(4f, 15.5f); lineTo(11.5f, 8f); lineTo(16f, 12.5f); lineTo(10.5f, 18f); lineTo(6.5f, 18f); close()
            moveTo(8f, 11.5f); lineTo(12.5f, 16f)
            moveTo(10.5f, 18f); lineTo(20f, 18f)
            moveTo(18f, 3f); lineTo(18f, 7f); moveTo(16f, 5f); lineTo(20f, 5f)
        }
    }

    /** Arrow curving back: undo. */
    val Undo = icon("undo") {
        stroke {
            moveTo(9f, 14f); lineTo(4f, 9f); lineTo(9f, 4f)
            moveTo(4f, 9f); lineTo(14.5f, 9f); arcTo(5.5f, 5.5f, 0f, false, true, 14.5f, 20f); lineTo(11f, 20f)
        }
    }

    /** Arrow curving forward: redo. */
    val Redo = icon("redo") {
        stroke {
            moveTo(15f, 14f); lineTo(20f, 9f); lineTo(15f, 4f)
            moveTo(20f, 9f); lineTo(9.5f, 9f); arcTo(5.5f, 5.5f, 0f, false, false, 9.5f, 20f); lineTo(13f, 20f)
        }
    }

    /** Crop corners: framing. */
    val Frame = icon("frame") {
        stroke {
            moveTo(7f, 3f); lineTo(7f, 17f); lineTo(21f, 17f)
            moveTo(3f, 7f); lineTo(17f, 7f); lineTo(17f, 21f)
        }
    }

    /** Film frame: videos. */
    val Video = icon("video") {
        stroke {
            moveTo(4f, 6f); lineTo(20f, 6f); lineTo(20f, 18f); lineTo(4f, 18f); close()
            moveTo(8f, 6f); lineTo(8f, 18f); moveTo(16f, 6f); lineTo(16f, 18f)
        }
    }

    /** Scissors: editing. */
    val Cut = icon("cut") {
        stroke {
            moveTo(8f, 6f); arcTo(2.5f, 2.5f, 0f, true, true, 3f, 6f); arcTo(2.5f, 2.5f, 0f, true, true, 8f, 6f)
            moveTo(8f, 18f); arcTo(2.5f, 2.5f, 0f, true, true, 3f, 18f); arcTo(2.5f, 2.5f, 0f, true, true, 8f, 18f)
            moveTo(7.5f, 7.5f); lineTo(20f, 17f)
            moveTo(7.5f, 16.5f); lineTo(20f, 7f)
        }
    }

    /** Note: music and sound. */
    val Music = icon("music") {
        stroke {
            moveTo(9f, 18f); lineTo(9f, 5f); lineTo(19f, 3f); lineTo(19f, 16f)
            moveTo(9f, 18f); arcTo(2.5f, 2.5f, 0f, true, true, 4f, 18f); arcTo(2.5f, 2.5f, 0f, true, true, 9f, 18f)
            moveTo(19f, 16f); arcTo(2.5f, 2.5f, 0f, true, true, 14f, 16f); arcTo(2.5f, 2.5f, 0f, true, true, 19f, 16f)
        }
    }

    /** Star: the best moments. */
    val Star = icon("star") {
        stroke {
            moveTo(12f, 3.5f); lineTo(14.6f, 9f); lineTo(20.5f, 9.6f); lineTo(16f, 13.6f); lineTo(17.3f, 19.5f)
            lineTo(12f, 16.5f); lineTo(6.7f, 19.5f); lineTo(8f, 13.6f); lineTo(3.5f, 9.6f); lineTo(9.4f, 9f); close()
        }
    }

    /** Watch: activity files. */
    val Watch = icon("watch") {
        stroke {
            moveTo(8f, 7f); lineTo(16f, 7f); lineTo(16f, 17f); lineTo(8f, 17f); close()
            moveTo(9.5f, 7f); lineTo(10f, 3f); lineTo(14f, 3f); lineTo(14.5f, 7f)
            moveTo(9.5f, 17f); lineTo(10f, 21f); lineTo(14f, 21f); lineTo(14.5f, 17f)
        }
    }
}
