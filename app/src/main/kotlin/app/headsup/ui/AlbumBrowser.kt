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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom

/**
 * The whole album, large: stars by hand (tap a star), Auto pick (takes of one shot grouped, the
 * best of each starred; nothing is ever rejected), a filter, and marking what is shown; the set
 * shown is the one the editor then works through (Edit these). A tap on a photo opens it large
 * ([PhotoViewer]); held, it is marked.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun AlbumBrowser(state: UiState, vm: HeadsupViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val thumbs = remember { Filmstrip(context, scope) }
    DisposableEffect(thumbs) { onDispose { thumbs.release() } }
    // Starts on the set the editor shows.
    var filter by rememberSaveable { mutableStateOf(state.albumScope) }
    val photos = state.clips.withIndex().filter { it.value.meta.isPhoto }
    val shown = photos.filter { (_, c) -> filter.has(c) }
    // Select: a tap marks (or unmarks) a photo instead of opening it.
    var selecting by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val markedCount = photos.count { it.value.picked }
    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete $markedCount marked photos from the album?") },
            text = { Text("They are taken out of this album only; the files stay on the phone. Undo brings them back.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false; vm.removePicked() }) { Text("Delete") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    // The photo looked at large (its place in [shown]), or null.
    var viewing by rememberSaveable { mutableStateOf<Int?>(null) }
    viewing?.let { start ->
        if (shown.isNotEmpty()) PhotoViewer(state, vm, shown.map { it.index }, start.coerceIn(0, shown.lastIndex), onEdit = { i -> vm.selectClip(i); viewing = null; onClose() }) { viewing = null }
        else viewing = null
    }
    // Takes of one shot: how many there are and which of them each is.
    val groupSizes = photos.mapNotNull { it.value.cullGroup }.groupingBy { it }.eachCount()
    val bars = systemBars()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(HeadsupColors.Background).padding(bars)) {
            // Top: what the album holds and which set the editor works through; closing.
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Album", style = MaterialTheme.typography.titleMedium)
                    val starred = photos.count { it.value.rating > 0 }
                    val editing = if (state.albumScope == AlbumScope.ALL) "all" else "${state.albumScope.label} (${photos.count { state.albumScope.has(it.value) }})"
                    Text(
                        "${photos.size} photos · $starred with stars · editing $editing",
                        style = MaterialTheme.typography.labelMedium, color = HeadsupColors.TextMuted, maxLines = 1,
                    )
                }
                SmallAction("✕", onClose)
            }
            // The sets, on one line.
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            ) {
                items(AlbumScope.entries.size) { k ->
                    val f = AlbumScope.entries[k]
                    val n = photos.count { f.has(it.value) }
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text("${f.label} · $n") })
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
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
                                .combinedClickable(onLongClick = { vm.togglePicked(i) }) {
                                    if (selecting) vm.togglePicked(i) else viewing = shown.indexOfFirst { it.index == i }
                                },
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
                            if (selecting && c.picked) Box(Modifier.matchParentSize().background(HeadsupColors.Highlight.copy(alpha = 0.15f)))
                            // The mark: always tappable while selecting, shown when set.
                            if (c.picked || selecting) {
                                Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) { MarkButton(c.picked, 26.dp) { vm.togglePicked(i) } }
                            }
                        }
                        StarRow(c.rating, c.suggested) { stars -> vm.setRating(i, stars) }
                    }
                }
            }
            // Bottom, under the thumb: one bar, its actions by what is going on.
            Column(
                Modifier.fillMaxWidth().background(HeadsupColors.Surface).padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                when {
                    state.busy && state.loadingMessage != null -> {
                        // Auto pick at work (shown here: the album covers the screen's loader).
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(state.loadingMessage, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            SmallAction("Cancel", { vm.cancelLoading() })
                        }
                        state.loadingProgress?.let { p -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) }
                            ?: LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                    selecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$markedCount marked", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        if (shown.any { !it.value.picked }) SmallAction("All", {
                            val ids = shown.map { it.value.id }.toSet()
                            state.clips.forEachIndexed { i, c -> if (c.meta.isPhoto && c.id in ids && !c.picked) vm.togglePicked(i) }
                        })
                        if (markedCount > 0) {
                            SmallAction("None", { state.clips.forEachIndexed { i, c -> if (c.picked) vm.togglePicked(i) } })
                            SmallAction("Delete", { confirmDelete = true })
                        }
                        Spacer(Modifier.width(4.dp))
                        PillAction("✓", "Done", { selecting = false }, selected = true)
                    }
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        AutoPickButton(enabled = photos.isNotEmpty(), suggested = photos.any { it.value.suggested }, onPick = vm::autoPick, onClear = vm::clearSuggestions)
                        Spacer(Modifier.width(6.dp))
                        PillAction("✓", "Select", { selecting = true })
                        Spacer(Modifier.weight(1f))
                        // The set shown becomes the one the editor works through.
                        PillAction("✎", if (filter == AlbumScope.ALL) "Edit all" else "Edit ${shown.size}", {
                            vm.setAlbumScope(filter)
                            onClose()
                        }, enabled = shown.isNotEmpty(), selected = true)
                    }
                }
            }
        }
    }
}

/**
 * Auto pick, with what it does a tap away: a first tap runs it; once it has suggested, a menu
 * offers running it again or taking its suggestions back.
 */
@Composable
private fun AutoPickButton(enabled: Boolean, suggested: Boolean, onPick: () -> Unit, onClear: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        PillAction("✦", "Auto pick", { if (suggested) menu = true else onPick() }, enabled = enabled)
        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Text(
                "Groups takes of one shot and stars the sharpest, level, well exposed one of each " +
                    "(${HeadsupViewModel.SUGGESTED_STARS} hollow stars). Leaves out screenshots. Never removes a photo; your stars stay.",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(260.dp),
                style = MaterialTheme.typography.labelSmall, color = HeadsupColors.TextMuted,
            )
            androidx.compose.material3.DropdownMenuItem(text = { Text("Pick again") }, onClick = { menu = false; onPick() })
            androidx.compose.material3.DropdownMenuItem(text = { Text("Clear suggested stars") }, onClick = { menu = false; onClear() })
        }
    }
}

/**
 * The system bars' room, read from the app's own window: a dialog's window is not told it (so
 * its content ran under the navigation bar), while the album and viewer cover the whole screen.
 */
@Composable
private fun systemBars(): androidx.compose.foundation.layout.PaddingValues {
    val top = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return androidx.compose.foundation.layout.PaddingValues(top = top, bottom = bottom)
}

/** Five stars: tap one to give that many (the same again takes them off); suggested ones are hollow. */
@Composable
fun StarRow(rating: Int, suggested: Boolean, modifier: Modifier = Modifier, big: Boolean = false, onRate: (Int) -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Center) {
        for (k in 1..5) {
            val on = k <= rating
            Box(Modifier.size(if (big) 44.dp else 22.dp).clickable { onRate(k) }, contentAlignment = Alignment.Center) {
                Text(
                    if (on && !suggested) "★" else if (on) "✩" else "☆",
                    style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.labelLarge,
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

/**
 * The album's photos one at a time, full screen: swipe to the next or the one before, pinch or
 * double tap to zoom (dragged while zoomed), stars, a mark, and looks to try (shown on the photo
 * at once) and keep. Edit opens the photo in the editor.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoViewer(state: UiState, vm: HeadsupViewModel, indices: List<Int>, start: Int, onEdit: (Int) -> Unit, onClose: () -> Unit) {
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = start) { indices.size }
    var zoomed by remember { mutableStateOf(false) }
    // A look tried on the photo on screen (null: its own).
    var tried by remember { mutableStateOf<TriedLook?>(null) }
    var looks by remember { mutableStateOf(false) }
    val page = pager.currentPage.coerceIn(0, indices.lastIndex)
    val index = indices[page]
    androidx.compose.runtime.LaunchedEffect(page) { tried = null; zoomed = false }
    val bars = systemBars()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).padding(bars)) {
            val clip = state.clips.getOrNull(index)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${page + 1} / ${indices.size}", style = MaterialTheme.typography.titleSmall, color = Color.White)
                    Text(clip?.meta?.name.orEmpty(), style = MaterialTheme.typography.labelSmall, color = HeadsupColors.TextMuted, maxLines = 1)
                }
                SmallAction("✕", onClose)
            }
            androidx.compose.foundation.pager.HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                userScrollEnabled = !zoomed,
                beyondViewportPageCount = 1,
                key = { indices[it] },
            ) { p ->
                ViewerPage(vm, indices[p], state.clips.getOrNull(indices[p])?.let { listOf(it.grade, it.lutName, it.colorGrade) }, if (p == page) tried else null, onZoomed = { if (p == page) zoomed = it })
            }
            if (clip != null) {
                // Under the thumb: stars, then the mark, the looks and Edit.
                StarRow(clip.rating, clip.suggested, Modifier.padding(top = 4.dp), big = true) { stars -> vm.setRating(index, stars) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    MarkButton(clip.picked) { vm.togglePicked(index) }
                    Spacer(Modifier.width(8.dp))
                    PillAction("◐", "Looks", { looks = !looks }, selected = looks)
                    Spacer(Modifier.weight(1f))
                    tried?.let { t ->
                        SmallAction("Undo", { tried = null })
                        PillAction("✓", "Keep look", { vm.keepTriedLook(index, t); tried = null })
                        Spacer(Modifier.width(6.dp))
                    }
                    PillAction("✎", "Edit", { onEdit(index) })
                }
                if (looks) {
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        items(app.headsup.core.color.Look.entries.size) { k ->
                            val look = app.headsup.core.color.Look.entries[k]
                            val t = TriedLook.Profile(look)
                            FilterChip(selected = tried == t, onClick = { tried = if (tried == t) null else t }, label = { Text(look.label) })
                        }
                        items(state.presets.size) { k ->
                            val preset = state.presets[k]
                            val t = TriedLook.Preset(preset)
                            FilterChip(selected = tried == t, onClick = { tried = if (tried == t) null else t }, label = { Text("★ ${preset.name}") })
                        }
                    }
                }
            }
        }
    }
}

/** One photo of the viewer, coloured as edited (or with the look tried), zoomable. */
@Composable
private fun ViewerPage(vm: HeadsupViewModel, index: Int, edit: Any?, tried: TriedLook?, onZoomed: (Boolean) -> Unit) {
    val picture by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, index) {
        value = vm.viewerPicture(index, 2048)?.asImageBitmap()
    }
    val shader = remember { if (android.os.Build.VERSION.SDK_INT >= 33) app.headsup.color.GradePreviewShader() else null }
    var tableVersion by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(index, tried, edit) {
        val table = vm.viewerTable(index, tried)
        shader?.setTables(app.headsup.color.GradeTables(table))
        tableVersion++
    }
    var zoom by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Box(
        Modifier.fillMaxSize().clipToBounds()
            .pointerInput(index) {
                // One handler for all of it, so a double tap is never taken for the start of a swipe.
                var lastTapMs = 0L
                var lastTap = androidx.compose.ui.geometry.Offset.Zero
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = false
                    var many = false
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2) many = true
                        event.changes.firstOrNull()?.let { c -> if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true }
                        // Two fingers zoom; one moves the zoomed photo (at 1× it swipes to the next).
                        if (fingers >= 2 || zoom > 1f) {
                            val z = event.calculateZoom()
                            val pan = event.calculatePan()
                            zoom = (zoom * z).coerceIn(1f, 6f)
                            offset = clampOffset(offset + pan, zoom, size.width.toFloat(), size.height.toFloat())
                            onZoomed(zoom > 1f)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    // A tap; a second one soon after, near the first, zooms in there (or back out).
                    if (!moved && !many) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapMs < 320L && (down.position - lastTap).getDistance() < 80f) {
                            if (zoom > 1f) {
                                zoom = 1f
                                offset = androidx.compose.ui.geometry.Offset.Zero
                            } else {
                                zoom = 2.5f
                                val centre = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                                offset = clampOffset((centre - down.position) * (zoom - 1f), zoom, size.width.toFloat(), size.height.toFloat())
                            }
                            onZoomed(zoom > 1f)
                            lastTapMs = 0L
                        } else {
                            lastTapMs = now
                            lastTap = down.position
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val img = picture
        if (img == null) {
            androidx.compose.material3.CircularProgressIndicator(color = HeadsupColors.TextMuted)
        } else {
            Image(
                img, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    tableVersion
                    scaleX = zoom
                    scaleY = zoom
                    translationX = offset.x
                    translationY = offset.y
                    renderEffect = shader?.effect(app.headsup.render.EffectSettings(), false, 0f, size.width, size.height, emptyList(), 0f)
                },
            )
        }
    }
}

/** How far a photo zoomed by [zoom] can be moved and still fill the view ([w]×[h]). */
private fun clampOffset(o: androidx.compose.ui.geometry.Offset, zoom: Float, w: Float, h: Float): androidx.compose.ui.geometry.Offset {
    if (zoom <= 1f) return androidx.compose.ui.geometry.Offset.Zero
    val mx = w * (zoom - 1f) / 2f
    val my = h * (zoom - 1f) / 2f
    return androidx.compose.ui.geometry.Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
}

/** A photo's mark: a tick, filled when marked. */
@Composable
fun MarkButton(marked: Boolean, size: androidx.compose.ui.unit.Dp = 36.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (marked) HeadsupColors.Highlight else Color.Black.copy(alpha = 0.35f))
            .border(1.5.dp, if (marked) HeadsupColors.Highlight else Color.White.copy(alpha = 0.8f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("✓", style = MaterialTheme.typography.labelLarge, color = if (marked) HeadsupColors.OnHighlight else Color.White)
    }
}
