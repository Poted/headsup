package app.headsup.core.color

/**
 * Lightroom's Presence corrections, which compare a pixel with its surroundings and so cannot be
 * baked into a colour table: texture (fine detail), clarity (midtone local contrast) and dehaze
 * (the haze lifting the darkest channel, estimated over a wide area, taken out). All −1..1, 0 off.
 * The preview and export shaders draw them on the picture before its colour table.
 */
data class Presence(
    val texture: Float = 0f,
    val clarity: Float = 0f,
    val dehaze: Float = 0f,
    /**
     * Defringe (0..1): the purple and green fringes a lens leaves along hard edges (branches against
     * the sky, a roof's edge) taken out, by taking colour out of such hues where the brightness
     * changes sharply nearby.
     */
    val defringe: Float = 0f,
) {
    /** Nothing for the shaders to do: dehaze works per colour, in the colour table ([Dehaze]). */
    val isNeutral: Boolean get() = texture == 0f && clarity == 0f && defringe == 0f

    companion object {
        val NONE = Presence()
    }
}
