package app.headsup.core.color

/**
 * Lightroom's Presence corrections, which compare a pixel with its surroundings and so cannot be
 * baked into a colour table: texture (fine detail), clarity (midtone local contrast) and dehaze
 * (the haze lifting the darkest channel, estimated over a wide area, taken out). All −1..1, 0 off.
 * The preview and export shaders draw them on the picture before its colour table.
 */
data class Presence(val texture: Float = 0f, val clarity: Float = 0f, val dehaze: Float = 0f) {
    val isNeutral: Boolean get() = texture == 0f && clarity == 0f && dehaze == 0f

    companion object {
        val NONE = Presence()
    }
}
