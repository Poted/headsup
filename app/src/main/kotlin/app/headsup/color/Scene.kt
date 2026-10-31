package app.headsup.color

/**
 * What the scene model tells apart (in the order of its output, tools/models/scene_model.py).
 * [thing]: separate objects, made exact with MobileSAM and followed by it on video; the others
 * are areas (stuff), laid onto the picture's edges and found again by the scene model on video.
 */
enum class Scene(val label: String, val symbol: String, val thing: Boolean) {
    SKY("Sky", "☁", false),
    PEOPLE("People", "☺", true),
    VEGETATION("Plants", "♣", false),
    WATER("Water", "≋", false),
    MOUNTAINS("Mountains", "▲", false),
    ARCHITECTURE("Buildings", "▥", false),
    GROUND("Ground", "▁", false),
    VEHICLES("Vehicles", "◫", true),
    ANIMALS("Animals", "◆", true),
}

/**
 * What a mask found at once by AI can be (kept on its stroke, [app.headsup.core.color.BrushStroke.scene]):
 * a [Scene] group by its name, the picture's [SUBJECT] (its people, animals or vehicles, else what
 * is in the middle), or the [BACKGROUND] (all but the subject).
 */
object SceneKeys {
    const val SUBJECT = "SUBJECT"
    const val BACKGROUND = "BACKGROUND"

    /** In the order the new-mask menu offers them. */
    val offered = listOf(SUBJECT, Scene.PEOPLE.name, Scene.SKY.name, BACKGROUND) +
        listOf(Scene.VEGETATION, Scene.WATER, Scene.MOUNTAINS, Scene.ARCHITECTURE, Scene.GROUND, Scene.VEHICLES, Scene.ANIMALS).map { it.name }

    fun scene(key: String?): Scene? = Scene.entries.firstOrNull { it.name == key }

    fun label(key: String): String = when (key) {
        SUBJECT -> "Subject"
        BACKGROUND -> "Background"
        else -> scene(key)?.label ?: "Object"
    }

    fun symbol(key: String): String = when (key) {
        SUBJECT -> "◉"
        BACKGROUND -> "◌"
        else -> scene(key)?.symbol ?: "◎"
    }
}
