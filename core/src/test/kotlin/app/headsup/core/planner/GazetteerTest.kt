package app.headsup.core.planner

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GazetteerTest {
    private val g = Gazetteer.parse(
        sequenceOf(
            "Zakopane\t49.29899\t19.94885\t838\tPPL\t27266\tPL",
            "Kasprowy Wierch\t49.23167\t19.98167\t1987\tPK\t0\tPL",
            "Łomnica\t50.80000\t15.70000\t\tPPL\t1200\tPL",
            "Lomnický štít\t49.19528\t20.21333\t2634\tPK\t0\tSK",
            "Morskie Oko\t49.19694\t20.07083\t1395\tLK\t0\tPL",
            "Zakopane Kościelisko\t49.29\t19.89\t\tPPLX\t0\tPL",
            "broken line",
        ),
    )

    @Test fun readsTheListAndLeavesOutWhatItCannotRead() {
        assertEquals(6, g.places.size)
        assertEquals("peak", g.places[1].kind)
        assertEquals("lake", g.places[4].kind)
        assertEquals("town", g.places[0].kind)
    }

    @Test fun namesMatchWithoutAccentsOrCase() {
        assertEquals("Łomnica", g.search("lomnica", 50.0, 20.0).first().name)
        assertEquals("Lomnický štít", g.search("lomnicky", 50.0, 20.0).first().name)
        assertEquals("Kasprowy Wierch", g.search("wierch", 50.0, 20.0).first().name)
        assertEquals("Morskie Oko", g.search("MORSKIE", 50.0, 20.0).first().name)
    }

    @Test fun theWholeNameComesBeforeLongerOnes() {
        val r = g.search("zakopane", 50.0, 20.0)
        assertEquals("Zakopane", r[0].name)
        assertEquals("Zakopane Kościelisko", r[1].name)
    }

    @Test fun aShortPlusCodeIsCompletedFromTheTownItNames() {
        val code = PlusCode.encode(49.2318, 19.9817)
        val short = code.substring(4)
        // Set far away (Gdańsk): without the town the short code lands in the wrong cell; with it, right.
        val locate = { words: String -> g.locate(words, 54.35, 18.65)?.let { it.lat to it.lon } }
        val p = assertNotNull(PlaceInput.parse("$short Zakopane, Polska", 54.35, 18.65, locate))
        assertTrue(abs(p.lat - 49.2318) < 2e-4 && abs(p.lon - 19.9817) < 2e-4, "${p.lat}, ${p.lon}")
        assertTrue(p.from.contains("Zakopane"))
    }

    @Test fun filterChoicesGiveTheIsoEachNeedsWeakestFirst() {
        // Noon EV 15 at f/2.8, 1/50 s, ISO 100–3200: ND64 gives ISO 100 (cleanest), stronger ones more ISO.
        val c = Exposure.filterChoices(15.0, 2.8, 1.0 / 50, CameraLimits(100.0, 3200.0))
        assertTrue(c.isNotEmpty())
        for (i in 0 until c.size - 1) assertTrue(c[i].second < c[i + 1].second)
        assertTrue(c.all { it.second in 98.0..3300.0 })
    }
}
