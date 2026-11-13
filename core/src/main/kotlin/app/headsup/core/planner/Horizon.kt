package app.headsup.core.planner

/**
 * The skyline around a place: for each direction, how high the land (hills, mountains) stands
 * above level, in degrees, as seen from the eye. The sun rises when it clears this line, not the flat horizon.
 */
class HorizonProfile(val angles: DoubleArray) {
    val stepDeg: Double get() = 360.0 / angles.size

    /** The skyline's height at an azimuth (degrees), between the directions measured. */
    fun at(azimuth: Double): Double {
        val a = ((azimuth % 360.0) + 360.0) % 360.0 / stepDeg
        val i0 = a.toInt() % angles.size
        val i1 = (i0 + 1) % angles.size
        val f = a - a.toInt()
        return angles[i0] * (1 - f) + angles[i1] * f
    }

    companion object {
        /** A flat horizon (open sea or plain), as the sky chart has without terrain. */
        fun flat(steps: Int = 360) = HorizonProfile(DoubleArray(steps))
    }
}

/** Working out the skyline from heights of the ground (a terrain model), looking out up to [Horizon.MAX_METRES]. */
object Horizon {
    const val MAX_METRES = 60_000.0

    /** The distances looked at along each direction: close together near the eye, wider far away. */
    fun distances(maxMetres: Double = MAX_METRES): List<Double> {
        val out = ArrayList<Double>()
        var d = 50.0
        while (d <= maxMetres) {
            out += d
            d = d * 1.035 + 25.0
        }
        return out
    }

    /** The terrain zoom a distance is read at: fine near (about 25 m), coarse far (about 100 m), where detail cannot be seen. */
    fun zoomFor(distance: Double): Int = when {
        distance < 8_000.0 -> 12
        distance < 25_000.0 -> 11
        else -> 10
    }

    /** The terrain tiles (zoom, x, y) the skyline of a place needs. */
    fun tilesNeeded(lat: Double, lon: Double, steps: Int = 360, maxMetres: Double = MAX_METRES): Set<Triple<Int, Int, Int>> {
        val out = HashSet<Triple<Int, Int, Int>>()
        val ds = distances(maxMetres)
        for (i in 0 until steps) {
            val b = i * 360.0 / steps
            for (d in ds) {
                val (la, lo) = Geo.destination(lat, lon, b, d)
                val z = zoomFor(d)
                val t = Terrain.tilePixel(la, lo, z)
                out += Triple(z, t.x, t.y)
            }
        }
        return out
    }

    /**
     * The skyline seen from an eye at [eyeElevation] metres above the sea at a place. [heightAt]
     * gives the ground's height (metres) at a latitude and longitude read at a zoom, or null where it is not known.
     */
    fun profile(
        lat: Double, lon: Double, eyeElevation: Double,
        heightAt: (Double, Double, Int) -> Double?,
        steps: Int = 360, maxMetres: Double = MAX_METRES,
    ): HorizonProfile {
        val ds = distances(maxMetres)
        val angles = DoubleArray(steps) { i ->
            val b = i * 360.0 / steps
            var best = -90.0
            for (d in ds) {
                val (la, lo) = Geo.destination(lat, lon, b, d)
                val h = heightAt(la, lo, zoomFor(d)) ?: continue
                val a = Geo.elevationAngle(d, h - eyeElevation)
                if (a > best) best = a
            }
            if (best == -90.0) 0.0 else best
        }
        return HorizonProfile(angles)
    }
}
