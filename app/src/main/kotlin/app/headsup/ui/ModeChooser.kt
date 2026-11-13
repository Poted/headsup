package app.headsup.ui

import androidx.compose.foundation.background
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
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("Sweet Spot", style = MaterialTheme.typography.headlineMedium, color = HeadsupColors.Ink)
        ModeCard("Editor", "Cut, grade and export films and photos.", onEditor)
        ModeCard("Planner", "Where the sun, moon and Milky Way will be, and the filter, shutter and ISO to set there.", onPlanner)
    }
}

@Composable
private fun ModeCard(title: String, text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(HeadsupColors.CardGradient)
            .clickable(onClick = onClick)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = HeadsupColors.Ink)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = HeadsupColors.TextMuted)
    }
}
