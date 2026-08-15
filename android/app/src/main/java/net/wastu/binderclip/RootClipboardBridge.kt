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
        if (hasBackgroundAccess(context)) return true
        runRootCommand("pm grant ${shellQuote(context.packageName)} $BACKGROUND_CLIPBOARD_PERMISSION")
        return hasBackgroundAccess(context)
    }

    fun revokeBackgroundAccess(context: Context) {
        runRootCommand("pm revoke ${shellQuote(context.packageName)} $BACKGROUND_CLIPBOARD_PERMISSION")
    }

    fun hasBackgroundAccess(context: Context): Boolean =
        context.checkSelfPermission(BACKGROUND_CLIPBOARD_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun read(context: Context, clipboard: ClipboardManager): Clip? = runCatching {
        val clip = clipboard.primaryClip ?: return null
        val imageMime = clip.description?.filterMimeTypes("image/*")
            ?.firstOrNull { it in ImagePayload.ALLOWED_MIME_TYPES }
        if (imageMime != null) {
            val uri = clip.getItemAt(0).uri
            val fingerprint = "unreadable-image:${uri ?: clip.description}"
            if (fingerprint == ignoredUnreadableImage) return Clip.UnreadableImage(fingerprint)
            ImageClipboard.read(context, clipboard)?.let {
                ignoredUnreadableImage = null
                return Clip.Image(it)
            }
            ignoredUnreadableImage = fingerprint
            return Clip.UnreadableImage(fingerprint)
        }
        ignoredUnreadableImage = null
        // Do not coerce a URI into its title: that is how a copied file became
        // a fake filename clipboard entry in earlier builds.
        clip.getItemAt(0).text?.toString()?.takeIf { it.isNotBlank() }?.let(Clip::Text)
    }.getOrNull()

    private fun runRootCommand(command: String): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        process.inputStream.close()
        process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"
}
