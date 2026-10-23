package app.headsup.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Typography
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object HeadsupColors {
    // Neutral charcoal surfaces with a faint warm glow; orange is reserved for the camera-style
    // controls (rulers and correction dials).
    // Neutral charcoal with a faint warm glow; light (white) selected states. Orange is kept for
    // the camera-style controls: play button, rulers, correction dials and the selected clip.
    val Background = Color(0xFF0A0A0A)
    val BackgroundGlow = Color(0xFF241F1B)
    val BackgroundMid = Color(0xFF141312)
    val Surface = Color(0xFF161616)
    val SurfaceTop = Color(0xFF1F1E1D)
    val SurfaceHigh = Color(0xFF242322)
    val Outline = Color(0xFF2F2E2C)
    val Highlight = Color(0xFFF2EFEB)
    val OnHighlight = Color(0xFF141414)
    val OnAccent = Color(0xFF1E1006)
    val Accent = Color(0xFFFF8A3D)
    val AccentSoft = Color(0xFFFFB574)
    val AccentStrong = Color(0xFFE5641C)
    val TextMuted = Color(0xFF9A9A9A)
    val Good = Color(0xFF7BD389)
    val Warn = Color(0xFFFFC14D)
    val Bad = Color(0xFFFF6B5B)

    /** Card fill: slightly lighter at the top, like the reference design. */
    val CardGradient = Brush.verticalGradient(listOf(SurfaceTop, Surface))

    /** Screen background: a warm amber glow fading into near-black. */
    val BackgroundGradient = Brush.verticalGradient(0f to BackgroundGlow, 0.35f to BackgroundMid, 1f to Background)
}

@Composable
fun HeadsupTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = HeadsupColors.Highlight,
            onPrimary = HeadsupColors.OnHighlight,
            primaryContainer = Color(0xFF2C2C2C),
            onPrimaryContainer = Color.White,
            secondaryContainer = Color(0xFF2E2D2C),
            onSecondaryContainer = Color.White,
            background = HeadsupColors.Background,
            onBackground = Color.White,
            surface = HeadsupColors.Surface,
            onSurface = Color(0xFFF2F2F2),
            surfaceVariant = HeadsupColors.SurfaceHigh,
            onSurfaceVariant = HeadsupColors.TextMuted,
            surfaceContainer = HeadsupColors.Surface,
            surfaceContainerHigh = HeadsupColors.SurfaceHigh,
            surfaceContainerHighest = HeadsupColors.SurfaceHigh,
            outline = HeadsupColors.Outline,
            outlineVariant = HeadsupColors.Outline,
            error = HeadsupColors.Bad,
            errorContainer = Color(0xFF3A1B18),
            onErrorContainer = Color(0xFFFFDAD3),
        ),
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
        // Text without an explicit colour is light on the dark background.
        CompositionLocalProvider(LocalContentColor provides Color(0xFFF2F2F2), content = content)
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

    val Add = icon("add") { stroke { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) } }

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
