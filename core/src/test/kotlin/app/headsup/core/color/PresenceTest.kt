package app.headsup.core.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresenceTest {
    @Test
    fun `presence is not a colour change`() {
        val g = ColorGrade(texture = 0.3f, clarity = -0.2f, dehaze = 0.5f)
        assertTrue(g.isNeutralColour)
        assertEquals(Presence(0.3f, -0.2f, 0.5f), g.presence)
        assertTrue(ColorGrade().presence.isNeutral)
    }

    @Test
    fun `sync carries only the changed presence value`() {
        val other = ColorGrade(texture = 0.1f, clarity = 0.4f)
        val before = ColorGrade(clarity = 0.2f)
        val after = before.copy(dehaze = 0.6f)
        val synced = other.synced(before, after)
        assertEquals(Presence(0.1f, 0.4f, 0.6f), synced.presence)
    }

    @Test
    fun `global presence adds to the clip's and scales`() {
        val sum = ColorGrade(clarity = 0.7f) + ColorGrade(clarity = 0.6f, texture = -0.2f)
        assertEquals(Presence(-0.2f, 1f, 0f), sum.presence)
        assertEquals(0.35f, (ColorGrade(clarity = 0.7f) * 0.5f).clarity, 1e-6f)
    }
}
