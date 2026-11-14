package app.headsup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.headsup.core.planner.Exposure
import kotlin.math.roundToInt

/**
 * Plans: everything set now (place, day, hour, the camera's settings, the sky) kept under a name,
 * to open again later, e.g. at home the day before and then on the spot.
 */
@Composable
internal fun PlansPanel(st: PlannerState, onOpened: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Int?>(null) }
    ToolSection("Save this plan", info = "A plan keeps the place, the day and hour, the camera's settings and the sky as they are set now, e.g. made at home the day before and opened on the spot.") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("Name", maxLines = 1) }, modifier = Modifier.weight(1f),
            )
            Chip("Save", {
                val n = name.trim()
                if (n.isNotEmpty()) { st.savePlan(n); name = "" }
            })
        }
    }
    ToolSection("Saved", info = "Tap a plan to open it. ✕, then Delete?, removes it.") {
        if (st.plans.isEmpty()) Text("No plans yet.", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
        st.plans.forEachIndexed { i, p ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(HeadsupColors.SurfaceHigh)
                    .clickable { st.openPlan(i); onOpened() }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = HeadsupColors.Ink, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${p.date} ${hhmm(p.minute)} · ${one(p.lat)}, ${one(p.lon)} · ${p.elevation.roundToInt()} m",
                        color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        "${p.camera.ifEmpty { "Any camera" }} · ${Exposure.formatAperture(p.aperture)} · ${if (p.shutterAuto) "Auto" else Exposure.formatShutter(p.shutter)} · ISO ${if (p.isoAuto) "Auto" else Exposure.snapIso(p.iso)}",
                        color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelSmall,
                    )
                }
                Chip(if (deleting == i) "Delete?" else "✕", {
                    if (deleting == i) { st.deletePlan(i); deleting = null } else deleting = i
                })
            }
        }
    }
}
