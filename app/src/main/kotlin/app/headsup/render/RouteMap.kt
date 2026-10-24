package app.headsup.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import app.headsup.core.track.ActivityTrack
import app.headsup.core.track.RouteShape
import java.util.WeakHashMap

/** The route map over the picture: where it sits, how big, in which colour, with what. */
data class RouteMap(
    val enabled: Boolean = false,
    /** Centre of the map, 0..1 of the frame. */
    val x: Float = 0.8f,
    val y: Float = 0.18f,
    /** Side of the map as a share of the frame's short side. */
    val size: Float = 0.3f,
    val colour: Int = 0xFFFFFFFF.toInt(),
    /** A dark rounded square behind the route. */
    val background: Boolean = true,
    /** A dot where the clip or photo is on the route. */
    val marker: Boolean = true,
)

/** Draws the route map, the same way in the preview and the export. */
object RouteMapRenderer {
    private val shapes = WeakHashMap<ActivityTrack, RouteShape?>()

    private fun shape(track: ActivityTrack) = synchronized(shapes) { shapes.getOrPut(track) { RouteShape.of(track) } }

    /** [track]'s route per [map], with a marker at [lat], [lon] when given. */
    fun draw(canvas: Canvas, w: Float, h: Float, map: RouteMap, track: ActivityTrack?, lat: Double?, lon: Double?) {
        if (!map.enabled || track == null) return
        val shape = shape(track) ?: return
        val side = map.size * minOf(w, h)
        val left = map.x * w - side / 2f
        val top = map.y * h - side / 2f
        val pad = side * 0.1f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (map.background) {
            paint.color = Color.argb(110, 0, 0, 0)
            canvas.drawRoundRect(RectF(left, top, left + side, top + side), side * 0.08f, side * 0.08f, paint)
        }
        val inner = side - 2 * pad
        val path = Path()
        val p = shape.points
        for (i in 0 until p.size / 2) {
            val x = left + pad + p[i * 2] * inner
            val y = top + pad + p[i * 2 + 1] * inner
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = side * 0.022f
        paint.color = map.colour
        paint.setShadowLayer(side * 0.02f, 0f, 0f, Color.argb(140, 0, 0, 0))
        canvas.drawPath(path, paint)
        paint.clearShadowLayer()
        // Start as a small ring.
        paint.strokeWidth = side * 0.015f
        canvas.drawCircle(left + pad + p[0] * inner, top + pad + p[1] * inner, side * 0.03f, paint)
        if (map.marker && lat != null && lon != null) {
            val (mx, my) = shape.project(lat, lon)
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFF9F0A.toInt()
            paint.setShadowLayer(side * 0.03f, 0f, 0f, Color.argb(160, 0, 0, 0))
            canvas.drawCircle(left + pad + mx * inner, top + pad + my * inner, side * 0.045f, paint)
            paint.clearShadowLayer()
            paint.style = Paint.Style.STROKE
            paint.color = Color.WHITE
            paint.strokeWidth = side * 0.012f
            canvas.drawCircle(left + pad + mx * inner, top + pad + my * inner, side * 0.045f, paint)
        }
    }
}
