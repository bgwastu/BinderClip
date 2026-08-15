package net.wastu.binderclip.webrtc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRTCQRCodecTest {
    private fun ipv4(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

    @Test
    fun `round trip IPv4 candidate`() {
        val packet = WebRTCQRCodec.Packet(
            fingerprint = ByteArray(32) { 7 },
            ufrag = WebRTCQRCodec.urlSafeBase64Encode(byteArrayOf(0x12, 0x34, 0x56, 0x78)),
            pwd = WebRTCQRCodec.urlSafeBase64Encode(ByteArray(18) { 0xAB.toByte() }),
            candidates = listOf(
                WebRTCQRCodec.Candidate(0, 0, 0, 0, ipv4(192, 168, 1, 5), 54321),
            ),
        )
        val encoded = WebRTCQRCodec.encode(packet)
        val decoded = WebRTCQRCodec.decode(encoded)
        assertArrayEquals(packet.fingerprint, decoded.fingerprint)
        assertEquals(packet.ufrag, decoded.ufrag)
        assertEquals(packet.pwd, decoded.pwd)
        assertEquals(packet.candidates.size, decoded.candidates.size)
        assertEquals(packet.candidates[0], decoded.candidates[0])
    }

    @Test
    fun `mixed candidates round trip`() {
        val ipv6 = ByteArray(16) { (0x20 + it).toByte() }
        val packet = WebRTCQRCodec.Packet(
            fingerprint = ByteArray(32) { 3 },
            ufrag = WebRTCQRCodec.urlSafeBase64Encode(byteArrayOf(1, 2, 3, 4)),
            pwd = WebRTCQRCodec.urlSafeBase64Encode(ByteArray(18) { 0x42.toByte() }),
            candidates = listOf(
                WebRTCQRCodec.Candidate(0, 0, 0, 0, ipv4(10, 0, 0, 1), 1111),
                WebRTCQRCodec.Candidate(1, 0, 1, 0, ipv6, 2222),
            ),
        )
        val encoded = WebRTCQRCodec.encode(packet)
        assertTrue("payload must stay small", encoded.size < 110)
        val decoded = WebRTCQRCodec.decode(encoded)
        assertEquals(packet, decoded)
    }

    @Test
    fun `rejects bad magic`() {
        val data = ByteArray(2 + 32 + 4 + 18)
        data[0] = 0x99.toByte()
        assertThrows(WebRTCQRCodec.CodecException::class.java) { WebRTCQRCodec.decode(data) }
    }

    @Test
    fun `rejects short payload`() {
        assertThrows(WebRTCQRCodec.CodecException::class.java) { WebRTCQRCodec.decode(byteArrayOf(0x51)) }
    }

    @Test
    fun `url safe base64 round trip`() {
        val data = ByteArray(17) { 0xEF.toByte() }
        val encoded = WebRTCQRCodec.urlSafeBase64Encode(data)
        assertTrue(!encoded.contains("+") && !encoded.contains("/") && !encoded.contains("="))
        assertArrayEquals(data, WebRTCQRCodec.urlSafeBase64Decode(encoded))
    }

    @Test
    fun `typical payload fits QR`() {
        val packet = WebRTCQRCodec.Packet(
            fingerprint = ByteArray(32) { 1 },
            ufrag = WebRTCQRCodec.urlSafeBase64Encode(byteArrayOf(1, 2, 3, 4)),
            pwd = WebRTCQRCodec.urlSafeBase64Encode(ByteArray(18) { 9.toByte() }),
            candidates = listOf(
                WebRTCQRCodec.Candidate(0, 0, 0, 0, ipv4(192, 168, 1, 1), 1),
                WebRTCQRCodec.Candidate(0, 0, 0, 0, ipv4(10, 0, 0, 1), 2),
                WebRTCQRCodec.Candidate(0, 0, 0, 0, ipv4(172, 16, 0, 1), 3),
                WebRTCQRCodec.Candidate(0, 0, 1, 0, ipv4(203, 0, 113, 50), 4),
            ),
        )
        val encoded = WebRTCQRCodec.encode(packet)
        assertTrue("typical payload must fit a small QR", encoded.size <= 100)
    }
}
