package net.wastu.clipboard.service

// Foreground service that orchestrates BLE advertising, L2CAP connections, and clipboard sync.

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.provider.Settings
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import net.wastu.clipboard.R
import net.wastu.clipboard.ble.Advertiser
import net.wastu.clipboard.ble.L2capServer
import net.wastu.clipboard.ble.L2capServerCallback
import net.wastu.clipboard.crypto.E2ECrypto
import net.wastu.clipboard.debug.DebugSmokeProbe
import net.wastu.clipboard.permissions.BlePermissions
import net.wastu.clipboard.pairing.PairingStore
import net.wastu.clipboard.protocol.ProtocolException
import net.wastu.clipboard.protocol.Session
import net.wastu.clipboard.protocol.SessionCallback
import net.wastu.clipboard.protocol.SessionMode
import net.wastu.clipboard.protocol.VersionMismatchException
import net.wastu.clipboard.tcp.TcpControlTransport
import net.wastu.clipboard.settings.ClipboardSettingsStore
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors

class ClipboardService : Service(), L2capServerCallback {
    companion object {
        const val ACTION_PUSH_TEXT = "net.wastu.clipboard.action.PUSH_TEXT"
        const val ACTION_OPEN_URL = "net.wastu.clipboard.action.OPEN_URL"
        const val ACTION_COPY_TO_CLIPBOARD = "net.wastu.clipboard.action.COPY_TO_CLIPBOARD"
        const val ACTION_RELOAD_PAIRING = "net.wastu.clipboard.action.RELOAD_PAIRING"
        const val ACTION_UNPAIR = "net.wastu.clipboard.action.UNPAIR"
        const val ACTION_FORGET_DEVICE = "net.wastu.clipboard.action.FORGET_DEVICE"
        const val ACTION_START_PAIRING = "net.wastu.clipboard.action.START_PAIRING"
        const val ACTION_CONNECTION_STATE = "net.wastu.clipboard.action.CONNECTION_STATE"
        const val ACTION_QUERY_CONNECTION = "net.wastu.clipboard.action.QUERY_CONNECTION"
        const val ACTION_CLIPBOARD_TRANSFER = "net.wastu.clipboard.action.CLIPBOARD_TRANSFER"
        const val ACTION_PAIRING_COMPLETE = "net.wastu.clipboard.action.PAIRING_COMPLETE"
        const val ACTION_PAIRING_STATUS = "net.wastu.clipboard.action.PAIRING_STATUS"
        const val ACTION_CANCEL_PAIRING = "net.wastu.clipboard.action.CANCEL_PAIRING"
        const val EXTRA_PAIRING_STAGE = "extra_pairing_stage"
        const val PAIRING_STAGE_CONNECTING = "CONNECTING"
        const val PAIRING_STAGE_EXCHANGING_KEYS = "EXCHANGING_KEYS"
        const val PAIRING_STAGE_FAILED = "FAILED"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_CONNECTED = "extra_connected"
        const val EXTRA_DEVICE_NAME = "extra_device_name"
        const val EXTRA_DEVICE_ID = "extra_device_id"
        const val EXTRA_CONNECTED_IDS = "extra_connected_ids"
        const val EXTRA_FROM_MAC = "extra_from_mac"

        const val ACTION_VERSION_MISMATCH = "net.wastu.clipboard.action.VERSION_MISMATCH"
        const val ACTION_GHOST_FINISHED = "net.wastu.clipboard.action.GHOST_FINISHED"
        const val ACTION_ACCESSIBILITY_COPY_DETECTED = "net.wastu.clipboard.action.ACCESSIBILITY_COPY_DETECTED"
        const val ACTION_SEND_CONFIG_UPDATE = "net.wastu.clipboard.action.SEND_CONFIG_UPDATE"
        const val ACTION_PUSH_IMAGE = "net.wastu.clipboard.action.PUSH_IMAGE"
        const val EXTRA_IMAGE_PATH = "extra_image_path"
        const val EXTRA_MIME_TYPE = "extra_mime_type"
        const val EXTRA_IMAGE_NAME = "extra_image_name"
        const val ACTION_RICH_MEDIA_SETTING_CHANGED = "net.wastu.clipboard.action.RICH_MEDIA_SETTING_CHANGED"
        const val EXTRA_RICH_MEDIA_ENABLED = "extra_rich_media_enabled"

        const val PREFS_NAME = "clipboard_state"
        const val KEY_CONNECTED_DEVICE = "connected_device_name"

        /** True while at least one Mac session is ready, used to disarm auto-copy
         *  detection entirely while disconnected. */
        @Volatile
        var anyMacConnected: Boolean = false
            private set
        const val KEY_PENDING_PAIRING_NAME = "pending_pairing_name"

        private const val TAG = "ClipboardService"
        private const val MAX_CLIPBOARD_BYTES = 102_400
        // One copy often fires several detections (click + "Copied" toast +
        // toolbar close); the first ghost read covers them all.
        private const val CLIPBOARD_DEBOUNCE_MS = 700L
        // Clears a stuck in-flight flag if the ghost activity never reports back.
        private const val GHOST_WATCHDOG_MS = 4_000L
        // Same-text sends are suppressed only briefly, so re-copying the same
        // text later still syncs (the Mac clipboard may have changed since).
        private const val SENT_TEXT_DEDUPE_WINDOW_MS = 3_000L
        // After a Mac→Android clipboard write, ignore copy detections briefly —
        // the system "Copied" overlay/toast would otherwise echo it back.
        private const val INBOUND_SUPPRESS_MS = 2_000L
        // Matches the 60s handshake-level pairing timeout in Session
        // (pairingTimeoutMs). A 20s cap here used to abort handshakes the
        // session layer was still pursuing; 60s also gives the Mac central more
        // advertising events to catch a scan response when its Wi-Fi/BT radio is
        // in a power-save state that drops the occasional SCAN_RSP.
        private const val PAIRING_TIMEOUT_MS = 60_000L
    }

    // BLE components
    private var advertiser: Advertiser? = null
    private var l2capServer: L2capServer? = null
    private var tcpTransport: TcpControlTransport? = null

    // Active L2CAP sessions, one per connected Mac (guarded by sessionLock).
    private val sessionLock = Any()
    private val sessions = mutableListOf<SessionHandle>()

    // Pairing state
    private val isPaired: Boolean get() = pairingStore.hasPairedMacs()
    @Volatile
    private var lastInboundHash: String? = null
    @Volatile
    private var lastSentTextHash: String? = null
    @Volatile
    private var lastReceivedImageHash: String? = null

    // Support
    private lateinit var clipboardWriter: ClipboardWriter
    private lateinit var clipboardSettingsStore: ClipboardSettingsStore
    private lateinit var pairingStore: PairingStore
    private lateinit var mediaOverlay: MediaTransferOverlay
    private val executor = Executors.newSingleThreadExecutor()
    private val clipboardAutoClearHandler = Handler(Looper.getMainLooper())
    private var pendingClipboardAutoClear: Runnable? = null

    @Volatile
    private var bleStarted = false
    @Volatile
    private var isDestroyed = false
    private var foregroundStarted = false
    @Volatile
    private var pairingInProgress = false
    private var pendingPairingKeyPair: java.security.KeyPair? = null
    private var pendingMacPublicKeyRaw: ByteArray? = null
    private val pairingTimeoutHandler = Handler(Looper.getMainLooper())
    private var pairingTimeoutRunnable: Runnable? = null

    // Auto-copy state (guards for ghost activity launches)
    @Volatile
    private var lastClipboardLaunchMs = 0L
    @Volatile
    private var ghostActivityInFlight = false
    private var ghostWatchdog: Runnable? = null
    @Volatile
    private var lastSentTextAtMs = 0L
    @Volatile
    private var suppressAutoCopyUntilMs = 0L

    // ── Session handles ───────────────────────────────────────────────

    /**
     * Per-connection SessionCallback adapter. Each connected Mac gets its own
     * handle; which Mac it is becomes known once the handshake identifies the
     * matching shared secret (or pairing completes).
     */
    private inner class SessionHandle : SessionCallback {
        @Volatile var session: Session? = null
        @Volatile var thread: Thread? = null
        @Volatile var isTcp = false
        /** Shared secret of the Mac on this connection (after handshake/pairing). */
        @Volatile var secretHex: String? = null
        @Volatile var ready = false

        fun close() {
            session?.close()
            thread?.let { t -> runCatching { t.join(2000) } }
        }

        override fun onSessionReady() = handleSessionReady(this)
        override fun onClipboardReceived(plaintext: ByteArray, hash: String) =
            handleClipboardReceivedFromMac(plaintext, hash)
        override fun onTransferComplete(hash: String) = handleTransferComplete(hash)
        override fun onSessionError(error: Exception) = handleSessionError(this, error)
        override fun hasHash(hash: String): Boolean = hash == lastInboundHash
        override fun onPairingComplete(sharedSecret: ByteArray, remoteName: String?) =
            handlePairingSecretEstablished(this, sharedSecret, remoteName)
        override fun onRichMediaSettingChanged(enabled: Boolean) =
            handleRichMediaSettingChanged(enabled)
        override fun onImageReceived(data: ByteArray, contentType: String, hash: String) =
            handleImageReceived(data, contentType, hash)
        override fun onImageSendFailed(reason: String) = handleImageSendFailed(reason)
        override fun onMediaTransferProgress(hash: String, transferred: Long, total: Long) =
            handleMediaTransferProgress(hash, transferred, total)
        override fun onMediaTransferProgress(hash: String, fileName: String?, transferred: Long, total: Long) =
            handleMediaTransferProgress(hash, fileName, transferred, total)
        override fun isDeviceAwake(): Boolean = isDeviceAwakeNow()
    }

    private fun readySessions(): List<SessionHandle> =
        synchronized(sessionLock) { sessions.filter { it.ready } }

    /** Remove a handle from the list. Returns false when it was already removed. */
    private fun removeSession(handle: SessionHandle): Boolean =
        synchronized(sessionLock) { sessions.remove(handle) }

    private fun closeAllSessions() {
        val snapshot = synchronized(sessionLock) {
            val copy = sessions.toList()
            sessions.clear()
            copy
        }
        anyMacConnected = false
        snapshot.forEach { it.close() }
    }

    private fun closeBleSessions() {
        val snapshot = synchronized(sessionLock) {
            val copy = sessions.filterNot { it.isTcp }
            sessions.removeAll(copy.toSet())
            copy
        }
        snapshot.forEach { it.close() }
        synchronized(sessionLock) { anyMacConnected = sessions.any { it.ready } }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    Log.w(TAG, "Bluetooth enabled — ensuring BLE components are running")
                    ensureBleComponentsState(restartIfRunning = true)
                    sendConnectionBroadcast(false)
                }
                BluetoothAdapter.STATE_OFF -> {
                    Log.w(TAG, "Bluetooth disabled — stopping BLE components")
                    stopBleComponents()
                }
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        clipboardWriter = ClipboardWriter(this)
        clipboardSettingsStore = ClipboardSettingsStore(this)
        pairingStore = PairingStore(this)
        mediaOverlay = MediaTransferOverlay(this)
        mediaOverlay.onCancel = {
            readySessions().forEach { it.session?.cancelMediaTransfer() }
            mediaOverlay.dismiss()
        }

        loadPairingState()
        DebugSmokeProbe.reset(this)

        // On Android 14+ a connectedDevice foreground service may not enter the
        // foreground without the Bluetooth runtime permissions — startForeground
        // throws SecurityException if the user denied "Nearby devices". Stop the
        // service instead of crashing the process.
        val foregrounded = runCatching {
            startForeground(1001, buildNotification())
        }.onFailure { error ->
            Log.e(TAG, "startForeground failed; stopping service", error)
        }.isSuccess
        if (!foregrounded) {
            stopSelf()
            return
        }
        foregroundStarted = true

        ContextCompat.registerReceiver(
            this,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ensureBleComponentsState()

        // Publish direct share shortcut if already paired
        if (isPaired) {
            publishDirectShareShortcut(directShareLabel())
        }
    }

    override fun onDestroy() {
        isDestroyed = true
        anyMacConnected = false
        clipboardAutoClearHandler.removeCallbacksAndMessages(null)
        clearPairingTimeout()
        // The receiver is only registered when startForeground succeeded in onCreate.
        if (foregroundStarted) {
            unregisterReceiver(bluetoothStateReceiver)
        }
        executor.shutdownNow()
        mediaOverlay.dismiss()
        stopBleComponents()
        tcpTransport?.close()
        tcpTransport = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        loadPairingState()

        when (intent?.action) {
            ACTION_UNPAIR -> {
                handleUnpairRequest()
                return START_STICKY
            }
            ACTION_FORGET_DEVICE -> {
                intent.getStringExtra(EXTRA_DEVICE_ID)?.let { handleForgetDevice(it) }
                return START_STICKY
            }
            ACTION_START_PAIRING -> {
                handleStartPairing()
                return START_STICKY
            }
            ACTION_CANCEL_PAIRING -> {
                cancelPairing(broadcastFailed = false)
                return START_STICKY
            }
            ACTION_RELOAD_PAIRING -> {
                // RELOAD_PAIRING handles its own BLE lifecycle — skip the
                // general ensureBleComponentsState() to avoid a double-start.
                if (!isPaired) {
                    if (bleStarted) {
                        stopBleComponents()
                    }
                    sendConnectionBroadcast(false)
                } else if (BlePermissions.hasRequiredRuntimePermissions(this)) {
                    if (bleStarted) {
                        stopBleComponents(broadcastDisconnected = false)
                    }
                    ensureBleComponentsState()
                } else {
                    Log.w(TAG, "BLE runtime permissions missing; stopping BLE components")
                    stopBleComponents()
                }
                return START_STICKY
            }
            ACTION_GHOST_FINISHED -> {
                clearGhostActivityInFlight()
                return START_STICKY
            }
            ACTION_ACCESSIBILITY_COPY_DETECTED -> {
                handleClipboardChanged()
                return START_STICKY
            }
            ACTION_SEND_CONFIG_UPDATE -> {
                readySessions().forEach { it.session?.sendConfigUpdate() }
                return START_STICKY
            }
            ACTION_PUSH_IMAGE -> {
                val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
                val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE) ?: "application/octet-stream"
                val fileName = intent.getStringExtra(EXTRA_IMAGE_NAME)
                if (imagePath != null) {
                    executor.execute {
                        pushImageToMac(imagePath, mimeType, fileName)
                    }
                }
            }
            ACTION_PUSH_TEXT -> {
                clearGhostActivityInFlight()
                val text = intent.getStringExtra(EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    executor.execute {
                        pushPlainTextToMac(text)
                    }
                }
            }
            ACTION_OPEN_URL -> {
                intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }?.let { url ->
                    executor.execute { openUrlOnMac(url) }
                }
            }
            ACTION_COPY_TO_CLIPBOARD -> return START_STICKY
            ACTION_QUERY_CONNECTION -> {
                broadcastConnectionState()
            }
        }

        ensureBleComponentsState()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── BLE stack management ──────────────────────────────────────────

    private fun ensureBleComponentsState(restartIfRunning: Boolean = false) {
        ensureTcpFallback()
        if (!isPaired && !pairingInProgress) {
            if (bleStarted) {
                Log.w(TAG, "Shared secret missing; stopping BLE components")
                stopBleComponents()
            }
            return
        }

        if (!BlePermissions.hasRequiredRuntimePermissions(this)) {
            if (bleStarted) {
                Log.w(TAG, "BLE runtime permissions missing; stopping BLE components")
                stopBleComponents()
            }
            return
        }

        if (restartIfRunning && bleStarted) {
            stopBleComponents(broadcastDisconnected = false)
        }

        if (bleStarted) {
            return
        }

        startBle()
    }

    private fun ensureTcpFallback() {
        if (!isPaired) return
        if (tcpTransport == null) {
            tcpTransport = TcpControlTransport { socket -> onTcpSocket(socket) }.also { it.start() }
        }
        pairingStore.loadPairedMacs().forEach { mac ->
            tcpTransport?.connect(mac.addresses) { _, socket -> onTcpSocket(socket, mac.secretHex, true) }
        }
    }

    private fun onTcpSocket(socket: java.net.Socket, secret: String? = null, initiator: Boolean = false) {
        val handle = SessionHandle()
        handle.isTcp = true
        val session = Session(socket.getInputStream(), socket.getOutputStream(), initiator, handle,
            sharedSecretHex = secret,
            settingsProvider = pairingStore,
            candidateSecretsHex = pairingStore.loadPairedMacs().map { it.secretHex })
        session.localName = localDeviceName()
        handle.session = session
        synchronized(sessionLock) { sessions.add(handle) }
        handle.thread = Thread({ session.performHandshake(); session.listenForMessages() }, "TCP-Session").apply {
            isDaemon = true
            start()
        }
    }

    private fun startBle() {
        Log.w(TAG, "startBle() — isPaired=$isPaired")
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            Log.e(TAG, "BluetoothAdapter unavailable")
            sendConnectionBroadcast(false)
            return
        }

        val serviceUUID = java.util.UUID.fromString("c10b0001-1234-5678-9abc-def012345678")

        val started = runCatching {
            // Replace any prior BLE session first — a lingering advertiser keeps
            // broadcasting a stale PSM and can block the new one (single LE slot).
            advertiser?.stop()
            advertiser = null
            l2capServer?.stop()
            l2capServer = null

            // 1. Start L2CAP server, get PSM
            val l2cap = L2capServer(adapter, this)
            val psm = l2cap.start()
            l2capServer = l2cap
            Log.w(TAG, "L2CAP server started on PSM $psm")

            // 2. Start advertising with PSM embedded in manufacturer data
            //    (No GATT server needed — Mac reads PSM from scan response)
            val adv = Advertiser(this, ParcelUuid(serviceUUID))
            adv.psm = psm
            adv.deviceTag = if (pairingInProgress) {
                pendingMacPublicKeyRaw?.let { macPub ->
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(macPub)
                        .copyOfRange(0, 8)
                }
            } else {
                pairingStore.identityTag()
            }
            adv.start()
            advertiser = adv
            Log.w(TAG, "BLE advertising started (psm=$psm, deviceTag=${advertiser?.deviceTag?.let { it.joinToString("") { b -> "%02x".format(b) } } ?: "null"})")

            true
        }.getOrElse { error ->
            bleStarted = false
            advertiser?.stop()
            advertiser = null
            l2capServer?.stop()
            l2capServer = null
            if (error is SecurityException) {
                Log.e(TAG, "BLE startup blocked by missing runtime permission", error)
            } else {
                Log.e(TAG, "BLE startup failed", error)
            }
            false
        }

        bleStarted = started
        if (!started) {
            sendConnectionBroadcast(false)
        }
    }

    private fun stopBleComponents(broadcastDisconnected: Boolean = true) {
        // Tear down all active sessions
        closeBleSessions()

        // Stop BLE stack
        advertiser?.stop()
        advertiser = null
        l2capServer?.stop()
        l2capServer = null
        bleStarted = false
        if (broadcastDisconnected) {
            sendConnectionBroadcast(false)
        }
    }

    private fun loadPairingState() {
        val identityTag = pairingStore.identityTag()
        advertiser?.deviceTag = identityTag
        if (identityTag == null) {
            saveConnectedDeviceName(null)
        }
    }

    // ── L2capServerCallback ───────────────────────────────────────────

    override fun onClientConnected(socket: BluetoothSocket) {
        Log.w(TAG, "L2CAP client connected")

        if (pairingInProgress) {
            sendPairingStatus(PAIRING_STAGE_EXCHANGING_KEYS)
        }

        // Determine session mode
        val mode = if (pairingInProgress) {
            val keyPair = pendingPairingKeyPair ?: run {
                Log.e(TAG, "Pairing in progress but no key pair available")
                return
            }
            val macPub = pendingMacPublicKeyRaw ?: run {
                Log.e(TAG, "Pairing in progress but no Mac public key available")
                return
            }
            SessionMode.Pairing(
                ownPrivateKey = keyPair.private,
                ownPublicKeyRaw = E2ECrypto.x25519PublicKeyToRaw(keyPair.public),
                remotePublicKeyRaw = macPub,
                identityTagHex = pairingStore.identityTagHex()
            )
        } else {
            SessionMode.Normal
        }

        // Create new session (Android is the responder). The HELLO's HMAC
        // identifies which paired Mac is connecting, so pass all secrets.
        val handle = SessionHandle()
        val session = Session(
            socket.inputStream, socket.outputStream,
            isInitiator = false,
            handle,
            mode = mode,
            settingsProvider = pairingStore,
            candidateSecretsHex = if (pairingInProgress) emptyList()
                else pairingStore.loadPairedMacs().map { it.secretHex }
        )
        session.localName = localDeviceName()
        handle.session = session
        synchronized(sessionLock) { sessions.add(handle) }

        handle.thread = Thread({
            session.performHandshake()
            session.listenForMessages()
        }, "L2CAP-Session").apply {
            isDaemon = true
            start()
        }
    }

    private fun localDeviceName(): String {
        val bluetoothName = runCatching {
            (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.name
        }.getOrNull()?.trim().orEmpty()
        if (bluetoothName.isNotEmpty()) return bluetoothName

        val systemName = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
            ?.trim().orEmpty()
        return systemName.ifEmpty { android.os.Build.MODEL }
    }

    override fun onAcceptError(error: IOException) {
        Log.e(TAG, "L2CAP accept error: ${error.message}")
        // The L2CAP server loop has exited. If we're still alive, restart.
        if (!isDestroyed && bleStarted) {
            Handler(Looper.getMainLooper()).post {
                if (!isDestroyed && bleStarted) {
                    Log.d(TAG, "Restarting BLE stack after L2CAP accept error")
                    stopBleComponents(broadcastDisconnected = false)
                    ensureBleComponentsState()
                }
            }
        }
    }

    // ── Session event handlers (called from SessionHandle) ───────────

    private fun handleSessionReady(handle: SessionHandle) {
        Log.w(TAG, "L2CAP session ready")

        val session = handle.session
        // The handshake identified which Mac this is (pairing sessions set
        // secretHex earlier, in handlePairingSecretEstablished).
        if (handle.secretHex == null) {
            handle.secretHex = session?.matchedSecretHex
        }
        handle.ready = true

        // If the same Mac reconnected while a stale session lingered, drop the
        // old one. Remove it from the list first so its error callback is a no-op.
        val stale = synchronized(sessionLock) {
            val others = sessions.filter { it !== handle && it.secretHex == handle.secretHex }
            sessions.removeAll(others)
            others
        }
        stale.forEach { it.close() }

        // If the advertiser's device tag was updated during pairing (without a
        // restart), restart it now so future reconnections use the correct tag.
        // The HELLO/WELCOME handshake is complete, so it's safe to cycle BLE
        // advertising without risking the active L2CAP connection.
        advertiser?.restart()

        // The HELLO/WELCOME handshake carries the remote device name. Persist
        // it so the UI always shows the real hostname (e.g. "Christian's Mac")
        // instead of null — during pairing, KEY_CONFIRM doesn't include a name
        // so this is the first point where the Mac's name is available.
        val remoteName = session?.remoteName
        val secret = handle.secretHex
        if (remoteName != null && secret != null) {
            pairingStore.updateMacName(secret, remoteName)
            saveConnectedDeviceName(remoteName)
        }
        if (secret != null) pairingStore.updateMacAddresses(secret, session?.remoteAddresses ?: emptyList())
        publishDirectShareShortcut(directShareLabel())

        broadcastConnectionState()
        DebugSmokeProbe.onConnectionChanged(this, true)
    }

    private fun handleClipboardReceivedFromMac(plaintext: ByteArray, hash: String) {
        val decodedText = plaintext.toString(Charsets.UTF_8)
        if (decodedText.isEmpty()) return

        lastInboundHash = hash
        suppressAutoCopyUntilMs = SystemClock.elapsedRealtime() + INBOUND_SUPPRESS_MS
        clipboardWriter.writeText(decodedText, markSensitive = clipboardSettingsStore.isHideSyncedClipboardEnabled())
        scheduleClipboardAutoClear(decodedText)
        sendClipboardTransferBroadcast(fromMac = true)
        DebugSmokeProbe.onInboundClipboardApplied(this, decodedText)
    }

    private fun handleTransferComplete(hash: String) {
        Log.d(TAG, "Outbound transfer complete: $hash")
        sendClipboardTransferBroadcast(fromMac = false)
        Handler(Looper.getMainLooper()).post { mediaOverlay.dismiss() }
    }

    private fun handleSessionError(handle: SessionHandle, error: Exception) {
        Log.e(TAG, "Session error: ${error.message}")
        // Already removed means we closed it deliberately — nothing to report.
        if (!removeSession(handle)) return

        broadcastConnectionState()
        DebugSmokeProbe.onConnectionChanged(this, readySessions().isNotEmpty())

        if (error is VersionMismatchException) {
            val intent = Intent(ACTION_VERSION_MISMATCH)
            intent.setPackage(packageName)
            sendBroadcast(intent)
        }
        // L2CAP server is still listening, will accept next connection
    }

    private fun handlePairingSecretEstablished(
        handle: SessionHandle,
        sharedSecret: ByteArray,
        remoteName: String?
    ) {
        val secretHex = sharedSecret.joinToString("") { "%02x".format(it) }
        Log.w(TAG, "ECDH pairing complete, storing shared secret")
        clearPairingTimeout()

        // Store the new pairing. The QR-scanned Mac name (if any) serves as the
        // initial display name until the handshake delivers the real hostname.
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val scannedName = prefs.getString(KEY_PENDING_PAIRING_NAME, null)?.takeIf { it.isNotBlank() }
            ?: remoteName
        if (!pairingStore.addPairedMac(secretHex, scannedName)) {
            Log.e(TAG, "Could not store new pairing (limit reached or storage unavailable)")
            cancelPairing(broadcastFailed = true)
            handle.session?.close()
            return
        }
        handle.secretHex = secretHex

        // Update the device tag for future advertisements, but do NOT restart
        // advertising now — the HELLO/WELCOME handshake is still in progress on
        // this same L2CAP connection.  Restarting BLE advertising mid-handshake
        // can disrupt the active connection on some Android devices.  The
        // advertiser will be restarted in handleSessionReady() once the full
        // handshake completes.
        advertiser?.deviceTag = pairingStore.identityTag()

        // Clear pairing state
        pairingInProgress = false
        pendingPairingKeyPair = null
        pendingMacPublicKeyRaw = null

        // Clean up temporary prefs
        prefs.edit()
            .remove("pending_pairing_pubkey")
            .remove(KEY_PENDING_PAIRING_NAME)
            .apply()

        // Save device name
        if (remoteName != null) {
            saveConnectedDeviceName(remoteName)
        }

        // Broadcast pairing complete (the UI re-reads the pairing store)
        val pairingIntent = Intent(ACTION_PAIRING_COMPLETE)
        pairingIntent.setPackage(packageName)
        pairingIntent.putExtra(EXTRA_DEVICE_NAME, scannedName)
        sendBroadcast(pairingIntent)
        publishDirectShareShortcut(directShareLabel())
    }

    private fun handleRichMediaSettingChanged(enabled: Boolean) {
        val intent = Intent(ACTION_RICH_MEDIA_SETTING_CHANGED)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_RICH_MEDIA_ENABLED, enabled)
        sendBroadcast(intent)
    }

    private fun handleImageSendFailed(reason: String) {
        Log.w(TAG, "Image send failed: $reason")
        Handler(Looper.getMainLooper()).post { mediaOverlay.dismiss() }
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                this,
                "Could not transfer image. Make sure both devices are on the same Wi-Fi network.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun handleMediaTransferProgress(hash: String, transferred: Long, total: Long) =
        handleMediaTransferProgress(hash, null, transferred, total)

    private fun handleMediaTransferProgress(hash: String, fileName: String?, transferred: Long, total: Long) {
        Handler(Looper.getMainLooper()).post {
            if (total > 0 && transferred >= total) mediaOverlay.dismiss()
            else mediaOverlay.update(fileName, transferred, total)
        }
        val intent = Intent(ACTION_CLIPBOARD_TRANSFER).apply {
            setPackage(packageName)
            putExtra("media_progress_hash", hash)
            putExtra("media_progress_transferred", transferred)
            putExtra("media_progress_total", total)
        }
        sendBroadcast(intent)
    }

    private fun handleImageReceived(data: ByteArray, contentType: String, hash: String) {
        lastReceivedImageHash = hash
        Log.w(TAG, "Received image from Mac (${data.size} bytes, $contentType)")
        clipboardWriter.writeMedia(data, contentType)
        sendClipboardTransferBroadcast(fromMac = true)
        Handler(Looper.getMainLooper()).post { mediaOverlay.dismiss() }
    }

    private fun isDeviceAwakeNow(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        return pm.isInteractive && !km.isDeviceLocked
    }

    // ── Outbound (Android → Mac) ─────────────────────────────────────

    private fun pushPlainTextToMac(text: String) {
        if (isDestroyed) return
        val plaintext = text.toByteArray(Charsets.UTF_8)
        if (plaintext.isEmpty() || plaintext.size > MAX_CLIPBOARD_BYTES) {
            return
        }

        // Dedup: skip if we sent this exact text a moment ago (double-fires from
        // click + toast + toolbar detections). Time-windowed, not forever — the
        // Mac clipboard may change in between, so re-copying must sync again.
        val textHash = MessageDigest.getInstance("SHA-256")
            .digest(plaintext).joinToString("") { "%02x".format(it) }
        val now = SystemClock.elapsedRealtime()
        if (textHash == lastSentTextHash && now - lastSentTextAtMs < SENT_TEXT_DEDUPE_WINDOW_MS) {
            Log.d(TAG, "Skipping send — same text sent very recently")
            return
        }

        val targets = readySessions()
        if (targets.isEmpty()) {
            Log.d(TAG, "No active L2CAP session; skipping Android->Mac push")
            return
        }

        targets.forEach { it.session?.sendClipboard(plaintext) }
        // Record only after an actual send, so text "sent" while disconnected
        // isn't suppressed after reconnecting.
        lastSentTextHash = textHash
        lastSentTextAtMs = now
        DebugSmokeProbe.onOutboundClipboardPublished(this, text)
    }

    private fun openUrlOnMac(url: String) {
        readySessions().forEach { it.session?.openUrl(url) }
    }

    private fun pushImageToMac(imagePath: String, mimeType: String, fileName: String? = null) {
        if (isDestroyed) return
        val file = java.io.File(imagePath)
        if (!file.exists()) {
            Log.e(TAG, "Image file not found: $imagePath")
            return
        }
        val imageData = file.readBytes()
        file.delete() // clean up cache file after reading

        val maxSize = ClipboardContentForwarder.MAX_MEDIA_BYTES
        if (imageData.isEmpty() || imageData.size > maxSize) {
            Log.w(TAG, "Image too large or empty: ${imageData.size} bytes")
            Handler(Looper.getMainLooper()).post {
                android.widget.Toast.makeText(this, "Media too large to send (max 20 MB)", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }

        val targets = readySessions()
        if (targets.isEmpty()) {
            Log.w(TAG, "No active session; cannot send image")
            Handler(Looper.getMainLooper()).post {
                android.widget.Toast.makeText(this, "Not connected to Mac", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (!pairingStore.isRichMediaEnabled()) {
            Log.w(TAG, "Rich media not enabled; cannot send image")
            Handler(Looper.getMainLooper()).post {
                android.widget.Toast.makeText(this, "Media sync is not enabled", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }

        val displayName = fileName ?: file.name.removePrefix("clipboard_media_").removePrefix("share_media_")
        targets.forEach { it.session?.sendImage(imageData, mimeType, displayName) }
        Log.w(TAG, "Queued image for send to ${targets.size} Mac(s) (${imageData.size} bytes, $mimeType)")
    }

    // ── Pairing ────────────────────────────────────────────────────────

    private fun handleStartPairing() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val macPubKeyHex = prefs.getString("pending_pairing_pubkey", null) ?: return
        val macPubKeyRaw = E2ECrypto.hexToBytes(macPubKeyHex)

        if (pairingStore.loadPairedMacs().size >= PairingStore.MAX_PAIRED_MACS) {
            Log.w(TAG, "Pairing rejected: limit of ${PairingStore.MAX_PAIRED_MACS} Macs reached")
            sendPairingStatus(PAIRING_STAGE_FAILED)
            return
        }

        // Replace any pairing already in flight (e.g. user re-scanned).
        clearPairingTimeout()

        // Generate ephemeral X25519 key pair
        val keyPair = E2ECrypto.generateX25519KeyPair()

        // Store pairing state for session creation
        pendingPairingKeyPair = keyPair
        pendingMacPublicKeyRaw = macPubKeyRaw
        pairingInProgress = true

        Log.w(TAG, "Started pairing mode with pairing tag")

        // Switch the advertisement to the pairing tag. Existing connections to
        // other Macs stay up — only the advertiser cycles; the L2CAP server
        // keeps listening on the same PSM.
        if (bleStarted) {
            advertiser?.deviceTag = java.security.MessageDigest.getInstance("SHA-256")
                .digest(macPubKeyRaw)
                .copyOfRange(0, 8)
            advertiser?.restart()
        } else {
            ensureBleComponentsState()
        }

        if (!bleStarted) {
            // BLE startup failed (e.g. Bluetooth off) — fail fast instead of
            // letting the user stare at "Connecting…" for the full timeout.
            cancelPairing(broadcastFailed = true)
            return
        }

        sendPairingStatus(PAIRING_STAGE_CONNECTING)
        pairingTimeoutRunnable = Runnable {
            if (pairingInProgress) {
                Log.w(TAG, "Pairing timed out after ${PAIRING_TIMEOUT_MS}ms")
                cancelPairing(broadcastFailed = true)
            }
        }.also { pairingTimeoutHandler.postDelayed(it, PAIRING_TIMEOUT_MS) }
    }

    private fun sendPairingStatus(stage: String) {
        val intent = Intent(ACTION_PAIRING_STATUS)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_PAIRING_STAGE, stage)
        sendBroadcast(intent)
    }

    private fun clearPairingTimeout() {
        pairingTimeoutRunnable?.let { pairingTimeoutHandler.removeCallbacks(it) }
        pairingTimeoutRunnable = null
    }

    // Aborts an in-progress pairing: stops advertising, clears pending keys/prefs.
    // broadcastFailed=true for timeouts/errors (UI shows the error card);
    // false for user-initiated cancel (UI already knows).
    private fun cancelPairing(broadcastFailed: Boolean) {
        clearPairingTimeout()
        if (!pairingInProgress) return
        Log.w(TAG, "Pairing cancelled (broadcastFailed=$broadcastFailed)")
        pairingInProgress = false
        pendingPairingKeyPair = null
        pendingMacPublicKeyRaw = null
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .remove("pending_pairing_pubkey")
            .remove(KEY_PENDING_PAIRING_NAME)
            .apply()
        if (broadcastFailed) {
            // Broadcast FAILED before tearing down BLE so the ViewModel is already
            // in Unpaired state when the disconnected broadcast arrives.
            sendPairingStatus(PAIRING_STAGE_FAILED)
        }
        if (isPaired && bleStarted) {
            // Other Macs remain paired — go back to advertising the identity
            // tag without dropping their live sessions.
            advertiser?.deviceTag = pairingStore.identityTag()
            advertiser?.restart()
        } else {
            stopBleComponents(broadcastDisconnected = false)
        }
    }

    // ── Unpair ────────────────────────────────────────────────────────

    private fun handleUnpairRequest() {
        cancelPairing(broadcastFailed = false)
        val hadConnection = bleStarted

        pairingStore.clear()
        clipboardSettingsStore.setAutoCopyOnboardingShown(false)
        clipboardSettingsStore.setAutoCopyEnabled(false)
        removeDirectShareShortcut()
        loadPairingState()

        if (hadConnection) {
            stopBleComponents()
        } else {
            sendConnectionBroadcast(false)
        }
    }

    /** Forget a single Mac (by PairedMac.id). Falls back to full unpair cleanup for the last one. */
    private fun handleForgetDevice(deviceId: String) {
        val mac = pairingStore.loadPairedMacs().firstOrNull { it.id == deviceId } ?: return
        Log.w(TAG, "Forgetting paired Mac ${mac.name ?: deviceId}")
        pairingStore.removePairedMac(mac.secretHex)

        // Drop the live session for that Mac, if any
        val doomed = synchronized(sessionLock) {
            val matches = sessions.filter { it.secretHex == mac.secretHex }
            sessions.removeAll(matches)
            matches
        }
        doomed.forEach { it.close() }

        if (!isPaired) {
            // Last Mac forgotten — same cleanup as a full unpair
            clipboardSettingsStore.setAutoCopyOnboardingShown(false)
            clipboardSettingsStore.setAutoCopyEnabled(false)
            removeDirectShareShortcut()
            loadPairingState()
            stopBleComponents()
        } else {
            publishDirectShareShortcut(directShareLabel())
            broadcastConnectionState()
        }
    }

    // ── Clipboard helpers ─────────────────────────────────────────────

    private fun scheduleClipboardAutoClear(inboundText: String) {
        pendingClipboardAutoClear?.let {
            clipboardAutoClearHandler.removeCallbacks(it)
            pendingClipboardAutoClear = null
        }

        if (!clipboardSettingsStore.isAutoClearSyncedClipboardEnabled()) {
            return
        }

        val clearRunnable = Runnable {
            pendingClipboardAutoClear = null
            if (!clipboardSettingsStore.isAutoClearSyncedClipboardEnabled()) {
                return@Runnable
            }
            clipboardWriter.clearClipIfMatches(inboundText)
        }
        pendingClipboardAutoClear = clearRunnable
        clipboardAutoClearHandler.postDelayed(clearRunnable, ClipboardSettingsStore.AUTO_CLEAR_DELAY_MS)
    }

    // ── Auto-copy (triggered by AccessibilityService) ────────────────

    private fun handleClipboardChanged() {
        val readyCount = readySessions().size
        Log.d(TAG, "Clipboard changed — readySessions=$readyCount, ghostInFlight=$ghostActivityInFlight")

        // Skip if no active session (no Mac connected)
        if (readyCount == 0) {
            Log.d(TAG, "Skipping clipboard: no active session")
            return
        }

        // Monotonic clock — wall clock can jump and break the debounce window
        val now = SystemClock.elapsedRealtime()

        // Skip detections caused by our own Mac→Android clipboard write
        if (now < suppressAutoCopyUntilMs) {
            Log.d(TAG, "Skipping clipboard: inbound write suppression")
            return
        }

        // Time guard to prevent double-fires (click + toast + toolbar close)
        if (now - lastClipboardLaunchMs < CLIPBOARD_DEBOUNCE_MS) {
            Log.d(TAG, "Skipping clipboard: debounce (${now - lastClipboardLaunchMs}ms)")
            return
        }
        lastClipboardLaunchMs = now

        // Skip if ghost activity is already in flight
        if (ghostActivityInFlight) {
            Log.d(TAG, "Skipping clipboard: ghost activity in flight")
            return
        }

        // Always launch ghost activity — even when the app is "foreground" per
        // ProcessLifecycleOwner, a Service cannot read the clipboard on Android 10+.
        // Only an Activity with window focus can call getPrimaryClip() successfully.
        Log.d(TAG, "Launching ghost activity for clipboard read")
        ghostActivityInFlight = true
        // Watchdog: if the ghost never launches or dies before reporting back,
        // clear the flag so auto-copy doesn't stay wedged until a restart.
        ghostWatchdog?.let(clipboardAutoClearHandler::removeCallbacks)
        ghostWatchdog = Runnable {
            if (ghostActivityInFlight) {
                Log.w(TAG, "Ghost activity watchdog fired — clearing in-flight flag")
                clearGhostActivityInFlight()
            }
        }.also { clipboardAutoClearHandler.postDelayed(it, GHOST_WATCHDOG_MS) }
        val ghostIntent = Intent(this, ClipboardGhostActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
        runCatching { startActivity(ghostIntent) }.onFailure {
            Log.e(TAG, "Could not launch ghost activity", it)
            clearGhostActivityInFlight()
        }
    }

    fun clearGhostActivityInFlight() {
        ghostActivityInFlight = false
        ghostWatchdog?.let(clipboardAutoClearHandler::removeCallbacks)
        ghostWatchdog = null
    }

    // ── Direct Share shortcut ─────────────────────────────────────────

    private fun publishDirectShareShortcut(deviceName: String?) {
        val label = deviceName ?: "Mac"
        ShortcutManagerCompat.removeDynamicShortcuts(this, listOf("send_to_mac"))
        val openShortcut = ShortcutInfoCompat.Builder(this, "open_on_device")
            .setShortLabel("Open in $label")
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_open_in_device))
            .setIntent(Intent(Intent.ACTION_SEND).apply {
                setClass(this@ClipboardService, net.wastu.clipboard.ui.OpenUrlShareReceiverActivity::class.java)
                type = "text/plain"
            })
            .setCategories(setOf("net.wastu.clipboard.category.SEND_TO_MAC"))
            .setLongLived(true)
            .build()

        val copyShortcut = ShortcutInfoCompat.Builder(this, "copy_to_clipboard")
            .setShortLabel("Copy to clipboard")
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_binder_clip))
            .setIntent(Intent(Intent.ACTION_SEND).apply {
                setClass(this@ClipboardService, net.wastu.clipboard.ui.CopyToClipboardShareReceiverActivity::class.java)
                type = "*/*"
            })
            .setCategories(setOf("net.wastu.clipboard.category.COPY_TO_CLIPBOARD"))
            .setLongLived(true)
            .build()

        ShortcutManagerCompat.addDynamicShortcuts(this, listOf(openShortcut, copyShortcut))
        Log.d(TAG, "Published direct share shortcuts for $label")
    }

    private fun removeDirectShareShortcut() {
        ShortcutManagerCompat.removeDynamicShortcuts(this, listOf("send_to_mac", "open_on_device", "copy_to_clipboard"))
        Log.d(TAG, "Removed direct share shortcut")
    }

    // ── Broadcasts ────────────────────────────────────────────────────

    private fun sendClipboardTransferBroadcast(fromMac: Boolean) {
        val intent = Intent(ACTION_CLIPBOARD_TRANSFER)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_FROM_MAC, fromMac)
        sendBroadcast(intent)
    }

    private fun sendConnectionBroadcast(connected: Boolean, deviceName: String? = null) {
        val intent = Intent(ACTION_CONNECTION_STATE)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_CONNECTED, connected)
        intent.putStringArrayListExtra(EXTRA_CONNECTED_IDS, ArrayList())
        if (deviceName != null) intent.putExtra(EXTRA_DEVICE_NAME, deviceName)
        sendBroadcast(intent)
        updateNotification()
    }

    /** Broadcast the full per-Mac connection state (ids of Macs with a ready session). */
    private fun broadcastConnectionState() {
        val macs = pairingStore.loadPairedMacs()
        val ids = ArrayList<String>()
        var firstName: String? = null
        readySessions().forEach { handle ->
            val mac = macs.firstOrNull { it.secretHex == handle.secretHex } ?: return@forEach
            ids.add(mac.id)
            if (firstName == null) firstName = mac.name ?: handle.session?.remoteName
        }
        val intent = Intent(ACTION_CONNECTION_STATE)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_CONNECTED, ids.isNotEmpty())
        intent.putStringArrayListExtra(EXTRA_CONNECTED_IDS, ids)
        firstName?.let { intent.putExtra(EXTRA_DEVICE_NAME, it) }
        sendBroadcast(intent)
        updateNotification()

        anyMacConnected = ids.isNotEmpty()
    }

    /** Share-sheet target label: single Mac shows its name, several show a collective label. */
    private fun directShareLabel(): String? {
        val macs = pairingStore.loadPairedMacs()
        return when {
            macs.isEmpty() -> null
            macs.size == 1 -> macs[0].name ?: loadConnectedDeviceName()
            else -> "All Macs"
        }
    }

    // ── Preferences ───────────────────────────────────────────────────

    private fun saveConnectedDeviceName(name: String?) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().apply {
            if (name != null) putString(KEY_CONNECTED_DEVICE, name) else remove(KEY_CONNECTED_DEVICE)
            apply()
        }
    }

    private fun loadConnectedDeviceName(): String? =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_CONNECTED_DEVICE, null)

    // ── Notification ──────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val channelId = "clipboard-service"
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            channelId,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, net.wastu.clipboard.ui.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(notificationText())
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .build()
    }

    private fun notificationText(): String {
        val macs = pairingStore.loadPairedMacs()
        if (macs.isEmpty()) return getString(R.string.notification_not_paired)
        val ready = readySessions()
        val connected = macs.filter { mac -> ready.any { it.secretHex == mac.secretHex } }
        return when (connected.size) {
            0 -> getString(R.string.notification_waiting)
            1 -> {
                val match = ready.firstOrNull { it.secretHex == connected[0].secretHex }
                getString(
                    R.string.notification_connected_one,
                    connected[0].name ?: match?.session?.remoteName ?: "Mac"
                )
            }
            else -> getString(R.string.notification_connected_many, connected.size)
        }
    }

    private fun updateNotification() {
        if (!foregroundStarted) return
        getSystemService(NotificationManager::class.java).notify(1001, buildNotification())
    }
}
