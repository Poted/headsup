package app.headsup.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Row
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * The first screen after the logo: the editor for films and photos, or the light planner for
 * working out where the sun goes and what to set on the camera. Back from either returns here.
 */
@Composable
fun ModeChooser(onEditor: () -> Unit, onPlanner: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(HeadsupColors.Background)
            // Touches stop here: nothing beneath may feel them.
            .pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            SweetSpotMark(Modifier.size(40.dp))
            Text("SweetSpot", style = MaterialTheme.typography.headlineMedium, color = HeadsupColors.Ink)
        }
        LastCrash()
        ModeCard(HeadsupIcons.Pencil, "Editor", "Cut, grade and export films and photos.", onEditor)
        ModeCard(HeadsupIcons.Sun, "Planner", "Where the sun, moon and Milky Way will be, and the aperture, shutter, ISO and filter to set there.", onPlanner)
        Text("Back from either returns here.", style = MaterialTheme.typography.labelSmall, color = HeadsupColors.TextMuted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ModeCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(HeadsupColors.CardGradient)
            .border(1.dp, HeadsupColors.Outline, shape)
            .clickable(onClickLabel = "Open the $title", onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(HeadsupColors.SurfaceHigh), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, Modifier.size(24.dp), tint = HeadsupColors.Ink)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = HeadsupColors.Ink)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = HeadsupColors.TextMuted)
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = HeadsupColors.TextMuted)
    }
}

/**
 * When the app stopped by itself last time, its report (kept by HeadsupApp in Reports) is shown here
 * once, before either half opens, with Copy so it can be sent to whoever fixes it.
 */
@Composable
private fun LastCrash() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val prefs = remember { context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE) }
    val report = remember {
        Reports.all(context).firstOrNull()?.let { f ->
            val at = f.name.removePrefix("report-").removeSuffix(".txt").toLongOrNull() ?: 0L
            if (at > prefs.getLong("chooser_seen", 0L)) at to runCatching { f.readText() }.getOrDefault("") else null
        }
    }
    var shown by remember { mutableStateOf(report != null) }
    if (!shown || report == null) return
    val hide = { prefs.edit().putLong("chooser_seen", report.first).apply(); shown = false }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(HeadsupColors.Bad.copy(alpha = 0.15f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("The app stopped last time", style = MaterialTheme.typography.titleSmall, color = HeadsupColors.Bad)
        Text(
            report.second.lines().take(8).joinToString("\n"),
            style = MaterialTheme.typography.labelSmall, color = HeadsupColors.Ink, maxLines = 8,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Copy", { clipboard.setText(AnnotatedString(report.second)) })
            Chip("Hide", { hide() })
        }
    }
}
