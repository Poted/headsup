package app.headsup.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
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
 * The light planner: three views of one plan. The sun view shows where the sun, moon and Milky Way
 * go over a place (with a map of it); the live view lays that sky over the camera and reads the
 * light; the exposure view works out the settings and the filter. The place, day and hour are
 * shared, so what is set in one is seen in the others. Laid out as the editor: the place at the top
 * (tap it to change it), the views at the bottom, what is used now and then in sheets. Back returns
 * to the first screen. Offline, except the map's tiles, which are kept once seen.
 */
@Composable
fun PlannerScreen(onBack: () -> Unit, onEditor: () -> Unit) {
    val context = LocalContext.current
    val st = remember { PlannerState(context) }
    var view by rememberSaveable { mutableStateOf(0) } // 0 sky, 1 exposure
    // The sky: here and now through the camera, or the place set on the map at the hour set.
    var hereNow by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf(0) } // 0 none, 1 place, 2 times, 3 plans
    androidx.activity.compose.BackHandler(onBack = onBack)

    // The ground's height follows the place (a moment after it stops changing, e.g. while typing).
    LaunchedEffect(st.lat, st.lon) {
        kotlinx.coroutines.delay(700)
        st.fetchGround()
        st.buildSkyline()
    }
    // A camera higher up sees another skyline.
    LaunchedEffect(st.aboveGround) {
        kotlinx.coroutines.delay(700)
        st.buildSkyline()
    }

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
        // The top: back, the place (tap to change it), plans and the editor.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconAction(HeadsupIcons.Back, "Back to the start", onBack)
            Column(
                Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClickLabel = "Change the place") { sheet = 1 }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(placeTitle(st), color = HeadsupColors.Ink, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    Text(" ▾", color = HeadsupColors.TextMuted, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "${st.elevation.roundToInt()} m" + (if (st.aboveGround > 0) " + ${st.aboveGround.roundToInt()} m up" else "") + " · UTC${if (st.offsetHours >= 0) "+" else ""}${st.offsetHours}",
                    color = HeadsupColors.TextMuted, style = MaterialTheme.typography.labelSmall.copy(fontFamily = GeistMonoFamily), maxLines = 1,
                )
            }
            IconAction(HeadsupIcons.Bookmark, "Plans", { sheet = 3 })
            IconAction(HeadsupIcons.Pencil, "Open the editor", onEditor)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (view) {
                0 -> Column(Modifier.fillMaxSize()) {
                    ChoiceGrid(
                        listOf("Here, now", "On the map"), if (hereNow) 0 else 1,
                        { i ->
                            hereNow = i == 0
                            if (hereNow) {
                                // Here and now: the phone's place and the present hour.
                                locate()
                                val t = java.time.LocalTime.now()
                                st.day = java.time.LocalDate.now()
                                st.minute = (t.hour * 60 + t.minute).toFloat()
                            }
                        },
                        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        // Here, now: the camera is the page. On the map: the sky chart with the map in its corner.
                        if (hereNow) LiveView(st, locate, onCalculator = { view = 1 }, modifier = Modifier.fillMaxSize())
                        else SunView(st, onCalculator = { view = 1 }, onTimes = { sheet = 2 }, modifier = Modifier.fillMaxSize())
                    }
                }
                // Each view opens at its top.
                else -> androidx.compose.runtime.key(view) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 16.dp)) {
                        CalculatorView(st)
                    }
                }
            }
        }
        PlannerBar(view) { view = it }
    }

    when (sheet) {
        1 -> OptionsSheet("Place", { sheet = 0 }) { PlacePanel(st, locate) }
        2 -> OptionsSheet("Times of the day", { sheet = 0 }) { TimesPanel(st) }
        3 -> OptionsSheet("Plans", { sheet = 0 }) { PlansPanel(st, onOpened = { sheet = 0 }) }
    }
}

/** The place as the top bar names it: the saved name, or its coordinates. */
private fun placeTitle(st: PlannerState): String =
    st.selectedPlace?.let { st.places.getOrNull(it)?.name }
        ?: String.format(java.util.Locale.US, "%.4f, %.4f", st.lat, st.lon)
