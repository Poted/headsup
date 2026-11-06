package app.headsup.core.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GradeKeysTest {
    private val shade = ColorGrade(exposure = 1f, temperature = 0.2f)
    private val sun = ColorGrade(exposure = 0f, temperature = -0.2f, look = "FILM")
    private val keys = listOf(GradeKey(1000, shade), GradeKey(3000, sun))

    @Test
    fun noKeysNoGrade() = assertNull(GradeKeys.at(emptyList(), 500))

    @Test
    fun theNearestKeyHoldsOutside() {
        assertEquals(shade, GradeKeys.at(keys, 0))
        assertEquals(sun, GradeKeys.at(keys, 9000))
    }

    @Test
    fun valuesGoSmoothlyBetween() {
        val mid = GradeKeys.at(keys, 2000)!!
        assertEquals(0.5f, mid.exposure, 1e-4f)
        assertEquals(0f, mid.temperature, 1e-4f)
        // Eased: a quarter of the way in time is less than a quarter of the way in value.
        val early = GradeKeys.at(keys, 1500)!!
        assertTrue(early.exposure > 0.75f)
        // A look switches half way.
        assertNull(GradeKeys.at(keys, 1900)!!.look)
        assertEquals("FILM", GradeKeys.at(keys, 2100)!!.look)
    }

    @Test
    fun aKeyNearbyIsChangedNotAdded() {
        val changed = GradeKeys.set(keys, 1040, ColorGrade(exposure = 2f))
        assertEquals(2, changed.size)
        assertEquals(1000, changed[0].timeMs)
        assertEquals(2f, changed[0].grade.exposure)
        val added = GradeKeys.set(keys, 2000, ColorGrade())
        assertEquals(listOf(1000L, 2000L, 3000L), added.map { it.timeMs })
        assertEquals(1, GradeKeys.remove(added, 2010).size - 1)
    }
}
