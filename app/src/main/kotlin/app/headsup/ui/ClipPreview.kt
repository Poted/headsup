package app.headsup.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChangedBy
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import app.headsup.color.LiveColorLut
import androidx.media3.exoplayer.ExoPlayer
import android.os.Build
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.alpha
import app.headsup.core.color.LutBaker
import app.headsup.render.EffectSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import android.view.TextureView
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.graphicsLayer
import app.headsup.color.BrushAtlas
import app.headsup.color.GradePreviewShader
import app.headsup.core.track.TelemetrySampler
import app.headsup.render.DashboardData
import app.headsup.render.DashboardRenderer
import app.headsup.render.DashboardStyle
import app.headsup.render.Formats
import app.headsup.render.Layouts
import app.headsup.render.Placement
import app.headsup.render.Widget
import java.time.Instant
import java.time.ZoneId
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import app.headsup.core.color.ColourMask
import app.headsup.core.color.ColourTransition
import app.headsup.core.color.MaskKind
import app.headsup.core.frame.Reframe
import app.headsup.core.edit.Fade
import app.headsup.core.edit.TextItem
import app.headsup.render.TextRenderer
import app.headsup.media.RawPhotos
import app.headsup.export.AudioClip
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.withTransform
import app.headsup.color.GradeTables
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberCoroutineScope
import androidx.media3.common.PlaybackException
import app.headsup.media.DecoderBudget
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Plays the clip with the dashboard drawn on top by the same renderer the export uses.
 * Widgets can be dragged with a finger; [onMoveWidget] receives the new placement while
 * dragging and [onMoveFinished] once the finger lifts.
 */
@Composable
fun ClipPreview(
    clip: ClipState,
    activity: LoadedActivity?,
    style: DashboardStyle,
    /** Baked colour table to preview, or null for untouched colours. */
    gradeCube: Array<Array<IntArray>>?,
    /** Masks and their colour tables (Android 13+ preview only). */
    masks: List<ColourMask> = emptyList(),
    maskCubes: List<Array<Array<IntArray>>> = emptyList(),
    /** Previous clip's colour table and how long the clip blends in from it. */
    fromCube: Array<Array<IntArray>>? = null,
    /** The masks of the clip blended in from (shown as at its end, [fromMaskMs]), and their tables. */
    fromMasks: List<ColourMask> = emptyList(),
    fromMaskCubes: List<Array<Array<IntArray>>> = emptyList(),
    fromMaskMs: Long = 0L,
    transitionMs: Long = 0L,
    /** Mask being edited: its outline is drawn, and dragging on the picture moves it. */
    guideMask: ColourMask? = null,
    onMoveMask: ((Float, Float) -> Unit)? = null,
    /** Two fingers on the picture while a mask is edited: move (fractions of the picture), scale, turn (degrees). */
    onTransformMask: ((dx: Float, dy: Float, zoom: Float, degrees: Float) -> Unit)? = null,
    /** An oval's end (0 right, 1 left, 2 bottom, 3 top, before turning) dragged to (x, y), fractions of the picture. */
    onMaskEnd: ((end: Int, x: Float, y: Float) -> Unit)? = null,
    /**
     * Painting a brush mask: a stroke starts (0), goes on (1) and ends (2) at a point of the picture
     * (fractions); the last argument grabs the picture (before colour) at a width, for finding edges.
     */
    onBrush: ((phase: Int, x: Float, y: Float, grab: (Int) -> Bitmap?) -> Unit)? = null,
    /** Which of [masks] [guideMask] is (its slot of the brush atlas). */
    guideIndex: Int = 0,
    /** An outline being drawn around an object (x, y pairs, fractions of the picture). */
    lassoPath: List<Float> = emptyList(),
    /** Told the picture's height on screen in pixels (as framed), e.g. to show a brush at its real size. */
    onPictureHeight: ((Float) -> Unit)? = null,
    /** Painting a mask: two fingers zoom and pan the view (one paints), to reach small things. */
    zoomForPainting: Boolean = false,
    /** With [zoomForPainting]: one finger also moves the zoomed view (nothing is painted). */
    panView: Boolean = false,
    /** The selected painted mask shown filled in red; otherwise only its outline. */
    maskOverlay: Boolean = false,
    /** A brush stroke being painted (fractions of the picture), its radius (of the picture's height), strength, and whether it erases. */
    paintPath: List<Float> = emptyList(),
    paintRadius: Float = 0f,
    paintStrength: Float = 1f,
    paintErase: Boolean = false,
    /** Changed when the brush's size, softness or strength is set: the brush shows on the picture for a moment. */
    brushRingAt: Long = 0L,
    /** The painted mask's softness, for the brush shown on the picture. */
    brushSoftness: Float = 0.5f,
    vignette: Float,
    /** Texture, clarity and dehaze of the selected clip. */
    presence: app.headsup.core.color.Presence = app.headsup.core.color.Presence.NONE,
    effects: EffectSettings,
    maxHeight: Dp,
    /** Full screen: the picture edge to edge, no margins or rounded corners. */
    edgeToEdge: Boolean = false,
    /** A finger held still on the picture: true while it shows the original (as in Lightroom). */
    onHoldOriginal: ((Boolean) -> Unit)? = null,
    /** The original is shown (the corrections left out): a label says so. */
    showingOriginal: Boolean = false,
    /** What the label says while [showingOriginal]. */
    originalLabel: String = "Original",
    onMoveWidget: (Widget, Placement) -> Unit,
    onMoveFinished: () -> Unit,
    /** When set, tapping the picture (away from widgets) grabs the frame for the vectorscope. */
    onSample: ((Bitmap, Float, Float) -> Unit)? = null,
    /** Point last measured, 0..1 of the frame, drawn as a target. */
    marker: Offset? = null,
    /** Playhead position within the (trimmed) clip, reported as it changes. */
    onPosition: (Long) -> Unit = {},
    /**
     * Position in file time while a cut handle is dragged: the whole file is loaded so the
     * picture can follow the handle, also outside the current cut.
     */
    cutScrub: State<Long?> = NO_SCRUB,
    /** Receives a function that grabs the current frame (before colour), e.g. for analysis. */
    onFrameSource: ((() -> Bitmap?) -> Unit)? = null,
    /** Receives a function that grabs the current picture (before colour) [width] pixels wide, e.g. for finding objects. */
    onPictureSource: (((width: Int) -> Bitmap?) -> Unit)? = null,
    /** Zoom, position and straightening; [onReframe] set means a finger on the picture pans and pinches it. */
    reframe: Reframe = Reframe(),
    onReframe: ((Reframe) -> Unit)? = null,
    /** Rule-of-thirds grid over the picture. */
    showGrid: Boolean = false,
    /** Start playing as soon as the clip is loaded (continuing from the previous clip). */
    autoPlay: Boolean = false,
    onAutoPlayStarted: () -> Unit = {},
    /** Playback reached the end of the clip. */
    onEnded: () -> Unit = {},
    /** To the clip before (-1) or after (+1): ⇤ pressed again at a clip's start, ⇥ at its end. */
    onStepClip: ((Int) -> Unit)? = null,
    /** Where a newly made preview starts in [clip] (from its cut): after turning the phone, where it was. */
    startMs: Long = 0L,
    /** Flags (or unflags) the moment shown: the clip's file time. */
    onFlag: ((Long) -> Unit)? = null,
    /** Position in the clip to jump to once (e.g. a highlight), then [onSeekDone]. */
    seekRequest: Long? = null,
    onSeekDone: () -> Unit = {},
    /** Audio clips on the tracks, played in step with the timeline; [timelineOffsetMs] is where this clip starts. */
    audio: List<AudioClip> = emptyList(),
    /** Level of the clips' own sound. */
    clipSound: Float = 1f,
    timelineOffsetMs: Long = 0L,
    /** The whole timeline's length, where the audio stops. */
    timelineTotalMs: Long = 0L,
    /** Position in the clip to show while the timeline is dragged (the picture follows as fast as it can). */
    scrubTo: State<Long?> = NO_SCRUB,
    /** Playback started or stopped. */
    onPlaying: (Boolean) -> Unit = {},
    /**
     * All clips of the timeline and the index of [clip] among them: the next video clip is loaded
     * ahead in a second player, with its first frame ready, and playback switches to it at the cut
     * without a pause; [onAdvance] reports the switch.
     */
    playlist: List<ClipState> = emptyList(),
    playlistIndex: Int = 0,
    onAdvance: (Int) -> Unit = {},
    /** Framing of any clip of the playlist (for a picture still showing another clip than [clip]). */
    framingOf: (ClipState) -> Reframe = { it.reframe },
    /** File the preview plays for a clip: a light preview file of a large clip, or the clip itself. */
    sourceOf: (ClipState) -> android.net.Uri = { it.meta.uri },
    /** Colour tables of the clips next to [clip] (null: untouched), for a picture still showing one at a cut. */
    neighbourCubes: Map<Long, Array<Array<IntArray>>?> = emptyMap(),
    /** The neighbours' blends in (the look of the clip before each). */
    neighbourFrom: Map<Long, Array<Array<IntArray>>?> = emptyMap(),
    /** The clip [gradeCube], [maskCubes] and [fromCube] were made for (they follow a new selection a moment later). */
    cubesFor: Long? = null,
    /** The timeline's texts, drawn over the picture at the timeline's time. */
    texts: List<TextItem> = emptyList(),
    /** Set while a text is edited: a finger on the picture places it (0..1 of the frame). */
    onMoveText: ((Float, Float) -> Unit)? = null,
    /** Only photos in the project: no play button or time. */
    photoMode: Boolean = false,
    /** Values of the texts' data fields at a position in this clip. */
    textValues: (Long) -> Map<String, String> = { emptyMap() },
    /** Draws the route map at a position in this clip (under the texts), or null. */
    routeOverlay: ((android.graphics.Canvas, Float, Float, Long) -> Unit)? = null,
    /** The bar under the picture (play, time or size, histogram, zebra). */
    showTransport: Boolean = true,
    /**
     * Held sideways, the transport can fold away for a bigger picture: when [compactTransport], play
     * and the time sit on the picture; [onCompact] (null: not offered) folds or unfolds it.
     */
    compactTransport: Boolean = false,
    onCompact: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    // Two players: one shows the clip, the other holds the next one ready (a different shape or
    // file needs a new decoder, which in a single player shows as a pause at the cut).
    val players = remember { List(2) { ExoPlayer.Builder(context).build() } }
    DisposableEffect(players) { onDispose { players.forEach { it.release() } } }
    var active by remember { mutableIntStateOf(0) }
    // The player shown, as of this composition; effects that may run after a switch in the same
    // frame read players[active] themselves.
    val exoPlayer = players[active]
    // What each player holds: the clip's file and cut, or null.
    val holds = remember { arrayOfNulls<Any>(2) }
    val textureViews = remember { arrayOfNulls<TextureView>(2) }
    // Android 13+: colours are applied by a GPU shader on the displayed picture, so slider changes
    // are immediate. Older versions fall back to a Media3 effect inside the player.
    val shaderPreview = Build.VERSION.SDK_INT >= 33
    val liveLut = remember { LiveColorLut() }
    val scope = rememberCoroutineScope()
    // Seeks go to whichever player is showing at the time (a switch may happen in the same frame).
    val scrubber = remember { Scrubber({ players[active] }, scope) }
    val scrubMs = cutScrub.value
    val scrubbing = scrubMs != null
    // Photos are shown as a picture with their own clock; the player only plays videos.
    val photo = clip.meta.isPhoto
    var photoPlaying by remember { mutableStateOf(false) }
    // For each player, the clip whose frame is on its picture and that frame's shape (null until
    // the first one): a picture is always laid out for the clip it shows, also while a cut is passed.
    val pictureClip = remember { mutableStateListOf<Long?>(null, null) }
    val pictureAspect = remember { mutableStateListOf<Float?>(null, null) }
    // A raw (DNG) photo is developed by the app, again whenever its development changes.
    val raw = photo && clip.meta.isRaw
    // The file the picture shown was decoded from: while another is loading, a spinner shows.
    var photoFrom by remember { mutableStateOf<android.net.Uri?>(null) }
    // Sharpening and colour noise are applied by the shader: changing them needs no new development.
    val developKey = if (raw) clip.rawDevelop.copy(sharpen = 0f, colourNoise = 0f) else null
    // Clean up's patches lie over the photo as decoded: a change of them decodes it again.
    val photoBitmap by produceState<ImageBitmap?>(null, clip.meta.uri, photo, developKey, clip.retouch) {
        if (!photo) { value = null; return@produceState }
        // Tried again a few times: while a project opens, other reads of the same files may hold
        // the memory or the file for a moment.
        for (attempt in 0 until 3) {
            val bitmap = withContext(Dispatchers.Default) {
                val decoded = (if (raw) RawPhotos.decode(context, clip.meta.uri, clip.rawDevelop, 2048) { isActive }?.asImageBitmap() else null)
                    ?: if (isActive) decodePhoto(context, clip.meta.uri) else null
                decoded?.let { app.headsup.retouch.Cleaner.applyTo(context, it.asAndroidBitmap(), clip.retouch).asImageBitmap() }
            }
            if (bitmap != null) {
                value = bitmap
                photoFrom = clip.meta.uri
                return@produceState
            }
            delay(400L * (attempt + 1))
        }
        photoFrom = clip.meta.uri
    }
    fun keyOf(c: ClipState): Any = Triple(sourceOf(c), c.trimStartMs, c.trimEndMs)
    fun clipped(c: ClipState) = MediaItem.Builder()
        .setUri(sourceOf(c))
        .setMediaId(c.id.toString())
        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.trimStartMs).setEndPositionMs(c.trimEndMs).build())
        .build()
    LaunchedEffect(players) { if (!shaderPreview) players.forEach { it.setVideoEffects(listOf<Effect>(liveLut)) } }
    // With one decoder (large clips), the next clip waits queued in the same player: its key.
    val queued = remember { arrayOfNulls<Any>(1) }
    // The clip the active player shows, to keep the picture where it was when only its cut
    // changes (a trim, a split undone): the same moment of the same file, not the clip's start.
    val shown = remember { arrayOfNulls<ClipState>(1) }
    LaunchedEffect(keyOf(clip), scrubbing) {
        val exoPlayer = players[active]
        if (photo) {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            holds[active] = null
            queued[0] = null
            photoPlaying = autoPlay
            if (autoPlay) onAutoPlayStarted()
            return@LaunchedEffect
        }
        photoPlaying = false
        val key = keyOf(clip)
        if (scrubbing) {
            exoPlayer.pause()
            exoPlayer.setMediaItem(MediaItem.Builder().setUri(sourceOf(clip)).setMediaId(clip.id.toString()).build(), cutScrub.value ?: 0L)
            exoPlayer.prepare()
            holds[active] = null
            queued[0] = null
        } else if (holds[active] != key) {
            if (holds[1 - active] == key) {
                // The other player already has it (the clip after the one shown): just switch.
                exoPlayer.pause()
                active = 1 - active
                players[active].seekTo(0L)
            } else {
                // Only the used part of the file plays; positions count from the cut.
                val before = shown[0]
                val fileMs = before?.takeIf { sourceOf(it) == sourceOf(clip) }?.let { it.trimStartMs + exoPlayer.currentPosition }
                val keep = if (before == null) startMs.coerceIn(0L, (clip.trimEndMs - clip.trimStartMs).coerceAtLeast(0L))
                else fileMs?.takeIf { it > clip.trimStartMs && it < clip.trimEndMs }?.minus(clip.trimStartMs) ?: 0L
                exoPlayer.setPlaybackSpeed(clip.speedAt(keep))
                exoPlayer.setMediaItem(clipped(clip), keep)
                exoPlayer.prepare()
                holds[active] = key
                queued[0] = null
            }
        }
        if (!scrubbing) shown[0] = clip
        if (autoPlay && !scrubbing) {
            players[active].play()
            onAutoPlayStarted()
        }
    }
    // The clip after this one, ready in the other player with its first frame decoded.
    val next = playlist.getOrNull(playlistIndex + 1)?.takeIf { !it.meta.isPhoto && !photo && playlist.getOrNull(playlistIndex)?.id == clip.id }
    val nextKey = next?.let(::keyOf)
    // Large clips (8K) get one decoder: the next one is queued after this one in the same player,
    // which goes on to it by itself (a new decoder at the cut, but never two at once).
    val light = next != null && sourceOf(clip) != clip.meta.uri && sourceOf(next) != next.meta.uri
    val lean = remember(clip.meta, next?.meta, light) { next != null && !light && !DecoderBudget.fitsTwo(clip.meta, next.meta) }
    LaunchedEffect(nextKey, active, scrubbing, lean, keyOf(clip)) {
        if (scrubbing) return@LaunchedEffect
        // Read now, not from the composition: the clip effect above may have just switched players.
        val shown = players[active]
        val standby = players[1 - active]
        if (lean && next != null && holds[active] == keyOf(clip)) {
            if (holds[1 - active] != null) {
                standby.stop()
                standby.clearMediaItems()
                holds[1 - active] = null
            }
            if (queued[0] == nextKey && shown.mediaItemCount == shown.currentMediaItemIndex + 2) return@LaunchedEffect
            if (shown.mediaItemCount > shown.currentMediaItemIndex + 1) shown.removeMediaItems(shown.currentMediaItemIndex + 1, shown.mediaItemCount)
            shown.addMediaItem(clipped(next))
            queued[0] = nextKey
            return@LaunchedEffect
        }
        if (queued[0] != null) {
            if (shown.mediaItemCount > shown.currentMediaItemIndex + 1) shown.removeMediaItems(shown.currentMediaItemIndex + 1, shown.mediaItemCount)
            queued[0] = null
        }
        if (next == null || lean) {
            if (holds[1 - active] != null) {
                standby.stop()
                standby.clearMediaItems()
                holds[1 - active] = null
            }
            return@LaunchedEffect
        }
        if (holds[1 - active] == nextKey) return@LaunchedEffect
        standby.playWhenReady = false
        standby.volume = 0f
        standby.setMediaItem(clipped(next))
        standby.setPlaybackSpeed(next.speedAt(0L))
        standby.prepare()
        holds[1 - active] = nextKey
    }
    // Slow motion plays slowed down in the preview too; the dashboard follows the picture's own time.
    // A ramp is followed as the clip plays (in the frame loop below).
    LaunchedEffect(clip.speed, clip.speedCurve) { players[active].setPlaybackSpeed(clip.speedAt(players[active].currentPosition)) }
    val clipVolume = if (clip.speedChanged && clip.muteChangedSpeed) 0f else clipSound * clip.soundLevel
    // One player per audio clip, kept while the clip exists.
    // Held sideways (the transport folds by a finger going down only then).
    val sideways = androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
    val audioPlayers = remember { HashMap<Long, Pair<android.net.Uri, ExoPlayer>>() }
    DisposableEffect(Unit) { onDispose { audioPlayers.values.forEach { it.second.release() }; audioPlayers.clear() } }
    LaunchedEffect(audio.map { it.id to it.uri }) {
        val wanted = audio.associate { it.id to it.uri }
        for (id in audioPlayers.keys.toList()) {
            if (wanted[id] != audioPlayers[id]?.first) audioPlayers.remove(id)?.second?.release()
        }
        for ((id, uri) in wanted) if (id !in audioPlayers) {
            audioPlayers[id] = uri to ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
            }
        }
    }
    val ended by rememberUpdatedState(onEnded)
    val advance by rememberUpdatedState(onAdvance)
    val nextKeyNow by rememberUpdatedState(nextKey)
    val indexNow by rememberUpdatedState(playlistIndex)
    // Set while the next clip has been started (hidden) just before the cut.
    val handover = remember { BooleanArray(1) }
    // Why the shown player stopped, if it failed (e.g. no decoder free), until it plays again.
    var playerError by remember { mutableStateOf<String?>(null) }
    val retried = remember { BooleanArray(2) }
    /** Empties the other player, which frees its decoder (large clips may not get two at once). */
    fun freeStandby() {
        val i = 1 - active
        if (holds[i] == null && players[i].playbackState == Player.STATE_IDLE) return
        players[i].stop()
        players[i].clearMediaItems()
        holds[i] = null
    }
    /** Shows the next clip, already in the other player, and plays on; false if it is not ready. */
    fun switchToNext(from: Int): Boolean {
        val other = players[1 - from]
        if (from != active || nextKeyNow == null || holds[1 - from] != nextKeyNow || other.playbackState == Player.STATE_IDLE) return false
        active = 1 - from
        if (!other.isPlaying) other.play()
        players[from].pause()
        handover[0] = false
        advance(indexNow + 1)
        return true
    }
    DisposableEffect(players) {
        val listeners = players.mapIndexed { i, player ->
            object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        retried[i] = false
                        if (i == active) playerError = null
                    }
                    if (state != Player.STATE_ENDED || i != active || !player.playWhenReady) return
                    // Normally the switch has happened just before the end (see the frame loop).
                    if (!switchToNext(i)) ended()
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (i != active) {
                        // The next clip could not be loaded ahead: it is loaded at the cut instead.
                        holds[i] = null
                        return
                    }
                    // Once: free the other player's decoder and try again from the same place.
                    if (!retried[i]) {
                        retried[i] = true
                        freeStandby()
                        player.prepare()
                    } else {
                        playerError = error.errorCodeName
                    }
                }

                // The queued clip (one decoder) has been reached: it becomes the clip shown.
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (i != active || reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || player.currentMediaItemIndex == 0) return
                    player.removeMediaItems(0, player.currentMediaItemIndex)
                    holds[i] = queued[0]
                    queued[0] = null
                    advance(indexNow + 1)
                }

                // A picture is laid out by the frames actually on it: until the first frame of a
                // new clip arrives, the previous clip's frame keeps the previous clip's layout.
                override fun onRenderedFirstFrame() = notePicture()

                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = notePicture()

                private fun notePicture() {
                    val size = player.videoSize
                    if (size.width <= 0 || size.height <= 0) return
                    pictureClip[i] = player.currentMediaItem?.mediaId?.toLongOrNull()
                    pictureAspect[i] = size.width * size.pixelWidthHeightRatio / size.height
                }
            }.also(player::addListener)
        }
        onDispose { players.forEachIndexed { i, p -> p.removeListener(listeners[i]) } }
    }
    LaunchedEffect(scrubMs) { scrubMs?.let(scrubber::seek) }
    // Declared after the media item is set, so it runs after it for a newly selected clip.
    LaunchedEffect(seekRequest, clip.id) {
        val target = seekRequest ?: return@LaunchedEffect
        photoPlaying = false
        val player = players[active]
        player.pause()
        player.seekTo(target.coerceIn(0L, clip.durationMs))
        onSeekDone()
    }
    var nudge by remember { mutableStateOf(1L) }
    LaunchedEffect(gradeCube) {
        if (shaderPreview) return@LaunchedEffect
        liveLut.update(gradeCube)
        // A paused player only draws when a new frame is decoded and ignores a seek to its current
        // position, so step 1 ms back and forth to re-render the picture.
        val exoPlayer = players[active]
        if (!exoPlayer.isPlaying) {
            exoPlayer.seekTo((exoPlayer.currentPosition + nudge).coerceAtLeast(0L))
            nudge = -nudge
        }
    }
    // Zebra: diagonal stripes over clipped highlights, as on a camera monitor.
    var zebra by remember { mutableStateOf(false) }
    var histogramOn by remember { mutableStateOf(false) }
    var histogram by remember { mutableStateOf<FloatArray?>(null) }
    // Which scope shows (a tap on it steps through them): histogram, waveform, RGB parade.
    var scopeKind by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    var scopeImage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val previewShader = remember { if (Build.VERSION.SDK_INT >= 33) GradePreviewShader() else null }
    var tableVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(gradeCube, maskCubes, fromCube, fromMaskCubes) {
        previewShader?.setTables(GradeTables(gradeCube, maskCubes, fromCube, fromMaskCubes))
        tableVersion++
    }
    // Each player's picture is coloured as its own clip: the selected one fully, a neighbour still on
    // screen at a cut (before the selection follows) by its look, so no frame shows another clip's colours.
    val playerShaders = remember { if (Build.VERSION.SDK_INT >= 33) List(2) { GradePreviewShader() } else null }
    // Just after the selection moved on (playing into the next clip), the tables are still the last
    // clip's: until the new ones come, the clip is coloured by its look as a neighbour, with its own
    // blend in (never the last clip's).
    val fresh = cubesFor == null || cubesFor == clip.id
    for (i in 0..1) key(i) {
        val id = pictureClip[i] ?: clip.id
        val own = id == clip.id && fresh
        val cube = if (own) gradeCube else neighbourCubes[id]
        val from = if (own) fromCube else neighbourFrom[id]
        LaunchedEffect(playerShaders, own, cube, maskCubes, fromCube, from, fromMaskCubes) {
            playerShaders?.get(i)?.setTables(if (own) GradeTables(gradeCube, maskCubes, fromCube, fromMaskCubes) else GradeTables(cube, emptyList(), from))
            tableVersion++
        }
    }
    // The mask worked on shows red over the picture while it changes (and a moment after), or all
    // the time with Overlay on.
    var maskFlash by remember { mutableStateOf(false) }
    LaunchedEffect(guideMask) {
        if (guideMask == null) { maskFlash = false; return@LaunchedEffect }
        maskFlash = true
        delay(900)
        maskFlash = false
    }
    val overlayLayer = if (guideMask != null && (maskOverlay || maskFlash) && guideIndex in masks.indices) {
        var l = guideIndex
        while (l > 0 && masks[l].join != app.headsup.core.color.MaskJoin.NEW) l--
        l
    } else -1
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    LaunchedEffect(active) { textureView = textureViews[active] }
    val sample by rememberUpdatedState(onSample)
    val maskMover by rememberUpdatedState(onMoveMask)
    val maskTransformer by rememberUpdatedState(onTransformMask)
    val maskEnd by rememberUpdatedState(onMaskEnd)
    // Where a finger measuring colour is now (fractions of the view), shown before it lifts.
    var liveMarker by remember { mutableStateOf<Offset?>(null) }
    val brushPainter by rememberUpdatedState(onBrush)
    // The brush shown on the picture for a moment after its size is set.
    var ringOn by remember { mutableStateOf(false) }
    LaunchedEffect(brushRingAt) {
        if (brushRingAt == 0L) return@LaunchedEffect
        ringOn = true
        delay(1400)
        ringOn = false
    }
    // Read by a gesture that lives as long as the preview: always the current setting.
    val panNow by rememberUpdatedState(panView)
    val reframer by rememberUpdatedState(onReframe)
    val currentReframe by rememberUpdatedState(reframe)
    // Sizes of the frame and the picture under it, for gestures (read when they happen).
    val geometryRef = remember { GeometryRef(FrameGeometry(1f, 1f, 1f, 1f)) }
    // The picture as decoded (before colour), from the photo or the video's TextureView.
    val grabFrame: (Int) -> Bitmap? = { w ->
        val bmp = photoBitmap?.takeIf { photo }?.asAndroidBitmap()
        if (bmp != null) {
            Bitmap.createScaledBitmap(bmp, w, (w * bmp.height / bmp.width.coerceAtLeast(1)).coerceAtLeast(1), true)
        } else {
            textureView?.let { tv -> tv.getBitmap(w, (w * tv.height / tv.width.coerceAtLeast(1)).coerceAtLeast(1)) }
        }
    }
    val grab by rememberUpdatedState(grabFrame)
    LaunchedEffect(textureView, photoBitmap) {
        onFrameSource?.invoke { grab(ANALYSIS_WIDTH) }
        onPictureSource?.invoke { w -> grab(w) }
    }

    LaunchedEffect(histogramOn, gradeCube, scopeKind) {
        while (histogramOn && scopeKind > 0) {
            val frame = grab(192)
            if (frame != null) scopeImage = withContext(Dispatchers.Default) { if (scopeKind == 1) waveform(frame, gradeCube) else parade(frame, gradeCube) }
            delay(250)
        }
        while (histogramOn && scopeKind == 0) {
            val frame = grab(160)
            if (frame != null) histogram = withContext(Dispatchers.Default) { luminanceHistogram(frame, gradeCube) }
            delay(250)
        }
    }

    // Player position; in file time while a cut handle is dragged, otherwise within the cut.
    var playerMs by remember { mutableLongStateOf(0L) }
    val reportPosition by rememberUpdatedState(onPosition)
    val fileTime by rememberUpdatedState(scrubbing)
    var playing by remember { mutableStateOf(false) }
    // Playing or about to (a player still loading counts): what the play button shows and toggles.
    var wantsPlay by remember { mutableStateOf(false) }
    // A jump within the clip (its first or last frame): paused there.
    var jumpTarget by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(jumpTarget) {
        val target = jumpTarget ?: return@LaunchedEffect
        photoPlaying = false
        wantsPlay = false
        val player = players[active]
        player.pause()
        player.seekTo(target.coerceIn(0L, clip.durationMs))
        jumpTarget = null
    }
    fun jumpInClip(ms: Long) { jumpTarget = ms }
    // A colour marker no longer points at what was measured once the picture played on: hidden
    // from then until the next measurement.
    var markerStale by remember { mutableStateOf(false) }
    LaunchedEffect(wantsPlay) { if (wantsPlay) markerStale = true }
    LaunchedEffect(marker) { markerStale = false }
    val photoNow by rememberUpdatedState(photo)
    val clipLength by rememberUpdatedState(clip.durationMs)
    val clipNow by rememberUpdatedState(clip)
    val playlistNow by rememberUpdatedState(playlist)
    LaunchedEffect(exoPlayer) {
        var lastFrame = 0L
        var lastSpeedChange = 0L
        while (true) withFrameMillis { t ->
            val dt = if (lastFrame > 0) t - lastFrame else 0L
            lastFrame = t
            if (photoNow) {
                // A photo's own clock, so it plays for its time on the timeline like a clip.
                if (photoPlaying) {
                    playerMs = (playerMs + dt).coerceAtMost(clipLength)
                    reportPosition(playerMs)
                    if (playerMs >= clipLength) {
                        photoPlaying = false
                        ended()
                    }
                }
                playing = photoPlaying
                wantsPlay = photoPlaying
                return@withFrameMillis
            }
            // Just switched to the other player: this loop is about to be replaced.
            if (exoPlayer !== players[active]) return@withFrameMillis
            if (playerMs != exoPlayer.currentPosition) {
                playerMs = exoPlayer.currentPosition
                if (!fileTime) reportPosition(playerMs)
            }
            // The speed of the clip actually on this player (just after a cut the selection may not
            // have followed yet): its ramp, or its steady speed, so no clip keeps another's speed.
            // Changed at most every 120 ms and by 3 % or more: each change costs the player a jolt.
            if (exoPlayer.isPlaying && t - lastSpeedChange >= 120) {
                val onPlayer = pictureClip[active]?.let { id -> if (id == clipNow.id) clipNow else playlistNow.firstOrNull { it.id == id } } ?: clipNow
                val s = onPlayer.speedAt(exoPlayer.currentPosition)
                val now = exoPlayer.playbackParameters.speed
                if (kotlin.math.abs(s - now) > 0.03f * now) {
                    exoPlayer.setPlaybackSpeed(s)
                    lastSpeedChange = t
                }
            }
            playing = exoPlayer.isPlaying
            wantsPlay = exoPlayer.playWhenReady && exoPlayer.playbackState.let { it == Player.STATE_READY || it == Player.STATE_BUFFERING }
            // At a cut: the next clip starts (hidden, silent) a moment before, so it is already moving
            // when the picture switches to it at the cut; a player needs a little time to start.
            val other = players[1 - active]
            val nextReady = nextKeyNow != null && holds[1 - active] == nextKeyNow && other.playbackState == Player.STATE_READY
            if (exoPlayer.isPlaying && !fileTime && nextReady) {
                val remaining = clipNow.timelineOfSource(clipLength) - clipNow.timelineOfSource(exoPlayer.currentPosition)
                if (remaining <= HANDOVER_LEAD_MS && !handover[0]) {
                    other.volume = 0f
                    if (other.currentPosition != 0L) other.seekTo(0L)
                    other.play()
                    handover[0] = true
                }
                if (remaining <= HANDOVER_AT_MS) switchToNext(active)
            } else if (handover[0] && !exoPlayer.playWhenReady) {
                // Paused before the cut: the next clip waits at its start again.
                other.pause()
                other.seekTo(0L)
                handover[0] = false
            }
        }
    }
    val playingChanged by rememberUpdatedState(onPlaying)
    LaunchedEffect(wantsPlay) { playingChanged(wantsPlay) }
    DisposableEffect(Unit) { onDispose { playingChanged(false) } }
    fun pause() {
        players[active].pause()
        // Reported at once, so a timeline still gliding after a flick stops too.
        playingChanged(false)
    }
    fun play() {
        val p = players[active]
        when (p.playbackState) {
            // At the end of a clip: on into the next one, or this one again from its start.
            Player.STATE_ENDED -> if (!switchToNext(active)) p.seekTo(0L)
            // After an error: free a decoder and load again.
            Player.STATE_IDLE -> {
                freeStandby()
                p.prepare()
            }
        }
        players[active].play()
        playingChanged(true)
    }
    fun togglePlay() {
        when {
            photo -> {
                if (!photoPlaying && playerMs >= clip.durationMs) playerMs = 0L
                photoPlaying = !photoPlaying
            }
            wantsPlay -> pause()
            else -> play()
        }
    }
    // The timeline being dragged: declared after the media item is set (above), so a newly reached clip is ready.
    val timelineScrub = scrubTo.value
    LaunchedEffect(timelineScrub, clip.id) {
        val target = timelineScrub ?: return@LaunchedEffect
        if (photo) {
            photoPlaying = false
            playerMs = target.coerceIn(0L, clip.durationMs)
            onPosition(playerMs)
        } else {
            if (players[active].playWhenReady) pause()
            scrubber.seek(target.coerceIn(0L, clip.durationMs))
        }
    }
    // Position within the clip, as the dashboard and timeline count it (negative before the cut).
    val positionMs = if (scrubbing) playerMs - clip.trimStartMs else playerMs
    // Masks that move are placed for this moment of the file.
    val fileMs = clip.trimStartMs + positionMs
    // The oval being shaped as it shows now, for grabbing its ends.
    val guideNow by rememberUpdatedState(guideMask?.at(fileMs))
    // Position on the timeline within this clip, for fades and music.
    val clipTimelineMs = if (photo) positionMs else clip.timelineOfSource(positionMs)
    val fadeGain = Fade.gain(clipTimelineMs, clip.timelineDurationMs, clip.fadeInMs, clip.fadeOutMs)
    // Bars sliding in (and out) with the clip's time, as in the export.
    // A photo has no time: its bars are simply there.
    val barsNow = if (effects.barsRatio == null) effects else if (photo) effects.copy(barsAmount = 1f) else effects.copy(barsAmount = effects.barsMotion.amount(clipTimelineMs, clip.timelineDurationMs))
    SideEffect { exoPlayer.volume = clipVolume * fadeGain }
    // Audio clips follow the timeline: each plays where it is placed, faded as in the export.
    val audioNow by rememberUpdatedState(audio)
    val timelineNow by rememberUpdatedState(timelineOffsetMs + clipTimelineMs)
    val offsetNow by rememberUpdatedState(timelineOffsetMs)
    val sourceNow by rememberUpdatedState(positionMs)
    val totalNow by rememberUpdatedState(timelineTotalMs)
    // Keyed to wanting to play, not to the picture actually moving: very slow parts can make the
    // picture's player wait a moment (buffering), and the music must not stop each time.
    LaunchedEffect(wantsPlay, clip.id) {
        if (!wantsPlay) {
            audioPlayers.values.forEach { it.second.pause() }
            return@LaunchedEffect
        }
        // The music keeps its own steady clock while playing: the picture's position moves in
        // small jumps where the speed changes (the player's speed is set in steps), and music
        // chasing those jumps would stutter. Only when the picture is clearly elsewhere (a jump,
        // a stall) does the clock take its place again.
        var anchorT = timelineNow
        var anchorAt = android.os.SystemClock.uptimeMillis()
        while (true) {
            val wall = android.os.SystemClock.uptimeMillis()
            var at = anchorT + (wall - anchorAt)
            // How far apart the picture and the clock are, in the recording: in slow parts a small
            // lag of the picture's player (its speed changes only once its sound buffer has played)
            // is several times longer on the timeline, and must not move the music.
            val c0 = clipNow
            val local = at - offsetNow
            val apart = if (!photoNow && local in 0L..c0.timelineDurationMs) {
                kotlin.math.abs(sourceNow - c0.sourceOfTimeline(local))
            } else {
                kotlin.math.abs(timelineNow - at)
            }
            if (apart > 500 || kotlin.math.abs(timelineNow - at) > 4_000) {
                anchorT = timelineNow
                anchorAt = wall
                at = anchorT
            }
            for (c in audioNow) {
                val player = audioPlayers[c.id]?.second ?: continue
                val p = c.piece.let { if (it.sourceDurationMs > 0) it else it.copy(sourceDurationMs = player.duration.coerceAtLeast(0L)) }
                val source = p.sourceAt(at)?.takeIf { at < totalNow }
                if (source == null) {
                    if (player.isPlaying) player.pause()
                } else {
                    // Asked to play already (it may still be loading): not sought again each round.
                    if (!player.playWhenReady || kotlin.math.abs(player.currentPosition - source) > 300) {
                        player.seekTo(source)
                        player.play()
                    }
                    player.volume = c.volume * p.gainAt(at)
                }
            }
            delay(50)
        }
    }

    val sampler = remember(activity) { activity?.let { TelemetrySampler(it.track) } }
    val renderer = remember { DashboardRenderer(context.assets) }
    val currentStyle by rememberUpdatedState(style)
    fun metaAspectOf(c: ClipState) = if (c.meta.width > 0 && c.meta.height > 0) c.meta.width.toFloat() / c.meta.height else 16f / 9f
    val metaAspect = metaAspectOf(clip)
    val sourceAspect = if (photo || scrubbing) metaAspect else pictureAspect[active]?.takeIf { pictureClip[active] == clip.id } ?: metaAspect
    /** The clip on player [i]'s picture, its framing and its shape (the selected clip's, until it has a frame). */
    fun pictureOf(i: Int): Triple<ClipState, Reframe, Float> {
        val c = pictureClip[i]?.let { id -> if (id == clip.id) clip else playlist.firstOrNull { it.id == id } } ?: clip
        val r = if (c.id == clip.id) reframe else framingOf(c)
        val a = (if (scrubbing && c.id == clip.id) null else pictureAspect[i]) ?: metaAspectOf(c)
        return Triple(c, r, a)
    }
    // The frame takes the reframe's shape; the picture is laid out under it, just covering it.
    val frameAspect = reframe.aspect ?: sourceAspect
    // Painted masks as one picture for the shaders, drawn again when a stroke changes. While a
    // finger paints, each drawing finishes and the next starts from the latest strokes (a new
    // stroke point must not cancel the drawing, or nothing would show until the finger lifts).
    // A mask tracked with AI changes from frame to frame: then the moment counts too (in 40 ms steps).
    val brushMs = if (BrushAtlas.changesOverTime(masks)) fileMs / 40 * 40 else 0L
    val brushInput = rememberUpdatedState(Triple(masks, sourceAspect, brushMs))
    var brushAtlas by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { brushInput.value }
            .distinctUntilChangedBy { (m, a, t) -> listOf(m.map { it.strokes }, m.map { it.softness }, m.map { it.edge }, m.map { it.sequence to it.sequenceStrokes }, a, t) }
            .conflate()
            .collect { (m, a, t) -> brushAtlas = withContext(Dispatchers.Default) { BrushAtlas.build(context, m, a, t) } }
    }
    // The selected painted mask's outline (drawn over the picture instead of a red fill, so what is
    // being corrected stays in sight).
    var brushOutline by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(brushAtlas, guideIndex) {
        val atlas = brushAtlas
        brushOutline = if (atlas == null) null else withContext(Dispatchers.Default) { outlineOf(atlas, guideIndex) }
    }
    // The clip blended in from: its painted masks as at its end (drawn once per change).
    LaunchedEffect(fromMasks, fromMaskMs) {
        val atlas = if (fromMasks.isEmpty()) null else withContext(Dispatchers.Default) { BrushAtlas.build(context, fromMasks, metaAspect, fromMaskMs) }
        previewShader?.setFromBrush(atlas)
        playerShaders?.forEach { it.setFromBrush(atlas) }
        tableVersion++
    }
    LaunchedEffect(brushAtlas) {
        previewShader?.setBrush(brushAtlas)
        playerShaders?.forEach { it.setBrush(brushAtlas) }
        tableVersion++
    }

    // Looking closely at a photo: the view's zoom and pan (not the photo's framing).
    val inspect = photoMode && onReframe == null && onMoveMask == null && onTransformMask == null && onBrush == null && onMoveText == null && onSample == null
    // The view's zoom applies while looking at a photo closely or painting a mask.
    val viewZoomed = inspect || zoomForPainting
    var viewZoom by remember(clip.id) { mutableStateOf(1f) }
    var viewPan by remember(clip.id) { mutableStateOf(Offset.Zero) }
    /**
     * The view zoomed by [factor] around [at] (where the fingers are, in a [w]×[h] area) and moved
     * by [pan]: the picture under the fingers stays under them. It never leaves the frame.
     */
    fun zoomViewAt(at: Offset, factor: Float, pan: Offset, w: Float, h: Float) {
        val z0 = viewZoom
        val z1 = (z0 * factor).coerceIn(1f, 8f)
        val k = z1 / z0
        // The view is scaled about its middle: keep the point under [at] where it is.
        val fromMiddle = at - Offset(w / 2f, h / 2f)
        val moved = fromMiddle * (1f - k) + viewPan * k + pan
        viewZoom = z1
        val maxX = w * (z1 - 1f) / 2f
        val maxY = h * (z1 - 1f) / 2f
        viewPan = if (z1 <= 1.001f) Offset.Zero else Offset(moved.x.coerceIn(-maxX, maxX), moved.y.coerceIn(-maxY, maxY))
    }
    Column(Modifier.padding(horizontal = if (edgeToEdge) 0.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(if (edgeToEdge) 0.dp else 20.dp)).background(Color.Black).then(
                // Holding a finger still shows the original until it lifts; moving or a second
                // finger (zoom) is left to the other gestures.
                if (onHoldOriginal != null) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val released = withTimeoutOrNull(400L) {
                                while (true) {
                                    val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed || e.changes.size > 1 || (c.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                                }
                                true
                            }
                            if (released == null) {
                                onHoldOriginal(true)
                                do {
                                    val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                } while (e.changes.any { it.pressed })
                                onHoldOriginal(false)
                            }
                        }
                    }
                } else Modifier,
            ).then(
                // A photo can be looked at closely: pinch to zoom the view, double tap to fit again
                // (only when the picture is not being framed, masked or written on).
                if (inspect) {
                    Modifier
                        .pointerInput(Unit) {
                            detectTransformGestures { centroid, pan, zoomChange, _ ->
                                zoomViewAt(centroid, zoomChange, pan, size.width.toFloat(), size.height.toFloat())
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { at ->
                                if (viewZoom > 1.01f) { viewZoom = 1f; viewPan = Offset.Zero }
                                else zoomViewAt(at, 3f, Offset.Zero, size.width.toFloat(), size.height.toFloat())
                            })
                        }
                } else if (zoomForPainting) {
                    // Painting: two fingers zoom and pan the view, taken before the painting sees
                    // them (one finger still paints; in View it moves the view). A double tap
                    // fits the view again and paints nothing: its first tap is taken back.
                    Modifier.pointerInput(Unit) {
                        var lastTapMs = 0L
                        var lastTapAt = Offset.Zero
                        awaitEachGesture {
                            val first = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            val downMs = first.uptimeMillis
                            if (downMs - lastTapMs < 300L && (first.position - lastTapAt).getDistance() < 48.dp.toPx()) {
                                first.consume()
                                lastTapMs = 0L
                                // Zoomed: back to the whole picture; not: 3× closer where tapped.
                                if (viewZoom > 1.01f) {
                                    viewZoom = 1f
                                    viewPan = Offset.Zero
                                } else {
                                    zoomViewAt(first.position, 3f, Offset.Zero, size.width.toFloat(), size.height.toFloat())
                                }
                                brushPainter?.invoke(4, 0f, 0f, grab)
                                do {
                                    val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    e.changes.forEach { it.consume() }
                                } while (e.changes.any { it.pressed })
                                return@awaitEachGesture
                            }
                            var moved = false
                            var multi = false
                            while (true) {
                                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                if (event.changes.none { it.pressed }) break
                                event.changes.firstOrNull()?.let { if ((it.position - first.position).getDistance() > 12.dp.toPx()) moved = true }
                                if (panNow && event.changes.count { it.pressed } == 1) {
                                    val maxX = size.width * (viewZoom - 1f) / 2f
                                    val maxY = size.height * (viewZoom - 1f) / 2f
                                    viewPan = if (viewZoom <= 1.001f) Offset.Zero
                                        else (viewPan + event.calculatePan()).let { Offset(it.x.coerceIn(-maxX, maxX), it.y.coerceIn(-maxY, maxY)) }
                                    event.changes.forEach { it.consume() }
                                }
                                if (event.changes.count { it.pressed } >= 2) {
                                    multi = true
                                    zoomViewAt(event.calculateCentroid(useCurrent = true), event.calculateZoom(), event.calculatePan(), size.width.toFloat(), size.height.toFloat())
                                    event.changes.forEach { it.consume() }
                                }
                            }
                            // A short tap in place may be the first of a double tap.
                            if (!moved && !multi) {
                                lastTapMs = downMs
                                lastTapAt = first.position
                            } else {
                                lastTapMs = 0L
                            }
                        }
                    }
                } else Modifier,
            ),
            contentAlignment = Alignment.Center,
        ) {
            // Fitted with blurred bars: the picture itself behind the frame, covering the whole view and
            // blurred into a cloud of its colours (as the export draws the bars, and as YouTube's
            // ambient light lights round its player). Frames are read from
            // the file (not the TextureView, which gives nothing for HDR clips such as screen
            // recordings), small, every few tenths of a second while playing.
            if (reframe.fit && reframe.blur) {
                var backdrop by remember { mutableStateOf<ImageBitmap?>(null) }
                val momentNow by rememberUpdatedState(fileMs)
                val source = if (photo) null else sourceOf(clip)
                val retriever = remember(source) {
                    source?.let { uri -> runCatching { android.media.MediaMetadataRetriever().apply { setDataSource(context, uri) } }.getOrNull() }
                }
                DisposableEffect(retriever) { onDispose { runCatching { retriever?.release() } } }
                LaunchedEffect(clip.id, photoBitmap, retriever) {
                    while (true) {
                        val frame = if (photo) {
                            grab(64)
                        } else retriever?.let { r ->
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    val us = momentNow * 1000L
                                    // Exact while paused; the nearest key frame while playing (quick).
                                    val option = if (wantsPlay) android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC else android.media.MediaMetadataRetriever.OPTION_CLOSEST
                                    r.getScaledFrameAtTime(us, option, 96, 96)
                                }.getOrNull()
                            }
                        }
                        frame?.let { backdrop = it.asImageBitmap() }
                        delay(if (wantsPlay) 300L else 500L)
                    }
                }
                backdrop?.let {
                    // Richer colours, a touch darker; blurred by the system where it can (Android 12+),
                    // else smooth enough from being so small.
                    val richer = androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(1.3f) }
                    Image(
                        it,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(richer),
                        modifier = Modifier.matchParentSize().graphicsLayer {
                            if (Build.VERSION.SDK_INT >= 31) {
                                renderEffect = androidx.compose.ui.graphics.BlurEffect(40.dp.toPx(), 40.dp.toPx(), androidx.compose.ui.graphics.TileMode.Clamp)
                            }
                        },
                    )
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.12f)))
                }
            }
            BoxWithConstraints(
                Modifier.heightIn(max = maxHeight).aspectRatio(frameAspect, matchHeightConstraintsFirst = true)
                    .graphicsLayer {
                        val z = if (viewZoomed) viewZoom else 1f
                        scaleX = z
                        scaleY = z
                        translationX = if (viewZoomed) viewPan.x else 0f
                        translationY = if (viewZoomed) viewPan.y else 0f
                    }
                    .clipToBounds(),
                contentAlignment = Alignment.Center,
            ) {
                val boxW = constraints.maxWidth.toFloat()
                val boxH = constraints.maxHeight.toFloat()
                val pictureW = if (sourceAspect > boxW / boxH) boxH * sourceAspect else boxW
                val pictureH = if (sourceAspect > boxW / boxH) boxH else boxW / sourceAspect
                val geometry = FrameGeometry(boxW, boxH, pictureW, pictureH)
                // A paused player keeps its last frame laid out for the old size (a corner of the
                // picture after changing the frame) until it draws again: make both draw. A seek to
                // the current position is ignored, so step 1 ms away and back.
                val shownHeight = pictureH * reframeTransform(reframe, geometry).zoom
                LaunchedEffect(shownHeight.toInt()) { onPictureHeight?.invoke(shownHeight) }
                LaunchedEffect(boxW.toInt(), boxH.toInt(), pictureW.toInt(), pictureH.toInt()) {
                    for (p in players) if (!p.isPlaying && p.playbackState == Player.STATE_READY) {
                        val at = p.currentPosition
                        p.seekTo(at + 1)
                        p.seekTo(at)
                    }
                }
                geometryRef.value = geometry
                val density = LocalDensity.current
                // A TextureView (unlike PlayerView's default SurfaceView) is drawn by the UI, so the
                // colour shader can be applied to it.
                val pictureLayer = Modifier.requiredSize(with(density) { pictureW.toDp() }, with(density) { pictureH.toDp() }).graphicsLayer {
                        // Reading these here re-applies the shader when they change, without recomposing.
                        tableVersion
                        val frame = if (effects.grain > 0f) (positionMs / 33).toFloat() else 0f
                        val fromWeight = if (fromCube != null) ColourTransition.fromWeight(positionMs, transitionMs) else 0f
                        previewShader?.overlayLayer = overlayLayer
                        renderEffect = previewShader?.effect(effects, zebra, frame, size.width, size.height, masks.map { it.at(fileMs) }, fromWeight, if (raw) clip.rawDevelop else null, presence, reframe.warp)
                        // Reframing moves the graded picture, as in the export (colour first, then framing).
                        val t = reframeTransform(reframe, geometry)
                        scaleX = t.zoom
                        scaleY = t.zoom
                        rotationZ = reframe.angleDegrees
                        translationX = t.translation.x
                        translationY = t.translation.y
                    }
                val bitmap = photoBitmap
                if (photo) {
                    if (bitmap != null) Image(bitmap, contentDescription = clip.meta.name, contentScale = ContentScale.FillBounds, modifier = pictureLayer)
                    if (photoFrom != clip.meta.uri) {
                        // The next photo is being developed: the previous one is dimmed under a spinner.
                        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                            androidx.compose.foundation.layout.Row(
                                Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.7f))
                                    .padding(horizontal = 18.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Spinner(Modifier.size(20.dp))
                                androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
                                androidx.compose.material3.Text(
                                    "Loading…",
                                    color = Color.White,
                                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                }
                // Both players' pictures stay in place; only the one playing is shown (and coloured),
                // each laid out for the clip whose frame it holds.
                for (i in 0..1) {
                    key(i) {
                        val shown = i == active && !photo
                        val (_, r, a) = pictureOf(i)
                        val w = if (a > boxW / boxH) boxH * a else boxW
                        val h = if (a > boxW / boxH) boxH else boxW / a
                        val g = FrameGeometry(boxW, boxH, w, h)
                        val size = Modifier.requiredSize(with(density) { w.toDp() }, with(density) { h.toDp() })
                        AndroidView(
                            factory = { ctx ->
                                TextureView(ctx).also { tv ->
                                    players[i].setVideoTextureView(tv)
                                    textureViews[i] = tv
                                    if (i == active) textureView = tv
                                }
                            },
                            modifier = if (!shown) {
                                size.alpha(0f)
                            } else {
                                size.graphicsLayer {
                                    tableVersion
                                    val frame = if (effects.grain > 0f) (positionMs / 33).toFloat() else 0f
                                    val from = if (fresh) fromCube else neighbourFrom[clip.id]
                                    val fromWeight = if (from != null) ColourTransition.fromWeight(positionMs, transitionMs) else 0f
                                    playerShaders?.get(i)?.overlayLayer = if (fresh) overlayLayer else -1
                                    renderEffect = if (pictureClip[i] == null || pictureClip[i] == clip.id) {
                                        playerShaders?.get(i)?.effect(
                                            effects, zebra, frame, this.size.width, this.size.height, if (fresh) masks.map { it.at(fileMs) } else emptyList(), fromWeight,
                                            presence = presence, warp = r.warp, fromMasks = if (fresh) fromMasks.map { it.at(fromMaskMs) } else emptyList(),
                                        )
                                    } else {
                                        playerShaders?.get(i)?.effect(effects, zebra, frame, this.size.width, this.size.height, emptyList(), 0f, warp = r.warp)
                                    }
                                    val t = reframeTransform(r, g)
                                    scaleX = t.zoom
                                    scaleY = t.zoom
                                    rotationZ = r.angleDegrees
                                    translationX = t.translation.x
                                    translationY = t.translation.y
                                }
                            },
                        )
                    }
                }
                Canvas(
                    Modifier
                        .matchParentSize()
                        .then(
                            if (onReframe == null) {
                                Modifier
                            } else {
                                Modifier.pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoomChange, _ ->
                                        val r = currentReframe
                                        val g = geometryRef.value
                                        val w = g.pictureW
                                        val h = g.pictureH
                                        val z = r.clamped(g.boxW, g.boxH, w, h).effectiveZoom(g.boxW, g.boxH, w, h)
                                        // A finger moving right shows more of the left: undo rotation and zoom.
                                        val a = Math.toRadians(r.angleDegrees.toDouble())
                                        val dx = (pan.x * kotlin.math.cos(a) + pan.y * kotlin.math.sin(a)).toFloat() / z
                                        val dy = (-pan.x * kotlin.math.sin(a) + pan.y * kotlin.math.cos(a)).toFloat() / z
                                        reframer?.invoke(
                                            r.copy(zoom = r.zoom * zoomChange, centerX = r.centerX - dx / w, centerY = r.centerY - dy / h),
                                        )
                                    }
                                }
                            },
                        )
                        .pointerInput(renderer) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                // While a mask is edited, a finger on the picture moves it (widgets stay put).
                                if (reframer != null) return@awaitEachGesture
                                val moveMask = maskMover
                                val paint = brushPainter
                                if (moveMask != null || paint != null || maskTransformer != null) {
                                    // Masks live in the picture, so map the finger back through the framing.
                                    // Masks are kept on the original picture: with its perspective
                                    // corrected, a point on screen is first taken back to it.
                                    fun original(x: Float, y: Float): Pair<Float, Float> {
                                        val m = currentReframe.warp ?: return x to y
                                        val (a, b) = app.headsup.core.frame.Perspective.apply(m, x.toDouble(), y.toDouble())
                                        val g = geometryRef.value
                                        return app.headsup.core.frame.Lens.sourceOf(a.toFloat(), b.toFloat(), m.getOrElse(app.headsup.core.frame.Lens.K) { 0.0 }.toFloat(), g.pictureW / g.pictureH.coerceAtLeast(1f))
                                    }
                                    fun move(p: Offset) {
                                        val g = geometryRef.value
                                        val (sx, sy) = currentReframe.sourceOf(p.x, p.y, g.boxW, g.boxH, g.pictureW, g.pictureH)
                                        original(sx / g.pictureW, sy / g.pictureH).let { (x, y) -> moveMask?.invoke(x, y) }
                                    }
                                    fun pictureOf(p: Offset): Pair<Float, Float> {
                                        val g = geometryRef.value
                                        val (sx, sy) = currentReframe.sourceOf(p.x, p.y, g.boxW, g.boxH, g.pictureW, g.pictureH)
                                        return original(sx / g.pictureW, sy / g.pictureH)
                                    }
                                    down.consume()
                                    // An oval's ends: a finger near one drags that end (the other stays);
                                    // two on opposite ends stretch it about its middle.
                                    val grips = HashMap<androidx.compose.ui.input.pointer.PointerId, Int>()
                                    fun endNear(p: Offset): Int? {
                                        val m = guideNow?.takeIf { it.kind == MaskKind.ELLIPSE || it.kind == MaskKind.LINEAR } ?: return null
                                        if (maskEnd == null) return null
                                        val g = geometryRef.value
                                        val (fx, fy) = pictureOf(p)
                                        val px = fx * g.pictureW
                                        val py = fy * g.pictureH
                                        val a = Math.toRadians(m.angleDegrees.toDouble())
                                        val c = kotlin.math.cos(a).toFloat()
                                        val s = kotlin.math.sin(a).toFloat()
                                        // The turning handle (4): on a stalk from the middle, past the top.
                                        run {
                                            val perPx = g.pictureW / g.boxW.coerceAtLeast(1f)
                                            val gap = ROTATE_GAP.toPx() * perPx
                                            val out = if (m.kind == MaskKind.ELLIPSE) m.sizeY / 2f * g.pictureH + gap else gap
                                            val hx = m.centerX * g.pictureW + s * out
                                            val hy = m.centerY * g.pictureH - c * out
                                            if (kotlin.math.hypot(px - hx, py - hy) < 24.dp.toPx() * perPx) return 4
                                        }
                                        if (m.kind == MaskKind.LINEAR) {
                                            // The dashed lines: how far the finger is from each, across them.
                                            val d = (-(px - m.centerX * g.pictureW) * s + (py - m.centerY * g.pictureH) * c)
                                            val half = m.softness / 2f * g.pictureH
                                            val reachL = 28.dp.toPx() * g.pictureW / g.boxW.coerceAtLeast(1f)
                                            val toFar = kotlin.math.abs(d - half)
                                            val toNear = kotlin.math.abs(d + half)
                                            // The middle line is left to move the whole gradient.
                                            if (kotlin.math.abs(d) < reachL && kotlin.math.abs(d) <= minOf(toFar, toNear)) return null
                                            return when {
                                                toFar < reachL && toFar <= toNear -> 0
                                                toNear < reachL -> 1
                                                else -> null
                                            }
                                        }
                                        val rx = m.sizeX / 2f * g.pictureH
                                        val ry = m.sizeY / 2f * g.pictureH
                                        val cx = m.centerX * g.pictureW
                                        val cy = m.centerY * g.pictureH
                                        val ends = listOf(Offset(cx + c * rx, cy + s * rx), Offset(cx - c * rx, cy - s * rx), Offset(cx - s * ry, cy + c * ry), Offset(cx + s * ry, cy - c * ry))
                                        val reach = 36.dp.toPx() * g.pictureW / g.boxW.coerceAtLeast(1f)
                                        return ends.indices.minByOrNull { (ends[it] - Offset(px, py)).getDistance() }?.takeIf { (ends[it] - Offset(px, py)).getDistance() < reach }
                                    }
                                    endNear(down.position)?.let { grips[down.id] = it }
                                    var painting = paint != null
                                    var last = down.position
                                    if (grips.isNotEmpty()) Unit else if (paint != null) pictureOf(down.position).let { (x, y) -> paint(0, x, y, grab) } else move(down.position)
                                    // One finger places the mask; two move, scale and turn it. After two,
                                    // the finger left on the picture no longer jumps it to itself.
                                    var pinched = false
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.isEmpty()) break
                                        if (grips.isNotEmpty()) {
                                            // A second finger near the other end grabs it too.
                                            for (ch in event.changes) if (ch.pressed && !ch.previousPressed && ch.id !in grips) endNear(ch.position)?.let { grips[ch.id] = it }
                                            for (ch in pressed) grips[ch.id]?.let { end -> pictureOf(ch.position).let { (x, y) -> maskEnd?.invoke(end, x, y) } }
                                            event.changes.forEach { it.consume() }
                                            continue
                                        }
                                        val transform = maskTransformer
                                        if (pressed.size >= 2) {
                                            pinched = true
                                            if (painting) {
                                                // A second finger drops the stroke begun: two fingers zoom the view (or move the mask).
                                                painting = false
                                                pictureOf(last).let { (x, y) -> paint?.invoke(3, x, y, grab) }
                                            }
                                        }
                                        if (pressed.size >= 2 && transform != null) {
                                            val g = geometryRef.value
                                            val before = event.calculateCentroid(useCurrent = false)
                                            val after = event.calculateCentroid(useCurrent = true)
                                            if (before.isSpecified && after.isSpecified) {
                                                val (ax, ay) = currentReframe.sourceOf(before.x, before.y, g.boxW, g.boxH, g.pictureW, g.pictureH)
                                                val (bx, by) = currentReframe.sourceOf(after.x, after.y, g.boxW, g.boxH, g.pictureW, g.pictureH)
                                                transform((bx - ax) / g.pictureW, (by - ay) / g.pictureH, event.calculateZoom(), event.calculateRotation())
                                            }
                                        } else if (!pinched) {
                                            val p = pressed.first().position
                                            when {
                                                painting -> pictureOf(p).let { (x, y) -> paint?.invoke(1, x, y, grab) }
                                                moveMask != null -> move(p)
                                                transform != null -> {
                                                    // A painted mask moves by the finger's travel, not to the finger.
                                                    val (ax, ay) = pictureOf(last)
                                                    val (bx, by) = pictureOf(p)
                                                    transform(bx - ax, by - ay, 1f, 0f)
                                                }
                                            }
                                            last = p
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                    if (painting) pictureOf(last).let { (x, y) -> paint?.invoke(2, x, y, grab) }
                                    return@awaitEachGesture
                                }
                                val widget = renderer.hitTest(down.position.x, down.position.y, slop = 12.dp.toPx())
                                if (widget == null) {
                                    // A tap elsewhere measures colour when the skin tool is open.
                                    // The marker follows the finger; the colour is measured where it lifts.
                                    val onTap = sample ?: return@awaitEachGesture
                                    fun at(p: Offset) = Offset(p.x / size.width, p.y / size.height)
                                    liveMarker = at(down.position)
                                    var last = down.position
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) {
                                            change.consume()
                                            last = change.position
                                            break
                                        }
                                        change.consume()
                                        last = change.position
                                        liveMarker = at(last)
                                    }
                                    val m = at(last)
                                    grab(SAMPLE_WIDTH)?.let { onTap(it, m.x, m.y) }
                                    liveMarker = null
                                    return@awaitEachGesture
                                }
                                val start = renderer.boxOf(widget) ?: return@awaitEachGesture
                                down.consume()
                                var dx = 0f
                                var dy = 0f
                                drag(down.id) { change ->
                                    val delta = change.positionChange()
                                    dx += delta.x
                                    dy += delta.y
                                    change.consume()
                                    val w = size.width.toFloat()
                                    val h = size.height.toFloat()
                                    val placement = Layouts.placementFor(
                                        start.left + dx, start.top + dy, start.width(), start.height(),
                                        w, h, DashboardRenderer.unit(w, h, currentStyle),
                                    )
                                    onMoveWidget(widget, placement)
                                }
                                onMoveFinished()
                            }
                        },
                ) {
                    drawIntoCanvas { renderer.drawVignette(it.nativeCanvas, size.width, size.height, vignette) }
                    guideMask?.at(fileMs)?.let { m ->
                        // Drawn in picture coordinates, moved like the picture.
                        val t = reframeTransform(reframe, geometry)
                        val o = Offset(geometry.pictureW / 2f, geometry.pictureH / 2f)
                        withTransform({
                            translate((geometry.boxW - geometry.pictureW) / 2f + t.translation.x, (geometry.boxH - geometry.pictureH) / 2f + t.translation.y)
                            rotate(reframe.angleDegrees, o)
                            scale(t.zoom, t.zoom, o)
                        }) {
                            // The mask is kept on the original picture: drawn through the perspective
                            // correction, so it shows where it applies.
                            reframe.warp?.let { warp -> drawIntoCanvas { it.nativeCanvas.concat(perspectiveMatrix(warp, geometry.pictureW, geometry.pictureH)) } }
                            if (m.kind == MaskKind.BRUSH) {
                                // With the overlay on, the picture itself shows the mask red (the shader).
                                if (previewShader == null && maskOverlay) {
                                    brushAtlas?.let { atlas -> drawIntoCanvas { drawBrushOverlay(it.nativeCanvas, atlas, guideIndex, m, geometry.pictureW, geometry.pictureH) } }
                                } else if (!maskOverlay) {
                                    brushOutline?.let { outline -> drawIntoCanvas { drawBrushOutline(it.nativeCanvas, outline, m, geometry.pictureW, geometry.pictureH) } }
                                }
                                if (paintPath.size >= 2) {
                                    // The stroke as it is painted, the brush's width, before the mask is drawn again.
                                    val path = androidx.compose.ui.graphics.Path()
                                    paintPath.chunked(2).forEachIndexed { k, (x, y) ->
                                        if (k == 0) path.moveTo(x * geometry.pictureW, y * geometry.pictureH) else path.lineTo(x * geometry.pictureW, y * geometry.pictureH)
                                    }
                                    if (paintPath.size == 2) path.lineTo(paintPath[0] * geometry.pictureW + 0.01f, paintPath[1] * geometry.pictureH)
                                    drawPath(
                                        path,
                                        if (paintErase) Color.Black.copy(alpha = 0.45f * paintStrength) else Color.White.copy(alpha = 0.45f * paintStrength),
                                        style = Stroke(2f * paintRadius * geometry.pictureH, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
                                    )
                                }
                                if (lassoPath.size >= 4) {
                                    val path = androidx.compose.ui.graphics.Path()
                                    lassoPath.chunked(2).forEachIndexed { k, (x, y) ->
                                        if (k == 0) path.moveTo(x * geometry.pictureW, y * geometry.pictureH) else path.lineTo(x * geometry.pictureW, y * geometry.pictureH)
                                    }
                                    drawPath(path, Color.Black.copy(alpha = 0.5f), style = Stroke(5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                                    drawPath(path, Color.White, style = Stroke(2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                                }
                            } else {
                                drawMaskGuide(m, geometry.pictureW, geometry.pictureH, gap = if (maskEnd != null) ROTATE_GAP.toPx() / t.zoom.coerceAtLeast(0.01f) else 0f)
                            }
                        }
                    }
                    // The brush as it paints, while its size is set: in the middle of what is in view
                    // (zoomed in with View, the part being worked on), as big as it paints there.
                    if (ringOn && paintRadius > 0f) {
                        val r = paintRadius * geometry.pictureH * reframeTransform(reframe, geometry).zoom
                        val z = if (viewZoomed) viewZoom else 1f
                        val c = if (viewZoomed) center - viewPan / z else center
                        val hard = (1f - brushSoftness).coerceIn(0f, 0.99f)
                        drawCircle(
                            androidx.compose.ui.graphics.Brush.radialGradient(
                                0f to Color.White.copy(alpha = 0.4f * paintStrength), hard to Color.White.copy(alpha = 0.4f * paintStrength), 1f to Color.Transparent,
                                center = c, radius = r,
                            ),
                            r, c,
                        )
                        drawCircle(Color.Black.copy(alpha = 0.5f), r + 1.5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
                        drawCircle(Color.White, r, c, style = Stroke(1.5.dp.toPx()))
                    }
                    // Clean up: the stroke over what is to be removed, as it is painted (white, so the photo shows through).
                    if (guideMask == null && paintPath.size >= 2) {
                        val t = reframeTransform(reframe, geometry)
                        val o = Offset(geometry.pictureW / 2f, geometry.pictureH / 2f)
                        withTransform({
                            translate((geometry.boxW - geometry.pictureW) / 2f + t.translation.x, (geometry.boxH - geometry.pictureH) / 2f + t.translation.y)
                            rotate(reframe.angleDegrees, o)
                            scale(t.zoom, t.zoom, o)
                        }) {
                            val path = androidx.compose.ui.graphics.Path()
                            paintPath.chunked(2).forEachIndexed { k, (x, y) ->
                                if (k == 0) path.moveTo(x * geometry.pictureW, y * geometry.pictureH) else path.lineTo(x * geometry.pictureW, y * geometry.pictureH)
                            }
                            if (paintPath.size == 2) path.lineTo(paintPath[0] * geometry.pictureW + 0.01f, paintPath[1] * geometry.pictureH)
                            drawPath(
                                path,
                                Color.White.copy(alpha = 0.55f),
                                style = Stroke(2f * paintRadius * geometry.pictureH, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
                            )
                        }
                    }
                    // Not while playing or after: the picture has moved on from what was measured.
                    (liveMarker ?: marker?.takeIf { !wantsPlay && !markerStale })?.let { m ->
                        val c = Offset(m.x * size.width, m.y * size.height)
                        drawCircle(Color.Black.copy(alpha = 0.5f), radius = 14.dp.toPx(), center = c, style = Stroke(3.dp.toPx()))
                        drawCircle(HeadsupColors.Accent, radius = 14.dp.toPx(), center = c, style = Stroke(1.5.dp.toPx()))
                        drawCircle(HeadsupColors.Accent, radius = 2.dp.toPx(), center = c)
                    }
                    val utc = clip.telemetryUtc(positionMs)
                    val track = activity?.track
                    val frame = utc?.let { sampler?.frameAt(it) }
                    if (utc == null || track == null || frame == null) {
                        // No watch data here: the picture effects still show.
                        drawIntoCanvas { renderer.drawBars(it.nativeCanvas, size.width, size.height, barsNow) }
                        return@Canvas
                    }
                    val zone = track.utcOffsetSeconds
                        ?: ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(utc)).totalSeconds
                    val data = DashboardData(track, frame, utc, zone)
                    drawIntoCanvas { renderer.draw(it.nativeCanvas, size.width, size.height, style, data, barsNow) }
                }
                // Texts sit on the frame (not the picture), over the dashboard, as in the export.
                val textMover by rememberUpdatedState(onMoveText)
                // While a text is dragged, guides show the middle and the margins it snaps to.
                var placing by remember { mutableStateOf(false) }
                Canvas(
                    Modifier.matchParentSize().then(
                        if (onMoveText == null) {
                            Modifier
                        } else {
                            Modifier.pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    down.consume()
                                    placing = true
                                    textMover?.invoke(down.position.x / size.width, down.position.y / size.height)
                                    drag(down.id) { change ->
                                        change.consume()
                                        textMover?.invoke((change.position.x / size.width).coerceIn(0f, 1f), (change.position.y / size.height).coerceIn(0f, 1f))
                                    }
                                    placing = false
                                }
                            }
                        },
                    ),
                ) {
                    routeOverlay?.let { draw -> drawIntoCanvas { draw(it.nativeCanvas, size.width, size.height, positionMs) } }
                    if (placing) {
                        val guide = Color.White.copy(alpha = 0.35f)
                        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                        drawLine(guide, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 1.5f, pathEffect = dash)
                        drawLine(guide, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1.5f, pathEffect = dash)
                        for (fx in listOf(0.06f, 0.94f)) drawLine(guide.copy(alpha = 0.2f), Offset(size.width * fx, 0f), Offset(size.width * fx, size.height), 1f, pathEffect = dash)
                        for (fy in listOf(0.1f, 0.9f)) drawLine(guide.copy(alpha = 0.2f), Offset(0f, size.height * fy), Offset(size.width, size.height * fy), 1f, pathEffect = dash)
                    }
                    if (texts.isNotEmpty()) {
                        val values = if (texts.any { app.headsup.core.edit.TextFields.hasFields(it.text) }) textValues(positionMs) else emptyMap()
                        drawIntoCanvas { TextRenderer.draw(it.nativeCanvas, size.width, size.height, texts, timelineOffsetMs + clipTimelineMs, values) }
                    }
                }
                if (fadeGain < 1f) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 1f - fadeGain)))
                if (showGrid) Canvas(Modifier.matchParentSize()) { drawThirds() }
                if (showingOriginal) {
                    Text(
                        originalLabel,
                        Modifier.align(Alignment.TopCenter).padding(top = 10.dp).clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 4.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                playerError?.takeIf { !photo }?.let { e ->
                    Text(
                        "Playback stopped: $e",
                        Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
                if (histogramOn) {
                    val at = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = if (compactTransport) 56.dp else 10.dp)
                        .clickable { scopeKind = (scopeKind + 1) % 3 }
                    if (scopeKind == 0) {
                        histogram?.let { bins -> Histogram(bins, at.size(width = 128.dp, height = 56.dp)) }
                    } else {
                        scopeImage?.let { img -> Scope(img, if (scopeKind == 1) "Waveform" else "RGB parade", at.size(width = 176.dp, height = 88.dp)) }
                    }
                }
                if (showTransport && compactTransport && !photoMode) {
                    // Folded transport: play, the time and unfolding, on the picture.
                    Row(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            // A finger going up here unfolds the transport and the timeline.
                            .pointerInput(onCompact) {
                                val unfold = onCompact ?: return@pointerInput
                                var dy = 0f
                                detectVerticalDragGestures(onDragStart = { dy = 0f }) { change, amount ->
                                    dy += amount
                                    if (dy < -28.dp.toPx()) { change.consume(); unfold(false); dy = 0f }
                                }
                            }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f))
                                .clickable { togglePlay() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(if (wantsPlay) HeadsupIcons.Pause else HeadsupIcons.Play, contentDescription = if (wantsPlay) "Pause" else "Play", tint = Color.White)
                        }
                        Text(
                            "  " + timecode(timelineOffsetMs + clipTimelineMs, clip.meta.frameRate) + "  /  " + Formats.duration(timelineTotalMs),
                            Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.4f)).padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                            color = Color.White,
                            maxLines = 1,
                        )
                        Spacer(Modifier.weight(1f))
                        // Sideways only: upright a double tap on the handle under the picture brings them back.
                        onCompact?.takeIf { sideways }?.let { unfold ->
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).clickable { unfold(false) },
                                contentAlignment = Alignment.Center,
                            ) { Icon(HeadsupIcons.ChevronUp, "Show the controls", Modifier.size(22.dp), tint = Color.White) }
                        }
                    }
                }
            }
        }

        if (showTransport && !compactTransport) Row(
            Modifier
                .fillMaxWidth()
                // Sideways, a finger going down the transport folds it and the timeline away.
                .pointerInput(onCompact, sideways) {
                    // Upright the arrow folds them; only sideways does a finger going down.
                    val fold = onCompact?.takeIf { sideways } ?: return@pointerInput
                    var dy = 0f
                    detectVerticalDragGestures(onDragStart = { dy = 0f }) { change, amount ->
                        dy += amount
                        if (dy > 28.dp.toPx()) { change.consume(); fold(true); dy = 0f }
                    }
                }
                .clip(RoundedCornerShape(50)).background(HeadsupColors.CardGradient).border(1.dp, HeadsupColors.Outline, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!photoMode) Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(HeadsupColors.Accent)
                    .clickable { togglePlay() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (wantsPlay) HeadsupIcons.Pause else HeadsupIcons.Play, contentDescription = if (wantsPlay) "Pause" else "Play", tint = HeadsupColors.OnAccent)
            }
            // To the clip's first or last frame exactly (where a mask is made or tracked from).
            if (!photoMode && !photo) {
                Spacer(Modifier.width(6.dp))
                // Pressed again where it already is (the first or last frame): the clip before or after.
                val frameMs = (1000f / (clip.meta.frameRate ?: 30f)).toLong().coerceAtLeast(1L)
                ToggleDot("⇤", false) {
                    if (playerMs <= frameMs && onStepClip != null) onStepClip(-1) else jumpInClip(0L)
                }
                Spacer(Modifier.width(4.dp))
                ToggleDot("⇥", false) {
                    if (playerMs >= clip.durationMs - 2 * frameMs && onStepClip != null) onStepClip(1)
                    else jumpInClip((clip.durationMs - frameMs).coerceAtLeast(0L))
                }
                // A flag here (lit when the moment shown has one; tapped then, it goes).
                if (onFlag != null) {
                    Spacer(Modifier.width(4.dp))
                    val here = clip.trimStartMs + playerMs
                    ToggleDot("⚑", clip.flags.any { kotlin.math.abs(it - here) <= HeadsupViewModel.FLAG_REACH_MS }) { onFlag(clip.trimStartMs + playerMs) }
                }
            }
            // Timeline time (the timeline below is scrubbed by dragging it); file time while a cut handle moves.
            if (photoMode) {
                // The file: its kind, size in pixels and on disk.
                val m = clip.meta
                // A raw is a DNG whatever its name says (pickers may name it .jpg).
                val ext = if (m.isRaw) "DNG" else m.name.substringAfterLast('.', "").uppercase().takeIf { it.isNotEmpty() && it.length <= 4 }
                val info = listOfNotNull(
                    ext,
                    "RAW".takeIf { m.isRaw && ext != "RAW" },
                    "${m.width}×${m.height}",
                    m.bytes?.let { b -> if (b >= 1_000_000) String.format(java.util.Locale.US, "%.1f MB", b / 1e6) else "${b / 1000} kB" },
                ).joinToString("  ·  ")
                Text("  $info", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), maxLines = 1)
            } else Text(
                "  " + (if (scrubbing) timecode(playerMs, clip.meta.frameRate) else timecode(timelineOffsetMs + clipTimelineMs, clip.meta.frameRate)) +
                    "  /  " + Formats.duration(timelineTotalMs),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
            )
            // The shown clip's own frame rate (the export's is chosen on the Export page).
            clip.meta.frameRate?.takeIf { !photo && !photoMode }?.let { fps ->
                Text(
                    String.format(java.util.Locale.US, "%.4g fps", fps) + (if (clip.speed != 1f) " · ${String.format(java.util.Locale.US, "%.3g", clip.speed)}×" else ""),
                    Modifier.padding(end = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = HeadsupColors.TextMuted,
                    maxLines = 1,
                )
            }
            // A ramped clip: a little speed graph.
            if (clip.speedCurve.isNotEmpty() && !photoMode) SpeedRampIcon(Modifier.padding(end = 8.dp), tint = HeadsupColors.TextMuted)
            // Scopes, a tap each: histogram, waveform, RGB parade, off.
            ToggleDot(if (!histogramOn) "H" else listOf("H", "W", "P")[scopeKind], histogramOn) {
                if (!histogramOn) { histogramOn = true; scopeKind = 0 }
                else if (scopeKind < 2) scopeKind++
                else { histogramOn = false; scopeKind = 0 }
            }
            Spacer(Modifier.width(4.dp))
            if (Build.VERSION.SDK_INT >= 33) ToggleDot("Z", zebra) { zebra = !zebra }
            // Sideways: fold the transport and the timeline away for a bigger picture (upright the
            // arrow is beside the handle under the transport: the transport has no room).
            onCompact?.takeIf { sideways }?.let { fold ->
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier.size(32.dp).clip(CircleShape).border(1.dp, HeadsupColors.Outline, CircleShape).clickable { fold(true) },
                    contentAlignment = Alignment.Center,
                ) { Icon(HeadsupIcons.ChevronDown, "Fold the controls", Modifier.size(18.dp), tint = HeadsupColors.TextMuted) }
            }
        }
    }
}

private const val SAMPLE_WIDTH = 192


/** How long before a cut the next clip starts (hidden), and when the picture switches to it. */
private const val HANDOVER_LEAD_MS = 120L
private const val HANDOVER_AT_MS = 20L

/** A photo for the preview: at most about 2048 px, turned upright by its EXIF orientation. */
private fun decodePhoto(context: android.content.Context, uri: android.net.Uri): ImageBitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    val raw = context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) } ?: return@runCatching null
    val orientation = app.headsup.media.Exif.orientation(context, uri)
    val degrees = when (orientation) {
        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    val upright = if (degrees == 0f) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, android.graphics.Matrix().apply { postRotate(degrees) }, true)
    upright.asImageBitmap()
}.getOrNull()

internal class ReframeTransform(val zoom: Float, val translation: Offset)

/** The preview frame ([boxW]×[boxH], the output shape) and the picture laid out centred under it, just covering it. */
private class GeometryRef(var value: FrameGeometry)

internal data class FrameGeometry(val boxW: Float, val boxH: Float, val pictureW: Float, val pictureH: Float)

/**
 * Layer transform (around the picture's centre) that shows the picture as [reframe] describes:
 * the source point at the reframe centre lands in the middle of the frame.
 */
internal fun reframeTransform(reframe: Reframe, g: FrameGeometry): ReframeTransform {
    val width = g.pictureW
    val height = g.pictureH
    if (reframe.isIdentity || width <= 0f || height <= 0f) return ReframeTransform(1f, Offset.Zero)
    val r = reframe.clamped(g.boxW, g.boxH, width, height)
    val z = r.effectiveZoom(g.boxW, g.boxH, width, height)
    val a = Math.toRadians(r.angleDegrees.toDouble())
    // T = z·R(θ)·(O − C), clockwise rotation with y down; the picture is already centred in the frame.
    val vx = width / 2f - r.centerX * width
    val vy = height / 2f - r.centerY * height
    val tx = z * (vx * kotlin.math.cos(a) - vy * kotlin.math.sin(a)).toFloat()
    val ty = z * (vx * kotlin.math.sin(a) + vy * kotlin.math.cos(a)).toFloat()
    return ReframeTransform(z, Offset(tx, ty))
}

/** Rule-of-thirds lines, with a faint shadow so they show on bright and dark pictures. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawThirds() {
    for (i in 1..2) {
        val x = size.width * i / 3f
        val y = size.height * i / 3f
        for ((colour, width) in listOf(Color.Black.copy(alpha = 0.35f) to 2.5f, Color.White.copy(alpha = 0.75f) to 1f)) {
            drawLine(colour, Offset(x, 0f), Offset(x, size.height), strokeWidth = width * density)
            drawLine(colour, Offset(0f, y), Offset(size.width, y), strokeWidth = width * density)
        }
    }
}
private const val ANALYSIS_WIDTH = 256

/**
 * From the original picture to the corrected one, in pixels of a [w]×[h] picture ([warp] takes
 * the corrected picture's fractions to the original's): what is drawn on the original shows where
 * it is on screen.
 */
private fun perspectiveMatrix(warp: DoubleArray, w: Float, h: Float): android.graphics.Matrix {
    val back = app.headsup.core.frame.Perspective.invert(warp)
    // In pixels: S · back · S⁻¹, S scaling fractions to pixels.
    val m = floatArrayOf(
        back[0].toFloat(), (back[1] * w / h).toFloat(), (back[2] * w).toFloat(),
        (back[3] * h / w).toFloat(), back[4].toFloat(), (back[5] * h).toFloat(),
        (back[6] / w).toFloat(), (back[7] / h).toFloat(), back[8].toFloat(),
    )
    return android.graphics.Matrix().apply { setValues(m) }
}

/** The mask's edge (solid) and the ends of its soft blend (dashed), as in DaVinci's window controls. */
/** How far the turning handle sits past a gradient's middle (a linear one) or its top end (a radial one). */
private val ROTATE_GAP = 40.dp

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMaskGuide(mask: ColourMask, width: Float, height: Float, gap: Float = 0f) {
    // A range mask is the whole picture: no window to show.
    if (mask.kind == MaskKind.RANGE) return
    val centre = Offset(mask.centerX * width, mask.centerY * height)
    val dash = PathEffect.dashPathEffect(floatArrayOf(8f * density, 6f * density))
    val solid = Color.White.copy(alpha = 0.9f)
    val faint = Color.White.copy(alpha = 0.6f)
    rotate(mask.angleDegrees, pivot = centre) {
        when (mask.kind) {
            MaskKind.LINEAR -> {
                val reach = width + height
                for ((offset, dashed) in listOf(0f to false, -mask.softness / 2f to true, mask.softness / 2f to true)) {
                    val y = centre.y + offset * height
                    drawLine(
                        if (dashed) faint else solid, Offset(centre.x - reach, y), Offset(centre.x + reach, y),
                        strokeWidth = (if (dashed) 1f else 1.5f) * density, pathEffect = if (dashed) dash else null,
                    )
                    // Where the dashed lines (the gradient's start and end) are grabbed.
                    if (dashed) {
                        drawCircle(Color.Black.copy(alpha = 0.45f), radius = 7.5f * density, center = Offset(centre.x, y))
                        drawCircle(solid, radius = 5.5f * density, center = Offset(centre.x, y))
                    }
                }
                // The handle that turns it, on a stalk from the middle.
                if (gap > 0f) {
                    drawLine(solid, centre, Offset(centre.x, centre.y - gap), strokeWidth = 1.5f * density)
                    drawCircle(Color.Black.copy(alpha = 0.45f), radius = 7.5f * density, center = Offset(centre.x, centre.y - gap))
                    drawCircle(Color(0xFFFF9A4D), radius = 5.5f * density, center = Offset(centre.x, centre.y - gap))
                }
            }
            MaskKind.BRUSH, MaskKind.RANGE -> Unit
            MaskKind.ELLIPSE -> {
                for ((scale, dashed) in listOf(1f to false, 1f - mask.softness / 2f to true, 1f + mask.softness / 2f to true)) {
                    if (scale <= 0f) continue
                    val rx = mask.sizeX / 2f * height * scale
                    val ry = mask.sizeY / 2f * height * scale
                    drawOval(
                        if (dashed) faint else solid, topLeft = Offset(centre.x - rx, centre.y - ry), size = Size(2 * rx, 2 * ry),
                        style = Stroke((if (dashed) 1f else 1.5f) * density, pathEffect = if (dashed) dash else null),
                    )
                }
                // Its four ends, to grab and drag; above, the handle that turns it.
                val rx = mask.sizeX / 2f * height
                val ry = mask.sizeY / 2f * height
                if (gap > 0f) {
                    drawLine(solid, Offset(centre.x, centre.y - ry), Offset(centre.x, centre.y - ry - gap), strokeWidth = 1.5f * density)
                    drawCircle(Color.Black.copy(alpha = 0.45f), radius = 7.5f * density, center = Offset(centre.x, centre.y - ry - gap))
                    drawCircle(Color(0xFFFF9A4D), radius = 5.5f * density, center = Offset(centre.x, centre.y - ry - gap))
                }
                for (p in listOf(Offset(centre.x + rx, centre.y), Offset(centre.x - rx, centre.y), Offset(centre.x, centre.y + ry), Offset(centre.x, centre.y - ry))) {
                    drawCircle(Color.Black.copy(alpha = 0.45f), radius = 7.5f * density, center = p)
                    drawCircle(solid, radius = 5.5f * density, center = p)
                }
            }
        }
    }
    drawCircle(solid, radius = 4f * density, center = centre)
}

/** Where a brush mask paints, in red over the picture (as Lightroom shows it), moved with the mask. */
private val brushOverlayPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
    colorFilter = android.graphics.ColorMatrixColorFilter(
        floatArrayOf(
            0f, 0f, 0f, 0f, 255f,
            0f, 0f, 0f, 0f, 40f,
            0f, 0f, 0f, 0f, 40f,
            0.45f, 0f, 0f, 0f, 0f,
        ),
    )
}

private fun drawBrushOverlay(canvas: android.graphics.Canvas, atlas: Bitmap, slot: Int, mask: ColourMask, width: Float, height: Float) {
    val slotW = atlas.width / ColourMask.MAX_PER_CLIP
    canvas.save()
    canvas.translate(mask.centerX * width, mask.centerY * height)
    canvas.rotate(mask.angleDegrees)
    canvas.scale(mask.sizeX, mask.sizeY)
    canvas.translate(-width / 2f, -height / 2f)
    canvas.drawBitmap(atlas, android.graphics.Rect(slot * slotW, 0, (slot + 1) * slotW, atlas.height), android.graphics.RectF(0f, 0f, width, height), brushOverlayPaint)
    canvas.restore()
}

/**
 * The edge of slot [slot]'s mask in [atlas] (where its coverage crosses one half) as a thin white
 * line with a dark rim, so it shows on any picture; the frame's own border is no edge.
 */
private fun outlineOf(atlas: Bitmap, slot: Int): Bitmap? {
    val w = atlas.width / ColourMask.MAX_PER_CLIP
    val h = atlas.height
    if (w < 3 || h < 3 || slot !in 0 until ColourMask.MAX_PER_CLIP) return null
    val px = IntArray(w * h)
    atlas.getPixels(px, 0, w, slot * w, 0, w, h)
    // The outline of what was selected or painted (blue: before feather and blend).
    val inside = BooleanArray(w * h) { (px[it] and 0xFF) >= 128 }
    val out = IntArray(w * h)
    val line = android.graphics.Color.WHITE
    val rim = android.graphics.Color.argb(140, 0, 0, 0)
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        val i = y * w + x
        if (inside[i] && (!inside[i - 1] || !inside[i + 1] || !inside[i - w] || !inside[i + w])) out[i] = line
    }
    // The dark rim around the line.
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        val i = y * w + x
        if (out[i] == 0 && (out[i - 1] == line || out[i + 1] == line || out[i - w] == line || out[i + w] == line)) out[i] = rim
    }
    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
}

private val outlinePaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

/** [outline] (a mask's edge, from [outlineOf]) placed as the mask is. */
private fun drawBrushOutline(canvas: android.graphics.Canvas, outline: Bitmap, mask: ColourMask, width: Float, height: Float) {
    canvas.save()
    canvas.translate(mask.centerX * width, mask.centerY * height)
    canvas.rotate(mask.angleDegrees)
    canvas.scale(mask.sizeX, mask.sizeY)
    canvas.translate(-width / 2f, -height / 2f)
    canvas.drawBitmap(outline, null, android.graphics.RectF(0f, 0f, width, height), outlinePaint)
    canvas.restore()
}

private val NO_SCRUB: State<Long?> = mutableStateOf(null)

/**
 * Seeking for a dragged finger: one seek at a time, always to the latest position, so the
 * picture keeps up instead of the player working through a queue of stale seeks.
 */
private class Scrubber(private val player: () -> ExoPlayer, private val scope: CoroutineScope) {
    private var target = 0L
    private var running = false

    fun seek(positionMs: Long) {
        target = positionMs.coerceAtLeast(0L)
        if (running) return
        running = true
        scope.launch {
            try {
                var done = -1L
                while (target != done) {
                    done = target
                    val p = player()
                    p.seekTo(done)
                    // Until the frame at the new position is ready (bounded, in case it never is).
                    withTimeoutOrNull(400) {
                        while (p.playbackState == Player.STATE_BUFFERING) delay(8)
                    }
                }
            } finally {
                running = false
            }
        }
    }
}

@Composable
private fun ToggleDot(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (on) HeadsupColors.Accent else Color.Transparent)
            .border(1.dp, if (on) HeadsupColors.Accent else HeadsupColors.Outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) HeadsupColors.OnAccent else HeadsupColors.TextMuted)
    }
}

/** Luminance histogram of the graded picture, drawn over the preview's corner. */
@Composable
private fun Histogram(bins: FloatArray, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.5f))) {
        val path = androidx.compose.ui.graphics.Path()
        val step = size.width / (bins.size - 1)
        path.moveTo(0f, size.height)
        bins.forEachIndexed { i, v -> path.lineTo(i * step, size.height - v * size.height * 0.92f) }
        path.lineTo(size.width, size.height)
        path.close()
        drawPath(path, Color.White.copy(alpha = 0.55f))
        // Clipping warnings at both ends.
        if (bins.first() > 0.6f) drawRect(HeadsupColors.Accent, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
        if (bins.last() > 0.6f) drawRect(HeadsupColors.Accent, topLeft = Offset(size.width - 2.dp.toPx(), 0f), size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
    }
}

/**
 * A waveform or parade drawn over the preview's corner: across, the picture's columns; up, how
 * bright (0 at the bottom, full at the top), with lines at 0, 50 and 100 %; a tap shows the next scope.
 */
@Composable
private fun Scope(image: androidx.compose.ui.graphics.ImageBitmap, label: String, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.6f))) {
        drawImage(
            image,
            dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
        )
        for (level in listOf(0f, 0.5f, 1f)) {
            val y = size.height * (1f - level * 0.96f) - 1f
            drawLine(Color.White.copy(alpha = if (level == 0.5f) 0.15f else 0.3f), Offset(0f, y), Offset(size.width, y))
        }
        drawIntoCanvas {
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(150, 255, 255, 255); textSize = 9.dp.toPx() }
            it.nativeCanvas.drawText(label, 5.dp.toPx(), 11.dp.toPx(), p)
        }
    }
}

/** The small frame's colours after the colour table, as 0..1 RGB in a row. */
private fun gradedPixels(frame: Bitmap, cube: Array<Array<IntArray>>?): Triple<FloatArray, Int, Int> {
    val w = frame.width
    val h = frame.height
    val pixels = IntArray(w * h)
    frame.getPixels(pixels, 0, w, 0, 0, w, h)
    frame.recycle()
    val out = FloatArray(w * h * 3)
    val rgb = FloatArray(3)
    for (i in pixels.indices) {
        val p = pixels[i]
        rgb[0] = ((p shr 16) and 0xFF) / 255f
        rgb[1] = ((p shr 8) and 0xFF) / 255f
        rgb[2] = (p and 0xFF) / 255f
        if (cube != null) LutBaker.sampleBaked(cube, rgb[0], rgb[1], rgb[2], rgb)
        out[i * 3] = rgb[0]; out[i * 3 + 1] = rgb[1]; out[i * 3 + 2] = rgb[2]
    }
    return Triple(out, w, h)
}

private const val SCOPE_LEVELS = 96

/** How many pixels of each column fall at each level, as a picture: brighter where more. */
private fun scopePicture(counts: Array<IntArray>, colour: (Int) -> Int, perColumn: Int): androidx.compose.ui.graphics.ImageBitmap {
    val cols = counts.size
    val px = IntArray(cols * SCOPE_LEVELS)
    for (c in 0 until cols) for (l in 0 until SCOPE_LEVELS) {
        val n = counts[c][l]
        if (n == 0) continue
        // A square root keeps a few pixels visible beside many.
        val a = (kotlin.math.sqrt(n.toFloat() / perColumn.coerceAtLeast(1)) * 255f * 2.2f).toInt().coerceIn(40, 255)
        px[(SCOPE_LEVELS - 1 - l) * cols + c] = (a shl 24) or (colour(c) and 0xFFFFFF)
    }
    return Bitmap.createBitmap(px, cols, SCOPE_LEVELS, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Luma waveform: for each column of the picture, where its pixels' brightness lies. */
private fun waveform(frame: Bitmap, cube: Array<Array<IntArray>>?): androidx.compose.ui.graphics.ImageBitmap {
    val (rgb, w, h) = gradedPixels(frame, cube)
    val counts = Array(w) { IntArray(SCOPE_LEVELS) }
    for (y in 0 until h) for (x in 0 until w) {
        val i = (y * w + x) * 3
        val l = 0.2126f * rgb[i] + 0.7152f * rgb[i + 1] + 0.0722f * rgb[i + 2]
        counts[x][(l * (SCOPE_LEVELS - 1)).toInt().coerceIn(0, SCOPE_LEVELS - 1)]++
    }
    return scopePicture(counts, { 0xCFF5D6 }, h)
}

/** RGB parade: red, green and blue waveforms side by side, each a third of the width. */
private fun parade(frame: Bitmap, cube: Array<Array<IntArray>>?): androidx.compose.ui.graphics.ImageBitmap {
    val (rgb, w, h) = gradedPixels(frame, cube)
    val third = (w / 2).coerceAtLeast(1)
    val counts = Array(third * 3) { IntArray(SCOPE_LEVELS) }
    for (y in 0 until h) for (x in 0 until w) {
        val i = (y * w + x) * 3
        val col = x * third / w
        for (c in 0..2) counts[c * third + col][(rgb[i + c] * (SCOPE_LEVELS - 1)).toInt().coerceIn(0, SCOPE_LEVELS - 1)]++
    }
    val colours = intArrayOf(0xFF5A5A, 0x5AE05A, 0x6A8CFF)
    return scopePicture(counts, { c -> colours[c / third] }, h * 2)
}

/** 64-bin luminance histogram of a small frame grab after the colour table, scaled to its peak. */
private fun luminanceHistogram(frame: Bitmap, cube: Array<Array<IntArray>>?): FloatArray {
    val pixels = IntArray(frame.width * frame.height)
    frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
    frame.recycle()
    val bins = FloatArray(64)
    val rgb = FloatArray(3)
    for (p in pixels) {
        rgb[0] = ((p shr 16) and 0xFF) / 255f
        rgb[1] = ((p shr 8) and 0xFF) / 255f
        rgb[2] = (p and 0xFF) / 255f
        if (cube != null) LutBaker.sampleBaked(cube, rgb[0], rgb[1], rgb[2], rgb)
        val y = 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2]
        bins[(y * 63).toInt().coerceIn(0, 63)] += 1f
    }
    val peak = bins.max().coerceAtLeast(1f)
    // Square root keeps small populations visible next to a dominant tone.
    for (i in bins.indices) bins[i] = kotlin.math.sqrt(bins[i] / peak)
    return bins
}

/** Camera-style timecode HH:MM:SS:FF. */
private fun timecode(ms: Long, frameRate: Float?): String {
    val fps = (frameRate ?: 30f).let { kotlin.math.round(it).toInt().coerceAtLeast(1) }
    val totalSeconds = ms / 1000
    val frames = ((ms % 1000) * fps / 1000).toInt()
    return String.format(java.util.Locale.US, "%02d:%02d:%02d:%02d", totalSeconds / 3600, totalSeconds / 60 % 60, totalSeconds % 60, frames)
}

/**
 * A turning arc driven by the frame clock itself, so it turns also when the phone's animations
 * are switched off (which stops the standard progress indicator).
 */
@Composable
private fun Spinner(modifier: Modifier) {
    var angle by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameMillis { t ->
            if (last > 0) angle = (angle + (t - last) * 0.36f) % 360f
            last = t
        }
    }
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = size.minDimension * 0.12f
        drawArc(
            Color.White, startAngle = angle, sweepAngle = 270f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
            size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}
