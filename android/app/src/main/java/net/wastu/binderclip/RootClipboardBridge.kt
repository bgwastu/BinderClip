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

    private fun getUserId(): Int = runCatching {
        android.os.Process.myUid() / 100000
    }.getOrDefault(0)

    private val suPaths = listOf("su", "/system/bin/su", "/system/xbin/su", "/data/adb/ksu/bin/su")

    fun isAvailable(): Boolean = runCatching {
        suPaths.any { path ->
            runCatching {
                val process = ProcessBuilder(path, "-c", "id -u").redirectErrorStream(true).start()
                val output = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
                process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0 &&
                    output.lines().any { it.trim() == "0" || it.contains("uid=0(") }
            }.getOrDefault(false)
        }
    }.getOrDefault(false)

    fun enableBackgroundAccess(context: Context): Boolean {
        val pkg = shellQuote(context.packageName)
        val userId = getUserId()

        // Grant permission targeting the active user/space and fallback to global
        runRootCommand("pm grant --user $userId $pkg $BACKGROUND_CLIPBOARD_PERMISSION")
        runRootCommand("pm grant $pkg $BACKGROUND_CLIPBOARD_PERMISSION")

        // Set appops for the active user/space and fallback
        runRootCommand("cmd appops set --user $userId $pkg READ_CLIPBOARD allow")
        runRootCommand("cmd appops set $pkg READ_CLIPBOARD allow")

        val granted = hasBackgroundAccess(context)
        if (!granted) {
            DiagnosticLog.warning("Background clipboard permission not granted after root commands (userId=$userId)")
        }
        return granted
    }

    fun revokeBackgroundAccess(context: Context) {
        val pkg = shellQuote(context.packageName)
        val userId = getUserId()
        runRootCommand("pm revoke --user $userId $pkg $BACKGROUND_CLIPBOARD_PERMISSION")
        runRootCommand("pm revoke $pkg $BACKGROUND_CLIPBOARD_PERMISSION")
        runRootCommand("cmd appops set --user $userId $pkg READ_CLIPBOARD default")
        runRootCommand("cmd appops set $pkg READ_CLIPBOARD default")
    }

    fun hasBackgroundAccess(context: Context): Boolean =
        context.checkSelfPermission(BACKGROUND_CLIPBOARD_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun read(context: Context, clipboard: ClipboardManager): Clip? = runCatching {
        val clip = clipboard.primaryClip ?: return null
        val item = clip.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
        val imageMime = clip.description?.filterMimeTypes("image/*")
            ?.firstOrNull { it in ImagePayload.ALLOWED_MIME_TYPES }
        val directText = item.text?.toString()?.takeIf { it.isNotBlank() }

        // 1. Direct text / URL precedence
        if (directText != null) {
            val trimmed = directText.trim().lowercase()
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || imageMime == null) {
                ignoredUnreadableImage = null
                return Clip.Text(directText)
            }
        }

        // 2. Image content
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
        val text = directText ?: runCatching { item.coerceToText(context)?.toString() }.getOrNull()?.takeIf { it.isNotBlank() }
        text?.let(Clip::Text)
    }.getOrNull()

    private fun runRootCommand(command: String): Boolean = runCatching {
        for (path in suPaths) {
            val success = runCatching {
                val process = ProcessBuilder(path, "-c", command).redirectErrorStream(true).start()
                val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
                val finished = process.waitFor(5, TimeUnit.SECONDS)
                finished && process.exitValue() == 0
            }.getOrDefault(false)
            if (success) return true
        }
        DiagnosticLog.warning("Root command failed ($command)")
        false
    }.getOrElse {
        DiagnosticLog.warning("Root command exception ($command): ${it.message}")
        false
    }

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"
}
