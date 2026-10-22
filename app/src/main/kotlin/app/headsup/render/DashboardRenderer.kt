package app.headsup.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import app.headsup.core.track.ActivityTrack
import app.headsup.core.track.Metric
import app.headsup.core.track.TelemetryFrame
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

data class DashboardData(
    val track: ActivityTrack,
    val frame: TelemetryFrame,
    val utcMs: Long,
    /** Zone used to show the time of day. */
    val zoneOffsetSeconds: Int,
)

/**
 * Draws the dashboard on an [android.graphics.Canvas]. The same code renders the preview and
 * (in stage 3) every exported frame, so both look identical. Sizes are derived from the
 * shorter side of the frame, which keeps 16:9, 9:16 and square output consistent.
 */
class DashboardRenderer {
    private val labelPaint = textPaint(Typeface.DEFAULT_BOLD, Color.argb(235, 255, 255, 255))
    private val valuePaint = textPaint(Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC), Color.WHITE)
    private val unitPaint = textPaint(Typeface.DEFAULT_BOLD, ACCENT)
    private val mapBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 0, 0, 0) }
    private val routePaint = strokePaint(Color.argb(170, 255, 255, 255))
    private val donePaint = strokePaint(ACCENT)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()
    private var route: Route? = null

    fun draw(canvas: Canvas, width: Float, height: Float, style: DashboardStyle, data: DashboardData) {
        val widgets = Widget.entries.filter { it in style.widgets && it.isAvailableIn(data.track) }
        if (widgets.isEmpty()) return

        val u = min(width, height) / 1000f * style.scale
        val margin = 40f * u
        labelPaint.textSize = LABEL_SIZE * u
        valuePaint.textSize = VALUE_SIZE * u
        unitPaint.textSize = UNIT_SIZE * u
        for (p in listOf(labelPaint, valuePaint, unitPaint)) p.setShadowLayer(4f * u, 0f, 2f * u, SHADOW)

        // Fill columns from the chosen edge inwards, keeping the widget order top to bottom.
        val columns = mutableListOf(mutableListOf<Widget>())
        var used = 0f
        for (widget in widgets) {
            val h = blockHeight(widget) * u
            if (used > 0f && used + h > height - 2 * margin) {
                columns += mutableListOf<Widget>()
                used = 0f
            }
            columns.last() += widget
            used += h
        }

        val corner = style.corner
        columns.forEachIndexed { index, column ->
            val edgeX = if (corner.right) width - margin - index * COLUMN_WIDTH * u else margin + index * COLUMN_WIDTH * u
            val columnHeight = column.sumOf { blockHeight(it).toDouble() }.toFloat() * u
            var top = if (corner.bottom) height - margin - columnHeight else margin
            for (widget in column) {
                drawBlock(canvas, widget, edgeX, top, corner.right, u, style, data)
                top += blockHeight(widget) * u
            }
        }
    }

    private fun blockHeight(widget: Widget) =
        LABEL_SIZE + LABEL_GAP + (if (widget == Widget.MAP) MAP_SIZE else VALUE_SIZE) + BLOCK_GAP

    private fun drawBlock(
        canvas: Canvas, widget: Widget, edgeX: Float, top: Float, alignRight: Boolean, u: Float,
        style: DashboardStyle, data: DashboardData,
    ) {
        val label = if (widget == Widget.TIME) Formats.date(data.utcMs, data.zoneOffsetSeconds) else widget.label
        val labelX = if (alignRight) edgeX - labelPaint.measureText(label) else edgeX
        canvas.drawText(label, labelX, top + LABEL_SIZE * 0.8f * u, labelPaint)
        val contentTop = top + (LABEL_SIZE + LABEL_GAP) * u

        if (widget == Widget.MAP) {
            drawMap(canvas, if (alignRight) edgeX - MAP_SIZE * u else edgeX, contentTop, MAP_SIZE * u, u, data)
            return
        }

        val reading = reading(widget, data)
        val hr = data.frame[Metric.HEART_RATE]
        valuePaint.color = if (widget == Widget.HEART_RATE && style.hrZoneColors && hr != null) {
            data.track.hrZone(hr)?.let { ZONE_COLORS[it.coerceIn(0, ZONE_COLORS.lastIndex)] } ?: Color.WHITE
        } else {
            Color.WHITE
        }
        val valueWidth = valuePaint.measureText(reading.value)
        val unitGap = 8f * u
        val unitWidth = if (reading.unit.isEmpty()) 0f else unitPaint.measureText(reading.unit) + unitGap
        val x = if (alignRight) edgeX - valueWidth - unitWidth else edgeX
        val baseline = contentTop + VALUE_SIZE * 0.82f * u
        canvas.drawText(reading.value, x, baseline, valuePaint)
        if (unitWidth > 0f) canvas.drawText(reading.unit, x + valueWidth + unitGap, baseline, unitPaint)
    }

    private fun reading(widget: Widget, data: DashboardData): Reading {
        val f = data.frame
        return when (widget) {
            Widget.TIME -> Reading(Formats.clock(data.utcMs, data.zoneOffsetSeconds), "")
            Widget.HEART_RATE -> Formats.heartRate(f[Metric.HEART_RATE])
            Widget.SPEED -> Formats.speed(f[Metric.SPEED])
            Widget.PACE -> Formats.pace(f[Metric.SPEED])
            Widget.ALTITUDE -> Formats.altitude(f[Metric.ALTITUDE])
            Widget.DISTANCE -> Formats.distance(f[Metric.DISTANCE])
            Widget.ASCENT -> Formats.ascent(f[Metric.ASCENT])
            Widget.GRADE -> Formats.grade(f[Metric.GRADE])
            Widget.CADENCE -> Formats.cadence(f[Metric.CADENCE], data.track.sport)
            Widget.POWER -> Formats.power(f[Metric.POWER])
            Widget.TEMPERATURE -> Formats.temperature(f[Metric.TEMPERATURE])
            Widget.MAP -> Reading("", "")
        }
    }

    private fun drawMap(canvas: Canvas, left: Float, top: Float, size: Float, u: Float, data: DashboardData) {
        canvas.drawRoundRect(left, top, left + size, top + size, 18f * u, 18f * u, mapBackground)
        val r = routeFor(data.track) ?: return
        val pad = 18f * u
        val inner = size - 2 * pad
        fun px(i: Int) = left + pad + r.x[i] * inner
        fun py(i: Int) = top + pad + r.y[i] * inner

        routePaint.strokeWidth = 4f * u
        donePaint.strokeWidth = 6f * u
        drawPolyline(canvas, r, 0, r.size, ::px, ::py, routePaint)
        val done = r.indexAfter(data.utcMs)
        if (done > 1) drawPolyline(canvas, r, 0, done, ::px, ::py, donePaint)

        val lat = data.frame[Metric.LATITUDE]
        val lon = data.frame[Metric.LONGITUDE]
        if (lat != null && lon != null) {
            val (nx, ny) = r.project(lat, lon)
            canvas.drawCircle(left + pad + nx * inner, top + pad + ny * inner, 9f * u, dotPaint)
        }
    }

    private fun drawPolyline(
        canvas: Canvas, r: Route, from: Int, until: Int, px: (Int) -> Float, py: (Int) -> Float, paint: Paint,
    ) {
        path.rewind()
        path.moveTo(px(from), py(from))
        for (i in from + 1 until until) path.lineTo(px(i), py(i))
        canvas.drawPath(path, paint)
    }

    private fun routeFor(track: ActivityTrack): Route? {
        route?.takeIf { it.track === track }?.let { return it }
        return Route.of(track)?.also { route = it }
    }

    /** GPS track projected into a unit square, preserving proportions and centred. */
    private class Route(
        val track: ActivityTrack,
        val times: LongArray,
        val x: FloatArray,
        val y: FloatArray,
        private val minX: Double,
        private val maxY: Double,
        private val span: Double,
        private val padX: Double,
        private val padY: Double,
        private val cosLat: Double,
    ) {
        val size get() = times.size

        fun project(lat: Double, lon: Double): Pair<Float, Float> =
            ((lon * cosLat - minX) / span + padX).toFloat() to ((maxY - lat) / span + padY).toFloat()

        /** Number of route points recorded at or before [utcMs]. */
        fun indexAfter(utcMs: Long): Int {
            var lo = 0
            var hi = times.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (times[mid] <= utcMs) lo = mid + 1 else hi = mid
            }
            return lo
        }

        companion object {
            private const val MAX_POINTS = 1500

            fun of(track: ActivityTrack): Route? {
                val lat = track.series[Metric.LATITUDE] ?: return null
                val lon = track.series[Metric.LONGITUDE] ?: return null
                if (lat.size < 2 || lat.size != lon.size) return null
                val step = max(1, ceil(lat.size / MAX_POINTS.toDouble()).toInt())
                val indices = (0 until lat.size step step).toList()
                val cosLat = cos(Math.toRadians(lat.values.average()))
                val xs = indices.map { lon.values[it] * cosLat }
                val ys = indices.map { lat.values[it] }
                val minX = xs.min()
                val minY = ys.min()
                val maxY = ys.max()
                val spanX = xs.max() - minX
                val spanY = maxY - minY
                val span = max(max(spanX, spanY), 1e-9)
                val padX = (1 - spanX / span) / 2
                val padY = (1 - spanY / span) / 2
                return Route(
                    track = track,
                    times = LongArray(indices.size) { lat.timesMs[indices[it]] },
                    x = FloatArray(indices.size) { ((xs[it] - minX) / span + padX).toFloat() },
                    y = FloatArray(indices.size) { ((maxY - ys[it]) / span + padY).toFloat() },
                    minX = minX, maxY = maxY, span = span, padX = padX, padY = padY, cosLat = cosLat,
                )
            }
        }
    }

    private companion object {
        const val LABEL_SIZE = 24f
        const val LABEL_GAP = 4f
        const val VALUE_SIZE = 64f
        const val UNIT_SIZE = 28f
        const val BLOCK_GAP = 22f
        const val MAP_SIZE = 260f
        const val COLUMN_WIDTH = 320f

        val ACCENT = Color.rgb(255, 214, 10)
        val SHADOW = Color.argb(160, 0, 0, 0)

        // Garmin zone colours: below zone 1, then zones 1–5.
        val ZONE_COLORS = intArrayOf(
            Color.WHITE,
            Color.rgb(170, 170, 170),
            Color.rgb(64, 156, 255),
            Color.rgb(80, 200, 120),
            Color.rgb(255, 150, 40),
            Color.rgb(255, 70, 70),
        )

        fun textPaint(typeface: Typeface, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            this.color = color
        }

        fun strokePaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }
}
