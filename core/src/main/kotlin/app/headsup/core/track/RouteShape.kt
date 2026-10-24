package app.headsup.core.track

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/**
 * The route of an activity as a shape: GPS points projected flat (longitude shrunk by the
 * latitude's cosine, so the shape is not stretched), fitted into a unit square keeping its
 * proportions and centred. [points] holds x, y pairs in 0..1, y down.
 */
class RouteShape private constructor(
    val points: FloatArray,
    private val minLat: Double,
    private val minLon: Double,
    private val scale: Double,
    private val kx: Double,
    private val offX: Double,
    private val offY: Double,
    private val spanLat: Double,
) {
    /** Where [lat], [lon] falls in the unit square. */
    fun project(lat: Double, lon: Double): Pair<Float, Float> {
        val x = ((lon - minLon) * kx) / scale + offX
        val y = 1.0 - ((lat - minLat) / scale + offY)
        return x.toFloat() to y.toFloat()
    }

    companion object {
        /** The route of [track] with at most [maxPoints] points, or null without GPS. */
        fun of(track: ActivityTrack, maxPoints: Int = 600): RouteShape? {
            val lat = track.series[Metric.LATITUDE]?.takeIf { it.size >= 2 } ?: return null
            val lon = track.series[Metric.LONGITUDE]?.takeIf { it.size >= 2 } ?: return null
            val n = minOf(lat.size, lon.size)
            val step = max(1, n / maxPoints)
            val idx = (0 until n step step).toList()
            val lats = DoubleArray(idx.size) { lat.values[idx[it]] }
            val lons = DoubleArray(idx.size) { lon.values[idx[it]] }
            val minLat = lats.min()
            val minLon = lons.min()
            val kx = cos((minLat + lats.max()) / 2 * PI / 180)
            val w = (lons.max() - minLon) * kx
            val h = lats.max() - minLat
            val scale = max(w, h).takeIf { it > 0 } ?: return null
            val offX = (1.0 - w / scale) / 2
            val offY = (1.0 - h / scale) / 2
            val pts = FloatArray(idx.size * 2)
            for (i in idx.indices) {
                pts[i * 2] = (((lons[i] - minLon) * kx) / scale + offX).toFloat()
                pts[i * 2 + 1] = (1.0 - ((lats[i] - minLat) / scale + offY)).toFloat()
            }
            return RouteShape(pts, minLat, minLon, scale, kx, offX, offY, h)
        }
    }
}
