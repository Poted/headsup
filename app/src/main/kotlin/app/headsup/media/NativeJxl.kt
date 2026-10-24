package app.headsup.media

import app.headsup.core.raw.JxlDecoder
import app.headsup.core.raw.JxlImage
import java.nio.ByteOrder

/**
 * libjxl, the reference JPEG XL decoder (native code): decodes a raw's JPEG XL data with its
 * picture outside the Java heap and on all cores, where the pure-Java decoder runs out of memory
 * on a 24 or 50 MP Samsung Expert RAW.
 */
object NativeJxl : JxlDecoder {
    private val loaded = runCatching { System.loadLibrary("headsupjxl") }.isSuccess

    /** The native decoder when its library could be loaded, else null (the Java one is used). */
    val decoder: JxlDecoder? get() = if (loaded) this else null

    override fun decode(codestream: ByteArray): JxlImage {
        val handle = decodeNative(codestream)
        val (width, height) = size(handle)
        val rgb = pixels(handle).order(ByteOrder.nativeOrder()).asShortBuffer()
        return JxlImage(width, height, rgb) { release(handle) }
    }

    private external fun decodeNative(input: ByteArray): Long

    private external fun size(handle: Long): IntArray

    private external fun pixels(handle: Long): java.nio.ByteBuffer

    private external fun release(handle: Long)
}
