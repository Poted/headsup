package app.headsup.color

import android.content.Context
import app.headsup.core.color.MaskSequence
import app.headsup.core.color.PackedFrames
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.GZIPInputStream

/**
 * Masks tracked frame by frame ([MaskSequence]), kept in the app's files and in memory by id, each
 * map packed on its own ([PackedFrames]): a long clip's thousands of maps take a few megabytes and
 * only the ones read are opened.
 */
object MaskSequences {
    /** Marks the packed format (older files are one gzip stream of open maps). */
    private const val PACKED = 0x4D534632

    private val cache = LinkedHashMap<String, MaskSequence?>()

    private fun file(context: Context, id: String) = File(File(context.filesDir, "sequence"), "$id.seq")

    /** Keeps [sequence] (written at once: it took long to make) and returns its id. */
    fun create(context: Context, sequence: MaskSequence): String {
        val id = UUID.randomUUID().toString()
        val target = file(context.applicationContext, id)
        target.parentFile?.mkdirs()
        val frames = sequence.frames
        val packed = List(frames.size) { i -> if (frames is PackedFrames) frames.packedAt(i) else PackedFrames.pack(frames[i]) }
        DataOutputStream(target.outputStream().buffered()).use { out ->
            out.writeInt(PACKED)
            out.writeInt(sequence.width)
            out.writeInt(sequence.height)
            out.writeInt(sequence.timesMs.size)
            for (t in sequence.timesMs) out.writeLong(t)
            for (p in packed) {
                out.writeInt(p.size)
                out.write(p)
            }
        }
        val kept = MaskSequence(sequence.width, sequence.height, sequence.timesMs, PackedFrames(packed, sequence.width * sequence.height))
        synchronized(cache) { remember(id, kept) }
        return id
    }

    /** The sequence kept as [id], or null when it is gone. */
    fun get(context: Context, id: String): MaskSequence? {
        synchronized(cache) { if (id in cache) return cache[id] }
        val f = file(context, id)
        val seq = runCatching {
            val packedFormat = DataInputStream(f.inputStream()).use { it.readInt() == PACKED }
            if (packedFormat) {
                DataInputStream(f.inputStream().buffered()).use { input ->
                    input.readInt()
                    val w = input.readInt()
                    val h = input.readInt()
                    val n = input.readInt()
                    val times = LongArray(n) { input.readLong() }
                    val packed = List(n) { ByteArray(input.readInt()).also { input.readFully(it) } }
                    MaskSequence(w, h, times, PackedFrames(packed, w * h))
                }
            } else {
                // Older: open maps in one gzip stream; packed as they are read.
                DataInputStream(GZIPInputStream(f.inputStream().buffered())).use { input ->
                    val w = input.readInt()
                    val h = input.readInt()
                    val n = input.readInt()
                    val times = LongArray(n) { input.readLong() }
                    val map = ByteArray(w * h)
                    val packed = List(n) { input.readFully(map); PackedFrames.pack(map) }
                    MaskSequence(w, h, times, PackedFrames(packed, w * h))
                }
            }
        }.getOrNull()
        synchronized(cache) { remember(id, seq) }
        return seq
    }

    private fun remember(id: String, seq: MaskSequence?) {
        cache[id] = seq
        while (cache.size > 8) cache.remove(cache.keys.first())
    }
}
