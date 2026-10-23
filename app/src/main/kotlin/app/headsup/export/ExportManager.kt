package app.headsup.export

import android.content.Context
import android.content.Intent
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
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
                ExportKind.VIDEO, ExportKind.TEN_BIT_TEST -> runVideo(job)
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
    private fun reframeEffect(reframe: Reframe, width: Int, height: Int): MatrixTransformation? {
        if (reframe.isIdentity || width <= 0 || height <= 0) return null
        val w = width.toFloat()
        val h = height.toFloat()
        // Output frame inside the source; a later Presentation crops to it around the centre.
        val (ow, oh) = reframe.outputSize(w, h)
        val r = reframe.clamped(ow, oh, w, h)
        val zoom = r.effectiveZoom(ow, oh, w, h)
        val aspect = w / h
        val matrix = Matrix().apply {
            postTranslate(-(2f * r.centerX - 1f), -(1f - 2f * r.centerY))
            postScale(aspect, 1f)
            // Clockwise on screen is a negative angle with y pointing up.
            postRotate(-r.angleDegrees)
            postScale(zoom, zoom)
            postScale(1f / aspect, 1f)
        }
        return MatrixTransformation { matrix }
    }

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
    private fun editedItem(request: ExportRequest, out: OutputSpec, test: Boolean): EditedMediaItem {
        val sampler = request.telemetry?.let { TelemetrySampler(it.track) }
        val overlay = TelemetryOverlay(
            DashboardRenderer(context.assets), request.style, request.effects, request.grade?.vignette ?: 0f, out.width, out.height,
        ) { positionMs -> request.dataAt(positionMs, sampler) }

        val effects = mutableListOf<Effect>()
        val grade = request.grade
        if (grade != null && grade.needsGradeEffect) {
            effects += GradeEffect(
                grade.cube ?: LutBaker.bake(null, ColorGrade()), grade.masks, grade.maskCubes, grade.fromCube, grade.transitionMs,
            )
        } else {
            grade?.cube?.let { effects += SingleColorLut.createFromCube(it) }
        }
        if (request.effects.changesPixels) effects += FinishEffect(request.effects)
        // Reframing comes after colour and texture (as in the preview) and before the dashboard.
        reframeEffect(request.reframe, request.width, request.height)?.let { effects += it }
        // The output frame: the clip's shape cropped around the centre and scaled to the output size,
        // so every clip of a joined export has the same frame.
        effects += Presentation.createForWidthAndHeight(out.width, out.height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        effects += OverlayEffect(ImmutableList.of<TextureOverlay>(overlay))
        val speed = request.speed
        if (speed != 1f) effects += SpeedChangeEffect(speed)
        // Fewer frames when the clip (after the speed change) has more than the timeline.
        out.frameRate?.let { target ->
            val clipFps = (request.frameRate ?: target) * speed
            if (clipFps > target + 0.5f) effects += FrameDropEffect.createDefaultFrameDropEffect(target)
        }
        val audio = if (speed != 1f && !request.muteChangedSpeed) listOf<AudioProcessor>(SpeedChangingAudioProcessor(ConstantSpeed(speed))) else emptyList()

        val endMs = if (test) minOf(request.trimStartMs + TEST_LENGTH_MS, request.trimEndMs) else request.trimEndMs
        val mediaItem = MediaItem.Builder()
            .setUri(request.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(request.trimStartMs).setEndPositionMs(endMs).build(),
            )
            .build()
        return EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(audio, effects))
            .setRemoveAudio(speed != 1f && request.muteChangedSpeed)
            .build()
    }

    private fun runVideo(job: ExportJob) {
        val spec = job.spec
        val out = requireNotNull(spec.output)
        val test = spec.kind == ExportKind.TEN_BIT_TEST
        val items = spec.parts.map { editedItem(it, out, test) }
        // Clips without sound (drones) or muted slow motion get silence, so the parts join cleanly.
        val composition = Composition.Builder(
            EditedMediaItemSequence.Builder(items).experimentalSetForceAudioTrack(items.size > 1).build(),
        ).build()

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(out.bitrate).build())
            .setEnableFallback(true)
            .build()
        val output = File(workDir, "job-${job.id}.mp4").apply { delete() }

        val t = Transformer.Builder(context)
            .setVideoMimeType(out.codec.mimeType)
            .setEncoderFactory(encoderFactory)
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
        val first = spec.parts.first()
        try {
            val result = withContext(Dispatchers.IO) { VideoFormatInfo.read(context, Uri.fromFile(output)) }
            var saved: Uri? = null
            val message = if (spec.kind == ExportKind.TEN_BIT_TEST) {
                output.delete()
                tenBitReport(first.sourceFormat, result)
            } else {
                val name = requireNotNull(spec.output).fileName
                saved = withContext(Dispatchers.IO) { MediaStoreOutput.saveVideo(context, output, name) }
                output.delete()
                val warning = if (spec.parts.any { it.sourceFormat?.tenBit == true } && result?.tenBit == false) {
                    "\nWarning: the source is 10-bit but this device produced 8-bit output."
                } else ""
                "Saved ${MediaStoreOutput.VIDEO_FOLDER}/$name ($result)$warning"
            }
            finish(job.id, JobStatus.DONE, message, saved)
        } catch (e: Throwable) {
            output.delete()
            finish(job.id, JobStatus.FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun tenBitReport(input: VideoFormatInfo?, output: VideoFormatInfo?): String = buildString {
        appendLine("Input:  ${input ?: "unknown"}")
        appendLine("Output: ${output ?: "unknown"} (first ${TEST_LENGTH_MS / 1000} s, with dashboard and colour)")
        appendLine(
            when {
                output?.tenBit == true -> "Result: 10-bit is preserved."
                input?.tenBit == true -> "Result: the export dropped to 8-bit."
                else -> "Result: the source is 8-bit, nothing to preserve."
            },
        )
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        append(hevcEncoders())
    }

    private fun hevcEncoders(): String = buildString {
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!info.isEncoder || MimeTypes.VIDEO_H265 !in info.supportedTypes) continue
            val caps = info.getCapabilitiesForType(MimeTypes.VIDEO_H265)
            val main10 = caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
            val hdrEditing = Build.VERSION.SDK_INT >= 33 &&
                caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing)
            appendLine("Encoder ${info.name}: Main10=${if (main10) "yes" else "no"}, HDR editing=${if (hdrEditing) "yes" else "no"}")
        }
    }

    companion object {
        const val TEST_LENGTH_MS = 3_000L
    }
}
