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
import androidx.compose.ui.unit.dp

object HeadsupColors {
    val Background = Color(0xFF0B0B10)
    val Surface = Color(0xFF16161D)
    val SurfaceHigh = Color(0xFF212129)
    val Outline = Color(0xFF34343F)
    val Accent = Color(0xFFC9A7FF)
    val AccentStrong = Color(0xFF8C5CFF)
    val TextMuted = Color(0xFF9C9AA8)
    val Good = Color(0xFF5BD68A)
    val Warn = Color(0xFFFFC14D)
    val Bad = Color(0xFFFF6B6B)
}

@Composable
fun HeadsupTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = HeadsupColors.Accent,
            onPrimary = Color(0xFF1C1033),
            primaryContainer = Color(0xFF3A2A5C),
            onPrimaryContainer = Color.White,
            secondaryContainer = Color(0xFF2E2540),
            onSecondaryContainer = Color(0xFFE9DDFF),
            background = HeadsupColors.Background,
            onBackground = Color.White,
            surface = HeadsupColors.Surface,
            onSurface = Color.White,
            surfaceVariant = HeadsupColors.SurfaceHigh,
            onSurfaceVariant = HeadsupColors.TextMuted,
            surfaceContainer = HeadsupColors.Surface,
            surfaceContainerHigh = HeadsupColors.SurfaceHigh,
            surfaceContainerHighest = HeadsupColors.SurfaceHigh,
            outline = HeadsupColors.Outline,
            outlineVariant = HeadsupColors.Outline,
            error = HeadsupColors.Bad,
            errorContainer = Color(0xFF4A1F24),
            onErrorContainer = Color(0xFFFFDAD6),
        ),
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}

/** Small line icons drawn here so the app does not need the large Material icon library. */
object HeadsupIcons {
    private fun icon(name: String, build: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(build).build()

    private val white = SolidColor(Color.White)

    private fun ImageVector.Builder.stroke(block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        path(stroke = white, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)

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

    /** Film frame: videos. */
    val Video = icon("video") {
        stroke {
            moveTo(4f, 6f); lineTo(20f, 6f); lineTo(20f, 18f); lineTo(4f, 18f); close()
            moveTo(8f, 6f); lineTo(8f, 18f); moveTo(16f, 6f); lineTo(16f, 18f)
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
