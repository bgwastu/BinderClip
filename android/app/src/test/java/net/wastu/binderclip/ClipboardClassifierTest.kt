package net.wastu.binderclip

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardClassifierTest {
    @Test
    fun imagePayloadMimeTypeValidation() {
        assertTrue("image/png" in ImagePayload.ALLOWED_MIME_TYPES)
        assertTrue("image/jpeg" in ImagePayload.ALLOWED_MIME_TYPES)
        assertTrue("image/webp" in ImagePayload.ALLOWED_MIME_TYPES)
        assertTrue("image/heic" in ImagePayload.ALLOWED_MIME_TYPES)
        assertFalse("text/plain" in ImagePayload.ALLOWED_MIME_TYPES)
        assertFalse("application/pdf" in ImagePayload.ALLOWED_MIME_TYPES)
    }

    @Test
    fun imagePayloadHashComputation() {
        val data = "test image data".toByteArray()
        val payload = ImagePayload(id = "test-123", mimeType = "image/png", data = data)
        assertEquals(64, payload.sha256.length)
        assertEquals("image/png", payload.mimeType)
    }

    @Test
    fun directProtocolTextMessageRoundTrip() {
        val key = ByteArray(32) { 42 }
        val original = JSONObject()
            .put("type", "clipboard")
            .put("id", "msg-1")
            .put("text", "https://example.com/test?param=value")
            .put("timestamp", 1234567890L)

        val sealed = DirectProtocol.seal(original, key)
        val opened = DirectProtocol.open(sealed, key)

        assertEquals("clipboard", opened.getString("type"))
        assertEquals("msg-1", opened.getString("id"))
        assertEquals("https://example.com/test?param=value", opened.getString("text"))
        assertEquals(1234567890L, opened.getLong("timestamp"))
    }
}
