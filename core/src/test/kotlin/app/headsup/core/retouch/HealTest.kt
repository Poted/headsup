package app.headsup.core.retouch

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealTest {
    private val w = 120
    private val h = 80

    /** A left-to-right brightness ramp with fine vertical stripes (texture), and a dark spot at (60, 40). */
    private fun photo(): FloatArray = FloatArray(w * h * 3) { k ->
        val i = k / 3
        val x = i % w
        val y = i / w
        val base = 0.3f + 0.4f * x / w + if (x % 4 < 2) 0.05f else 0f
        val spot = (x - 60) * (x - 60) + (y - 40) * (y - 40) <= 16
        if (spot) 0.05f else base
    }

    private fun spotHole() = BooleanArray(w * h) { i -> val x = i % w; val y = i / w; (x - 60) * (x - 60) + (y - 40) * (y - 40) <= 25 }

    @Test
    fun aSourceIsFoundAwayFromTheSpot() {
        val hole = spotHole()
        val s = Heal.sources(photo(), w, h, hole, PixelBox(55, 35, 66, 46), ring = 4)
        assertTrue(s.isNotEmpty())
        // The stripes repeat every 4 px: the best match keeps them in step.
        assertEquals(0, ((s[0].dx % 4) + 4) % 4)
        assertTrue(abs(s[0].dx) > 5 || abs(s[0].dy) > 5)
    }

    @Test
    fun healingCoversTheSpotAndMatchesTheLight() {
        val rgb = photo()
        val hole = spotHole()
        val box = PixelBox(50, 30, 71, 51)
        // A source from a brighter part of the ramp: clone shows the step, heal does not.
        val src = Heal.Source(dx = 40, dy = 0, cost = 0f)
        val healed = Heal.fill(rgb, w, h, hole, box, src, heal = true)
        val cloned = Heal.fill(rgb, w, h, hole, box, src, heal = false)
        val bw = box.width
        val centre = (40 - box.y0) * bw + (60 - box.x0)
        val expected = 0.3f + 0.4f * 60f / w + if (60 % 4 < 2) 0.05f else 0f
        assertTrue(abs(healed[centre * 3] - expected) < 0.04f, "healed ${healed[centre * 3]} vs $expected")
        assertTrue(abs(cloned[centre * 3] - expected) > 0.1f)
        // Away from the hole, the photo as it is.
        assertEquals(rgb[((30) * w + 50) * 3], healed[0], 1e-6f)
    }
}
