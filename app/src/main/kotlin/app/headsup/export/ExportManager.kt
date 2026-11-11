package app.headsup.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.SingleColorLut
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.FrameDropEffect
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedChangingAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.transformer.EditedMediaItemSequence
import android.graphics.Matrix
import app.headsup.core.edit.TextItem
import androidx.media3.common.util.Size
import app.headsup.core.frame.Reframe
import app.headsup.core.color.ColorGrade
import app.headsup.core.color.LutBaker
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import app.headsup.core.track.TelemetrySampler
import app.headsup.media.VideoFormatInfo
import app.headsup.render.DashboardRenderer
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class JobStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class ExportJob(
    val id: Int,
    val spec: JobSpec,
    val status: JobStatus = JobStatus.QUEUED,
    val progress: Int = 0,
    /** Result or error, shown under the job. */
    val message: String? = null,
    /** The saved video, for sharing. */
    val resultUri: Uri? = null,
)

/**
 * Runs exports one after another. Lives in the Application so it survives the screen being
 * recreated; [ExportService] keeps the process in the foreground while jobs are pending.
 * All Transformer calls happen on the main thread, as Media3 requires.
 */
@OptIn(UnstableApi::class)
class ExportManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _jobs = MutableStateFlow<List<ExportJob>>(emptyList())
    val jobs: StateFlow<List<ExportJob>> = _jobs.asStateFlow()

    private var nextId = 1
    private var transformer: Transformer? = null
    private var worker: Job? = null
    private val workDir get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "export").apply { mkdirs() }

    val isBusy: Boolean get() = _jobs.value.any { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }

    fun enqueue(specs: List<JobSpec>) {
        if (specs.isEmpty()) return
        _jobs.update { jobs -> jobs.filter { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING } + specs.map { ExportJob(nextId++, it) } }
        // Without the service the export still runs, it just may stop if the app goes to the background.
        runCatching { context.startForegroundService(Intent(context, ExportService::class.java)) }
        startNextIfIdle()
    }

    fun cancelAll() {
        transformer?.cancel()
        transformer = null
        worker?.cancel()
        worker = null
        _jobs.update { jobs ->
            jobs.map { if (it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING) it.copy(status = JobStatus.CANCELLED) else it }
        }
    }

    fun clearFinished() = _jobs.update { jobs -> jobs.filter { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING } }

    private fun startNextIfIdle() {
        if (_jobs.value.any { it.status == JobStatus.RUNNING }) return
        val job = _jobs.value.firstOrNull { it.status == JobStatus.QUEUED } ?: return
        setJob(job.id) { it.copy(status = JobStatus.RUNNING, progress = 0) }
        try {
            when (job.spec.kind) {
                ExportKind.PNG_SEQUENCE -> runPng(job)
                ExportKind.VIDEO -> runVideo(job)
            }
        } catch (e: Throwable) {
            finish(job.id, JobStatus.FAILED, "Could not start: ${e.stackTraceToString().take(1500)}")
        }
    }

    private fun finish(id: Int, status: JobStatus, message: String?, resultUri: Uri? = null) {
        // A job cancelled by the user stays cancelled even if a late callback arrives.
        setJob(id) { if (it.status == JobStatus.RUNNING) it.copy(status = status, progress = 100, message = message, resultUri = resultUri) else it }
        transformer = null
        worker = null
        startNextIfIdle()
    }

    private fun setJob(id: Int, transform: (ExportJob) -> ExportJob) =
        _jobs.update { jobs -> jobs.map { if (it.id == id) transform(it) else it } }

    private fun runPng(job: ExportJob) {
        worker = scope.launch {
            try {
                val name = withContext(Dispatchers.Default) {
                    PngSequenceExporter.export(context, job.spec.parts.single()) { p -> setJob(job.id) { it.copy(progress = p) } }
                }
                finish(job.id, JobStatus.DONE, "Saved ${MediaStoreOutput.OVERLAY_FOLDER}/$name")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                finish(job.id, JobStatus.FAILED, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * The reframe as a Media3 matrix on normalised device coordinates (y up, −1..1 on both axes):
     * aspect-corrected so rotation does not shear the picture.
     */
    /**
     * The clip in the timeline's frame, drawn straight at the export size [outW]×[outH]: the frame
     * ([Reframe.outputSize]: inside the picture when filling, around it when fitting) with the
     * picture zoomed, moved and turned into it; bars stay black. Working in normalised coordinates
     * keeps any size in between (an 8K clip fitted into a wide frame) from being drawn.
     */
    private fun reframeEffect(reframe: Reframe, width: Int, height: Int, outW: Int, outH: Int): androidx.media3.common.Effect? {
        val matrix = reframeMatrix(reframe, width, height) ?: return null
        // Fitted with the bars filled by the picture blurred: drawn by an effect of its own.
        if (reframe.fit && reframe.blur) return BlurFillEffect(matrix, outW, outH)
        return object : MatrixTransformation {
            override fun configure(inputWidth: Int, inputHeight: Int) = Size(outW, outH)
            override fun getMatrix(presentationTimeUs: Long) = matrix
        }
    }

    /**
     * The shot steadied: each frame moved, turned and zoomed by its correction
     * ([app.headsup.core.frame.Stabilizer.corrections]), in Media3's normalised coordinates,
     * aspect-corrected so the turn does not shear the picture.
     */
    private fun stabilizeEffect(path: app.headsup.core.frame.Stabilizer.Path, width: Int, height: Int, fileStartMs: Long): androidx.media3.common.Effect? {
        if (width <= 0 || height <= 0) return null
        val corrections = app.headsup.core.frame.Stabilizer.corrections(path, width.toFloat() / height)
        if (corrections.all { it.isNone }) return null
        val w = width.toFloat()
        val h = height.toFloat()
        return object : MatrixTransformation {
            // Time counts from the first frame, however Media3 timestamps a clipped item.
            private var firstUs = -1L
            override fun getMatrix(presentationTimeUs: Long): Matrix {
                if (firstUs < 0) firstUs = presentationTimeUs
                val c = app.headsup.core.frame.Stabilizer.at(path, corrections, fileStartMs + (presentationTimeUs - firstUs) / 1000)
                return Matrix().apply {
                    postScale(w / 2f, h / 2f)
                    // Clockwise on screen is a negative angle with y pointing up.
                    postRotate(-c.degrees)
                    postScale(2f / w, 2f / h)
                    // Fractions of the frame to normalised coordinates (y up).
                    postTranslate(2f * c.dx, -2f * c.dy)
                    postScale(c.zoom, c.zoom)
                }
            }
        }
    }

    /** Texts stuck to the picture of [request]'s clip, where the place they follow is at a moment (the clip's own time). */
    private fun textPlacer(request: ExportRequest): (List<TextItem>, Long) -> List<TextItem> {
        if (request.isPhoto || request.texts.none { it.follow?.clipId == request.clipId }) return { t, _ -> t }
        val srcW = request.width.toFloat().coerceAtLeast(1f)
        val srcH = request.height.toFloat().coerceAtLeast(1f)
        val r = request.reframe
        val (ow, oh) = r.outputSize(srcW, srcH)
        val toFrame: (Float, Float) -> Pair<Float, Float> = { x, y -> r.frameOf(x * srcW, y * srcH, ow, oh, srcW, srcH).let { (fx, fy) -> fx / ow to fy / oh } }
        return { texts, positionMs -> texts.map { it.placed(request.clipId, request.trimStartMs + positionMs, toFrame) } }
    }

    /**
     * The clip's subject drawn where it is in the exported frame: its map (over the upright
     * source) taken through the same steps as the picture (steadied, then framed) into pixels.
     */
    private fun subjectDrawer(request: ExportRequest): ((TextItem, android.graphics.Canvas, android.graphics.Paint, Long, Float, Float) -> Unit)? {
        val id = request.subject
        if (request.isPhoto || request.texts.none { it.behind }) return null
        val aspect = request.width.toFloat() / request.height.coerceAtLeast(1)
        val reframe = reframeMatrix(request.reframe, request.width, request.height)
        val steady = request.stabilize?.let { p -> p to app.headsup.core.frame.Stabilizer.corrections(p, request.width.toFloat() / request.height.coerceAtLeast(1)) }
        return { item, canvas, paint, positionMs, w, h ->
            val fileMs = request.trimStartMs + positionMs
            val chosen = item.behindMask?.let { i -> request.textMasks.getOrNull(i)?.let { app.headsup.color.TextMasks.alphaOf(context, it, aspect, fileMs) } }
                ?: if (item.behindMask == null && id != null) app.headsup.color.SubjectMaps.frameAt(context, id, fileMs) else null
            chosen?.let { map ->
                val m = Matrix().apply {
                    // Map pixels → source NDC (y up).
                    postScale(2f / map.width, -2f / map.height)
                    postTranslate(-1f, 1f)
                    steady?.let { (p, cs) -> postConcat(stabilizeMatrix(app.headsup.core.frame.Stabilizer.at(p, cs, fileMs), request.width.toFloat(), request.height.toFloat())) }
                    reframe?.let { postConcat(it) }
                    // Frame NDC → the overlay's pixels (y down).
                    postScale(w / 2f, -h / 2f)
                    postTranslate(w / 2f, h / 2f)
                }
                canvas.save()
                canvas.concat(m)
                canvas.drawBitmap(map, 0f, 0f, paint)
                canvas.restore()
            }
        }
    }

    /** A steadying correction as a Media3 matrix on normalised coordinates, for a [w]×[h] source. */
    private fun stabilizeMatrix(c: app.headsup.core.frame.Stabilizer.Correction, w: Float, h: Float) = Matrix().apply {
        postScale(w / 2f, h / 2f)
        // Clockwise on screen is a negative angle with y pointing up.
        postRotate(-c.degrees)
        postScale(2f / w, 2f / h)
        // Fractions of the frame to normalised coordinates (y up).
        postTranslate(2f * c.dx, -2f * c.dy)
        postScale(c.zoom, c.zoom)
    }

    /** Source → frame (Media3's normalised coordinates) for [reframe]; null when it changes nothing. */
    private fun reframeMatrix(reframe: Reframe, width: Int, height: Int): Matrix? {
        if (reframe.isIdentity || width <= 0 || height <= 0) return null
        val w = width.toFloat()
        val h = height.toFloat()
        val (fw, fh) = reframe.outputSize(w, h)
        val r = reframe.clamped(fw, fh, w, h)
        val scale = Reframe.baseScale(fw, fh, w, h) * r.effectiveZoom(fw, fh, w, h)
        val matrix = Matrix().apply {
            // Source NDC → source pixels around the chosen centre (y up) → turned and scaled → frame NDC.
            postTranslate(-(2f * r.centerX - 1f), -(1f - 2f * r.centerY))
            // Mirrored about the chosen centre, before it is turned (as in the preview).
            if (r.mirrored) postScale(-1f, 1f)
            postScale(w / 2f, h / 2f)
            // Clockwise on screen is a negative angle with y pointing up.
            postRotate(-r.angleDegrees)
            postScale(scale, scale)
            postScale(2f / fw, 2f / fh)
        }
        return matrix
    }

    /**
     * [request] starting no earlier than its file's first frame of picture, when its speed is
     * changed. Media3 changes a picture's speed from its first frame on, a sound's from its start:
     * in a file whose picture begins after its sound (screen recordings: 2.8 s), the picture of a
     * clip at 2× ended 1.4 s late, past the clip's end, and the next clip's sound could not start:
     * the export stalled (MUXING_TIMEOUT). Cut at the picture's start, both begin together.
     */
    private fun startedWithPicture(request: ExportRequest): ExportRequest {
        if (request.isPhoto || request.speed == 1f) return request
        val first = firstPictureMs(request.uri) ?: return request
        if (first <= request.trimStartMs + 20 || first >= request.trimEndMs - 100) return request
        return request.copy(trimStartMs = first, durationMs = request.trimEndMs - first)
    }

    /** When the file's picture begins (its first video sample), in ms; null if unknown. */
    private fun firstPictureMs(uri: android.net.Uri): Long? = runCatching {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return@runCatching null
            extractor.selectTrack(track)
            extractor.sampleTime.takeIf { it >= 0 }?.let { it / 1000 }
        } finally {
            extractor.release()
        }
    }.getOrNull()

    /** A constant speed for Media3's speed effects. */
    private class ConstantSpeed(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long) = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET
    }

    /**
     * One clip as Media3 should render it into a [out]-sized frame: colour, texture, framing,
     * scaling to the output, dashboard, then speed and frame rate. The dashboard comes before the
     * speed change so it reads the clip's own time and slows down with the picture.
     */
    private fun editedItem(asked: ExportRequest, out: OutputSpec, soundGain: Float = 1f): EditedMediaItem {
        val request = startedWithPicture(asked)
        val sampler = request.telemetry?.let { TelemetrySampler(it.track) }
        val hdr = out.tenBit
        val overlay = TelemetryOverlay(
            DashboardRenderer(context.assets), request.style, request.effects, request.grade?.vignette ?: 0f, out.width, out.height,
            texts = request.texts,
            // The overlay counts the clip's own time (before the speed change); texts are placed on the timeline.
            timelineMsOf = { positionMs -> request.timelineStartMs + if (request.isPhoto) positionMs else (positionMs / request.speed).toLong() },
            textValues = { data, positionMs ->
                // The video's own clock, the moment's values, then the clip's own (a photo's place and time win over the watch's).
                val own = request.videoStartUtcMs?.let { app.headsup.render.TextValues.clock(it + request.trimStartMs + positionMs) }.orEmpty()
                own + app.headsup.render.TextValues.moment(data?.frame, data?.utcMs, data?.zoneOffsetSeconds) + request.textValues
            },
            drawMap = if (request.routeMap.enabled && request.mapTrack != null) { canvas, w, h, data ->
                val (lat, lon) = request.mapMarker ?: markerOf(data)
                app.headsup.render.RouteMapRenderer.draw(canvas, w, h, request.routeMap, request.mapTrack, lat, lon)
            } else null,
            // The marker moves in steps of ~1 m of latitude, so the bitmap is redrawn only when it moves.
            mapKey = { data -> if (request.routeMap.enabled) markerOf(data).let { (a, b) -> "m${a?.let { String.format(java.util.Locale.US, "%.5f", it) }},${b?.let { String.format(java.util.Locale.US, "%.5f", it) }}" } else "" },
            dataAt = { positionMs -> request.dataAt(positionMs, sampler) },
            barsAt = { timelineMs -> request.effects.barsMotion.amount(timelineMs - request.clipStartMs, request.clipLengthMs) },
            placeTexts = textPlacer(request),
            subject = subjectDrawer(request),
            subjectKey = { positionMs ->
                val fileMs = request.trimStartMs + positionMs
                (request.subject?.takeIf { !request.isPhoto && request.texts.any { it.behind && it.behindMask == null } }
                    ?.let { "s" + app.headsup.color.SubjectMaps.frameIndex(context, it, fileMs) } ?: "") +
                    // A mask that moves (keys, or tracked) changes the cut from moment to moment.
                    (if (request.texts.any { t -> t.behind && t.behindMask?.let { request.textMasks.getOrNull(it) }?.let { m -> m.keys.isNotEmpty() || m.sequence != null } == true }) "m${fileMs / 40}" else "")
            },
        )

        val effects = mutableListOf<Effect>()
        // Sped up: the file's frames that make one frame of the film run together (motion blur, as
        // with a shutter open about three quarters of the frame), so a rush flows instead of jumping.
        if (!request.isPhoto && request.speed > 1.5f) {
            val fileFps = request.frameRate ?: 30f
            val perFrame = fileFps * request.speed / (out.frameRate ?: fileFps)
            if (perFrame >= MotionBlurEffect.FROM_FRAMES) effects += MotionBlurEffect(perFrame * 0.75f)
        }
        val grade = request.grade
        if (hdr || (grade != null && grade.needsGradeEffect)) {
            // The HDR route always needs it: it undoes Media3's HLG conversion before the colour.
            effects += GradeEffect(
                grade?.cube ?: LutBaker.bake(null, ColorGrade()), grade?.masks.orEmpty(), grade?.maskCubes.orEmpty(),
                grade?.fromCube, grade?.transitionMs ?: 0L,
                hdrInput = if (hdr) HdrRoute.inputOf(request.sourceFormat?.transfer, request.sourceFormat?.primaries) else null,
                fileStartMs = request.trimStartMs,
                presence = grade?.presence ?: app.headsup.core.color.Presence.NONE,
                warp = grade?.warp,
                timeOffsetMs = grade?.timeOffsetMs ?: 0L,
                fromMasks = grade?.fromMasks.orEmpty(),
                fromMaskTables = grade?.fromMaskCubes.orEmpty(),
                fromMaskMs = grade?.fromMaskMs ?: 0L,
                tablesAt = grade?.tablesAt,
            )
        } else {
            grade?.cube?.let { effects += SingleColorLut.createFromCube(it) }
        }
        if (request.effects.changesPixels) effects += FinishEffect(request.effects)
        // Steadied: each frame moved by its correction, before it is framed (as in the preview).
        request.stabilize?.let { path -> stabilizeEffect(path, request.width, request.height, request.trimStartMs)?.let { effects += it } }
        // Reframing comes after colour and texture (as in the preview) and before the dashboard.
        reframeEffect(request.reframe, request.width, request.height, out.width, out.height)?.let { effects += it }
        // The timeline's frame scaled to the output size (the same shape), so every clip of a joined
        // export has the same frame.
        effects += Presentation.createForWidthAndHeight(out.width, out.height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        // Blurred effect bars: the finished frame's own colours, blurred, where the bars are.
        if (request.effects.barsBlur && request.effects.barsRatio != null) {
            val bars = DashboardRenderer(context.assets)
            val w = out.width.toFloat()
            val h = out.height.toFloat()
            effects += BarsBlurEffect { positionMs ->
                val timelineMs = request.timelineStartMs + if (request.isPhoto) positionMs else (positionMs / request.speed).toLong()
                val now = request.effects.copy(barsAmount = request.effects.barsMotion.amount(timelineMs - request.clipStartMs, request.clipLengthMs))
                val out = FloatArray(8) { -1f }
                bars.barRects(w, h, now).take(2).forEachIndexed { k, r ->
                    out[k * 4] = r.left / w; out[k * 4 + 1] = r.top / h; out[k * 4 + 2] = r.right / w; out[k * 4 + 3] = r.bottom / h
                }
                out
            }
        }
        val speed = request.speed
        val timelineMs = if (request.isPhoto) request.durationMs else (request.durationMs / speed).toLong()
        val fade = if (request.fadeInMs > 0 || request.fadeOutMs > 0) {
            FadeEffect(timelineMs, request.fadeInMs, request.fadeOutMs, if (request.isPhoto) 1f else speed)
        } else null
        if (hdr) {
            // Dashboard and fade on display colours, then into the 10-bit signal (SDR or HLG light).
            effects += DashboardEffect(overlay)
            fade?.let { effects += it }
            effects += HdrOutputEffect(if (out.hlg) out.hdrWhite else null)
        } else {
            effects += OverlayEffect(ImmutableList.of<TextureOverlay>(overlay))
            fade?.let { effects += it }
        }
        if (speed != 1f) effects += SpeedChangeEffect(speed)
        // Fewer frames when the clip (after the speed change) has more than the timeline.
        out.frameRate?.let { target ->
            val clipFps = (request.frameRate ?: target) * speed
            if (clipFps > target + 0.5f) effects += FrameDropEffect.createDefaultFrameDropEffect(target)
        }
        val audio = buildList<AudioProcessor> {
            if (speed != 1f && !request.muteChangedSpeed) add(SpeedChangingAudioProcessor(ConstantSpeed(speed)))
            // Under a music track the clips' own sound is lowered.
            if (soundGain != 1f) add(AudioMix.gain(soundGain))
            // After the speed change, so the sound fades in timeline time like the picture.
            if (request.fadeInMs > 0 || request.fadeOutMs > 0) add(FadeAudioProcessor(timelineMs * 1000L, request.fadeInMs * 1000L, request.fadeOutMs * 1000L))
        }

        if (request.isPhoto) {
            // A photo becomes video: shown for its time on the timeline at the output frame rate.
            val photo = MediaItem.Builder().setUri(request.uri).setImageDurationMs(request.durationMs).build()
            return EditedMediaItem.Builder(photo)
                .setFrameRate(kotlin.math.round(out.frameRate ?: 30f).toInt().coerceAtLeast(1))
                .setEffects(Effects(emptyList(), effects))
                .build()
        }
        val mediaItem = MediaItem.Builder()
            .setUri(request.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(request.trimStartMs).setEndPositionMs(request.trimEndMs).build(),
            )
            .build()
        return EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(audio, effects))
            .setRemoveAudio(speed != 1f && request.muteChangedSpeed)
            .build()
    }

    /**
     * One audio sequence per track: its clips in order with silence between them, each cut where
     * the timeline ends (and where the next one on the same track begins), at its level and with
     * its fades. Media3 mixes the sequences with the video's sound.
     */
    private fun audioTracks(clips: List<AudioClip>, totalMs: Long, duck: FloatArray? = null): List<EditedMediaItemSequence> =
        clips.groupBy { it.lane }.toSortedMap().values.mapNotNull { lane ->
            val builder = EditedMediaItemSequence.Builder(emptyList<EditedMediaItem>()).experimentalSetForceAudioTrack(true)
            var at = 0L
            var any = false
            for (clip in lane.sortedBy { it.startOnTimelineMs }) {
                val p = clip.piece
                val start = maxOf(p.startOnTimelineMs, at)
                val end = minOf(p.endOnTimelineMs, totalMs)
                if (end - start < 50) continue
                if (start > at) builder.addGap((start - at) * 1000L)
                val from = p.sourceStartMs + (start - p.startOnTimelineMs)
                val item = MediaItem.Builder()
                    .setUri(clip.uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setStartPositionMs(from).setEndPositionMs(from + (end - start)).build(),
                    )
                    .build()
                val audio = buildList<AudioProcessor> {
                    add(AudioMix.gain(clip.volume))
                    // Lowered under the clips' own sound, where they have it.
                    duck?.let { add(DuckAudioProcessor(it, start)) }
                    // Fades as placed: in from the clip's start, out before its own end (even if cut earlier).
                    if (p.fadeIn > 0 || p.fadeOut > 0) {
                        add(FadeAudioProcessor((p.endOnTimelineMs - start) * 1000L, (p.fadeIn - (start - p.startOnTimelineMs)).coerceAtLeast(0L) * 1000L, p.fadeOut * 1000L))
                    }
                }
                builder.addItem(EditedMediaItem.Builder(item).setRemoveVideo(true).setEffects(Effects(audio, emptyList())).build())
                at = end
                any = true
            }
            if (any) builder.build() else null
        }

    private fun markerOf(data: app.headsup.render.DashboardData?): Pair<Double?, Double?> =
        data?.frame?.get(app.headsup.core.track.Metric.LATITUDE) to data?.frame?.get(app.headsup.core.track.Metric.LONGITUDE)

    private fun runVideo(job: ExportJob) {
        val spec = job.spec
        val out = requireNotNull(spec.output)
        // Sped-up clips start at their first frame of picture (startedWithPicture): the length counts that.
        val parts = spec.parts.map(::startedWithPicture)
        val items = parts.map { editedItem(it, out, soundGain = spec.clipSound * it.soundLevel) }
        val totalMs = parts.sumOf { if (it.isPhoto) it.durationMs else (it.durationMs / it.speed).toLong() }
        val tracks = audioTracks(spec.audio, totalMs, spec.duck)
        // Clips without sound (drones) or muted slow motion get silence, so the parts join cleanly.
        val video = EditedMediaItemSequence.Builder(items).experimentalSetForceAudioTrack(items.size > 1 || tracks.isNotEmpty()).build()
        val composition = Composition.Builder(listOf(video) + tracks).build()

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(out.bitrate).build())
            .setEnableFallback(true)
            .build()
        val output = File(workDir, "job-${job.id}.mp4").apply { delete() }

        // Media3 then says, when an export fails, which of its stages last did what (decoding,
        // effects, encoding, muxing): an error report that tells where it stuck.
        androidx.media3.effect.DebugTraceUtil.enableTracing = true
        val t = Transformer.Builder(context)
            // A heavy frame (masks tracked with AI, glow) may take long on a slow phone: the muxer
            // waits a minute for it, not 10 s, before calling the export stuck.
            .setMaxDelayBetweenMuxerSamplesMs(60_000L)
            .setVideoMimeType(if (out.tenBit) MimeTypes.VIDEO_H265 else out.codec.mimeType)
            .apply { if (out.tenBit) setAssetLoaderFactory(HdrRoute.assetLoaderFactory(context)) }
            .setEncoderFactory(if (out.tenBit && !out.hlg) HdrRoute.sdrEncoderFactory(context, out.bitrate, encoderFactory) else encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    scope.launch { completeVideo(job, output) }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    output.delete()
                    val cause = generateSequence(exportException.cause) { it.cause }.joinToString("\n") { "caused by ${it.javaClass.simpleName}: ${it.message}" }
                    finish(job.id, JobStatus.FAILED, "${exportException.errorCodeName}: ${exportException.message}\n$cause")
                }
            })
            .build()
        transformer = t
        t.start(composition, output.absolutePath)

        worker = scope.launch {
            val holder = ProgressHolder()
            while (transformer === t) {
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) setJob(job.id) { it.copy(progress = holder.progress) }
                delay(500)
            }
        }
    }

    private suspend fun completeVideo(job: ExportJob, output: File) {
        val spec = job.spec
        try {
            val result = withContext(Dispatchers.IO) { VideoFormatInfo.read(context, Uri.fromFile(output)) }
            var saved: Uri? = null
            val name = requireNotNull(spec.output).fileName
            saved = withContext(Dispatchers.IO) { MediaStoreOutput.saveVideo(context, output, name) }
            output.delete()
            val warning = if (spec.output.tenBit && result?.tenBit == false) {
                "\nWarning: 10-bit was chosen but this device produced 8-bit output."
            } else ""
            val message = "Saved ${MediaStoreOutput.VIDEO_FOLDER}/$name ($result)$warning"
            finish(job.id, JobStatus.DONE, message, saved)
        } catch (e: Throwable) {
            output.delete()
            finish(job.id, JobStatus.FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

}
