package app.headsup.core.planner

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** A point on the screen in pixels; [front] is false when the thing is behind the camera (the point is then not meaningful). */
data class ScreenPoint(val x: Double, val y: Double, val front: Boolean)

/**
 * The maths of laying the sky over the phone's camera. The phone's attitude is the 3×3 rotation
 * matrix `r` (row by row, as Android's rotation-vector sensor gives it): its rows are the east,
 * north and up directions written in the phone's own axes (x to the right of the screen, y up the
 * screen, z out of the screen; the back camera looks along −z). Directions here are measured from
 * magnetic north, as the sensor does; use [magneticAzimuth] to turn a true azimuth into that.
 */
object Ar {
    private const val RAD = PI / 180.0

    /** A true azimuth turned into one from magnetic north, given the place's magnetic [declination] (degrees, east positive). */
    fun magneticAzimuth(trueAzimuth: Double, declination: Double): Double = trueAzimuth - declination

    /** A direction from north azimuth and altitude (degrees) as east, north, up. */
    fun enu(azimuth: Double, altitude: Double): DoubleArray {
        val a = azimuth * RAD
        val h = altitude * RAD
        return doubleArrayOf(sin(a) * cos(h), cos(a) * cos(h), sin(h))
    }

    /** The point the camera looks at: azimuth (from magnetic north) and altitude, in degrees. */
    fun cameraDirection(r: DoubleArray): SkyPoint {
        val east = -r[2]; val north = -r[5]; val up = -r[8]
        val az = (atan2(east, north) / RAD + 360.0) % 360.0
        return SkyPoint(az, asin(up.coerceIn(-1.0, 1.0)) / RAD)
    }

    /**
     * Where a direction ([azimuth] from magnetic north, [altitude], degrees) lands on a screen of
     * [width]×[height] pixels, for a camera whose focal length is [focalPx] pixels.
     */
    fun project(r: DoubleArray, azimuth: Double, altitude: Double, focalPx: Double, width: Double, height: Double): ScreenPoint {
        val d = enu(azimuth, altitude)
        val x = r[0] * d[0] + r[3] * d[1] + r[6] * d[2]
        val y = r[1] * d[0] + r[4] * d[1] + r[7] * d[2]
        val z = r[2] * d[0] + r[5] * d[1] + r[8] * d[2]
        if (z >= -1e-6) return ScreenPoint(width / 2 + x * 1e6, height / 2 - y * 1e6, false)
        return ScreenPoint(width / 2 + focalPx * x / -z, height / 2 - focalPx * y / -z, true)
    }

    /**
     * The focal length in pixels for a preview that fills a screen of [width]×[height] from a picture
     * [pictureAspect] (width ÷ height, portrait) whose long side sees [longSideFov] degrees: the
     * picture is scaled up to cover the screen, so the side it fits by sets the scale.
     */
    fun focalPx(longSideFov: Double, pictureAspect: Double, width: Double, height: Double): Double {
        val verticalLimited = width / height <= pictureAspect
        return if (verticalLimited) height / (2 * tan(longSideFov * RAD / 2))
        else {
            // The short side sees fov' where tan(fov'/2) = aspect · tan(fov/2).
            val shortFov = 2 * atan(pictureAspect * tan(longSideFov * RAD / 2))
            width / (2 * tan(shortFov / 2))
        }
    }

    /** The field of view of a sensor side of [sensorSide] mm behind a lens of [focalLength] mm, in degrees. */
    fun fieldOfView(sensorSide: Double, focalLength: Double): Double = 2 * atan(sensorSide / (2 * focalLength)) / RAD
}
