package net.wastu.clipboard.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaBundleTest {
    private val maxBytes = 20_971_520
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

    @Test
    fun `bundle accepts exactly the 20 MiB media limit`() {
        val item = MediaBundle.Item("application/octet-stream", ByteArray(maxBytes) { 7 })
        val decoded = MediaBundle.decode(MediaBundle.encode(listOf(item)))!!
        assertEquals(maxBytes, decoded.single().data.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bundle rejects one byte over the 20 MiB media limit`() {
        val item = MediaBundle.Item("application/octet-stream", ByteArray(maxBytes + 1))
        MediaBundle.encode(listOf(item))
    }
}
