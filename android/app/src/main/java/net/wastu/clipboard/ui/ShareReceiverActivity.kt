package net.wastu.clipboard.ui

// Handles Android share-sheet intents to send shared text or media to the connected Mac.

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import net.wastu.clipboard.R
import net.wastu.clipboard.service.ClipboardContentForwarder
import net.wastu.clipboard.service.MediaBundle
import net.wastu.clipboard.service.ClipboardService
import java.io.File

open class ShareReceiverActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_OPEN_ON_DEVICE = "open_on_device"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("text/") == true) handleTextShare() else handleMediaShare()
            }
            Intent.ACTION_SEND_MULTIPLE -> handleMediaShare()
        }

        finish()
    }

    private fun handleTextShare() {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!text.isNullOrBlank()) {
            if (isWebUrl(text)) {
                val serviceIntent = Intent(this, ClipboardService::class.java).apply {
                    action = ClipboardService.ACTION_OPEN_URL
                    putExtra(ClipboardService.EXTRA_URL, text)
                }
                runCatching { ContextCompat.startForegroundService(this, serviceIntent) }
                    .onFailure {
                        Toast.makeText(this, "Could not start BinderClip", Toast.LENGTH_SHORT).show()
                        return
                    }
                showOpenedToast()
                return
            }
            val serviceIntent = Intent(this, ClipboardService::class.java).apply {
                action = ClipboardService.ACTION_PUSH_TEXT
                putExtra(ClipboardService.EXTRA_TEXT, text)
            }
            runCatching {
                ContextCompat.startForegroundService(this, serviceIntent)
            }.onFailure {
                Toast.makeText(this, "Could not start BinderClip", Toast.LENGTH_SHORT).show()
                return
            }
            showSentToast()
        }
    }

    private fun isWebUrl(value: String): Boolean {
        val uri = Uri.parse(value.trim())
        return (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
    }

    private fun showOpenedToast() {
        val deviceName = getSharedPreferences(ClipboardService.PREFS_NAME, MODE_PRIVATE)
            .getString(ClipboardService.KEY_CONNECTED_DEVICE, null) ?: "Mac"
        Toast.makeText(this, "Opening in $deviceName", Toast.LENGTH_SHORT).show()
    }

    private fun handleMediaShare() {
        val uris = buildList {
            intent.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add)
            }
            if (isEmpty()) {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                uri?.let(::add)
            }
        }

        if (uris.isEmpty()) {
            Toast.makeText(this, "No media to send", Toast.LENGTH_SHORT).show()
            return
        }

        val maxSize = ClipboardContentForwarder.MAX_MEDIA_BYTES
        val items = uris.mapNotNull { uri ->
            val mimeType = contentResolver.getType(uri) ?: intent.type ?: "application/octet-stream"
            runCatching {
                contentResolver.openInputStream(uri)?.use { MediaBundle.Item(mimeType, it.readBytes()) }
            }.getOrNull()
        }
        val mediaData = if (items.size == 1) items[0].data else runCatching { MediaBundle.encode(items) }.getOrNull()
        if (mediaData == null || mediaData.isEmpty() || mediaData.size > maxSize) {
            Toast.makeText(this, "Media too large to send (max 20 MB)", Toast.LENGTH_SHORT).show()
            return
        }

        val mimeType = if (items.size == 1) items[0].mimeType else MediaBundle.MIME_TYPE
        val extension = mimeType.substringAfterLast('/').take(8).ifBlank { "bin" }
        val cacheDir = File(cacheDir, "shared_images")
        cacheDir.mkdirs()
        val cacheFile = File(cacheDir, "share_media_${System.currentTimeMillis()}.$extension")

        try {
            cacheFile.writeBytes(mediaData)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not read media", Toast.LENGTH_SHORT).show()
            return
        }

        // Double-check actual file size after copy
        if (cacheFile.length() > maxSize) {
            cacheFile.delete()
            Toast.makeText(this, "Media too large to send (max 20 MB)", Toast.LENGTH_SHORT).show()
            return
        }

        val serviceIntent = Intent(this, ClipboardService::class.java).apply {
            action = ClipboardService.ACTION_PUSH_IMAGE
            putExtra(ClipboardService.EXTRA_IMAGE_PATH, cacheFile.absolutePath)
            putExtra(ClipboardService.EXTRA_MIME_TYPE, mimeType)
            putExtra(ClipboardService.EXTRA_IMAGE_NAME, uris.firstOrNull()?.let { contentResolver.query(it, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            } } ?: "Shared media")
        }
        runCatching {
            ContextCompat.startForegroundService(this, serviceIntent)
        }.onFailure {
            cacheFile.delete()
            Toast.makeText(this, "Could not start BinderClip", Toast.LENGTH_SHORT).show()
            return
        }
        showSentToast()
    }

    private fun showSentToast() {
        val deviceName = getSharedPreferences(ClipboardService.PREFS_NAME, MODE_PRIVATE)
            .getString(ClipboardService.KEY_CONNECTED_DEVICE, null)
        val message = if (deviceName != null)
            getString(R.string.toast_sent_to, deviceName)
        else
            getString(R.string.toast_sent)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

/** Dedicated direct-share target because Android may drop custom shortcut extras. */
class OpenUrlShareReceiverActivity : ShareReceiverActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        intent.putExtra(EXTRA_OPEN_ON_DEVICE, true)
        super.onCreate(savedInstanceState)
    }
}
