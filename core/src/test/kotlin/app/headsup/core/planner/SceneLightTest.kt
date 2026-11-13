package app.headsup.core.planner

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SceneLightTest {
    private fun ms(h: Int, mo: Int = 6) = ZonedDateTime.of(2026, mo, 21, h, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
    private val place = Place(50.0, 20.0)

    @Test fun clearNoonIsAboutEv15() {
        val ev = SceneLight.ev(place, ms(10), SkyConditions(3, 0.0))
        assertTrue(ev in 14.0..15.6, "ev $ev")
    }

    @Test fun cloudsDimTheDayByAboutTwoStops() {
        val clear = SceneLight.ev(place, ms(10), SkyConditions(3, 0.0))
        val over = SceneLight.ev(place, ms(10), SkyConditions(3, 1.0))
        assertTrue(clear - over in 1.7..2.3, "diff ${clear - over}")
    }

    @Test fun darkerSkiesAreDimmerAtNight() {
        // A night of the new moon (2026-05-16), so the moon adds nothing.
        val night = ZonedDateTime.of(2026, 5, 16, 0, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val dark = SceneLight.ev(place, night, SkyConditions(1, 0.0))
        val city = SceneLight.ev(place, night, SkyConditions(9, 0.0))
        assertTrue(dark < -5.0, "dark $dark")
        assertTrue(city > dark + 5.0, "city $city")
    }

    @Test fun lightFallsThroughTheEvening() {
        val evs = (14..23).map { SceneLight.ev(place, ms(it), SkyConditions()) }
        for (i in 0 until evs.size - 1) assertTrue(evs[i + 1] <= evs[i] + 1e-9, "evs $evs")
    }

    @Test fun higherPlacesAreSlightlyBrighterByDay() {
        val low = SceneLight.ev(Place(50.0, 20.0, 0.0), ms(10), SkyConditions())
        val high = SceneLight.ev(Place(50.0, 20.0, 3000.0), ms(10), SkyConditions())
        assertTrue(high > low && high - low < 0.5)
    }

    @Test fun aMeasurementIsMovedByTheModelsChange() {
        val c = SkyConditions()
        val base = ms(10); val later = ms(17)
        val r = SceneLight.relative(place, base, 11.0, later, c)
        assertEquals(11.0 + SceneLight.ev(place, later, c) - SceneLight.ev(place, base, c), r, 1e-9)
        assertEquals(11.0, SceneLight.relative(place, base, 11.0, base, c), 1e-9)
    }
}
