package net.wastu.binderclip

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import net.wastu.binderclip.R

data class AppState(
    val status: String = "Not paired",
    val peer: RememberedPeer? = null,
    val pendingText: Boolean = false,
    val pendingImage: Boolean = false,
    val transferStatus: String? = null,
    val members: List<RememberedPeer> = emptyList(),
    val rootAvailable: Boolean = false,
    val automaticClipboardEnabled: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val localDeviceId: String = "",
    val hosting: Boolean = false,
)

object AppRuntime {
    val state = kotlinx.coroutines.flow.MutableStateFlow(AppState())
    val pairingUrl = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}

/** The only Android background component: a direct-connection foreground service. */
class BinderClipService : Service() {
    companion object {
        const val ACTION_START = "net.wastu.binderclip.START"
        const val ACTION_PAIR = "net.wastu.binderclip.PAIR"
        const val ACTION_CREATE_CHAIN = "net.wastu.binderclip.CREATE_CHAIN"
        const val ACTION_SEND_CURRENT = "net.wastu.binderclip.SEND_CURRENT"
        const val ACTION_COPY_PENDING = "net.wastu.binderclip.COPY_PENDING"
        const val ACTION_UI_VISIBLE = "net.wastu.binderclip.UI_VISIBLE"
        const val ACTION_TOGGLE_ROOT_AUTOMATION = "net.wastu.binderclip.TOGGLE_ROOT_AUTOMATION"
        const val ACTION_REFRESH_CAPABILITIES = "net.wastu.binderclip.REFRESH_CAPABILITIES"
        const val ACTION_DISABLE_ACCESSIBILITY = "net.wastu.binderclip.DISABLE_ACCESSIBILITY"
        const val ACTION_REMOVE_MEMBER = "net.wastu.binderclip.REMOVE_MEMBER"
        const val ACTION_UPDATE_DEVICE_NAME = "net.wastu.binderclip.UPDATE_DEVICE_NAME"
        const val ACTION_SEND_SHARED = "net.wastu.binderclip.SEND_SHARED"
        const val ACTION_REQUEST_INVITE = "net.wastu.binderclip.REQUEST_INVITE"
        const val ACTION_SEARCH_RECONNECT = "net.wastu.binderclip.SEARCH_RECONNECT"
        const val EXTRA_MEMBER_ID = "member_id"
        const val EXTRA_TARGET_DEVICE_ID = "target_device_id"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_URI = "uri"
        private const val CHANNEL = "binderclip_sync"
        private const val URL_CHANNEL = "binderclip_urls"
        private const val NOTIFICATION_ID = 101
    }

    private lateinit var store: DeviceStore
    private lateinit var client: DirectClient
    private lateinit var server: DirectServer
    private lateinit var clipboard: ClipboardManager
    private lateinit var nsdManager: NsdManager
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val reconnectExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val reconnectAttempts = AtomicInteger(0)
    private var scheduledReconnectFuture: ScheduledFuture<*>? = null
    private var meshScanFuture: ScheduledFuture<*>? = null
    @Volatile
    private var uiVisible = false
    private var suppressClipboard: String? = null
    private var suppressImageHash: String? = null
    private var pendingImage: ImagePayload? = null
    private var transferStatus: String? = null
    @Volatile
    private var rootAvailable = false
    @Volatile
    private var automaticClipboardEnabled = false
    private var rootPoll: ScheduledFuture<*>? = null
    private var rootFingerprint: String? = null
    private var lastSentText: String? = null
    private var lastSentImageHash: String? = null
    private var lastSendAt = 0L

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    if (automaticClipboardEnabled) startRootPolling()
                    if (!client.isConnected()) resetReconnectBackoffAndTrigger("screen_on")
                }

                Intent.ACTION_SCREEN_OFF -> {
                    stopRootPolling()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate(); DiagnosticLog.initialize(this); store = DeviceStore(this); clipboard =
            getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; ImageClipboard.clearStale(this)
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        createChannel(); startForeground(NOTIFICATION_ID, notification("Starting BinderClip…"))
        client = DirectClient(
            store = store,
            deviceNameProvider = { DeviceNames.android(this) },
            onText = ::receiveText,
            onOpenUrl = ::receiveOpenUrl,
            onImage = ::receiveImage,
            onTransferStatus = ::updateTransferStatus,
            onStatus = ::updateStatus,
            onFailure = ::reportFailure,
            onPeerIdentity = { id, name ->
                store.peer = store.peer?.copy(name = name, deviceId = id)
                reconnectAttempts.set(0)
                publishState()
            },
            onRosterChanged = { publishState() },
            onInvite = { url -> AppRuntime.pairingUrl.value = url; updateStatus("Pairing code ready") },
            onDisconnected = ::scheduleReconnect,
        )
        server = DirectServer(
            store = store,
            deviceNameProvider = { DeviceNames.android(this) },
            onText = ::receiveText,
            onOpenUrl = ::receiveOpenUrl,
            onImage = ::receiveImage,
            onTransferStatus = ::updateTransferStatus,
            onStatus = ::updateStatus,
            onRosterChanged = { publishState() },
            onInvite = { url -> AppRuntime.pairingUrl.value = url; updateStatus("Pairing code ready") },
        )
        clipboard.addPrimaryClipChangedListener {
            if (uiVisible) executor.execute(::sendCurrentClipboard)
        }
        AccessibilityClipboardBridge.onClipboard =
            { payload -> executor.execute { sendAccessibilityClipboard(payload) } }
        AccessibilityClipboardBridge.onAvailabilityChanged = { executor.execute(::publishState) }
        registerNetworkCallback()
        startNsdDiscovery()
        startMeshScan()
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenStateReceiver, screenFilter)
        executor.execute {
            rootAvailable = RootClipboardBridge.isAvailable()
            automaticClipboardEnabled =
                rootAvailable && store.isRootClipboardAutomationEnabled() && RootClipboardBridge.enableBackgroundAccess(
                    this
                )
            if (store.hosting && store.groupKey != null) {
                if (uiVisible) server.start()
            } else if (store.peer != null) {
                resetReconnectBackoffAndTrigger("service_create")
            }
            if (automaticClipboardEnabled) startRootPolling()
            publishState()
        }
        publishState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_PAIR -> intent?.getStringExtra(EXTRA_URI)?.let { uri ->
                executor.execute {
                    // Joining a chain always leaves hosting mode first.
                    if (store.hosting) {
                        server.stop()
                        store.hosting = false
                        store.members = emptyList()
                        store.peer = null
                    }
                    runCatching { client.pair(uri) }.onFailure { reportFailure(it.message ?: "Pairing failed") }
                }
            }

            ACTION_CREATE_CHAIN -> executor.execute(::createNewChain)

            ACTION_SEND_CURRENT -> executor.execute { sendCurrentClipboard(userInitiated = true) }
            ACTION_SEARCH_RECONNECT -> resetReconnectBackoffAndTrigger("user_reconnect")
            ACTION_SEND_SHARED -> {
                val targetDeviceId = intent?.getStringExtra(EXTRA_TARGET_DEVICE_ID)
                executor.execute {
                    when (val shared = SharedPayloadCache.value.also { SharedPayloadCache.value = null }) {
                        is SharedPayload.Image -> sendSharedImage(shared.value)
                        is SharedPayload.Text -> {
                            val trimmed = shared.value.trim()
                            val isUrl = isWebUrl(trimmed)
                            // URLs are sent to be OPENED on the target device; only
                            // non-URL text is mirrored into the local clipboard.
                            if (!isUrl) applyText(shared.value)
                            if (store.hosting) {
                                if (!server.isRunning) reportFailure(if (isUrl) "Link not sent — hosting is not active" else "Content copied, but hosting is not active")
                                else {
                                    lastSentText = shared.value
                                    lastSendAt = System.currentTimeMillis()
                                    if (isUrl) server.broadcastOpenUrl(trimmed, targetDeviceId)
                                    else server.broadcastText(shared.value, targetDeviceId)
                                }
                            } else if (!client.isConnected()) client.reconnect()
                            else {
                                lastSentText = shared.value
                                lastSendAt = System.currentTimeMillis()
                                if (isUrl) {
                                    client.sendOpenUrl(trimmed, targetDeviceId)
                                } else {
                                    client.sendText(shared.value, targetDeviceId)
                                }
                            }
                        }

                        null -> reportFailure("Nothing to share")
                    }
                }
            }

            ACTION_REQUEST_INVITE -> executor.execute {
                if (store.hosting) {
                    if (server.isRunning) server.createInvite() else reportFailure("Hosting is not active")
                } else {
                    client.requestInvite()
                }
            }
            ACTION_COPY_PENDING -> {
                store.pendingText?.let { text ->
                    applyText(text)
                    store.pendingText = null
                    toast("Copied text")
                }
                pendingImage?.let { image ->
                    applyImage(image)
                    pendingImage = null
                    toast("Copied image")
                }
                publishState()
            }

            ACTION_UI_VISIBLE -> {
                uiVisible = intent?.getBooleanExtra("visible", false) ?: false
                if (uiVisible) {
                    if (store.hosting && store.groupKey != null) executor.execute { server.start() }
                    if (!client.isConnected() && !store.hosting) resetReconnectBackoffAndTrigger("ui_visible")
                } else if (store.hosting) {
                    executor.execute { server.stop() }
                }
            }

            ACTION_TOGGLE_ROOT_AUTOMATION -> executor.execute {
                val enabled = intent?.getBooleanExtra("enabled", false) ?: false
                rootAvailable = RootClipboardBridge.isAvailable()
                automaticClipboardEnabled = enabled && rootAvailable && RootClipboardBridge.enableBackgroundAccess(this)
                store.setRootClipboardAutomationEnabled(automaticClipboardEnabled)
                if (automaticClipboardEnabled) startRootPolling() else {
                    stopRootPolling()
                    RootClipboardBridge.revokeBackgroundAccess(this)
                }
                updateStatus(if (automaticClipboardEnabled) "Automatic clipboard sync is on" else "Automatic clipboard sync is off")
                toast(
                    when {
                        automaticClipboardEnabled -> "Automatic sync on"
                        enabled -> "Allow root access, then try again"
                        else -> "Automatic sync off"
                    },
                )
            }

            ACTION_REFRESH_CAPABILITIES -> executor.execute {
                rootAvailable = RootClipboardBridge.isAvailable()
                if ((!rootAvailable || !RootClipboardBridge.hasBackgroundAccess(this)) && automaticClipboardEnabled) {
                    automaticClipboardEnabled = false
                    store.setRootClipboardAutomationEnabled(false)
                    stopRootPolling()
                }
                publishState()
            }

            ACTION_REMOVE_MEMBER -> intent?.getStringExtra(EXTRA_MEMBER_ID)
                ?.let { id ->
                    executor.execute {
                        if (id == store.deviceId) {
                            leaveChain()
                        } else if (store.hosting) {
                            server.removeMember(id)
                        } else {
                            client.removeMember(id)
                        }
                    }
                }

            ACTION_UPDATE_DEVICE_NAME -> {
                val newName = intent?.getStringExtra(EXTRA_DEVICE_NAME)
                val targetId = intent?.getStringExtra(EXTRA_MEMBER_ID) ?: store.deviceId
                if (!newName.isNullOrBlank()) {
                    executor.execute {
                        if (store.hosting) {
                            server.renameMember(targetId, newName)
                        } else {
                            client.renameMember(targetId, newName)
                        }
                        publishState()
                    }
                }
            }

            ACTION_DISABLE_ACCESSIBILITY -> {
                val disabled = AccessibilityClipboardBridge.disable()
                executor.execute {
                    android.os.Handler(mainLooper).postDelayed({
                        publishState()
                        if (!disabled) toast("Turn off Accessibility in Settings")
                    }, 250)
                }
            }

            ACTION_START -> if (store.peer != null && !client.isConnected()) resetReconnectBackoffAndTrigger("action_start")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        runCatching { unregisterReceiver(screenStateReceiver) }
        stopNsdDiscovery()
        unregisterNetworkCallback()
        stopMeshScan()
        AccessibilityClipboardBridge.onClipboard = null
        AccessibilityClipboardBridge.onAvailabilityChanged = null
        stopRootPolling(); server.stop(); client.shutdown(); executor.shutdownNow(); reconnectExecutor.shutdownNow(); super.onDestroy()
    }

    private fun createNewChain() {
        server.stop()
        client.close()
        store.createNewChain()
        if (uiVisible) {
            server.start()
            server.createInvite()
        }
        publishState()
        updateStatus("Created a new chain — scan to add devices")
    }

    private fun leaveChain() {
        if (store.hosting) {
            server.leaveChain()
        } else {
            client.leaveChain()
        }
        reconnectAttempts.set(0)
        scheduledReconnectFuture?.cancel(false)
        scheduledReconnectFuture = null
        publishState()
    }

    private fun registerNetworkCallback() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    resetReconnectBackoffAndTrigger("network_available")
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        resetReconnectBackoffAndTrigger("network_capabilities")
                    }
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            runCatching {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(
                    it
                )
            }
            networkCallback = null
        }
    }

    private fun startNsdDiscovery() {
        if (nsdDiscoveryListener != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.contains("binderclip", ignoreCase = true)) {
                    runCatching {
                        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                val host = serviceInfo.host?.hostAddress ?: return
                                val port = serviceInfo.port
                                val currentPeer = store.peer ?: return
                                if (host.isNotBlank() && (currentPeer.host != host || !client.isConnected())) {
                                    store.peer = currentPeer.copy(host = host, port = port)
                                    resetReconnectBackoffAndTrigger("nsd_resolved")
                                }
                            }
                        })
                    }
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsdDiscoveryListener = listener
        runCatching {
            nsdManager.discoverServices("_binderclip._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    private fun stopNsdDiscovery() {
        nsdDiscoveryListener?.let {
            runCatching { nsdManager.stopServiceDiscovery(it) }
            nsdDiscoveryListener = null
        }
    }

    /**
     * Periodic mesh re-scan: while paired but disconnected, retry reconnecting on
     * a short cadence so a mesh/interface IP change heals automatically without
     * user intervention.
     */
    private fun startMeshScan() {
        if (meshScanFuture != null) return
        meshScanFuture = reconnectExecutor.scheduleWithFixedDelay({
            if (store.peer != null && !client.isConnected()) {
                executor.execute { client.reconnect() }
            }
        }, 30, 30, TimeUnit.SECONDS)
    }

    private fun stopMeshScan() {
        meshScanFuture?.cancel(true); meshScanFuture = null
    }

    private fun startRootPolling() {
        if (rootPoll != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isInteractive) return
        // Seed rootFingerprint with current clipboard to prevent echoing stale clipboard at start
        rootFingerprint = runCatching { RootClipboardBridge.read(this, clipboard)?.fingerprint }.getOrNull()
        rootPoll = reconnectExecutor.scheduleWithFixedDelay({
            if (!automaticClipboardEnabled) return@scheduleWithFixedDelay
            if (!client.isConnected()) return@scheduleWithFixedDelay
            val clip = RootClipboardBridge.read(this, clipboard) ?: return@scheduleWithFixedDelay
            if (clip.fingerprint == rootFingerprint) return@scheduleWithFixedDelay
            rootFingerprint = clip.fingerprint
            when (clip) {
                is RootClipboardBridge.Clip.Text -> sendTextIfFresh(clip.value)
                is RootClipboardBridge.Clip.Image -> sendImageIfFresh(clip.value)
                is RootClipboardBridge.Clip.UnreadableImage -> Unit
            }
        }, 0, 900, TimeUnit.MILLISECONDS)
    }

    private fun stopRootPolling() {
        rootPoll?.cancel(true); rootPoll = null; rootFingerprint = null
    }

    private fun resetReconnectBackoffAndTrigger(reason: String = "") {
        reconnectAttempts.set(0)
        scheduledReconnectFuture?.cancel(false)
        scheduledReconnectFuture = null
        if (store.peer != null && !client.isConnected()) {
            executor.execute { client.reconnect() }
        }
    }

    private fun scheduleReconnect() {
        if (store.peer == null || client.isConnected()) return
        // Reflect the loss immediately: no stale "connected" in the UI.
        if (reconnectAttempts.get() == 0) {
            store.members = store.members.map { if (it.deviceId != store.deviceId) it.copy(connected = false) else it }
            store.peer = store.peer?.copy(connected = false)
            publishState()
        }
        val attempts = reconnectAttempts.getAndIncrement()
        val baseDelay = when (attempts) {
            0 -> 3L
            1 -> 6L
            2 -> 12L
            3 -> 25L
            else -> 60L
        }
        val jitter = (Math.random() * 0.3 - 0.15) * baseDelay
        val delaySeconds = (baseDelay + jitter).toLong().coerceIn(2L, 65L)

        scheduledReconnectFuture?.cancel(false)
        scheduledReconnectFuture = reconnectExecutor.schedule({
            if (!client.isConnected()) {
                executor.execute { client.reconnect() }
            }
        }, delaySeconds, TimeUnit.SECONDS)
    }

    private fun receiveText(text: String) {
        store.pendingText = text
        val applyImmediately = uiVisible || automaticClipboardEnabled
        if (applyImmediately) {
            applyText(text); store.pendingText = null
        }
        publishState(); updateStatus(if (applyImmediately) "Received text" else "Text ready to copy")
    }

    private fun receiveOpenUrl(url: String) {
        val trimmed = url.trim()
        if (!isWebUrl(trimmed)) {
            receiveText(url)
            return
        }
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(trimmed)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            trimmed.hashCode(),
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, URL_CHANNEL)
            .setSmallIcon(R.drawable.ic_binder_clip)
            .setContentTitle("Open Link")
            .setContentText(trimmed)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()
        nm.notify(System.currentTimeMillis().toInt(), notif)
        updateStatus("Received link")
        publishState()
    }

    private fun isWebUrl(text: String): Boolean {
        val lower = text.lowercase()
        return (lower.startsWith("http://") || lower.startsWith("https://")) && android.util.Patterns.WEB_URL.matcher(
            text
        ).matches()
    }

    private fun receiveImage(image: ImagePayload) {
        val applyImmediately = uiVisible || automaticClipboardEnabled
        if (applyImmediately) applyImage(image) else pendingImage = image
        publishState(); updateStatus(if (applyImmediately) "Received image" else "Image ready to copy")
    }

    private fun sendCurrentClipboard(userInitiated: Boolean = false) {
        when (val content = ClipboardClassifier.read(this, clipboard)) {
            is LocalClipboardContent.Text -> sendTextIfFresh(content.value)
            is LocalClipboardContent.Image -> sendImageIfFresh(content.value)
            LocalClipboardContent.Unsupported -> if (userInitiated) reportFailure("Copy text or a supported image first")
        }
    }

    private fun sendAccessibilityClipboard(payload: AccessibilityClipboard) {
        if (rootAvailable || !AccessibilityClipboardBridge.isEnabled(this)) return
        when (payload) {
            is AccessibilityClipboard.Text -> sendTextIfFresh(payload.value)
            is AccessibilityClipboard.Image -> sendImageIfFresh(payload.value)
        }
    }

    private fun sendTextIfFresh(text: String) {
        if (store.hosting) {
            if (!server.isRunning) return
            server.broadcastText(text)
            return
        }
        if (!client.isConnected()) return
        if (text.isBlank() || text == suppressClipboard) return
        val now = System.currentTimeMillis()
        if (text == lastSentText && now - lastSendAt < 1_500) return
        lastSentText = text; lastSendAt = now; client.sendText(text)
    }

    private fun sendImageIfFresh(image: ImagePayload) {
        if (store.hosting) {
            if (!server.isRunning) return
            if (image.sha256 == suppressImageHash) return
            server.sendImage(image)
            return
        }
        if (!client.isConnected()) return
        if (image.sha256 == suppressImageHash) return
        val now = System.currentTimeMillis()
        if (image.sha256 == lastSentImageHash && now - lastSendAt < 1_500) return
        lastSentImageHash = image.sha256; lastSendAt = now; client.sendImage(image)
    }

    private fun sendSharedImage(image: ImagePayload) {
        // A share is also a local copy operation. Suppression prevents the
        // clipboard listener/root bridge from turning it into a second send.
        applyImage(image)
        if (store.hosting) {
            if (!server.isRunning) {
                reportFailure("Image copied, but hosting is not active")
                return
            }
            lastSentImageHash = image.sha256
            lastSendAt = System.currentTimeMillis()
            server.sendImage(image)
            return
        }
        if (!client.isConnected()) client.reconnect()
        if (!client.isConnected()) {
            reportFailure("Image copied, but no device is connected")
            return
        }
        lastSentImageHash = image.sha256
        lastSendAt = System.currentTimeMillis()
        client.sendImage(image)
    }

    private fun applyText(text: String) {
        suppressClipboard = text
        rootFingerprint = "text:$text"
        lastSentText = text
        lastSendAt = System.currentTimeMillis()
        clipboard.setPrimaryClip(ClipData.newPlainText("BinderClip", text))
        android.os.Handler(mainLooper).postDelayed({ if (suppressClipboard == text) suppressClipboard = null }, 1_500)
    }

    private fun applyImage(image: ImagePayload) {
        suppressImageHash = image.sha256
        rootFingerprint = "image:${image.sha256}"
        lastSentImageHash = image.sha256
        lastSendAt = System.currentTimeMillis()
        ImageClipboard.write(this, clipboard, image)
        android.os.Handler(mainLooper)
            .postDelayed({ if (suppressImageHash == image.sha256) suppressImageHash = null }, 1_500)
    }

    private fun updateStatus(message: String) {
        Log.i("BinderClip", message)
        if (!message.startsWith("Sending image") && !message.startsWith("Receiving image")) {
            if (message.contains("failed", ignoreCase = true) || message.contains(
                    "lost",
                    ignoreCase = true
                )
            ) DiagnosticLog.warning(message)
            else if (message.startsWith("Connected") || message.startsWith("Paired") || message.startsWith("Received") || message == "Image sent") DiagnosticLog.info(
                message
            )
        }
        val peer = store.peer
        AppRuntime.state.value = AppState(
            status = message,
            peer = peer,
            pendingText = store.pendingText != null,
            pendingImage = pendingImage != null,
            transferStatus = transferStatus,
            members = store.members,
            rootAvailable = rootAvailable,
            automaticClipboardEnabled = automaticClipboardEnabled,
            accessibilityEnabled = AccessibilityClipboardBridge.isEnabled(this),
            localDeviceId = store.deviceId,
            hosting = store.hosting,
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(
            NOTIFICATION_ID,
            notification(message)
        )
    }

    private fun toast(message: String) = android.os.Handler(mainLooper).post {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun updateTransferStatus(message: String) {
        transferStatus = message; updateStatus(message)
    }

    private fun reportFailure(message: String) {
        DiagnosticLog.error(message)
        transferStatus = message
        updateStatus(message)
        toast(message)
    }

    private fun publishState() {
        updateStatus(connectionSummary())
    }

    private fun connectionSummary(): String {
        if (store.hosting && store.groupKey != null) {
            val names = store.members.filter { it.deviceId != store.deviceId }.map { it.name }
            return when (names.size) {
                0 -> "Hosting chain"
                1 -> "Hosting · ${names[0]}"
                2, 3 -> "Hosting · ${names.joinToString(", ")}"
                else -> "Hosting · ${names.size} Devices"
            }
        }
        if (store.peer == null) return "No trusted device"
        if (!client.isConnected()) return "Waiting for ${store.peer?.name ?: "device"}"
        val names = (store.members + listOfNotNull(store.peer))
            .filter { it.deviceId != store.deviceId && it.connected }
            .distinctBy { it.deviceId }
            .map { it.name }
        return when (names.size) {
            0 -> "Connected"
            1 -> "Connected to ${names[0]}"
            2, 3 -> "Connected to ${names.joinToString(", ")}"
            else -> "Connected to ${names.size} Devices"
        }
    }

    private fun notification(status: String): android.app.Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_binder_clip)
            .setContentTitle("BinderClip")
            .setContentText(status)
            .setContentIntent(open)
            .setOngoing(true)
        val percentMatch = Regex("(\\d+)%").find(status)
        if (percentMatch != null) {
            val percent = percentMatch.groupValues[1].toIntOrNull() ?: 0
            builder.setProgress(100, percent, false)
        }
        val send = PendingIntent.getService(
            this,
            2,
            Intent(this, BinderClipService::class.java).setAction(ACTION_SEND_CURRENT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(0, "Send clipboard", send)
        if (store.pendingText != null || pendingImage != null) {
            val copy = PendingIntent.getService(
                this,
                1,
                Intent(this, BinderClipService::class.java).setAction(ACTION_COPY_PENDING),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, if (pendingImage != null) "Copy image" else "Copy text", copy)
        }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "BinderClip sync", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(URL_CHANNEL, "BinderClip links", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Shared web links from connected devices"
                }
            )
        }
    }
}
