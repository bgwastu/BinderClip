package net.wastu.binderclip.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

class WebRTCQRCodecEncodeTest {
    @Test
    fun `encode mdns candidate writes full 19 bytes`() {
        val candidate = WebRTCQRCodec.Candidate(2, 0, 0, 0, ByteArray(16) { it.toByte() }, 1234)
        val packet = WebRTCQRCodec.Packet(
            fingerprint = ByteArray(32) { 1 },
            ufrag = WebRTCQRCodec.urlSafeBase64Encode(byteArrayOf(1,2,3,4)),
            pwd = WebRTCQRCodec.urlSafeBase64Encode(ByteArray(18) { 9.toByte() }),
            candidates = listOf(candidate),
        )
        val encoded = WebRTCQRCodec.encode(packet)
        // 2 magic/version + 32 fp + 1 ufrag-len + 4 ufrag + 1 pwd-len + 18 pwd + 19 candidate
        assertEquals(2 + 32 + 1 + 4 + 1 + 18 + 19, encoded.size)
    }
}
