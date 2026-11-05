package app.headsup.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Which album photos the browser shows. */
private enum class AlbumFilter(val label: String) { ALL("All"), STARRED("★ 1+"), GOOD("★ 3+"), UNRATED("No stars"), MARKED("Marked") }

/**
 * The whole album, large: stars by hand (tap a star), Auto pick (takes of one shot grouped, the
 * best of each starred; nothing is ever rejected), a filter, and marking what is shown. A tap on
 * a photo opens it for editing; held, it is marked.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun AlbumBrowser(state: UiState, vm: HeadsupViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val thumbs = remember { Filmstrip(context, scope) }
    DisposableEffect(thumbs) { onDispose { thumbs.release() } }
    var filter by rememberSaveable { mutableStateOf(AlbumFilter.ALL) }
    val photos = state.clips.withIndex().filter { it.value.meta.isPhoto }
    val shown = photos.filter { (_, c) ->
        when (filter) {
            AlbumFilter.ALL -> true
            AlbumFilter.STARRED -> c.rating >= 1
            AlbumFilter.GOOD -> c.rating >= 3
            AlbumFilter.UNRATED -> c.rating == 0
            AlbumFilter.MARKED -> c.picked
        }
    }
    // Takes of one shot: how many there are and which of them each is.
    val groupSizes = photos.mapNotNull { it.value.cullGroup }.groupingBy { it }.eachCount()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(HeadsupColors.Background).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Album", style = MaterialTheme.typography.titleMedium)
                    val starred = photos.count { it.value.rating > 0 }
                    val marked = photos.count { it.value.picked }
                    Text(
                        "${photos.size} photos · $starred with stars" + (if (marked > 0) " · $marked marked" else ""),
                        style = MaterialTheme.typography.labelMedium, color = HeadsupColors.TextMuted,
                    )
                }
                SmallAction("Close", onClose)
            }
            if (state.busy && state.loadingMessage != null) {
                // Auto pick at work (it shows here: the dialog covers the screen's loader).
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.loadingMessage, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        SmallAction("Cancel", { vm.cancelLoading() })
                    }
                    state.loadingProgress?.let { p -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) }
                        ?: LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillAction("✦", "Auto pick", { vm.autoPick() }, enabled = photos.isNotEmpty())
                    Spacer(Modifier.width(6.dp))
                    if (photos.any { it.value.suggested }) SmallAction("Clear suggested", { vm.clearSuggestions() })
                    Spacer(Modifier.weight(1f))
                    if (filter != AlbumFilter.ALL && filter != AlbumFilter.MARKED && shown.isNotEmpty()) {
                        SmallAction("Mark these ${shown.size}", {
                            val ids = shown.map { it.value.id }.toSet()
                            state.clips.forEachIndexed { i, c -> if (c.picked != (c.id in ids) && c.meta.isPhoto) vm.togglePicked(i) }
                        })
                    }
                }
                Text(
                    "Auto pick groups takes of one shot and stars the sharpest, best exposed of each (${HeadsupViewModel.SUGGESTED_STARS} stars, shown hollow until you change them). It never removes or rejects a photo; stars you gave stay.",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = HeadsupColors.TextMuted,
                )
            }
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (f in AlbumFilter.entries) FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                items(shown, key = { it.value.id }) { (i, c) ->
                    val selected = i == state.selectedClip
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) HeadsupColors.SurfaceHigh else HeadsupColors.Surface)
                            .border(if (selected) 2.dp else 1.dp, if (selected) HeadsupColors.Accent else HeadsupColors.Outline, RoundedCornerShape(10.dp)),
                    ) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(1f)
                                .combinedClickable(onLongClick = { vm.togglePicked(i) }) { vm.selectClip(i); onClose() },
                        ) {
                            thumbs.version.intValue
                            thumbs.frame(c.meta.uri, 0L, 1L, photo = true)?.let { img ->
                                Image(img, contentDescription = c.meta.name, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                            }
                            // Takes of one shot: which shot, and how many takes it has.
                            c.cullGroup?.let { g ->
                                val n = groupSizes[g] ?: 1
                                if (n > 1) {
                                    Text(
                                        "Shot ${g + 1} · $n",
                                        Modifier.align(Alignment.TopStart).padding(4.dp).clip(RoundedCornerShape(6.dp))
                                            .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 5.dp, vertical = 1.dp),
                                        style = MaterialTheme.typography.labelSmall, color = Color.White,
                                    )
                                }
                            }
                            if (c.picked) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).clip(CircleShape).background(HeadsupColors.Highlight),
                                    contentAlignment = Alignment.Center,
                                ) { Text("✓", style = MaterialTheme.typography.labelSmall, color = HeadsupColors.OnHighlight) }
                            }
                        }
                        StarRow(c.rating, c.suggested) { stars -> vm.setRating(i, stars) }
                    }
                }
            }
        }
    }
}

/** Five stars: tap one to give that many (the same again takes them off); suggested ones are hollow. */
@Composable
fun StarRow(rating: Int, suggested: Boolean, modifier: Modifier = Modifier, onRate: (Int) -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Center) {
        for (k in 1..5) {
            val on = k <= rating
            Box(Modifier.size(22.dp).clickable { onRate(k) }, contentAlignment = Alignment.Center) {
                Text(
                    if (on && !suggested) "★" else if (on) "✩" else "☆",
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        on && suggested -> HeadsupColors.AccentSoft
                        on -> HeadsupColors.Accent
                        else -> HeadsupColors.TextMuted.copy(alpha = 0.6f)
                    },
                )
            }
        }
    }
}
