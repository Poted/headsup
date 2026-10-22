package app.headsup.render

import android.content.res.AssetManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
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
class DashboardRenderer(private val assets: AssetManager) {
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = Color.argb(220, 0, 0, 0)
    }
    private val mapBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 0, 0, 0) }
    private val routePaint = strokePaint(Color.argb(170, 255, 255, 255))
    private val donePaint = strokePaint(Layouts.ACCENT)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var route: Route? = null
    private val typefaces = HashMap<DashboardFont, Typeface>()

    /** Boxes of the widgets drawn by the last [draw] call, in canvas pixels, for touch handling. */
    private val boxes = LinkedHashMap<Widget, RectF>()

    fun boxOf(widget: Widget): RectF? = boxes[widget]?.let(::RectF)

    fun hitTest(x: Float, y: Float, slop: Float): Widget? =
        boxes.entries.lastOrNull { (_, r) -> x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop }?.key

    fun draw(canvas: Canvas, width: Float, height: Float, style: DashboardStyle, data: DashboardData) {
        boxes.clear()
        val shown = Widget.entries.filter { it in style.widgets && it.isAvailableIn(data.track) }
        if (shown.isEmpty()) return

        val u = unit(width, height, style)
        val typeface = typeface(style.font)
        for ((p, size, color) in listOf(
            Triple(labelPaint, Layouts.LABEL_SIZE, style.labelColor),
            Triple(valuePaint, Layouts.VALUE_SIZE, style.valueColor),
            Triple(unitPaint, Layouts.UNIT_SIZE, style.unitColor),
        )) {
            p.typeface = typeface
            p.textSize = size * u
            p.color = color
            if (style.effect == TextEffect.SHADOW) p.setShadowLayer(4f * u, 0f, 2f * u, SHADOW) else p.clearShadowLayer()
        }
        outlinePaint.typeface = typeface
        outlinePaint.strokeWidth = 5f * u

        val placements = style.resolvedPlacements(shown, width / u, height / u)
        for (widget in shown) {
            drawWidget(canvas, widget, placements.getValue(widget), width, height, u, style, data)
        }
    }

    private fun drawWidget(
        canvas: Canvas, widget: Widget, placement: Placement, width: Float, height: Float, u: Float,
        style: DashboardStyle, data: DashboardData,
    ) {
        val label = if (widget == Widget.TIME) Formats.date(data.utcMs, data.zoneOffsetSeconds) else widget.label
        val reading = if (widget == Widget.MAP) null else reading(widget, data)
        val unitGap = 8f * u
        val valueWidth = reading?.let { valuePaint.measureText(it.value) } ?: 0f
        val unitWidth = if (reading == null || reading.unit.isEmpty()) 0f else unitPaint.measureText(reading.unit) + unitGap
        val contentWidth = if (widget == Widget.MAP) Layouts.MAP_SIZE * u else valueWidth + unitWidth
        val boxWidth = max(labelPaint.measureText(label), contentWidth)
        val boxHeight = Layouts.contentHeight(widget) * u

        val left = when (placement.h) {
            HAnchor.LEFT -> placement.dx * u
            HAnchor.RIGHT -> width - placement.dx * u - boxWidth
            HAnchor.CENTER -> width / 2 + placement.dx * u - boxWidth / 2
        }
        val top = when (placement.v) {
            VAnchor.TOP -> placement.dy * u
            VAnchor.BOTTOM -> height - placement.dy * u - boxHeight
            VAnchor.CENTER -> height / 2 + placement.dy * u - boxHeight / 2
        }
        boxes[widget] = RectF(left, top, left + boxWidth, top + boxHeight)

        // Text lines line up on the side the widget is anchored to.
        fun alignedX(lineWidth: Float) = when (placement.h) {
            HAnchor.LEFT -> left
            HAnchor.RIGHT -> left + boxWidth - lineWidth
            HAnchor.CENTER -> left + (boxWidth - lineWidth) / 2
        }

        text(canvas, label, alignedX(labelPaint.measureText(label)), top + Layouts.LABEL_SIZE * 0.8f * u, labelPaint, style)
        val contentTop = top + (Layouts.LABEL_SIZE + Layouts.LABEL_GAP) * u

        if (reading == null) {
            drawMap(canvas, alignedX(contentWidth), contentTop, Layouts.MAP_SIZE * u, u, data)
            return
        }

        val hr = data.frame[Metric.HEART_RATE]
        if (widget == Widget.HEART_RATE && style.hrZoneColors && hr != null) {
            data.track.hrZone(hr)?.let { valuePaint.color = ZONE_COLORS[it.coerceIn(0, ZONE_COLORS.lastIndex)] }
        }
        val x = alignedX(contentWidth)
        val baseline = contentTop + Layouts.VALUE_SIZE * 0.82f * u
        text(canvas, reading.value, x, baseline, valuePaint, style)
        valuePaint.color = style.valueColor
        if (unitWidth > 0f) text(canvas, reading.unit, x + valueWidth + unitGap, baseline, unitPaint, style)
    }

    /**
     * Identifies what [draw] would put on screen. Consecutive video frames usually show the same
     * numbers, so export only redraws (and re-uploads) the overlay when this changes.
     */
    fun contentKey(style: DashboardStyle, data: DashboardData, width: Float, height: Float): String {
        val sb = StringBuilder()
        val u = unit(width, height, style)
        for (widget in Widget.entries) {
            if (widget !in style.widgets || !widget.isAvailableIn(data.track)) continue
            sb.append(widget.ordinal).append(':')
            when (widget) {
                Widget.TIME -> sb.append(Formats.date(data.utcMs, data.zoneOffsetSeconds)).append(' ')
                    .append(Formats.clock(data.utcMs, data.zoneOffsetSeconds))
                Widget.MAP -> {
                    val r = routeFor(data.track)
                    val lat = data.frame[Metric.LATITUDE]
                    val lon = data.frame[Metric.LONGITUDE]
                    if (r != null) {
                        sb.append(r.indexAfter(data.utcMs))
                        if (lat != null && lon != null) {
                            val inner = (Layouts.MAP_SIZE - 36f) * u
                            val (nx, ny) = r.project(lat, lon)
                            sb.append('@').append((nx * inner).toInt()).append(',').append((ny * inner).toInt())
                        }
                    }
                }
                else -> {
                    val reading = reading(widget, data)
                    sb.append(reading.value)
                    val hr = data.frame[Metric.HEART_RATE]
                    if (widget == Widget.HEART_RATE && style.hrZoneColors && hr != null) sb.append('z').append(data.track.hrZone(hr))
                }
            }
            sb.append('|')
        }
        return sb.toString()
    }

    /** Darkens the frame towards its corners; drawn under the dashboard so the numbers stay bright. */
    fun drawVignette(canvas: Canvas, width: Float, height: Float, strength: Float) {
        if (strength <= 0f) return
        val radius = kotlin.math.hypot(width, height) / 2f
        vignettePaint.shader = RadialGradient(
            width / 2f, height / 2f, radius,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb((strength.coerceIn(0f, 1f) * 230).toInt(), 0, 0, 0)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width, height, vignettePaint)
    }

    private fun text(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint, style: DashboardStyle) {
        if (style.effect == TextEffect.OUTLINE) {
            outlinePaint.textSize = paint.textSize
            canvas.drawText(text, x, y, outlinePaint)
        }
        canvas.drawText(text, x, y, paint)
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

    private fun typeface(font: DashboardFont): Typeface = typefaces.getOrPut(font) {
        when {
            font == DashboardFont.CLASSIC -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
            font == DashboardFont.MONO -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            font.weight != null -> Typeface.Builder(assets, font.asset!!)
                .setFontVariationSettings("'wght' ${font.weight}")
                .build() ?: Typeface.DEFAULT_BOLD
            else -> runCatching { Typeface.createFromAsset(assets, font.asset!!) }.getOrDefault(Typeface.DEFAULT_BOLD)
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
        drawPolyline(canvas, 0, r.size, ::px, ::py, routePaint)
        val done = r.indexAfter(data.utcMs)
        if (done > 1) drawPolyline(canvas, 0, done, ::px, ::py, donePaint)

        val lat = data.frame[Metric.LATITUDE]
        val lon = data.frame[Metric.LONGITUDE]
        if (lat != null && lon != null) {
            val (nx, ny) = r.project(lat, lon)
            canvas.drawCircle(left + pad + nx * inner, top + pad + ny * inner, 9f * u, dotPaint)
        }
    }

    private fun drawPolyline(canvas: Canvas, from: Int, until: Int, px: (Int) -> Float, py: (Int) -> Float, paint: Paint) {
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

    companion object {
        private val SHADOW = Color.argb(160, 0, 0, 0)

        // Garmin zone colours: below zone 1, then zones 1–5.
        private val ZONE_COLORS = intArrayOf(
            Color.WHITE,
            Color.rgb(170, 170, 170),
            Color.rgb(64, 156, 255),
            Color.rgb(80, 200, 120),
            Color.rgb(255, 150, 40),
            Color.rgb(255, 70, 70),
        )

        /** Pixels per dashboard unit for a frame of this size. */
        fun unit(width: Float, height: Float, style: DashboardStyle) = min(width, height) / 1000f * style.scale

        private fun strokePaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }
}
