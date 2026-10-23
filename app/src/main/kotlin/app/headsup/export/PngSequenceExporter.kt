package app.headsup.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import app.headsup.core.track.TelemetrySampler
import app.headsup.render.DashboardRenderer
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Writes the dashboard alone as transparent PNG frames for DaVinci Resolve, packed into one
 * ZIP in Download/TelemetryOverlay. Frame n shows the telemetry at n / fps seconds into the
 * clip, so the sequence lines up with the clip when both start at the same timeline position.
 */
object PngSequenceExporter {
    suspend fun export(context: Context, request: ExportRequest, onProgress: (Int) -> Unit): String {
        val fps = request.frameRate ?: 30f
        val frames = ceil(request.durationMs * fps / 1000.0).toLong().coerceAtLeast(1)
        // The overlay matches the exported frame, including a changed shape (e.g. 9:16).
        val width = request.frameWidth
        val height = request.frameHeight
        val renderer = DashboardRenderer(context.assets)
        // Only clips with watch data are sent here; the dashboard is what this export is for.
        val sampler = TelemetrySampler(requireNotNull(request.telemetry) { "No watch data for ${request.name}" }.track)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fpsLabel = if (fps % 1f == 0f) fps.toInt().toString() else String.format(Locale.US, "%.3f", fps).trimEnd('0')
        val zipName = "${request.baseName}_overlay_${fpsLabel}fps.zip"
        val job = coroutineContext

        try {
            MediaStoreOutput.writeDownload(context, zipName, "application/zip") { out ->
                ZipOutputStream(BufferedOutputStream(out, 1 shl 20)).use { zip ->
                    var lastKey: String? = null
                    var png = ByteArray(0)
                    var crc = 0L
                    for (i in 0 until frames) {
                        val positionMs = (i * 1000.0 / fps).roundToLong()
                        val data = checkNotNull(request.dataAt(positionMs, sampler)) { "No watch data for ${request.name}" }
                        val key = renderer.contentKey(request.style, data, width.toFloat(), height.toFloat(), request.effects)
                        if (key != lastKey) {
                            bitmap.eraseColor(Color.TRANSPARENT)
                            renderer.draw(canvas, width.toFloat(), height.toFloat(), request.style, data, request.effects)
                            png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                            crc = CRC32().apply { update(png) }.value
                            lastKey = key
                        }
                        zip.putStored(String.format(Locale.US, "frame_%06d.png", i), png, crc)
                        if (i % 10 == 0L) {
                            onProgress((i * 100 / frames).toInt())
                            job.ensureActive() // cancellation is checked between frames
                        }
                    }
                    val readme = """
                        Telemetry overlay for ${request.name}
                        Frames: $frames, frame rate: $fpsLabel fps, ${width}x$height, transparent PNG.
                        In DaVinci Resolve: import the folder as an image sequence, set its frame rate to $fpsLabel,
                        and place it on the track above the clip, starting at the clip's first frame.
                    """.trimIndent().toByteArray()
                    zip.putStored("README.txt", readme, CRC32().apply { update(readme) }.value)
                }
            }
        } finally {
            bitmap.recycle()
        }
        return zipName
    }

    /** PNGs are already compressed; storing them avoids a second, useless compression pass. */
    private fun ZipOutputStream.putStored(name: String, bytes: ByteArray, crc: Long) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc
        }
        putNextEntry(entry)
        write(bytes)
        closeEntry()
    }
}
