package app.headsup.core.raw

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpcodeTest {
    private fun list(vararg opcodes: Pair<Int, ByteArray>): ByteArray {
        val b = ByteBuffer.allocate(4 + opcodes.sumOf { 16 + it.second.size })
        b.putInt(opcodes.size)
        for ((id, params) in opcodes) b.putInt(id).putInt(0x01030000).putInt(0).putInt(params.size).put(params)
        return b.array()
    }

    @Test
    fun `parses a warp, a vignette and a gain map`() {
        val warp = ByteBuffer.allocate(4 + 6 * 8 + 16).putInt(1)
        doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.5, 0.5).forEach { warp.putDouble(it) }
        val vignette = ByteBuffer.allocate(7 * 8)
        doubleArrayOf(0.5, 0.0, 0.0, 0.0, 0.0, 0.5, 0.5).forEach { vignette.putDouble(it) }
        // 2×1 map: gain 1 at the left, 3 at the right.
        val gain = ByteBuffer.allocate(4 * 10 + 8 * 4 + 4 + 2 * 4)
        intArrayOf(0, 0, 100, 100, 0, 1, 1, 1, 1, 2).forEach { gain.putInt(it) }
        doubleArrayOf(1.0, 1.0, 0.0, 0.0).forEach { gain.putDouble(it) }
        gain.putInt(1).putFloat(1f).putFloat(3f)
        val ops = Opcode.parse(list(1 to warp.array(), 3 to vignette.array(), 9 to gain.array()))
        assertEquals(3, ops.size)
        val map = ops[2] as Opcode.GainMap
        assertTrue(abs(map.gain(0, 49, 0, 100, 100) - 1.98f) < 0.05f)
        val v = ops[1] as Opcode.FixVignetteRadial
        // Corners (r = 1) get 1 + 0.5, the centre 1.
        assertTrue(abs(v.gain(0.0, 0.0, 101, 101) - 1.5f) < 0.01f)
        assertTrue(abs(v.gain(50.0, 50.0, 101, 101) - 1f) < 0.01f)
    }

    @Test
    fun `an identity warp keeps the picture and a barrel correction samples inwards`() {
        val identity = Opcode.WarpRectilinear(arrayOf(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)), 0.5, 0.5)
        val pixels = IntArray(16 * 16) { (0xFF shl 24) or (it * 997 and 0xFFFFFF) }
        assertTrue(pixels.contentEquals(identity.applyTo(pixels, 16, 16)))
        val barrel = Opcode.WarpRectilinear(arrayOf(doubleArrayOf(1.0, -0.1, 0.0, 0.0, 0.0, 0.0)), 0.5, 0.5)
        val src = DoubleArray(2)
        barrel.source(0.0, 0.0, 0, 101, 101, src)
        assertTrue(src[0] > 0.0 && src[1] > 0.0)
    }
}
