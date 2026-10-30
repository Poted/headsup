package app.headsup.color

import android.content.Context
import app.headsup.core.color.MaskSequence
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Masks tracked frame by frame ([MaskSequence]), kept in the app's files (compressed) and in memory, by id. */
object MaskSequences {
    private val cache = LinkedHashMap<String, MaskSequence?>()

    private fun file(context: Context, id: String) = File(File(context.filesDir, "sequence"), "$id.seq")

    /** Keeps [sequence] (written at once: it took long to make) and returns its id. */
    fun create(context: Context, sequence: MaskSequence): String {
        val id = UUID.randomUUID().toString()
        val target = file(context.applicationContext, id)
        target.parentFile?.mkdirs()
        DataOutputStream(GZIPOutputStream(target.outputStream().buffered())).use { out ->
            out.writeInt(sequence.width)
            out.writeInt(sequence.height)
            out.writeInt(sequence.timesMs.size)
            for (t in sequence.timesMs) out.writeLong(t)
            for (f in sequence.frames) out.write(f)
        }
        synchronized(cache) { remember(id, sequence) }
        return id
    }

    /** The sequence kept as [id], or null when it is gone. */
    fun get(context: Context, id: String): MaskSequence? {
        synchronized(cache) { if (id in cache) return cache[id] }
        val seq = runCatching {
            DataInputStream(GZIPInputStream(file(context, id).inputStream().buffered())).use { input ->
                val w = input.readInt()
                val h = input.readInt()
                val n = input.readInt()
                val times = LongArray(n) { input.readLong() }
                val frames = List(n) { ByteArray(w * h).also { input.readFully(it) } }
                MaskSequence(w, h, times, frames)
            }
        }.getOrNull()
        synchronized(cache) { remember(id, seq) }
        return seq
    }

    private fun remember(id: String, seq: MaskSequence?) {
        cache[id] = seq
        // A few at a time: each can hold many frames.
        while (cache.size > 4) cache.remove(cache.keys.first())
    }
}
