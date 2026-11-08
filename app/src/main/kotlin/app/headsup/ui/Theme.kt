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
 * A SweetSpot theme's palette. Values are the design system's tokens (named in the comments).
 * Each theme is made for a way of working, not for a colour: see [Theme].
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
    val accent: Color, // accent: the sweet-spot colour as a fill
    val accentSoft: Color,
    val accentStrong: Color,
    val onAccent: Color,
    val accentInk: Color, // accent-ink: the accent as text or a thin line
    val accentMark: Color, // accent-mark: the logo point
    val good: Color,
    val warn: Color,
    val bad: Color,
    val badContainer: Color,
    /** Behind graphs that draw light lines (curves, scopes, wheels): dark in every theme, like the picture. */
    val instrument: Color,
    /** The lines of those graphs. */
    val instrumentInk: Color,
    /** Round the photo or clip. */
    val picture: Color,
    val isDark: Boolean,
    /** Field: heavier lines and type, to read in the sun. */
    val bold: Boolean = false,
)

val DarkPalette = Palette(
    background = Color(0xFF0A0A0A), backgroundGlow = Color(0xFF241F1B), backgroundMid = Color(0xFF141312),
    surface = Color(0xFF161616), surfaceTop = Color(0xFF1F1E1D), surfaceHigh = Color(0xFF242322),
    outline = Color(0xFF2F2E2C), controlEdge = Color(0xFF7A766F),
    ink = Color(0xFFF2EFEB), textMuted = Color(0xFF9A9A9A),
    highlight = Color(0xFFF2EFEB), onHighlight = Color(0xFF141414),
    accent = Color(0xFFFF8A3D), accentSoft = Color(0xFFFFB574), accentStrong = Color(0xFFE5641C), onAccent = Color(0xFF1E1006),
    accentInk = Color(0xFFFF8A3D), accentMark = Color(0xFFFF8A3D),
    good = Color(0xFF7BD389), warn = Color(0xFFFFC14D), bad = Color(0xFFFF6B5B), badContainer = Color(0xFF3A1B18),
    instrument = Color.Black.copy(alpha = 0.35f), instrumentInk = Color.White, picture = Color.Black,
    isDark = true,
)

val LightPalette = Palette(
    background = Color(0xFFF6F4F1), backgroundGlow = Color(0xFFFBF8F4), backgroundMid = Color(0xFFF8F6F3),
    surface = Color(0xFFFFFFFF), surfaceTop = Color(0xFFFBFAF8), surfaceHigh = Color(0xFFEBE8E4),
    outline = Color(0xFFDCD8D2), controlEdge = Color(0xFF878279),
    ink = Color(0xFF141414), textMuted = Color(0xFF66635D),
    highlight = Color(0xFF141414), onHighlight = Color(0xFFF6F4F1),
    accent = Color(0xFFFF8A3D), accentSoft = Color(0xFFFFB574), accentStrong = Color(0xFFE5641C), onAccent = Color(0xFF1E1006),
    accentInk = Color(0xFFA8430A), accentMark = Color(0xFFE5641C),
    good = Color(0xFF2F7A3D), warn = Color(0xFF8A5A00), bad = Color(0xFFC4352A), badContainer = Color(0xFFFBE9E6),
    instrument = Color(0xFF1A1918), instrumentInk = Color.White, picture = Color.Black,
    isDark = false,
)

/**
 * Grading: the neutral grey of a colourist's suite (ISO 3664, SMPTE), no warm cast and an almost
 * colourless accent, so nothing round the picture tints how its colour is judged; the picture sits
 * on a mid-dark grey, not on black.
 */
val GradingPalette = Palette(
    background = Color(0xFF1F1F1F), backgroundGlow = Color(0xFF1F1F1F), backgroundMid = Color(0xFF1F1F1F),
    surface = Color(0xFF262626), surfaceTop = Color(0xFF2A2A2A), surfaceHigh = Color(0xFF333333),
    outline = Color(0xFF3D3D3D), controlEdge = Color(0xFF808080),
    ink = Color(0xFFE8E8E8), textMuted = Color(0xFFA3A3A3),
    highlight = Color(0xFFE8E8E8), onHighlight = Color(0xFF1A1A1A),
    accent = Color(0xFFCFCFCF), accentSoft = Color(0xFFA8A8A8), accentStrong = Color(0xFFB5B5B5), onAccent = Color(0xFF141414),
    accentInk = Color(0xFFE0E0E0), accentMark = Color(0xFFD0D0D0),
    good = Color(0xFF8FCF99), warn = Color(0xFFE6C36B), bad = Color(0xFFF08A7E), badContainer = Color(0xFF3A2826),
    instrument = Color(0xFF181818), instrumentInk = Color(0xFFE8E8E8), picture = Color(0xFF333333),
    isDark = true,
)

/**
 * Darkroom: red on black, dimmed, like a darkroom's safelight; it keeps the eyes used to the
 * dark (as astronomers' red torches do), for editing at night between shots.
 */
val DarkroomPalette = Palette(
    background = Color(0xFF000000), backgroundGlow = Color(0xFF0A0302), backgroundMid = Color(0xFF050101),
    surface = Color(0xFF0E0605), surfaceTop = Color(0xFF140807), surfaceHigh = Color(0xFF1E0B09),
    outline = Color(0xFF2A0F0C), controlEdge = Color(0xFFB8382A),
    ink = Color(0xFFFF5A48), textMuted = Color(0xFFE8483A),
    highlight = Color(0xFFFF5A48), onHighlight = Color(0xFF000000),
    accent = Color(0xFFFF3B2A), accentSoft = Color(0xFFC0301F), accentStrong = Color(0xFFD02A1A), onAccent = Color(0xFF000000),
    accentInk = Color(0xFFFF5A48), accentMark = Color(0xFFFF3B2A),
    good = Color(0xFFFF7A6A), warn = Color(0xFFFF6A58), bad = Color(0xFFFF3B2A), badContainer = Color(0xFF2A0A07),
    instrument = Color(0xFF050101), instrumentInk = Color(0xFFFF5A48), picture = Color.Black,
    isDark = true,
)

/** Field: the most contrast and heavier lines and type, to read outdoors in full sun. */
val FieldPalette = Palette(
    background = Color(0xFFFFFFFF), backgroundGlow = Color(0xFFFFFFFF), backgroundMid = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF), surfaceTop = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFE9E9E9),
    outline = Color(0xFF9A9A9A), controlEdge = Color(0xFF000000),
    ink = Color(0xFF000000), textMuted = Color(0xFF3D3D3D),
    highlight = Color(0xFF000000), onHighlight = Color(0xFFFFFFFF),
    accent = Color(0xFFD4560F), accentSoft = Color(0xFFE5641C), accentStrong = Color(0xFFB84A0C), onAccent = Color(0xFF1E1006),
    accentInk = Color(0xFF8F3600), accentMark = Color(0xFFD4560F),
    good = Color(0xFF1F5E2B), warn = Color(0xFF6B4500), bad = Color(0xFFA32A20), badContainer = Color(0xFFFBE9E6),
    instrument = Color(0xFF000000), instrumentInk = Color.White, picture = Color.Black,
    isDark = false, bold = true,
)

/** The themes, each for a way of working. */
enum class Theme(val label: String, val palette: Palette, val about: String) {
    STUDIO("Studio", DarkPalette, "Black and orange, for the edit suite."),
    PAPER("Paper", LightPalette, "Warm white, like prints on a table."),
    GRADING("Grading", GradingPalette, "Neutral grey all round, for judging colour."),
    DARKROOM("Darkroom", DarkroomPalette, "Red on black, keeps your eyes used to the dark."),
    FIELD("Field", FieldPalette, "Most contrast, for the sun."),
    ;

    /**
     * The palette round the picture shown alone (full screen, the photo viewer): a dark one, the
     * theme's own where it is made for that.
     */
    val onPicture: Palette get() = if (palette.isDark) palette else DarkPalette
}

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
    val InstrumentInk get() = palette.instrumentInk
    val Good get() = palette.good
    val Warn get() = palette.warn
    val Bad get() = palette.bad

    // The sweet-spot colour (orange, except where a theme needs another); text on it is OnAccent.
    val OnAccent get() = palette.onAccent
    val Accent get() = palette.accent
    val AccentSoft get() = palette.accentSoft
    val AccentStrong get() = palette.accentStrong

    /** Round the photo or clip: black, or the grading suite's grey. */
    val Picture get() = palette.picture

    /** A ruler's zero tick: the soft accent on dark, the darker one on light. */
    val ZeroTick get() = if (palette.isDark) AccentSoft else palette.accentInk

    /** How much heavier lines are drawn (Field: in the sun). */
    val StrokeScale get() = if (palette.bold) 1.6f else 1f

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

val GeistFamily = variable(R.font.geist, 300, 400, 450, 500, 550, 600, 650, 700, 750)
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

private fun Typography.heavier(): Typography {
    fun TextStyle.h() = copy(fontWeight = FontWeight(((fontWeight ?: FontWeight.Normal).weight + 150).coerceAtMost(800)))
    return copy(
        displayLarge = displayLarge.h(), displayMedium = displayMedium.h(), displaySmall = displaySmall.h(),
        headlineLarge = headlineLarge.h(), headlineMedium = headlineMedium.h(), headlineSmall = headlineSmall.h(),
        titleLarge = titleLarge.h(), titleMedium = titleMedium.h(), titleSmall = titleSmall.h(),
        bodyLarge = bodyLarge.h(), bodyMedium = bodyMedium.h(), bodySmall = bodySmall.h(),
        labelLarge = labelLarge.h(), labelMedium = labelMedium.h(), labelSmall = labelSmall.h(),
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
        onSecondary = if (p.isDark) p.onAccent else Color.White,
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
    HeadsupTheme(if (dark) DarkPalette else LightPalette, content)
}

/** The theme with a given [palette]; nested, it changes a part and gives the outer one back when that part leaves. */
@Composable
fun HeadsupTheme(palette: Palette, content: @Composable () -> Unit) {
    val outer = LocalPalette.current
    // Set before the content reads it, so the first frame already has the right colours.
    remember(palette) { HeadsupColors.palette = palette; palette }
    if (outer != null) {
        // The outer palette as it is when this part leaves (the system's setting may change meanwhile).
        val outerNow by rememberUpdatedState(outer)
        DisposableEffect(palette) { onDispose { HeadsupColors.palette = outerNow } }
    }
    val base = Typography().inFamily(GeistFamily)
    // Lighter weights for an elegant look.
    val typography = base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Light, letterSpacing = 0.5.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Normal),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Light),
    )
    MaterialTheme(
        colorScheme = scheme(palette),
        // Field reads in the sun: every style a step heavier (sizes stay, so layouts stay).
        typography = if (palette.bold) typography.heavier() else typography,
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

    /** Two arrows, one each way: switch to another project. */
    val Switch = icon("switch") {
        stroke {
            moveTo(4f, 8f); lineTo(19f, 8f); moveTo(15f, 4f); lineTo(19f, 8f); lineTo(15f, 12f)
            moveTo(20f, 16f); lineTo(5f, 16f); moveTo(9f, 12f); lineTo(5f, 16f); lineTo(9f, 20f)
        }
    }

    /** A pencil: rename. */
    val Pencil = icon("pencil") {
        stroke {
            moveTo(4f, 20f); lineTo(5f, 15.5f); lineTo(15.5f, 5f); lineTo(19f, 8.5f); lineTo(8.5f, 19f); close()
            moveTo(13f, 7.5f); lineTo(16.5f, 11f)
        }
    }

    /** Four squares: the album view. */
    val Grid = icon("grid") {
        stroke {
            for ((x, y) in listOf(4f to 4f, 13f to 4f, 4f to 13f, 13f to 13f)) {
                moveTo(x, y); lineTo(x + 7f, y); lineTo(x + 7f, y + 7f); lineTo(x, y + 7f); close()
            }
        }
    }

    /** Three short lines with knobs: a layer's options. */
    val Options = icon("options") {
        stroke {
            moveTo(4f, 7f); lineTo(20f, 7f); moveTo(4f, 12f); lineTo(20f, 12f); moveTo(4f, 17f); lineTo(20f, 17f)
        }
        path(fill = white) {
            for ((x, y) in listOf(9f to 7f, 15f to 12f, 7f to 17f)) {
                moveTo(x + 2f, y); arcTo(2f, 2f, 0f, true, true, x - 2f, y); arcTo(2f, 2f, 0f, true, true, x + 2f, y); close()
            }
        }
    }

    /** A gear: the app's settings. */
    val Settings = icon("settings") {
        stroke {
            // The wheel and its hub.
            moveTo(12f, 5.5f); arcTo(6.5f, 6.5f, 0f, true, true, 12f, 18.5f); arcTo(6.5f, 6.5f, 0f, true, true, 12f, 5.5f)
            moveTo(12f, 9.5f); arcTo(2.5f, 2.5f, 0f, true, true, 12f, 14.5f); arcTo(2.5f, 2.5f, 0f, true, true, 12f, 9.5f)
            // Eight teeth round it.
            for (k in 0 until 8) {
                val a = Math.toRadians(k * 45.0)
                val c = kotlin.math.cos(a).toFloat()
                val s = kotlin.math.sin(a).toFloat()
                moveTo(12f + 6.5f * c, 12f + 6.5f * s); lineTo(12f + 9f * c, 12f + 9f * s)
            }
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
