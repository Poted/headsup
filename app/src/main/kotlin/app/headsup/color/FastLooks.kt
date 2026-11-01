package app.headsup.color

import app.headsup.core.color.ColorGrade
import app.headsup.render.EffectSettings

/**
 * Fast edit: complete looks in one tap, made the way a colourist would: colour taken away rather
 * than added (tame, density), contrast from a soft shoulder and lifted blacks instead of the
 * contrast slider, then grain, glow, halation and a lens for texture. [grade] is added to the
 * clip's corrections; the effects are set (towards [effects] by the strength).
 */
enum class FastLook(val label: String, val grade: ColorGrade, val effects: EffectSettings) {
    CLEAN(
        "Clean cinema",
        ColorGrade(highlights = -0.2f, shadows = 0.08f, blacks = 0.05f, saturation = -0.08f, rolloff = 0.4f, tame = 0.3f, density = 0.3f),
        EffectSettings(grain = 0.18f, grainSize = 0.7f, grainColour = 0.15f, glow = 0.12f, glowThreshold = 0.82f, glowSpread = 0.8f, halation = 0.08f),
    ),
    FILM_35(
        "35 mm film",
        ColorGrade(highlights = -0.15f, blacks = 0.1f, saturation = -0.05f, rolloff = 0.5f, toneAmount = 0.25f, tame = 0.35f, density = 0.35f),
        EffectSettings(grain = 0.32f, grainSize = 1f, grainColour = 0.3f, glow = 0.1f, glowThreshold = 0.78f, halation = 0.25f),
    ),
    WARM_FILM(
        "Warm film",
        ColorGrade(
            temperature = 0.12f, saturation = -0.08f, blacks = 0.12f, rolloff = 0.4f, toneAmount = 0.35f,
            shadowHue = 250f, highlightHue = 135f, tame = 0.3f, density = 0.3f,
        ),
        EffectSettings(grain = 0.2f, grainSize = 1f, grainColour = 0.3f, halation = 0.3f, glowThreshold = 0.78f),
    ),
    VINTAGE(
        "Vintage lens",
        ColorGrade(temperature = 0.1f, blacks = 0.14f, saturation = -0.15f, rolloff = 0.45f, tame = 0.3f, density = 0.2f),
        EffectSettings(
            grain = 0.4f, grainSize = 1.8f, grainColour = 0.4f, glow = 0.25f, glowThreshold = 0.72f, glowSpread = 1.4f, glowWarmth = 0.4f,
            halation = 0.2f, lensAberration = 0.35f, lensSoftness = 0.35f,
        ),
    ),
    TEAL_ORANGE(
        "Teal & orange",
        ColorGrade(contrast = 0.08f, vibrance = 0.1f, rolloff = 0.3f, toneAmount = 0.45f, tame = 0.2f, density = 0.3f),
        EffectSettings(grain = 0.2f, grainSize = 1f, grainColour = 0.25f, halation = 0.12f),
    ),
    MUTED(
        "Muted",
        ColorGrade(blacks = 0.12f, saturation = -0.25f, rolloff = 0.4f, tame = 0.5f, density = 0.2f),
        EffectSettings(grain = 0.25f, grainSize = 1.2f, grainColour = 0.2f, halation = 0.1f),
    ),
    NIGHT(
        "Night glow",
        ColorGrade(highlights = -0.15f, blacks = 0.04f, rolloff = 0.45f, tame = 0.25f, density = 0.3f),
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
