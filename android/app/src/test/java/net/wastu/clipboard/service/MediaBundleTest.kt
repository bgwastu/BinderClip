package net.wastu.clipboard.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaBundleTest {
    @Test
    fun `bundle round trip preserves item types and bytes`() {
        val items = listOf(
            MediaBundle.Item("image/heic", byteArrayOf(1, 2, 3)),
            MediaBundle.Item("video/quicktime", byteArrayOf(4, 5, 6, 7))
        )
        val decoded = MediaBundle.decode(MediaBundle.encode(items))!!
        assertEquals(items.map { it.mimeType }, decoded.map { it.mimeType })
        items.zip(decoded).forEach { (expected, actual) -> assertArrayEquals(expected.data, actual.data) }
    }
}
