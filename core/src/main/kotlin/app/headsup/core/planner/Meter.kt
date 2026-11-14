package app.headsup.core.planner

import kotlin.math.log2

/**
 * A camera as far as exposure goes: the ISO, shutter and aperture it offers (an aperture range of one
 * value is a fixed aperture, as an action camera's f/2.8), and the filters owned for it. Only limits
 * and lists: it says nothing.
 */
data class CameraProfile(
    val name: String,
    val isoMin: Double = 100.0,
    val isoMax: Double = 25_600.0,
    val shutterMin: Double = 1.0 / 8000,
    val shutterMax: Double = 30.0,
    /** The widest aperture (smallest f-number) and the narrowest; the same for a fixed aperture. */
    val apertureMin: Double = 1.0,
    val apertureMax: Double = 32.0,
    /** The filters owned for it (None is always there). */
    val filters: List<NdFilter> = NdFilter.entries.toList(),
) {
    /** The aperture when the lens has only one, else null. */
    val fixedAperture: Double? get() = if (apertureMax - apertureMin < 0.05) apertureMin else null

    /** The filters to choose from: None and those owned, weakest first. */
    val choices: List<NdFilter> get() = (listOf(NdFilter.None) + filters).distinct().sortedBy { it.stops }

    companion object {
        /** No camera in particular: every filter, a wide ISO range. */
        val ANY = CameraProfile("Any camera")
    }
}

/**
 * The camera's settings as set on it: the aperture, the shutter and the ISO, either of the last two
 * left to the camera (Auto: null), and the filter on the lens.
 */
data class MeterSettings(val aperture: Double, val shutter: Double?, val iso: Double?, val nd: NdFilter)

/**
 * What the camera will do in a light: the shutter and ISO it ends with (its own choice where set
 * to Auto, kept within its range) and how far the picture is from a correct exposure, in stops:
 * 0.0 right, +1.7 that much too bright, −1.7 too dark.
 */
data class MeterReading(val aperture: Double, val shutter: Double, val iso: Double, val offset: Double)

/** A light meter: a scene's light against the camera's settings. */
object Meter {
    /**
     * The exposure value (at ISO 100) a scene must have to be exposed right by these settings:
     * log2(N² / t) − log2(ISO / 100), plus the stops the filter takes away.
     */
    fun settingsEv(aperture: Double, shutter: Double, iso: Double, nd: NdFilter): Double =
        log2(aperture * aperture / shutter) - log2(iso / 100.0) + nd.stops

    /**
     * Reads a scene of [sceneEv] (at ISO 100) with [s] on [camera]. With the shutter on Auto the
     * camera keeps its lowest ISO and moves the shutter; with only the ISO on Auto it moves the ISO;
     * whatever is left past their limits is the offset.
     */
    fun read(sceneEv: Double, s: MeterSettings, camera: CameraProfile): MeterReading {
        val n = s.aperture.coerceIn(camera.apertureMin, camera.apertureMax)
        val needed = { t: Double, iso: Double -> sceneEv - settingsEv(n, t, iso, s.nd) } // stops too bright
        var shutter = s.shutter ?: camera.shutterMin
        var iso = s.iso ?: camera.isoMin
        if (s.shutter == null) {
            // The shutter the scene wants at this ISO, held to the camera's range.
            // Too bright by k stops: a shutter 2^k times shorter.
            shutter = (shutter / Math.pow(2.0, needed(shutter, iso))).coerceIn(camera.shutterMin, camera.shutterMax)
        }
        if (s.iso == null) {
            iso = (iso / Math.pow(2.0, needed(shutter, iso))).coerceIn(camera.isoMin, camera.isoMax)
        }
        return MeterReading(n, shutter, iso, needed(shutter, iso))
    }

    /** An offset as a meter shows it: EV +1.7, EV 0.0, EV −1.7 (one decimal, a true minus). */
    fun formatOffset(offset: Double): String {
        val r = Math.round(offset * 10) / 10.0
        return when {
            r > 0.0 -> "EV +${r}"
            r < 0.0 -> "EV −${-r}"
            else -> "EV 0.0"
        }
    }
}
