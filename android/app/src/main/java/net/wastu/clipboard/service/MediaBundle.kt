package net.wastu.clipboard.service

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Lossless container for multi-resource media such as Live Photos. */
internal object MediaBundle {
    const val MIME_TYPE = "application/x-binderclip-media-bundle"
    private val MAGIC = "BCMEDIA1".toByteArray(Charsets.US_ASCII)

    data class Item(val mimeType: String, val data: ByteArray)

    fun encode(items: List<Item>): ByteArray {
        require(items.isNotEmpty())
        require(items.sumOf { it.data.size.toLong() } <= 20_971_520L)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(MAGIC)
                output.writeInt(items.size)
                items.forEach { item ->
                    val mime = item.mimeType.toByteArray(Charsets.UTF_8)
                    output.writeInt(mime.size)
                    output.writeLong(item.data.size.toLong())
                    output.write(mime)
                    output.write(item.data)
                }
            }
            bytes.toByteArray()
        }
    }

    fun decode(data: ByteArray): List<Item>? = runCatching {
        DataInputStream(ByteArrayInputStream(data)).use { input ->
            val magic = ByteArray(MAGIC.size)
            input.readFully(magic)
            require(magic.contentEquals(MAGIC))
            val count = input.readInt()
            require(count in 1..32)
            buildList {
                var totalBytes = 0L
                repeat(count) {
                    val mimeLength = input.readInt()
                    val dataLength = input.readLong()
                    require(mimeLength in 1..1024 && dataLength in 1..20_971_520)
                    val mime = ByteArray(mimeLength).also(input::readFully).toString(Charsets.UTF_8)
                    val itemData = ByteArray(dataLength.toInt()).also(input::readFully)
                    totalBytes += dataLength
                    require(totalBytes <= 20_971_520L)
                    add(Item(mime, itemData))
                }
            }
        }
    }.getOrNull()
}
