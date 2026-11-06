package app.headsup.core.color

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class ApplyTableTest {
    @Test
    fun aNeutralTableLeavesThePicture() {
        val table = LutBaker.bake(null, ColorGrade())
        val px = IntArray(256) { i -> (0xFF shl 24) or (i shl 16) or ((255 - i) shl 8) or ((i * 7) and 0xFF) }
        val out = LutBaker.applyTable(px, table)
        for (i in px.indices) for (s in listOf(16, 8, 0)) {
            assertTrue(abs(((px[i] shr s) and 0xFF) - ((out[i] shr s) and 0xFF)) <= 2, "pixel $i")
        }
        assertTrue(out.all { (it ushr 24) == 0xFF })
    }

    @Test
    fun aTableIsFollowed() {
        val table = LutBaker.bake(null, ColorGrade(exposure = 1f))
        val grey = (0xFF shl 24) or (100 shl 16) or (100 shl 8) or 100
        val out = LutBaker.applyTable(intArrayOf(grey), table)[0]
        assertTrue(((out shr 8) and 0xFF) > 110)
    }
}
