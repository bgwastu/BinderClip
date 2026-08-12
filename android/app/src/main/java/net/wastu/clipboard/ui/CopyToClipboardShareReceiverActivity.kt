package net.wastu.clipboard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Explicit local-only share target for text and media. */
class CopyToClipboardShareReceiverActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val stream = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        if (!text.isNullOrEmpty()) {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("clipboard", text))
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        } else if (stream != null) {
            val mimeType = intent.type ?: contentResolver.getType(stream) ?: "application/octet-stream"
            val clip = ClipData(
                android.content.ClipDescription("clipboard", arrayOf(mimeType)),
                ClipData.Item(stream)
            )
            getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
