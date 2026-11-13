package app.headsup.core.planner

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

/**
 * The flat map used by street-map tiles: places on Earth as pixels of one big picture of the
 * world. At [zoom] the world is [tileSize] × 2^zoom pixels across; zoom may be fractional, so a
 * map can be zoomed smoothly between the tile levels.
 */
object WebMercator {
    private const val MAX_LAT = 85.0511

    fun worldSize(zoom: Double, tileSize: Double = 256.0): Double = tileSize * 2.0.pow(zoom)

    /** Pixel across (from the left edge of the world) of a longitude in degrees. */
    fun x(lon: Double, zoom: Double, tileSize: Double = 256.0): Double = (lon + 180.0) / 360.0 * worldSize(zoom, tileSize)

    /** Pixel down (from the top of the world) of a latitude in degrees. */
    fun y(lat: Double, zoom: Double, tileSize: Double = 256.0): Double {
        val s = sin(Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT)))
        return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * worldSize(zoom, tileSize)
    }

    /** The longitude (degrees) at a pixel across. */
    fun lon(x: Double, zoom: Double, tileSize: Double = 256.0): Double = x / worldSize(zoom, tileSize) * 360.0 - 180.0

    /** The latitude (degrees) at a pixel down. */
    fun lat(y: Double, zoom: Double, tileSize: Double = 256.0): Double =
        Math.toDegrees(atan(sinh(PI * (1 - 2 * y / worldSize(zoom, tileSize)))))
}
