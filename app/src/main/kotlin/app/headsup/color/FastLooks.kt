package app.headsup.color

import app.headsup.core.color.ColorGrade
import app.headsup.render.EffectSettings

/** The RGB cinematic contrast curve at [amount], as a grade's curves. */
private fun contrast(amount: Float): List<List<Float>> = listOf(app.headsup.core.color.ToneCurve.cinematic(amount), emptyList(), emptyList(), emptyList())

/**
 * Fast edit: complete looks in one tap, made the way a colourist would: colour taken away rather
 * than added (tame, density), contrast from a soft shoulder and lifted blacks instead of the
 * contrast slider, then grain, glow, halation and a lens for texture. [grade] is added to the
 * clip's corrections; the effects are set (towards [effects] by the strength). Contrast comes
 * from the cinematic curve ([ToneCurve.cinematic]): lifted, flat shadows and a soft top, not the
 * contrast slider.
 */
enum class FastLook(val label: String, val grade: ColorGrade, val effects: EffectSettings) {
    CLEAN(
        "Clean cinema",
        ColorGrade(highlights = -0.12f, saturation = -0.08f, rolloff = 0.2f, tame = 0.3f, density = 0.3f, curves = contrast(0.6f)),
        EffectSettings(grain = 0.18f, grainSize = 0.7f, grainColour = 0.15f, glow = 0.12f, glowThreshold = 0.82f, glowSpread = 0.8f, halation = 0.08f),
    ),
    FILM_35(
        "35 mm film",
        ColorGrade(saturation = -0.05f, rolloff = 0.25f, toneAmount = 0.25f, tame = 0.35f, density = 0.35f, curves = contrast(1f)),
        EffectSettings(grain = 0.32f, grainSize = 1f, grainColour = 0.3f, glow = 0.1f, glowThreshold = 0.78f, halation = 0.25f, lensAberration = 0.08f, lensSoftness = 0.12f),
    ),
    WARM_FILM(
        "Warm film",
        ColorGrade(
            temperature = 0.12f, saturation = -0.08f, rolloff = 0.25f, toneAmount = 0.35f,
            shadowHue = 250f, highlightHue = 135f, tame = 0.3f, density = 0.3f, curves = contrast(1f),
        ),
        EffectSettings(grain = 0.2f, grainSize = 1f, grainColour = 0.3f, halation = 0.3f, glowThreshold = 0.78f, lensAberration = 0.06f, lensSoftness = 0.12f),
    ),
    VINTAGE(
        "Vintage lens",
        ColorGrade(temperature = 0.1f, saturation = -0.15f, rolloff = 0.3f, tame = 0.3f, density = 0.2f, curves = contrast(1.2f)),
        EffectSettings(
            grain = 0.4f, grainSize = 1.8f, grainColour = 0.4f, glow = 0.25f, glowThreshold = 0.72f, glowSpread = 1.4f, glowWarmth = 0.4f,
            halation = 0.2f, lensAberration = 0.35f, lensSoftness = 0.35f,
        ),
    ),
    TEAL_ORANGE(
        "Teal & orange",
        ColorGrade(vibrance = 0.1f, rolloff = 0.2f, toneAmount = 0.45f, tame = 0.2f, density = 0.3f, curves = contrast(0.8f)),
        EffectSettings(grain = 0.2f, grainSize = 1f, grainColour = 0.25f, halation = 0.12f),
    ),
    MUTED(
        "Muted",
        ColorGrade(saturation = -0.25f, rolloff = 0.25f, tame = 0.5f, density = 0.2f, curves = contrast(1f)),
        EffectSettings(grain = 0.25f, grainSize = 1.2f, grainColour = 0.2f, halation = 0.1f),
    ),
    NIGHT(
        "Night glow",
        ColorGrade(highlights = -0.1f, rolloff = 0.3f, tame = 0.25f, density = 0.3f, curves = contrast(0.5f)),
        EffectSettings(grain = 0.3f, grainSize = 1.1f, grainColour = 0.3f, glow = 0.35f, glowThreshold = 0.7f, glowSpread = 1.6f, glowWarmth = 0.2f, halation = 0.3f),
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
        )
    }
}
