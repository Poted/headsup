package app.headsup.core.planner

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Places on the Earth relative to each other: how far, in which direction, and how high one looks from the other. */
object Geo {
    /** The Earth's mean radius in metres. */
    const val RADIUS = 6_371_000.0

    /** How much the air bends a nearly level line of sight (the usual coefficient over land). */
    const val REFRACTION_K = 0.13

    private const val RAD = PI / 180.0

    /** The distance in metres between two places along the ground. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * RAD
        val dLon = (lon2 - lon1) * RAD
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * RAD) * cos(lat2 * RAD) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * RADIUS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** The direction from the first place to the second, in degrees from north through east. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val y = sin((lon2 - lon1) * RAD) * cos(lat2 * RAD)
        val x = cos(lat1 * RAD) * sin(lat2 * RAD) - sin(lat1 * RAD) * cos(lat2 * RAD) * cos((lon2 - lon1) * RAD)
        return (atan2(y, x) / RAD + 360.0) % 360.0
    }

    /** The place [distance] metres from a place in the direction [bearing] (degrees): latitude and longitude. */
    fun destination(lat: Double, lon: Double, bearing: Double, distance: Double): Pair<Double, Double> {
        val d = distance / RADIUS
        val b = bearing * RAD
        val la1 = lat * RAD
        val la2 = asin(sin(la1) * cos(d) + cos(la1) * sin(d) * cos(b))
        val lo2 = lon * RAD + atan2(sin(b) * sin(d) * cos(la1), cos(d) - sin(la1) * sin(la2))
        return la2 / RAD to ((lo2 / RAD + 540.0) % 360.0 - 180.0)
    }

    /**
     * How high (degrees above level) a point [heightDifference] metres above the eye looks from
     * [distance] metres away: the Earth's curve lowers it, the air's bending lifts it a little.
     */
    fun elevationAngle(distance: Double, heightDifference: Double): Double {
        if (distance <= 0.0) return if (heightDifference >= 0) 90.0 else -90.0
        val drop = distance * distance / (2 * RADIUS) * (1 - REFRACTION_K)
        return atan((heightDifference - drop) / distance) / RAD
    }

    /**
     * How much the air lifts a body seen at [altitude] degrees (true, without the air), in degrees:
     * about 0.57° at the horizon, a tenth of that at 30°. (Sæmundsson's formula; below −1° held at its −1° value.)
     */
    fun refraction(altitude: Double): Double {
        val h = altitude.coerceAtLeast(-1.0)
        return 1.02 / tan((h + 10.3 / (h + 5.11)) * RAD) / 60.0
    }
}
