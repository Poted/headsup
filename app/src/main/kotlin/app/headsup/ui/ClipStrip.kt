package app.headsup.ui

import android.media.MediaMetadataRetriever
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.headsup.core.sync.Confidence
import app.headsup.render.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val THUMB_SHAPE = RoundedCornerShape(12.dp)

/** Horizontal strip of clip thumbnails, like the timeline of a video editor. */
@Composable
fun ClipStrip(
    clips: List<ClipState>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onDelete: (Int) -> Unit,
    groups: List<ClipGroup> = emptyList(),
    /** Puts a clip in a group (null takes it out). */
    onGroup: (Int, Long?) -> Unit = { _, _ -> },
    onNewGroup: (Int) -> Unit = {},
) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(clips, key = { _, c -> c.id }) { i, clip ->
            // Long-press opens the clip menu.
            var menu by remember { mutableStateOf(false) }
            Box {
                val group = groups.firstOrNull { it.id == clip.groupId }
                ClipThumb(clip, group?.name, i == selected, onClick = { onSelect(i) }, onLongClick = { onSelect(i); menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (i > 0) DropdownMenuItem(text = { Text("Move left") }, onClick = { menu = false; onMove(i, -1) })
                    if (i < clips.lastIndex) DropdownMenuItem(text = { Text("Move right") }, onClick = { menu = false; onMove(i, 1) })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete(i) })
                    HorizontalDivider()
                    // A clip is in at most one group, so picking another one moves it.
                    for (g in groups) {
                        DropdownMenuItem(
                            text = { Text((if (g.id == clip.groupId) "✓ " else "") + g.name) },
                            onClick = { menu = false; onGroup(i, if (g.id == clip.groupId) null else g.id) },
                        )
                    }
                    DropdownMenuItem(text = { Text("New group") }, onClick = { menu = false; onNewGroup(i) })
                }
            }
        }
        item {
            Box(
                Modifier
                    .size(width = 56.dp, height = 56.dp)
                    .clip(THUMB_SHAPE)
                    .border(1.dp, HeadsupColors.Outline, THUMB_SHAPE)
                    .clickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) { Icon(HeadsupIcons.Add, contentDescription = "Add videos", tint = HeadsupColors.Highlight) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipThumb(clip: ClipState, groupName: String?, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val thumbnail by produceState<ImageBitmap?>(null, clip.meta.uri, clip.trimStartMs) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, clip.meta.uri)
                    retriever.getScaledFrameAtTime(clip.trimStartMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)?.asImageBitmap()
                } finally {
                    retriever.release()
                }
            }.getOrNull()
        }
    }
    val confidence = clip.sync?.confidence
    Box(
        Modifier
            .size(width = 96.dp, height = 56.dp)
            .clip(THUMB_SHAPE)
            .background(HeadsupColors.SurfaceHigh)
            .border(if (selected) 2.dp else 1.dp, if (selected) HeadsupColors.Accent else HeadsupColors.Outline, THUMB_SHAPE)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        thumbnail?.let { Image(it, contentDescription = clip.meta.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .size(9.dp)
                .background(
                    when (confidence) {
                        Confidence.HIGH -> HeadsupColors.Good
                        Confidence.MEDIUM -> HeadsupColors.Warn
                        else -> HeadsupColors.Bad
                    },
                    CircleShape,
                ),
        )
        groupName?.let {
            Text(
                it,
                Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(HeadsupColors.Highlight.copy(alpha = 0.9f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = HeadsupColors.OnHighlight,
                maxLines = 1,
            )
        }
        Text(
            Formats.duration(clip.durationMs) + if (clip.isTrimmed) " ✂" else "",
            Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                .padding(horizontal = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}
