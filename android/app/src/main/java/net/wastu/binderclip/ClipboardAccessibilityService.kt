package net.wastu.binderclip

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings

sealed interface AccessibilityClipboard {
    data class Text(val value: String) : AccessibilityClipboard
    data class Image(val value: ImagePayload) : AccessibilityClipboard
}

/**
 * User-enabled, narrow clipboard observer for non-root devices. It never reads
 * windows, sends gestures, or interprets accessibility events; it only receives
 * the system clipboard callback while Android keeps this service enabled.
 * When Android binds it after boot, it starts BinderClipService if the phone is paired.
 */
object AccessibilityClipboardBridge {
    @Volatile var onClipboard: ((AccessibilityClipboard) -> Unit)? = null
    @Volatile var onAvailabilityChanged: (() -> Unit)? = null
    @Volatile private var service: ClipboardAccessibilityService? = null

    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, ClipboardAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return service != null && enabled.split(':').any { ComponentName.unflattenFromString(it)?.flattenToString() == expected }
    }

    /** Android only permits the user to turn this service back on in Settings. */
    fun disable(): Boolean = service?.let { it.disableSelf(); true } ?: false

    internal fun connected(instance: ClipboardAccessibilityService) {
        service = instance
        onAvailabilityChanged?.invoke()
    }

    internal fun disconnected(instance: ClipboardAccessibilityService) {
        if (service === instance) service = null
        onAvailabilityChanged?.invoke()
    }
}

class ClipboardAccessibilityService : AccessibilityService() {
    private lateinit var clipboard: ClipboardManager
    private val listener = ClipboardManager.OnPrimaryClipChangedListener(::forwardClipboard)

    override fun onServiceConnected() {
        super.onServiceConnected()
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener(listener)
        AccessibilityClipboardBridge.connected(this)
        BinderClipService.startIfPaired(this)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (::clipboard.isInitialized) clipboard.removePrimaryClipChangedListener(listener)
        AccessibilityClipboardBridge.disconnected(this)
        super.onDestroy()
    }

    private fun forwardClipboard() {
        val clip = clipboard.primaryClip
        val payload = ImageClipboard.read(this, clipboard)?.let(AccessibilityClipboard::Image)
            ?: clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                ?.takeIf { it.isNotBlank() }?.let(AccessibilityClipboard::Text)
        payload?.let { AccessibilityClipboardBridge.onClipboard?.invoke(it) }
    }
}
