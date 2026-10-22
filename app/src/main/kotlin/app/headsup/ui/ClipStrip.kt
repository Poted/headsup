package app.headsup.ui

import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
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
fun ClipStrip(clips: List<ClipState>, selected: Int?, onSelect: (Int) -> Unit, onAdd: () -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(clips, key = { _, c -> c.meta.uri.toString() }) { i, clip ->
            ClipThumb(clip, i == selected) { onSelect(i) }
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

@Composable
private fun ClipThumb(clip: ClipState, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumbnail by produceState<ImageBitmap?>(null, clip.meta.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, clip.meta.uri)
                    retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)?.asImageBitmap()
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
            .clickable(onClick = onClick),
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
        Text(
            Formats.duration(clip.meta.durationMs),
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
