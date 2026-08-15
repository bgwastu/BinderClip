package net.wastu.binderclip

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class DirectProtocolTest {
    @Test fun framingAndEncryptionRoundTrip() {
        val key = ByteArray(32) { it.toByte() }
        val message = JSONObject().put("type", "clipboard").put("text", "hello")
        val encrypted = DirectProtocol.seal(message, key)
        val output = ByteArrayOutputStream()
        DirectProtocol.write(DataOutputStream(output), encrypted)
        val decoded = DirectProtocol.read(DataInputStream(ByteArrayInputStream(output.toByteArray())))
        val opened = DirectProtocol.open(decoded, key)
        assertEquals("clipboard", opened.getString("type"))
        assertEquals("hello", opened.getString("text"))
    }

    @Test fun openUrlFramingAndTargetDeviceIdRoundTrip() {
        val key = ByteArray(32) { it.toByte() }
        val message = JSONObject().put("type", "openUrl").put("url", "https://example.com").put("targetDeviceId", "device-xyz")
        val encrypted = DirectProtocol.seal(message, key)
        val output = ByteArrayOutputStream()
        DirectProtocol.write(DataOutputStream(output), encrypted)
        val decoded = DirectProtocol.read(DataInputStream(ByteArrayInputStream(output.toByteArray())))
        val opened = DirectProtocol.open(decoded, key)
        assertEquals("openUrl", opened.getString("type"))
        assertEquals("https://example.com", opened.getString("url"))
        assertEquals("device-xyz", opened.getString("targetDeviceId"))
    }

    @Test fun renameFramingAndPayloadRoundTrip() {
        val key = ByteArray(32) { it.toByte() }
        val message = JSONObject().put("type", "rename").put("id", "device-abc").put("name", "Studio Mac")
        val encrypted = DirectProtocol.seal(message, key)
        val output = ByteArrayOutputStream()
        DirectProtocol.write(DataOutputStream(output), encrypted)
        val decoded = DirectProtocol.read(DataInputStream(ByteArrayInputStream(output.toByteArray())))
        val opened = DirectProtocol.open(decoded, key)
        assertEquals("rename", opened.getString("type"))
        assertEquals("device-abc", opened.getString("id"))
        assertEquals("Studio Mac", opened.getString("name"))
    }

    @Test fun pairingKeyIsStableAndAuthenticationIsConstantTimeComparable() {
        val first = DirectProtocol.pairSessionKey(ByteArray(32) { 7 }, "client", "server")
        val second = DirectProtocol.pairSessionKey(ByteArray(32) { 7 }, "client", "server")
        assertTrue(first.contentEquals(second))
        assertTrue(DirectProtocol.constantTimeEquals(DirectProtocol.hmac(first, "message"), DirectProtocol.hmac(second, "message")))
    }

    @Test fun largestImageChunkFitsAnEncryptedFrame() {
        val key = ByteArray(32) { it.toByte() }
        val imageChunk = ByteArray(ImagePayload.CHUNK_BYTES) { (it % 251).toByte() }
        val message = JSONObject()
            .put("type", "mediaChunk")
            .put("id", "test")
            .put("index", 0)
            .put("data", java.util.Base64.getEncoder().encodeToString(imageChunk))
        val output = ByteArrayOutputStream()
        DirectProtocol.write(DataOutputStream(output), DirectProtocol.seal(message, key))
        assertTrue(output.size() > ImagePayload.CHUNK_BYTES)
    }
}
