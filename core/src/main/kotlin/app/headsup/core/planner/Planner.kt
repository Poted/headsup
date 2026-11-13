package app.headsup.core.planner

/** A light reading taken with the phone's camera at [atMillis]: the planner's later hours are worked from it. */
data class Measured(val atMillis: Long, val ev: Double)

/** Everything the planner shows for one moment. */
data class PlannerFrame(
    val sun: SkyPoint,
    val moon: MoonState,
    val galacticCentre: SkyPoint,
    /** The light of the scene as exposure value at ISO 100. */
    val ev: Double,
    /** Day, Golden hour, Blue hour… */
    val phase: String,
    val exposure: ExposureResult,
)

/** The planner's whole calculation for a place and a moment: where things are, how bright it is, what to set. */
object Planner {
    /**
     * Everything for one moment. With a [forecast], the cloud at that moment is the forecast's and [conditions] give the rest
     * (the night sky); a [measured] reading is taken to have been under [conditions] as set, so the forecast then moves it.
     */
    fun frame(
        place: Place, epochMillis: Long, conditions: SkyConditions, setup: ExposureSetup,
        measured: Measured? = null, forecast: CloudForecast? = null,
    ): PlannerFrame {
        val sun = Sky.sun(place, epochMillis)
        val sky = forecast?.let { conditions.copy(cloud = it.at(epochMillis)) } ?: conditions
        val ev = if (measured == null) SceneLight.ev(place, epochMillis, sky)
        else SceneLight.relative(place, measured.atMillis, measured.ev, epochMillis, sky, conditions)
        return PlannerFrame(
            sun, Sky.moon(place, epochMillis), Sky.galacticCentre(place, epochMillis),
            ev, SceneLight.phase(sun.altitude), Exposure.solve(ev, setup),
        )
    }
}
