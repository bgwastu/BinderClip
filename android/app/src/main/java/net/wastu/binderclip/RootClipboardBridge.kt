package net.wastu.binderclip

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Optional root integration. It never reads a clipboard through a forged system identity.
 * Instead, after the owner approves BinderClip in KernelSU, it grants BinderClip the Android
 * background clipboard capability and verifies that grant before automatic sync is enabled.
 */
object RootClipboardBridge {
    private const val BACKGROUND_CLIPBOARD_PERMISSION = "android.permission.READ_CLIPBOARD_IN_BACKGROUND"
    @Volatile private var ignoredUnreadableImage: String? = null
    sealed interface Clip {
        val fingerprint: String

        data class Text(val value: String) : Clip {
            override val fingerprint = "text:$value"
        }

        data class Image(val value: ImagePayload) : Clip {
            override val fingerprint = "image:${value.sha256}"
        }

        /** A private provider can expose an image only to its share-sheet recipient. */
        data class UnreadableImage(override val fingerprint: String) : Clip
    }

    fun isAvailable(): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
        val output = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
        process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0 &&
            output.lines().any { it.trim() == "0" || it.contains("uid=0(") }
    }.getOrDefault(false)

    fun enableBackgroundAccess(context: Context): Boolean {
        if (hasBackgroundAccess(context)) {
            val pkg = shellQuote(context.packageName)
            runRootCommand("cmd appops set $pkg READ_CLIPBOARD allow")
            return true
        }
        val pkg = shellQuote(context.packageName)
        runRootCommand("pm grant $pkg $BACKGROUND_CLIPBOARD_PERMISSION")
        runRootCommand("cmd appops set $pkg READ_CLIPBOARD allow")
        return hasBackgroundAccess(context)
    }

    fun revokeBackgroundAccess(context: Context) {
        val pkg = shellQuote(context.packageName)
        runRootCommand("pm revoke $pkg $BACKGROUND_CLIPBOARD_PERMISSION")
        runRootCommand("cmd appops set $pkg READ_CLIPBOARD default")
    }

    fun hasBackgroundAccess(context: Context): Boolean =
        context.checkSelfPermission(BACKGROUND_CLIPBOARD_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun read(context: Context, clipboard: ClipboardManager): Clip? = runCatching {
        val clip = clipboard.primaryClip ?: return null
        val item = clip.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
        val imageMime = clip.description?.filterMimeTypes("image/*")
            ?.firstOrNull { it in ImagePayload.ALLOWED_MIME_TYPES }
        if (imageMime != null || item.uri != null) {
            val scheme = item.uri?.scheme?.lowercase()
            if (scheme == "content" || scheme == "file" || imageMime != null) {
                val fingerprint = "unreadable-image:${item.uri ?: clip.description}"
                if (fingerprint == ignoredUnreadableImage) return Clip.UnreadableImage(fingerprint)
                ImageClipboard.read(context, clipboard)?.let {
                    ignoredUnreadableImage = null
                    return Clip.Image(it)
                }
                if (scheme == "file") {
                    ignoredUnreadableImage = fingerprint
                    return Clip.UnreadableImage(fingerprint)
                }
            } else if (scheme == "http" || scheme == "https") {
                ignoredUnreadableImage = null
                return Clip.Text(item.uri.toString())
            }
        }
        ignoredUnreadableImage = null
        val text = item.text?.toString()?.takeIf { it.isNotBlank() }
            ?: runCatching { item.coerceToText(context)?.toString() }.getOrNull()?.takeIf { it.isNotBlank() }
        text?.let(Clip::Text)
    }.getOrNull()

    private fun runRootCommand(command: String): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        process.inputStream.close()
        process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"
}
