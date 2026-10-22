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
    val request: ExportRequest,
    val status: JobStatus = JobStatus.QUEUED,
    val progress: Int = 0,
    /** Result or error, shown under the job. */
    val message: String? = null,
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

    fun enqueue(requests: List<ExportRequest>) {
        if (requests.isEmpty()) return
        _jobs.update { jobs -> jobs.filter { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING } + requests.map { ExportJob(nextId++, it) } }
        context.startForegroundService(Intent(context, ExportService::class.java))
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
        when (job.request.kind) {
            ExportKind.PNG_SEQUENCE -> runPng(job)
            ExportKind.VIDEO, ExportKind.TEN_BIT_TEST -> runVideo(job)
        }
    }

    private fun finish(id: Int, status: JobStatus, message: String?) {
        // A job cancelled by the user stays cancelled even if a late callback arrives.
        setJob(id) { if (it.status == JobStatus.RUNNING) it.copy(status = status, progress = 100, message = message) else it }
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
                    PngSequenceExporter.export(context, job.request) { p -> setJob(job.id) { it.copy(progress = p) } }
                }
                finish(job.id, JobStatus.DONE, "Saved ${MediaStoreOutput.OVERLAY_FOLDER}/$name")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                finish(job.id, JobStatus.FAILED, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun runVideo(job: ExportJob) {
        val request = job.request
        val test = request.kind == ExportKind.TEN_BIT_TEST
        val sampler = TelemetrySampler(request.track)
        val overlay = TelemetryOverlay(
            DashboardRenderer(context.assets), request.style, request.grade?.vignette ?: 0f, request.width, request.height,
        ) { positionMs -> request.dataAt(positionMs, sampler) }

        val effects = mutableListOf<Effect>()
        request.grade?.cube?.let { effects += SingleColorLut.createFromCube(it) }
        effects += OverlayEffect(ImmutableList.of<TextureOverlay>(overlay))

        val mediaItem = MediaItem.Builder().setUri(request.uri).apply {
            if (test) setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(TEST_LENGTH_MS).build())
        }.build()
        val edited = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), effects))
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(request.targetBitrate()).build())
            .setEnableFallback(true)
            .build()
        val output = File(workDir, "job-${job.id}.mp4").apply { delete() }

        val t = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    scope.launch { completeVideo(job, output) }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    output.delete()
                    finish(job.id, JobStatus.FAILED, "${exportException.errorCodeName}: ${exportException.message}")
                }
            })
            .build()
        transformer = t
        t.start(edited, output.absolutePath)

        worker = scope.launch {
            val holder = ProgressHolder()
            while (transformer === t) {
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) setJob(job.id) { it.copy(progress = holder.progress) }
                delay(500)
            }
        }
    }

    private suspend fun completeVideo(job: ExportJob, output: File) {
        val request = job.request
        try {
            val result = withContext(Dispatchers.IO) { VideoFormatInfo.read(context, Uri.fromFile(output)) }
            val message = if (request.kind == ExportKind.TEN_BIT_TEST) {
                output.delete()
                tenBitReport(request.sourceFormat, result)
            } else {
                val name = "${request.baseName}_headsup.mp4"
                withContext(Dispatchers.IO) { MediaStoreOutput.saveVideo(context, output, name) }
                output.delete()
                val warning = if (request.sourceFormat?.tenBit == true && result?.tenBit == false) {
                    "\nWarning: the source is 10-bit but this device produced 8-bit output."
                } else ""
                "Saved ${MediaStoreOutput.VIDEO_FOLDER}/$name ($result)$warning"
            }
            finish(job.id, JobStatus.DONE, message)
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
