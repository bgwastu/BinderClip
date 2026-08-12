package net.wastu.clipboard.service

import android.content.ClipDescription
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File

/** Dispatches the current clipboard item through the same service paths as ShareReceiverActivity. */
internal object ClipboardContentForwarder {
    const val MAX_MEDIA_BYTES = 20_971_520L

    fun forward(context: Context, clip: ClipData, description: ClipDescription?, tag: String): Boolean {
        val mediaMimeType = description
            ?.takeIf { it.mimeTypeCount > 0 }
            ?.let { clipDescription ->
                (0 until clipDescription.mimeTypeCount)
                    .map { clipDescription.getMimeType(it) }
                    .firstOrNull { it != "text/plain" && it != "text/*" }
            }

        if (mediaMimeType != null) {
            val items = buildList {
                for (index in 0 until clip.itemCount) {
                    val uri = clip.getItemAt(index).uri ?: continue
                    val mimeType = context.contentResolver.getType(uri) ?: mediaMimeType
                    val data = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull() ?: continue
                    if (data.isNotEmpty()) add(MediaBundle.Item(mimeType, data))
                }
            }
            if (items.isEmpty()) return true
            return forwardMedia(context, items, tag)
        }

        val item = clip.getItemAt(0)
        val text = item.coerceToText(context)?.toString()
        if (text.isNullOrBlank()) {
            Log.d(tag, "Clipboard text empty")
            return false
        }

        val pushIntent = Intent(context, ClipboardService::class.java).apply {
            action = ClipboardService.ACTION_PUSH_TEXT
            putExtra(ClipboardService.EXTRA_TEXT, text)
        }
        ContextCompat.startForegroundService(context, pushIntent)
        Log.d(tag, "Forwarded clipboard text to service (${text.length} chars)")
        return true
    }

    private fun forwardMedia(context: Context, items: List<MediaBundle.Item>, tag: String): Boolean {
        val cacheDir = File(context.cacheDir, "shared_images").apply { mkdirs() }
        val mimeType = if (items.size == 1) items[0].mimeType else MediaBundle.MIME_TYPE
        val data = runCatching {
            if (items.size == 1) items[0].data else MediaBundle.encode(items)
        }.getOrNull() ?: return true
        val extension = mimeType.substringAfterLast('/').take(8).ifBlank { "bin" }
        val cacheFile = File(cacheDir, "clipboard_media_${System.currentTimeMillis()}.$extension")

        return try {
            if (data.isEmpty() || data.size > MAX_MEDIA_BYTES) {
                Log.w(tag, "Clipboard media too large or empty: ${data.size} bytes")
                return true
            }
            cacheFile.writeBytes(data)

            val pushIntent = Intent(context, ClipboardService::class.java).apply {
                action = ClipboardService.ACTION_PUSH_IMAGE
                putExtra(ClipboardService.EXTRA_IMAGE_PATH, cacheFile.absolutePath)
                putExtra(ClipboardService.EXTRA_MIME_TYPE, mimeType)
            }
            ContextCompat.startForegroundService(context, pushIntent)
            Log.d(tag, "Forwarded clipboard media to service (${cacheFile.length()} bytes, $mimeType)")
            true
        } catch (error: Exception) {
            cacheFile.delete()
            Log.w(tag, "Could not forward clipboard image", error)
            true
        }
    }
}
