package app.headsup.core.photo

import kotlin.test.Test
import kotlin.test.assertTrue

class LightFxTest {
    private val w = 160
    private val h = 90

    /** Black with one small bright light in the middle. */
    private fun scene() = IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x in 79..80 && y in 44..45) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }

    private fun lum(px: IntArray, x: Int, y: Int) = px[y * w + x].let { ((it shr 16) and 0xFF) + ((it shr 8) and 0xFF) + (it and 0xFF) }

    @Test
    fun streaksRunSidewaysNotUp() {
        val l = LightFx.light(scene(), w, h, LightFx.Settings(streaks = 1f))
        // Far to the side there is light; as far up there is none.
        assertTrue(lum(l, 80 + 20, 45) > 0, "side ${lum(l, 100, 45)}")
        assertTrue(lum(l, 80, 45 - 20) == 0, "up ${lum(l, 80, 25)}")
        // Blue more than red.
        val c = l[45 * w + 90]
        assertTrue((c and 0xFF) >= ((c shr 16) and 0xFF))
    }

    @Test
    fun aStarHasRaysBothWays() {
        val l = LightFx.light(scene(), w, h, LightFx.Settings(star = 1f))
        assertTrue(lum(l, 86, 45) > 0)
        assertTrue(lum(l, 80, 40) > 0)
        // Not between the rays.
        assertTrue(lum(l, 86, 39) == 0)
    }

    @Test
    fun aPrismRepeatsTheLightBesideIt() {
        val l = LightFx.light(scene(), w, h, LightFx.Settings(prism = 1f, prismSpread = 4f))
        // A copy down and to the right (30°), none on the other side.
        val d = (0.03f * h * 4f).toInt()
        val x = 80 + (d * 0.866f).toInt()
        val y = 45 + (d * 0.5f).toInt()
        assertTrue(lum(l, x, y) > 0, "copy ${lum(l, x, y)}")
        assertTrue(lum(l, 80 - (x - 80), 45 - (y - 45)) == 0)
    }

    @Test
    fun nothingSetGivesNoLight() {
        val l = LightFx.light(scene(), w, h, LightFx.Settings())
        assertTrue(l.all { it and 0xFFFFFF == 0 })
    }
}
