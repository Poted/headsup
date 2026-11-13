package app.headsup.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * The light planner: two views of one plan. The sun view shows where the sun, moon and Milky Way
 * go over a place (with a map of it); the calculator works out the exposure and the filter. The
 * place, day and hour are shared, so what is set in one is seen in the other. Offline, except
 * the map's tiles, which are kept once seen.
 */
@Composable
fun PlannerScreen(onEditor: () -> Unit) {
    val context = LocalContext.current
    val st = remember { PlannerState(context) }
    var view by rememberSaveable { mutableStateOf(0) } // 0 sun, 1 calculator

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) lastKnown(context)?.let { st.setPlace(it.first, it.second, it.third) }
    }
    val locate: () -> Unit = {
        val ok = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (ok) lastKnown(context)?.let { st.setPlace(it.first, it.second, it.third) }
        else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(HeadsupColors.Background)
            // Touches stop here: the editor beneath must not feel them.
            .pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Planner", style = MaterialTheme.typography.titleMedium, color = HeadsupColors.Ink, modifier = Modifier.padding(end = 8.dp))
            Chip("Sun", { view = 0 }, selected = view == 0)
            Chip("Calculator", { view = 1 }, selected = view == 1)
            Row(Modifier.weight(1f)) {}
            Chip("Editor", onEditor)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (view == 0) SunView(st, locate) else CalculatorView(st)
            Row(Modifier.padding(bottom = 16.dp)) {}
        }
    }
}
