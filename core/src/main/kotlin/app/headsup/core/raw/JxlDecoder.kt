package app.headsup.core.raw

/**
 * Decodes a JPEG XL codestream (a DNG tile or strip) to linear light. The app gives a native one
 * (libjxl); without it the pure-Java decoder is used, which needs far more memory.
 */
fun interface JxlDecoder {
    fun decode(codestream: ByteArray): JxlImage
}

/** A decoded picture: [rgb] interleaved red, green, blue, linear, 0..65535 (unsigned in shorts). */
class JxlImage(
    val width: Int,
    val height: Int,
    val rgb: java.nio.ShortBuffer,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    override fun close() = onClose()
}
