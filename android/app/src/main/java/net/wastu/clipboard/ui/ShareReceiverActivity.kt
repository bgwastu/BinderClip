package net.wastu.clipboard.ui

// Handles Android share-sheet intents to send shared text or images to the connected Mac.

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import net.wastu.clipboard.R
import net.wastu.clipboard.service.ClipboardService
import java.io.File

open class ShareReceiverActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_OPEN_ON_DEVICE = "open_on_device"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == Intent.ACTION_SEND) {
            when {
                intent.type?.startsWith("image/") == true -> handleImageShare()
                intent.type?.startsWith("text/") == true -> handleTextShare()
            }
        }

        finish()
    }

    private fun handleTextShare() {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!text.isNullOrBlank()) {
            if (intent.getBooleanExtra(EXTRA_OPEN_ON_DEVICE, false)) {
                if (!isWebUrl(text)) {
                    Toast.makeText(this, "Only web links can be opened on the Mac", Toast.LENGTH_SHORT).show()
                    return
                }
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

    private fun handleImageShare() {
        val imageUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

        if (imageUri == null) {
            Toast.makeText(this, "No image to send", Toast.LENGTH_SHORT).show()
            return
        }

        // Check size via OpenableColumns
        val maxSize = 10_485_760L // 10 MB
        val size = getUriSize(imageUri)
        if (size != null && size > maxSize) {
            Toast.makeText(this, "Image too large to send (max 10 MB)", Toast.LENGTH_SHORT).show()
            return
        }

        // Copy to cache file
        val mimeType = intent.type ?: contentResolver.getType(imageUri) ?: "image/png"
        val extension = when {
            mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
            else -> "png"
        }
        val cacheDir = File(cacheDir, "shared_images")
        cacheDir.mkdirs()
        val cacheFile = File(cacheDir, "share_image_${System.currentTimeMillis()}.$extension")

        try {
            contentResolver.openInputStream(imageUri)?.use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                Toast.makeText(this, "Could not read image", Toast.LENGTH_SHORT).show()
                return
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not read image", Toast.LENGTH_SHORT).show()
            return
        }

        // Double-check actual file size after copy
        if (cacheFile.length() > maxSize) {
            cacheFile.delete()
            Toast.makeText(this, "Image too large to send (max 10 MB)", Toast.LENGTH_SHORT).show()
            return
        }

        val serviceIntent = Intent(this, ClipboardService::class.java).apply {
            action = ClipboardService.ACTION_PUSH_IMAGE
            putExtra(ClipboardService.EXTRA_IMAGE_PATH, cacheFile.absolutePath)
            putExtra(ClipboardService.EXTRA_MIME_TYPE, mimeType)
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

    private fun getUriSize(uri: Uri): Long? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
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
