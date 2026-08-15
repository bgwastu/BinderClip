package net.wastu.binderclip

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context

sealed interface LocalClipboardContent {
    data class Text(val value: String) : LocalClipboardContent
    data class Image(val value: ImagePayload) : LocalClipboardContent
    data object Unsupported : LocalClipboardContent
}

/**
 * Classifies only text and supported image media. URI items are never coerced
 * to text: Android would turn a copied file into its display name.
 */
object ClipboardClassifier {
    fun read(context: Context, clipboard: ClipboardManager): LocalClipboardContent {
        val clip = clipboard.primaryClip ?: return LocalClipboardContent.Unsupported
        val item = clip.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return LocalClipboardContent.Unsupported
        if (item.uri != null) {
            return ImageClipboard.readUri(context, item.uri, clip.description?.getMimeType(0))
                ?.let(LocalClipboardContent::Image) ?: LocalClipboardContent.Unsupported
        }
        val hasText = clip.description?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true || item.text != null || item.htmlText != null
        return item.text?.toString()?.takeIf { hasText && it.isNotBlank() }?.let(LocalClipboardContent::Text)
            ?: LocalClipboardContent.Unsupported
    }
}
