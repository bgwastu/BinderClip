package net.wastu.binderclip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SyncProtocolTest {
    @Test
    fun pairingUrlRoundTripUrlSafePsk() {
        val id = "mac-studio-123"
        val name = "Studio Mac"
        val raw = ByteArray(32) { it.toByte() }
        val psk = SyncProtocol.urlSafeBase64(raw)
        assertFalse(psk.contains("+"))
        assertFalse(psk.contains("/"))
        assertFalse(psk.contains("="))
        val endpoints = listOf("192.168.1.100:39421", "100.64.0.1:39421")

        val urlString = SyncProtocol.createPairingUrl(
            deviceId = id,
            deviceName = name,
            psk = psk,
            endpoints = endpoints
        )

        val parsed = SyncProtocol.parsePairingUrl(urlString)
        assertNotNull(parsed)
        assertEquals(SyncProtocol.VERSION, parsed!!.version)
        assertEquals(id, parsed.deviceId)
        assertEquals(name, parsed.deviceName)
        assertEquals(psk, parsed.psk)
        assertEquals(endpoints, parsed.endpoints)
        assertTrue(raw.contentEquals(SyncProtocol.decodeBase64(parsed.psk)))
    }

    @Test
    fun decodeStandardBase64PlusSlash() {
        val raw = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xef.toByte()) + ByteArray(29) { 1 }
        val standard = java.util.Base64.getEncoder().encodeToString(raw)
        val decoded = SyncProtocol.decodeBase64(standard)
        assertNotNull(decoded)
        assertTrue(raw.contentEquals(decoded))
    }

    @Test
    fun parsePreservesPlusInLegacyPsk() {
        val psk = "abc+def/ghi="
        val url = "binderclip://pair?v=2&id=mac&name=Mac&psk=$psk&endpoints=192.168.1.1:39421"
        val parsed = SyncProtocol.parsePairingUrl(url)
        assertEquals(psk, parsed?.psk)
    }

    @Test
    fun imagePackingAndUnpackingRoundTrip() {
        val imageData = ByteArray(1024) { (it % 255).toByte() }
        val id = UUID.randomUUID().toString()
        val imagePayload = ImagePayload(id = id, mimeType = "image/png", data = imageData)

        val packet = SyncProtocol.packImage(imagePayload, "android-device-1")
        assertTrue(packet.size > 1024)

        val unpacked = SyncProtocol.unpackImage(packet)
        assertNotNull(unpacked)
        val (meta, extractedBytes) = unpacked!!

        assertEquals("image/png", meta.mimeType)
        assertEquals("android-device-1", meta.originId)
        assertEquals(id, meta.id)
        assertEquals(1024, meta.size)
        assertTrue(imageData.contentEquals(extractedBytes))
    }

    @Test
    fun sha256HexProducesCorrectHash() {
        val text = "hello binderclip"
        val hash = SyncProtocol.sha256Hex(text)
        assertEquals(64, hash.length)
        assertEquals(hash, SyncProtocol.sha256Hex(text.toByteArray(Charsets.UTF_8)))
    }
}
