package app.headsup.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import app.headsup.core.planner.Horizon
import app.headsup.core.planner.HorizonProfile
import app.headsup.core.planner.Terrain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * The land's heights for the planner: terrain tiles (Terrain Tiles on AWS) fetched when needed and
 * kept on the phone, so a place looked at once works later with no signal. Gives the ground's
 * height at a point and the skyline (hills and mountains) seen from a place.
 */
internal class TerrainStore(private val cacheDir: File, private val download: suspend (String) -> ByteArray?) {
    // Heights of the tiles in use, whole metres (a skyline needs about 70 tiles, 128 kB each).
    private val tiles = object : LruCache<String, ShortArray>(140) {}

    /** A tile's heights (256 × 256, row by row), or null when it cannot be had. */
    suspend fun tile(z: Int, x: Int, y: Int): ShortArray? = withContext(Dispatchers.IO) {
        val key = "$z-$x-$y"
        tiles.get(key)?.let { return@withContext it }
        val file = File(File(cacheDir, "terrain_tiles").apply { mkdirs() }, "$key.png")
        val url = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/$z/$x/$y.png"
        val bytes = (if (file.exists()) runCatching { file.readBytes() }.getOrNull() else null)
            ?: download(url)?.also { b -> runCatching { file.writeBytes(b) } }
            ?: return@withContext null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
        if (bmp.width != 256 || bmp.height != 256) { bmp.recycle(); return@withContext null }
        val px = IntArray(256 * 256)
        bmp.getPixels(px, 0, 256, 0, 0, 256, 256)
        bmp.recycle()
        val h = ShortArray(px.size) { i ->
            val c = px[i]
            Terrain.decode((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF).roundToInt().coerceIn(-32000, 32000).toShort()
        }
        tiles.put(key, h)
        h
    }

    /** The ground's height from a tile already loaded, or null. */
    fun heightLoaded(lat: Double, lon: Double, z: Int): Double? {
        val t = Terrain.tilePixel(lat, lon, z)
        val a = tiles.get("$z-${t.x}-${t.y}") ?: return null
        return Terrain.bilinear(t.px, t.py) { x, y -> a[y * 256 + x].toDouble() }
    }

    /** The ground's height at a point (fetching its tile if needed), or null. */
    suspend fun height(lat: Double, lon: Double, z: Int = Terrain.ZOOM): Double? {
        val t = Terrain.tilePixel(lat, lon, z)
        tile(z, t.x, t.y) ?: return null
        return heightLoaded(lat, lon, z)
    }

    /** The skyline seen from an eye [eye] metres above the sea at a place; null when no land could be read. */
    suspend fun skyline(lat: Double, lon: Double, eye: Double, onProgress: (Int, Int) -> Unit): HorizonProfile? {
        val need = withContext(Dispatchers.Default) { Horizon.tilesNeeded(lat, lon) }
        var done = 0
        var missing = 0
        for ((z, x, y) in need) {
            if (tile(z, x, y) == null) missing++
            done++
            onProgress(done, need.size)
        }
        if (missing == need.size) return null
        return withContext(Dispatchers.Default) { Horizon.profile(lat, lon, eye, { la, lo, z -> heightLoaded(la, lo, z) }) }
    }
}
