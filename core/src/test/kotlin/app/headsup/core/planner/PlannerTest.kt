package app.headsup.core.planner

import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlannerTest {
    private val place = Place(50.0, 20.0)
    private fun ms(h: Int) = ZonedDateTime.of(2026, 6, 21, h, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test fun noonAtNdSixteenAndHalfASecondOverexposesAndSuggestsAStrongerFilter() {
        val setup = ExposureSetup(AutoSetting.Iso, 2.8, 0.5, nd = NdFilter.Nd16)
        val f = Planner.frame(place, ms(10), SkyConditions(), setup)
        assertEquals(ExposureWarning.IsoTooLow, f.exposure.warning)
        // ND2000 (11 stops) just brings 1/2 s at noon into reach.
        assertEquals(NdFilter.Nd2000, f.exposure.suggestedNd)
        assertEquals(true, f.exposure.suggestionEnough)
        // At 4 s even the strongest filter is not enough: the suggestion says so.
        val longer = Planner.frame(place, ms(10), SkyConditions(), setup.copy(shutter = 4.0))
        assertEquals(NdFilter.strongest, longer.exposure.suggestedNd)
        assertEquals(false, longer.exposure.suggestionEnough)
        assertEquals("Day", f.phase)
    }

    @Test fun aSufficientFilterIsSuggestedWhenOneExists() {
        val setup = ExposureSetup(AutoSetting.Iso, 2.8, 0.5, nd = NdFilter.None)
        val r = Exposure.solve(8.0, setup)
        assertEquals(ExposureWarning.IsoTooLow, r.warning)
        assertEquals(NdFilter.Nd32, r.suggestedNd)
        assertEquals(true, r.suggestionEnough)
    }

    @Test fun theIsoRisesThroughTheEvening() {
        val setup = ExposureSetup(AutoSetting.Iso, 2.8, 0.5, nd = NdFilter.None)
        val a = Planner.frame(place, ms(17), SkyConditions(), setup).exposure.wantedIso
        val b = Planner.frame(place, ms(21), SkyConditions(), setup).exposure.wantedIso
        assertTrue(b > a * 8, "$a -> $b")
    }

    @Test fun aMeasuredValueIsTheStartingPoint() {
        val setup = ExposureSetup()
        val m = Measured(ms(10), 9.0)
        assertEquals(9.0, Planner.frame(place, ms(10), SkyConditions(), setup, m).ev, 1e-9)
        assertTrue(Planner.frame(place, ms(21), SkyConditions(), setup, m).ev < 9.0)
    }
}
