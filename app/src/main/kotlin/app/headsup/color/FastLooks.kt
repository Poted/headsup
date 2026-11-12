package app.headsup.color

import app.headsup.core.color.ColorGrade
import app.headsup.core.color.FrameStats
import app.headsup.core.color.SceneTrait
import app.headsup.render.EffectSettings

/**
 * Fast edit: complete looks in one tap, made the way a colourist would: colour taken away rather
 * than added (tame, density), contrast from a soft shoulder and lifted blacks instead of the
 * contrast slider, then grain, glow, halation and a lens for texture. [grade] is added to the
 * clip's corrections; the effects are set (towards [effects] by the strength). Contrast comes
 * from the cinematic curve ([ToneCurve.cinematic]): lifted, flat shadows and a soft top, not the
 * contrast slider.
 */
enum class FastLook(
    val label: String,
    /** What it is, in a line (shown under the strength). */
    val description: String,
    val grade: ColorGrade,
    val effects: EffectSettings,
    /** How strong its cinematic contrast curve is ([ToneCurve.cinematic]), fitted to the clip when applied. */
    val contrast: Float,
    /** How well it suits a scene with each trait (what Analyse suggests it by); unlisted: neutral. */
    val suits: Map<SceneTrait, Float>,
    /** Its character is mostly light (glow, mist, flares) rather than colour. */
    val light: Boolean = false,
    /** Meant to stay dark: a dark clip is not brightened for it. */
    val keepDark: Boolean = false,
) {
    CLEAN(
        "Clean cinema",
        "Natural colour, calmer and denser, soft highlights and fine grain.",
        ColorGrade(highlights = -0.12f, saturation = -0.08f, rolloff = 0.2f, tame = 0.3f, density = 0.3f),
        EffectSettings(grain = 0.18f, grainSize = 0.7f, grainColour = 0.15f, glow = 0.12f, glowThreshold = 0.82f, glowSpread = 0.8f, halation = 0.08f),
        0.6f,
        mapOf(SceneTrait.PEOPLE to 2f, SceneTrait.DAYLIGHT to 0.8f, SceneTrait.NIGHT to -0.5f),
    ),
    BLUE(
        "Cinematic blue",
        "The cool blue film look: blue-teal shadows and mid-tones, warm lights kept, calmer colour.",
        ColorGrade(
            temperature = -0.16f, tint = -0.02f, saturation = -0.12f, blacks = 0.03f, rolloff = 0.3f,
            toneAmount = 0.45f, shadowHue = 330f, highlightHue = 150f, tame = 0.2f, density = 0.25f,
            wheels = listOf(215f, 0.35f, -0.04f, 205f, 0.12f, 0f, 40f, 0.08f, 0f, 0f),
        ),
        EffectSettings(grain = 0.18f, grainSize = 0.9f, grainColour = 0.2f, glow = 0.1f, glowThreshold = 0.8f, halation = 0.1f),
        0.9f,
        mapOf(SceneTrait.FLAT to 2f, SceneTrait.COOL to 1.5f, SceneTrait.SKY to 1f, SceneTrait.NIGHT to 1.2f, SceneTrait.CITY_LIGHTS to 0.5f, SceneTrait.GOLDEN to -1.5f, SceneTrait.WARM to -0.8f),
    ),
    TEAL_ORANGE(
        "Teal & orange",
        "Blockbuster contrast of colour: teal shadows, warm skin and lights.",
        ColorGrade(vibrance = 0.1f, rolloff = 0.2f, toneAmount = 0.45f, tame = 0.2f, density = 0.3f),
        EffectSettings(grain = 0.2f, grainSize = 1f, grainColour = 0.25f, halation = 0.12f),
        0.8f,
        mapOf(SceneTrait.PEOPLE to 1.5f, SceneTrait.SKY to 1f, SceneTrait.CITY_LIGHTS to 1f, SceneTrait.DAYLIGHT to 0.5f, SceneTrait.FLAT to -0.5f),
    ),
    FILM_35(
        "35 mm film",
        "Colour negative: soft shoulder, film grain, red halation around lights.",
        ColorGrade(saturation = -0.05f, rolloff = 0.25f, toneAmount = 0.25f, tame = 0.35f, density = 0.35f),
        EffectSettings(grain = 0.32f, grainSize = 1f, grainColour = 0.3f, glow = 0.1f, glowThreshold = 0.78f, halation = 0.25f, lensAberration = 0.08f, lensSoftness = 0.12f, weave = 0.15f, flicker = 0.1f),
        1f,
        mapOf(SceneTrait.DAYLIGHT to 1f, SceneTrait.FOLIAGE to 0.8f, SceneTrait.COLOURFUL to 0.5f),
    ),
    WARM_FILM(
        "Warm film",
        "Warm print film: golden mid-tones, green-teal shadows, halation.",
        ColorGrade(
            temperature = 0.12f, saturation = -0.08f, rolloff = 0.25f, toneAmount = 0.35f,
            shadowHue = 250f, highlightHue = 135f, tame = 0.3f, density = 0.3f,
        ),
        EffectSettings(grain = 0.2f, grainSize = 1f, grainColour = 0.3f, halation = 0.3f, glowThreshold = 0.78f, lensAberration = 0.06f, lensSoftness = 0.12f),
        1f,
        mapOf(SceneTrait.COOL to 1.2f, SceneTrait.PEOPLE to 1f, SceneTrait.FLAT to 0.8f, SceneTrait.WARM to -0.8f, SceneTrait.NIGHT to -0.8f),
    ),
    GOLDEN(
        "Golden hour",
        "Low sun: warm, glowing highlights and a light haze in the air.",
        ColorGrade(
            temperature = 0.18f, tint = 0.03f, vibrance = 0.12f, highlights = -0.08f, rolloff = 0.3f,
            toneAmount = 0.4f, shadowHue = 255f, highlightHue = 145f, density = 0.2f,
            wheels = listOf(0f, 0f, 0f, 30f, 0.15f, 0f, 35f, 0.4f, 0f, 0f),
        ),
        EffectSettings(
            grain = 0.15f, grainSize = 1f, grainColour = 0.25f, glow = 0.3f, glowThreshold = 0.65f, glowSpread = 1.6f, glowWarmth = 0.7f,
            halation = 0.2f, mist = 0.25f,
        ),
        0.8f,
        mapOf(SceneTrait.GOLDEN to 3f, SceneTrait.WARM to 1.5f, SceneTrait.FOLIAGE to 0.5f, SceneTrait.DAYLIGHT to 0.5f, SceneTrait.NIGHT to -3f),
        light = true,
    ),
    VIVID(
        "Vivid day",
        "Clear and lush: deeper sky, fresher greens, crisp detail, haze taken out.",
        ColorGrade(
            vibrance = 0.3f, saturation = 0.04f, highlights = -0.15f, shadows = 0.1f, temperature = 0.03f,
            clarity = 0.25f, dehaze = 0.15f, texture = 0.15f, rolloff = 0.2f, density = 0.15f,
            mixer = mixer(green = floatArrayOf(-0.05f, 0.15f, -0.05f), aqua = floatArrayOf(0.05f, 0.1f, -0.05f), blue = floatArrayOf(0f, 0.15f, -0.12f)),
        ),
        EffectSettings(),
        0.7f,
        mapOf(SceneTrait.FLAT to 2f, SceneTrait.SKY to 1.5f, SceneTrait.FOLIAGE to 1.5f, SceneTrait.DAYLIGHT to 1f, SceneTrait.NIGHT to -3f, SceneTrait.COLOURFUL to -0.8f),
    ),
    VINTAGE(
        "Vintage lens",
        "An old lens: warm, softer, coloured fringes and glowing lights.",
        ColorGrade(temperature = 0.1f, saturation = -0.15f, rolloff = 0.3f, tame = 0.3f, density = 0.2f),
        EffectSettings(
            grain = 0.4f, grainSize = 1.8f, grainColour = 0.4f, glow = 0.25f, glowThreshold = 0.72f, glowSpread = 1.4f, glowWarmth = 0.4f,
            halation = 0.2f, lensAberration = 0.35f, lensSoftness = 0.35f, prism = 0.12f, weave = 0.3f, flicker = 0.2f,
        ),
        1.2f,
        mapOf(SceneTrait.WARM to 1f, SceneTrait.GOLDEN to 1f, SceneTrait.PEOPLE to 0.5f),
        light = true,
    ),
    DREAMY(
        "Dreamy",
        "Soft and romantic: lifted blacks, pastel warmth, mist blooming from the lights.",
        ColorGrade(
            temperature = 0.08f, tint = 0.04f, saturation = -0.1f, blacks = 0.1f, highlights = -0.1f, rolloff = 0.35f,
            toneAmount = 0.3f, shadowHue = 255f, highlightHue = 120f, density = 0.1f,
        ),
        EffectSettings(grain = 0.15f, grainSize = 1f, grainColour = 0.2f, glow = 0.25f, glowThreshold = 0.6f, glowSpread = 1.8f, glowWarmth = 0.4f, halation = 0.15f, mist = 0.45f, lensSoftness = 0.1f),
        0.3f,
        mapOf(SceneTrait.PEOPLE to 1.5f, SceneTrait.GOLDEN to 1f, SceneTrait.DAYLIGHT to 0.5f, SceneTrait.FOLIAGE to 0.3f),
        light = true,
    ),
    MUTED(
        "Muted",
        "Colour held back, print-like blacks: quiet and documentary.",
        ColorGrade(saturation = -0.25f, rolloff = 0.25f, tame = 0.5f, density = 0.2f),
        EffectSettings(grain = 0.25f, grainSize = 1.2f, grainColour = 0.2f, halation = 0.1f),
        1f,
        mapOf(SceneTrait.COLOURFUL to 2f, SceneTrait.FOLIAGE to 1f, SceneTrait.FLAT to -1f),
    ),
    MOODY(
        "Moody",
        "Drama: darker, cooler, colour drained towards blue-violet, a vignette.",
        ColorGrade(
            saturation = -0.2f, temperature = -0.06f, tint = 0.06f, rolloff = 0.3f,
            toneAmount = 0.4f, shadowHue = 20f, highlightHue = 150f, tame = 0.3f, density = 0.3f, vignette = 0.25f,
            wheels = listOf(260f, 0.3f, -0.08f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        ),
        EffectSettings(grain = 0.3f, grainSize = 1.2f, grainColour = 0.2f, halation = 0.1f),
        1f,
        mapOf(SceneTrait.FLAT to 1.5f, SceneTrait.COOL to 1f, SceneTrait.COLOURFUL to 0.5f, SceneTrait.GOLDEN to -1f),
        keepDark = true,
    ),
    NIGHT(
        "Night glow",
        "Lights that glow into the dark, film grain, halation.",
        ColorGrade(highlights = -0.1f, rolloff = 0.3f, tame = 0.25f, density = 0.3f),
        EffectSettings(grain = 0.3f, grainSize = 1.1f, grainColour = 0.3f, glow = 0.35f, glowThreshold = 0.7f, glowSpread = 1.6f, glowWarmth = 0.2f, halation = 0.3f),
        0.5f,
        mapOf(SceneTrait.NIGHT to 3f, SceneTrait.CITY_LIGHTS to 1f, SceneTrait.DAYLIGHT to -3f),
        light = true,
        keepDark = true,
    ),
    NIGHT_TEAL(
        "Night teal",
        "The teal-green night: cool green shadows, warm lamps, a little mist.",
        ColorGrade(
            temperature = -0.08f, tint = -0.08f, saturation = -0.1f, highlights = -0.05f, rolloff = 0.3f,
            toneAmount = 0.55f, shadowHue = 260f, highlightHue = 140f, tame = 0.2f, density = 0.3f,
            wheels = listOf(170f, 0.35f, -0.05f, 0f, 0f, 0f, 35f, 0.15f, 0f, 0f),
        ),
        EffectSettings(grain = 0.25f, grainSize = 1.1f, grainColour = 0.3f, glow = 0.2f, glowThreshold = 0.75f, glowSpread = 1.2f, glowWarmth = 0.3f, halation = 0.2f, mist = 0.15f),
        0.8f,
        mapOf(SceneTrait.NIGHT to 2.5f, SceneTrait.CITY_LIGHTS to 0.5f, SceneTrait.DAYLIGHT to -3f),
        keepDark = true,
    ),
    ANAMORPHIC(
        "Anamorphic",
        "Wide-screen lens: blue streaks across the lights, teal and orange, a vignette.",
        ColorGrade(
            temperature = -0.05f, saturation = -0.05f, rolloff = 0.3f, toneAmount = 0.5f, tame = 0.2f, density = 0.3f, vignette = 0.15f,
        ),
        EffectSettings(grain = 0.2f, grainSize = 1f, grainColour = 0.25f, glow = 0.15f, glowThreshold = 0.8f, halation = 0.15f, lensAberration = 0.1f, streaks = 0.5f, streakLength = 1.2f),
        0.9f,
        mapOf(SceneTrait.CITY_LIGHTS to 3f, SceneTrait.NIGHT to 1f, SceneTrait.DAYLIGHT to -1f),
        light = true,
        keepDark = true,
    ),
    STARLIGHT(
        "Starlight",
        "A star filter: every point of light becomes a star; cool night, warm lamps.",
        ColorGrade(highlights = -0.05f, temperature = -0.04f, rolloff = 0.3f, toneAmount = 0.35f, shadowHue = 330f, highlightHue = 145f, density = 0.2f),
        EffectSettings(grain = 0.25f, grainSize = 1f, grainColour = 0.3f, glow = 0.25f, glowThreshold = 0.72f, glowSpread = 1.2f, glowWarmth = 0.3f, halation = 0.15f, star = 0.5f, starLength = 1f),
        0.6f,
        mapOf(SceneTrait.CITY_LIGHTS to 2.8f, SceneTrait.NIGHT to 0.5f, SceneTrait.DAYLIGHT to -2f),
        light = true,
        keepDark = true,
    ),
    ;

    /** [base] with this look's effects at [k] (0..1): amounts towards the look's, its character set. */
    fun effectsOn(base: EffectSettings, k: Float): EffectSettings {
        fun mix(a: Float, b: Float) = a + (b - a) * k
        val e = effects
        return base.copy(
            grain = mix(base.grain, e.grain), grainSize = e.grainSize, grainColour = e.grainColour,
            glow = mix(base.glow, e.glow), glowThreshold = e.glowThreshold, glowSpread = e.glowSpread, glowWarmth = e.glowWarmth,
            halation = mix(base.halation, e.halation),
            lensAberration = mix(base.lensAberration, e.lensAberration), lensSoftness = mix(base.lensSoftness, e.lensSoftness),
            mist = mix(base.mist, e.mist),
            streaks = mix(base.streaks, e.streaks), streakLength = if (e.streaks > 0f) e.streakLength else base.streakLength,
            star = mix(base.star, e.star), starLength = if (e.star > 0f) e.starLength else base.starLength,
            weave = mix(base.weave, e.weave), flicker = mix(base.flicker, e.flicker),
            prism = mix(base.prism, e.prism), prismSpread = if (e.prism > 0f) e.prismSpread else base.prismSpread,
        )
    }

    companion object {
        /**
         * The three looks that suit what was measured, the best first: scored by the scene's
         * traits (night with lamps, low sun, a grey day, greenery, sky, people…), one of them a
         * look of light (glow, mist, flares) and one of colour, so the three are different choices.
         */
        fun suggested(stats: FrameStats): List<FastLook> {
            val traits = stats.traits
            val ranked = entries.sortedByDescending { look -> traits.sumOf { (look.suits[it] ?: 0f).toDouble() } }
            val first = ranked.first()
            val other = ranked.first { it.light != first.light }
            val third = ranked.first { it != first && it != other }
            return listOf(first, other, third).sortedBy { ranked.indexOf(it) }
        }
    }
}

/** A colour mixer with the given hue, saturation and luminance per band (each null: none). */
private fun mixer(green: FloatArray? = null, aqua: FloatArray? = null, blue: FloatArray? = null): List<Float> =
    app.headsup.core.color.ColourMixer.NEUTRAL.toMutableList().also { m ->
        val bands = listOf(
            app.headsup.core.color.ColourMixer.Band.GREEN to green,
            app.headsup.core.color.ColourMixer.Band.AQUA to aqua,
            app.headsup.core.color.ColourMixer.Band.BLUE to blue,
        )
        for ((band, v) in bands) {
            if (v == null) continue
            for (p in 0..2) m[band.ordinal * 3 + p] = v[p]
        }
    }

/**
 * A cinematic look on a clip: which, how strongly, the clip's corrections and effects before it
 * ([baseGrade], [baseEffects]) and as the look left them ([grade], [effects]). Kept with the clip,
 * so None takes the look off at any time, keeping what was changed by hand since.
 */
data class AppliedLook(
    val name: String,
    val strength: Float,
    val baseGrade: ColorGrade,
    val baseEffects: EffectSettings,
    val grade: ColorGrade,
    val effects: EffectSettings,
) {
    val look: FastLook? get() = FastLook.entries.firstOrNull { it.name == name }

    /** The clip's corrections without the look: as before it, with the changes made since on top. */
    fun gradeWithout(current: ColorGrade): ColorGrade = baseGrade.synced(grade, current)

    fun effectsWithout(current: EffectSettings): EffectSettings = baseEffects.synced(effects, current)
}

/**
 * Colour harmony on a clip ([app.headsup.core.color.ColourHarmony]): the scheme, its key hue and
 * amount, and the clip's corrections before it and as it left them, so it can be changed or taken
 * off later, keeping what was changed by hand since.
 */
data class AppliedHarmony(
    val scheme: app.headsup.core.color.ColourHarmony.Scheme,
    val hue: Float,
    val amount: Float,
    val baseGrade: ColorGrade,
    val grade: ColorGrade,
) {
    fun gradeWithout(current: ColorGrade): ColorGrade = baseGrade.synced(grade, current)
}

/**
 * One of the user's presets on a clip: its name, and the clip's corrections, effects and LUT
 * before it and as it left them, so None takes it off later, keeping what was changed since.
 */
data class AppliedPreset(
    val name: String,
    val baseGrade: ColorGrade,
    val baseEffects: EffectSettings,
    val baseLut: String?,
    val grade: ColorGrade,
    val effects: EffectSettings,
    val lut: String?,
    /** The look and harmony the clip had before (they come back with None). */
    val baseLook: AppliedLook? = null,
    val baseHarmony: AppliedHarmony? = null,
) {
    fun gradeWithout(current: ColorGrade): ColorGrade = baseGrade.synced(grade, current)

    fun effectsWithout(current: EffectSettings): EffectSettings = baseEffects.synced(effects, current)

    /** The LUT without the preset: the one before it, unless another was chosen since. */
    fun lutWithout(current: String?): String? = if (current == lut) baseLut else current
}
