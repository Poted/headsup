package app.headsup.core.planner

/** What is being shot: a photo, a video, or a timelapse; each holds the shutter in its own way. */
enum class CaptureMode(val label: String) { Photo("Photo"), Video("Video"), Timelapse("Timelapse") }

/**
 * The shooting set-up beyond the exposure triangle: for video the frame rate and shutter angle
 * (180° is the usual, natural motion blur: 1/50 s at 25 fps); for a timelapse the interval between
 * frames (the shutter must end before the next frame; half the interval gives smooth blur).
 */
data class CaptureConfig(
    val mode: CaptureMode = CaptureMode.Photo,
    val fps: Double = 25.0,
    val shutterAngle: Double = 180.0,
    val interval: Double = 5.0,
)

/** How each mode sets the exposure, and what to watch for in it. */
object Capture {
    /** The shutter a video's frame rate and angle give: angle / 360 / fps. */
    fun videoShutter(fps: Double, angle: Double = 180.0): Double = angle / 360.0 / fps

    /** The shutter for a timelapse with smooth motion: half the interval (the "180° rule" of timelapses). */
    fun timelapseShutter(interval: Double): Double = interval / 2.0

    /**
     * The exposure set-up for a mode: a photo as set; a video or timelapse with the shutter its mode
     * asks for and the ISO following the light, the filter as set (or picked when left to the planner).
     */
    fun setup(config: CaptureConfig, base: ExposureSetup): ExposureSetup = when (config.mode) {
        CaptureMode.Photo -> base
        CaptureMode.Video -> base.copy(auto = AutoSetting.Iso, shutter = videoShutter(config.fps, config.shutterAngle))
        CaptureMode.Timelapse -> base.copy(auto = AutoSetting.Iso, shutter = timelapseShutter(config.interval))
    }

    /** Things to tell the user about a result in a mode (plain sentences). */
    fun notes(config: CaptureConfig, result: ExposureResult): List<String> {
        val out = ArrayList<String>()
        when (config.mode) {
            CaptureMode.Photo -> {}
            CaptureMode.Video -> {
                if (result.warning == ExposureWarning.IsoTooLow) {
                    out += "Too bright for ${Exposure.formatShutter(result.shutter)} at ${config.fps.toInt()} fps even at the lowest ISO: " +
                        (result.suggestedNd?.let { if (result.suggestionEnough) "use ${it.label}." else "even ${it.label} is not enough; a faster shutter will make motion choppy." } ?: "a faster shutter will make motion choppy.")
                }
                if (result.warning == ExposureWarning.IsoTooHigh) out += "Too dark for ${config.fps.toInt()} fps: a lower frame rate or a wider aperture lets more light in."
            }
            CaptureMode.Timelapse -> {
                if (result.warning == ExposureWarning.IsoTooHigh) {
                    out += "Too dark for a ${Exposure.formatShutter(result.shutter)} shutter: a longer interval allows a longer shutter."
                }
                if (result.warning == ExposureWarning.IsoTooLow) {
                    out += "Too bright for half-interval blur: " + (result.suggestedNd?.let { "use ${it.label}" } ?: "use a stronger filter") + ", or a shorter shutter (less blur)."
                }
            }
        }
        return out
    }
}
