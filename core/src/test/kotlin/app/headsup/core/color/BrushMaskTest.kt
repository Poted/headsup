package app.headsup.core.color

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrushMaskTest {
    private fun at(map: FloatArray, w: Int, x: Int, y: Int) = map[y * w + x]

    @Test
    fun `a stroke covers its path and fades at its edge`() {
        val stroke = BrushStroke(listOf(0.2f, 0.5f, 0.8f, 0.5f), radius = 0.1f)
        val map = BrushMask.rasterize(listOf(stroke), 100, 100, softness = 0.5f)
        assertEquals(1f, at(map, 100, 50, 50), 1e-4f)
        assertTrue(at(map, 100, 50, 58) in 0.01f..0.99f)
        assertEquals(0f, at(map, 100, 50, 80))
        assertEquals(0f, at(map, 100, 5, 50))
    }

    @Test
    fun `flow builds up over strokes and erasing takes it away`() {
        val once = BrushStroke(listOf(0.5f, 0.5f), radius = 0.2f, flow = 0.5f)
        val one = BrushMask.rasterize(listOf(once), 50, 50, softness = 0f)
        val two = BrushMask.rasterize(listOf(once, once), 50, 50, softness = 0f)
        assertEquals(0.5f, at(one, 50, 25, 25), 1e-4f)
        assertEquals(0.75f, at(two, 50, 25, 25), 1e-4f)
        val erased = BrushMask.rasterize(listOf(once.copy(flow = 1f), once.copy(flow = 1f, erase = true)), 50, 50, softness = 0f)
        assertEquals(0f, at(erased, 50, 25, 25), 1e-4f)
    }

    @Test
    fun `painting on a moved mask lands where it shows`() {
        val base = ColourMask(MaskKind.BRUSH)
        assertEquals(0.3f to 0.7f, BrushMask.unmove(0.3f, 0.7f, base, 16f / 9f))
        // Moved right by a tenth: a point there was painted a tenth to the left.
        val moved = base.copy(centerX = 0.6f)
        val (x, y) = BrushMask.unmove(0.6f, 0.5f, moved, 16f / 9f)
        assertTrue(abs(x - 0.5f) < 1e-5f && abs(y - 0.5f) < 1e-5f)
        // Doubled in size: a point 0.2 below the centre was painted 0.1 below.
        val (_, y2) = BrushMask.unmove(0.5f, 0.7f, base.copy(sizeX = 2f, sizeY = 2f), 1f)
        assertEquals(0.6f, y2, 1e-5f)
    }

    @Test
    fun `softness widens the fading edge`() {
        val stroke = BrushStroke(listOf(0.5f, 0.5f), radius = 0.3f)
        val hard = BrushMask.rasterize(listOf(stroke), 100, 100, softness = 0f)
        val soft = BrushMask.rasterize(listOf(stroke), 100, 100, softness = 1f)
        assertTrue(at(soft, 100, 70, 50) < at(hard, 100, 70, 50) - 0.3f)
    }

    @Test
    fun `an edge-finding stroke stops where the colour changes`() {
        // Left half sky blue, right half a dark tree.
        val w = 100
        val h = 100
        val rgb = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 3
            if (x < 50) { rgb[i] = 0.5f; rgb[i + 1] = 0.7f; rgb[i + 2] = 0.95f } else { rgb[i] = 0.1f; rgb[i + 1] = 0.25f; rgb[i + 2] = 0.1f }
        }
        val ref = BrushReference(w, h, rgb, BrushMask.BASE)
        val stroke = BrushStroke(listOf(0.4f, 0.5f), radius = 0.3f, reference = "sky")
        val map = BrushMask.rasterize(listOf(stroke), w, h, softness = 0f, references = mapOf("sky" to ref))
        assertTrue(at(map, w, 45, 50) > 0.9f)
        assertTrue(at(map, w, 55, 50) < 0.05f)
        // Without edge finding the brush goes over the tree.
        val plain = BrushMask.rasterize(listOf(stroke.copy(reference = null)), w, h, softness = 0f)
        assertTrue(at(plain, w, 55, 50) > 0.9f)
    }

    @Test
    fun `place undoes unmove`() {
        val now = MaskKey(0L, 0.6f, 0.4f, 25f, 1.3f, 1.3f)
        val (ux, uy) = BrushMask.unmove(0.7f, 0.2f, now, 1.5f)
        val (x, y) = BrushMask.place(ux, uy, now, 1.5f)
        assertEquals(0.7f, x, 1e-4f)
        assertEquals(0.2f, y, 1e-4f)
    }

    @Test
    fun `an outline around an object selects the object, not the background inside it`() {
        // A dark stick on a light, slightly noisy table.
        val w = 120
        val h = 120
        val rnd = java.util.Random(1)
        val rgb = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 3
            val stick = x in 50..62 && y in 20..100
            val n = (rnd.nextFloat() - 0.5f) * 0.08f
            if (stick) { rgb[i] = 0.08f + n; rgb[i + 1] = 0.08f + n; rgb[i + 2] = 0.09f + n } else { rgb[i] = 0.85f + n; rgb[i + 1] = 0.78f + n; rgb[i + 2] = 0.6f + n }
        }
        val ref = BrushReference(w, h, rgb, BrushMask.BASE)
        // A loose outline, well around the stick, left open at the top.
        val outline = listOf(40f, 12f, 38f, 60f, 40f, 108f, 72f, 110f, 74f, 60f, 72f, 14f).map { it / 120f }
        val stroke = BrushStroke(outline, radius = 0.05f, reference = "r", lasso = true)
        val map = BrushMask.rasterize(listOf(stroke), w, h, softness = 0f, references = mapOf("r" to ref))
        assertTrue(at(map, w, 56, 60) > 0.9f, "stick")
        assertTrue(at(map, w, 56, 25) > 0.9f, "stick top")
        assertTrue(at(map, w, 44, 60) < 0.1f, "table inside the outline")
        assertTrue(at(map, w, 68, 90) < 0.1f, "table inside the outline")
        assertTrue(at(map, w, 20, 60) < 0.01f, "outside")
    }

    @Test
    fun `a line splits the picture and follows a tree against the sky`() {
        // Sky above a horizon at y = 60, a dark tree crown standing up into the sky at x 40..60, y 30..60.
        val w = 120
        val h = 120
        val rnd = java.util.Random(2)
        val rgb = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 3
            val n = (rnd.nextFloat() - 0.5f) * 0.06f
            val tree = x in 40..60 && y in 30..60
            when {
                tree || y >= 60 -> { rgb[i] = 0.12f + n; rgb[i + 1] = 0.25f + n; rgb[i + 2] = 0.1f + n }
                else -> { rgb[i] = 0.55f + n; rgb[i + 1] = 0.72f + n; rgb[i + 2] = 0.95f + n }
            }
        }
        val ref = BrushReference(w, h, rgb, BrushMask.BASE)
        // A rough line along the horizon that goes up round the tree.
        val line = listOf(5f, 62f, 30f, 60f, 36f, 40f, 42f, 26f, 58f, 26f, 64f, 40f, 68f, 58f, 115f, 61f).map { it / 120f }
        assertTrue(!BrushMask.isLoop(line.chunked(2).map { it[0] to it[1] }))
        val stroke = BrushStroke(line, radius = 0.05f, reference = "r", lasso = true)
        val map = BrushMask.rasterize(listOf(stroke), w, h, softness = 0f, references = mapOf("r" to ref))
        assertTrue(at(map, w, 10, 20) > 0.9f, "sky")
        assertTrue(at(map, w, 100, 30) > 0.9f, "sky")
        assertTrue(at(map, w, 50, 45) < 0.1f, "tree")
        assertTrue(at(map, w, 30, 90) < 0.05f, "ground")
        assertTrue(at(map, w, 66, 50) > 0.6f, "sky beside the tree, inside the drawn bulge")
    }

    @Test
    fun `a found object covers what the finder was sure of`() {
        val w = 50
        val h = 50
        val rgb = FloatArray(w * h * 3) { i -> val p = i / 3; if (p % w in 10..20 && p / w in 30..40) 0.95f else 0.05f }
        val ref = BrushReference(w, h, rgb, BrushMask.BASE)
        val stroke = BrushStroke(listOf(0.3f, 0.7f), radius = 0.01f, reference = "o", segment = true)
        val map = BrushMask.rasterize(listOf(stroke), w, h, references = mapOf("o" to ref))
        assertTrue(at(map, w, 15, 35) > 0.95f)
        assertTrue(at(map, w, 35, 10) < 0.05f)
    }
}
