package net.wastu.binderclip

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
}
