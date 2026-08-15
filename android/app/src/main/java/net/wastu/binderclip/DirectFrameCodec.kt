package net.wastu.binderclip

import java.io.ByteArrayOutputStream

/**
 * Length-prefixed framing shared by TCP and WebRTC transports. Mirrors the
 * macOS FrameCodec so both platforms agree on the 4-byte big-endian prefix.
 */
object DirectFrameCodec {
    private const val MAX_FRAME = DirectProtocol.MAXIMUM_TEXT_BYTES + 65_536

    /** Encode a payload with its 4-byte big-endian length prefix. */
    fun encode(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_FRAME) { "Frame too large" }
        val out = ByteArrayOutputStream()
        out.write((payload.size ushr 24) and 0xFF)
        out.write((payload.size ushr 16) and 0xFF)
        out.write((payload.size ushr 8) and 0xFF)
        out.write(payload.size and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    /**
     * Read one frame from [buffer]. Returns the payload (without the prefix) or
     * null when a complete frame is not yet available. Throws on an oversized or
     * malformed frame.
     */
    fun nextFrame(buffer: ByteArrayOutputStream): ByteArray? {
        val bytes = buffer.toByteArray()
        if (bytes.size < 4) return null
        val length =
            ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
        require(length in 1..MAX_FRAME) { "Invalid frame" }
        if (bytes.size < 4 + length) return null
        val payload = bytes.copyOfRange(4, 4 + length)
        buffer.reset()
        buffer.write(bytes.copyOfRange(4 + length, bytes.size))
        return payload
    }
}
