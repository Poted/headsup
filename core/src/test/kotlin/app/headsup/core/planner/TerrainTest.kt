package app.headsup.core.planner

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerrainTest {
    @Test fun terrariumColoursHoldMetres() {
        // 128,0,0 is sea level; each red step is 256 m; blue holds fractions.
        assertEquals(0.0, Terrain.decode(128, 0, 0), 1e-9)
        assertEquals(256.0 + 50 + 0.5, Terrain.decode(129, 50, 128), 1e-9)
        assertEquals(-32768.0, Terrain.decode(0, 0, 0), 1e-9)
    }

    @Test fun aPlaceFallsInTheRightTileAndPixel() {
        // London at zoom 10 is tile 511, 340 (as the street map names it).
        val t = Terrain.tilePixel(51.5074, -0.1278, 10)
        assertEquals(511, t.x)
        assertEquals(340, t.y)
        assertTrue(t.px in 0.0..256.0 && t.py in 0.0..256.0)
    }

    @Test fun heightsAreReadBetweenPixels() {
        // A slope rising 1 m a pixel to the right: halfway between two pixel centres is halfway between them.
        val h = Terrain.bilinear(10.0, 20.0) { x, _ -> x.toDouble() }
        assertEquals(9.5, h, 1e-9)
    }

    @Test fun aHigherEyeSeesTheHorizonLowerAndTheSunSooner() {
        assertEquals(0.0, Terrain.horizonDip(0.0), 1e-12)
        assertEquals(0.2933, Terrain.horizonDip(100.0), 1e-3)
        val place = Place(50.06, 19.94)
        val zone = ZoneId.of("Europe/Warsaw")
        val day = LocalDate.of(2026, 3, 20)
        val ground = Sky.sunTimes(place, zone, day).sunrise!!
        val drone = Sky.sunTimes(place, zone, day, aboveGround = 100.0).sunrise!!
        val minutes = (ground - drone) / 60_000.0
        assertTrue(minutes in 1.0..3.0, "earlier by $minutes min")
    }
}
