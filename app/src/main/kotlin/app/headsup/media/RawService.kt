package app.headsup.media

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.SharedMemory
import app.headsup.core.raw.Allocator
import app.headsup.core.raw.Dng
import app.headsup.core.raw.RawImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reads raw files in a process of its own (":raw"), with its own memory: a large JPEG XL raw
 * needs hundreds of MB while it is decoded, and if that is too much, only this process ends,
 * not the app. The image comes back in shared memory, outside both Java heaps.
 */
class RawService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("raw-reader").apply { start() }
        messenger = Messenger(Handler(worker.looper) { msg -> handle(msg); true })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }

    private fun handle(msg: Message) {
        val request = msg.data
        val reply = Message.obtain(null, MSG_RESULT, msg.arg1, 0)
        val regions = ArrayList<Pair<SharedMemory, ByteBuffer>>()
        try {
            val uri = Uri.parse(request.getString(KEY_URI))
            val alloc = Allocator { bytes ->
                val shared = SharedMemory.create("raw", Math.toIntExact(bytes))
                shared.mapReadWrite().also { regions += shared to it }
            }
            val raw = RawPhotos.withSource(this, uri) { Dng.read(it, request.getInt(KEY_MAX_SIDE), request.getBoolean(KEY_MOSAIC), alloc, NativeJxl.decoder) }
            // The image's data is the last region of its size (earlier ones were working sums).
            val bytes = raw.data.capacity().toLong() * 2
            val region = regions.last { it.first.size.toLong() == bytes }.first
            reply.data = Bundle().apply {
                putParcelable(KEY_DATA, region)
                putInt("width", raw.width)
                putInt("height", raw.height)
                putIntArray("cfa", raw.cfa)
                putFloatArray("black", raw.black)
                putFloat("white", raw.white)
                putDoubleArray("matrix", raw.colorMatrix)
                putDoubleArray("neutral", raw.asShotNeutral)
                putDouble("baseline", raw.baselineExposure)
                putString("make", raw.make)
                putString("model", raw.model)
                putInt("orientation", raw.orientation)
                putInt("channels", raw.channels)
                putByteArray("opcodes3", raw.opcodes3)
                putDoubleArray("balancedToSrgb", raw.balancedToSrgb)
                putString("info", raw.info)
                putFloatArray("toneCurve", raw.toneCurve)
            }
        } catch (e: Throwable) {
            reply.data = Bundle().apply {
                putString(KEY_ERROR, if (e is OutOfMemoryError) "Not enough memory to develop this file" else e.message ?: e.javaClass.simpleName)
            }
        }
        runCatching { msg.replyTo.send(reply) }
        // Sent (the receiver has its own handle on the memory): this side lets go.
        regions.forEach { (shared, buffer) -> SharedMemory.unmap(buffer); shared.close() }
    }

    companion object {
        const val MSG_READ = 1
        const val MSG_RESULT = 2
        const val KEY_URI = "uri"
        const val KEY_MAX_SIDE = "maxSide"
        const val KEY_MOSAIC = "mosaic"
        const val KEY_DATA = "data"
        const val KEY_ERROR = "error"
    }
}

/** A raw image read by [RawService]; its data stays mapped until [release]. */
class RemoteRaw(val image: RawImage, private val memory: SharedMemory, private val mapped: ByteBuffer) {
    fun release() {
        SharedMemory.unmap(mapped)
        memory.close()
    }
}

/** The app's side of [RawService]: requests block their (background) thread until the answer comes. */
object RawReader {
    private val lock = Any()
    private var service: Messenger? = null
    private var bound: CountDownLatch? = null
    private val nextId = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val replies by lazy {
        val thread = HandlerThread("raw-replies").apply { start() }
        Messenger(Handler(thread.looper) { msg ->
            pending.remove(msg.arg1)?.let { it.result = msg.data; it.done.countDown() }
            true
        })
    }

    private class Pending {
        val done = CountDownLatch(1)
        @Volatile var result: Bundle? = null
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            synchronized(lock) { service = Messenger(binder) }
            bound?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The reading process ended (e.g. out of memory): waiting requests fail, the next one starts it again.
            synchronized(lock) { service = null }
            pending.values.forEach { it.done.countDown() }
            pending.clear()
        }
    }

    private fun messenger(context: Context): Messenger {
        synchronized(lock) { service?.let { return it } }
        val latch = synchronized(lock) {
            bound ?: CountDownLatch(1).also {
                bound = it
                context.applicationContext.bindService(Intent(context, RawService::class.java), connection, Context.BIND_AUTO_CREATE)
            }
        }
        latch.await(20, TimeUnit.SECONDS)
        synchronized(lock) {
            bound = null
            return service ?: error("The raw reader did not start")
        }
    }

    /** Reads [uri] as [Dng.read] does, in the reading process. */
    fun read(context: Context, uri: Uri, maxSide: Int, mosaic: Boolean): RemoteRaw {
        val id = nextId.incrementAndGet()
        val wait = Pending()
        pending[id] = wait
        val msg = Message.obtain(null, RawService.MSG_READ, id, 0).apply {
            data = Bundle().apply {
                putString(RawService.KEY_URI, uri.toString())
                putInt(RawService.KEY_MAX_SIDE, maxSide)
                putBoolean(RawService.KEY_MOSAIC, mosaic)
            }
            replyTo = replies
        }
        messenger(context).send(msg)
        wait.done.await(3, TimeUnit.MINUTES)
        pending.remove(id)
        val data = wait.result ?: error("Not enough memory to develop this file (the raw reader stopped)")
        data.classLoader = SharedMemory::class.java.classLoader
        data.getString(RawService.KEY_ERROR)?.let { error(it) }
        @Suppress("DEPRECATION")
        val memory = data.getParcelable<SharedMemory>(RawService.KEY_DATA) ?: error("No image came back")
        val mapped = memory.mapReadOnly()
        val image = RawImage(
            data.getInt("width"), data.getInt("height"),
            mapped.order(ByteOrder.nativeOrder()).asShortBuffer(),
            data.getIntArray("cfa")!!, data.getFloatArray("black")!!, data.getFloat("white"),
            data.getDoubleArray("matrix")!!, data.getDoubleArray("neutral")!!, data.getDouble("baseline"),
            data.getString("make").orEmpty(), data.getString("model").orEmpty(),
            data.getInt("orientation"), data.getInt("channels"), data.getByteArray("opcodes3"),
            data.getDoubleArray("balancedToSrgb"), data.getString("info").orEmpty(), data.getFloatArray("toneCurve"),
        )
        return RemoteRaw(image, memory, mapped)
    }
}
