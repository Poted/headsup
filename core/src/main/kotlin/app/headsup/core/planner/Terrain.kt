package app.headsup.core.planner

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The ground's height from terrain tiles in the "Terrarium" encoding (map tiles whose colours hold
 * the height: metres = red × 256 + green + blue / 256 − 32768), as served by Terrain Tiles on AWS.
 * Its sources (SRTM, Copernicus EU-DEM, USGS and others) may be used commercially with credit.
 */
object Terrain {
    /** The zoom the heights are read at: about 38 m a pixel at the equator, 25 m in Poland. */
    const val ZOOM = 12

    /** The height in metres a Terrarium pixel's red, green and blue (0–255) hold. */
    fun decode(r: Int, g: Int, b: Int): Double = r * 256.0 + g + b / 256.0 - 32768.0

    /** Which tile holds a place, and where in it (pixels from its top left, fractional), at [zoom]. */
    data class TilePixel(val x: Int, val y: Int, val px: Double, val py: Double)

    fun tilePixel(lat: Double, lon: Double, zoom: Int = ZOOM): TilePixel {
        val wx = WebMercator.x(lon, zoom.toDouble())
        val wy = WebMercator.y(lat, zoom.toDouble())
        val tx = floor(wx / 256.0).toInt()
        val ty = floor(wy / 256.0).toInt()
        return TilePixel(tx, ty, wx - tx * 256.0, wy - ty * 256.0)
    }

    /** A height read between four neighbouring pixels ([at] gives a pixel's height; coordinates clamped to the tile). */
    fun bilinear(px: Double, py: Double, at: (Int, Int) -> Double): Double {
        val x = (px - 0.5).coerceIn(0.0, 255.0)
        val y = (py - 0.5).coerceIn(0.0, 255.0)
        val x0 = floor(x).toInt().coerceAtMost(254)
        val y0 = floor(y).toInt().coerceAtMost(254)
        val fx = x - x0
        val fy = y - y0
        val top = at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx
        val bottom = at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx
        return top * (1 - fy) + bottom * fy
    }

    /**
     * How far the horizon dips below level for an eye [height] metres above the ground around it
     * (a drone, a roof, a tower), in degrees: 1.76′ × √height, refraction included.
     */
    fun horizonDip(height: Double): Double = if (height <= 0) 0.0 else 1.76 / 60.0 * sqrt(height)
}
