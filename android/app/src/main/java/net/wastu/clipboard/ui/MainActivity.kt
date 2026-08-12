package net.wastu.clipboard.ui

// Main activity: handles permissions, QR scanning results, and hosts the Compose UI.

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import net.wastu.clipboard.R
import net.wastu.clipboard.pairing.PairingStore
import net.wastu.clipboard.permissions.BlePermissions
import net.wastu.clipboard.service.ClipboardAccessibilityService
import net.wastu.clipboard.service.ClipboardService
import net.wastu.clipboard.settings.ClipboardSettingsStore

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var clipboardSettingsStore: ClipboardSettingsStore
    private var pendingMediaOverlayEnable = false

    // Pairing is gated on the BLE ("Nearby devices") runtime permission: without it
    // the connectedDevice foreground service cannot start and pairing would fail.
    private var showBlePermissionDialog by mutableStateOf(false)
    private var blePermissionPermanentlyDenied by mutableStateOf(false)

    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ClipboardService.ACTION_CONNECTION_STATE -> {
                    val connectedIds =
                        intent.getStringArrayListExtra(ClipboardService.EXTRA_CONNECTED_IDS)
                            ?: arrayListOf()
                    viewModel.onMacsChanged(loadMacsForUi(connectedIds.toSet()))
                }
                ClipboardService.ACTION_PAIRING_COMPLETE -> {
                    viewModel.onPaired(loadMacsForUi(emptySet()))
                    requestBatteryOptimizationAndOnboarding()
                }
                ClipboardService.ACTION_PAIRING_STATUS -> {
                    when (intent.getStringExtra(ClipboardService.EXTRA_PAIRING_STAGE)) {
                        ClipboardService.PAIRING_STAGE_CONNECTING ->
                            viewModel.onPairingStatus(PairingStage.Connecting)
                        ClipboardService.PAIRING_STAGE_EXCHANGING_KEYS ->
                            viewModel.onPairingStatus(PairingStage.ExchangingKeys)
                        ClipboardService.PAIRING_STAGE_FAILED ->
                            viewModel.onPairingFailed()
                    }
                }
                ClipboardService.ACTION_CLIPBOARD_TRANSFER -> Unit
                ClipboardService.ACTION_VERSION_MISMATCH -> {
                    viewModel.onVersionMismatch()
                }
                ClipboardService.ACTION_RICH_MEDIA_SETTING_CHANGED -> {
                    val enabled = intent.getBooleanExtra(ClipboardService.EXTRA_RICH_MEDIA_ENABLED, false)
                    viewModel.onImageSyncSettingChanged(enabled)
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        ensureServiceRunning()
        val queryIntent = Intent(this, ClipboardService::class.java)
        queryIntent.action = ClipboardService.ACTION_QUERY_CONNECTION
        startServiceSafely(queryIntent)
    }

    // Set when a clipboard://pair deep link arrives before BLE permission is granted, so the
    // post-grant callback resumes pairing from the link instead of opening the camera scanner.
    private var pendingDeepLinkInfo: net.wastu.clipboard.pairing.PairingInfo? = null

    private val pairPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (BlePermissions.hasRequiredRuntimePermissions(this)) {
            ensureServiceRunning()
            val deepLink = pendingDeepLinkInfo
            if (deepLink != null) {
                pendingDeepLinkInfo = null
                startPairingFromInfo(deepLink)
            } else {
                launchQrScanner()
            }
        } else {
            // Denied again — re-show the explanation. If Android will no longer
            // show the system prompt, the dialog routes to app settings instead.
            blePermissionPermanentlyDenied = BlePermissions.requiredRuntimePermissions()
                .none { shouldShowRequestPermissionRationale(it) }
            showBlePermissionDialog = true
        }
    }

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // The service broadcasts pairing progress (CONNECTING → EXCHANGING_KEYS →
            // PAIRING_COMPLETE/FAILED); set Connecting optimistically since the
            // CONNECTING broadcast may fire before this activity resumes.
            viewModel.onPairingStarted()
        }
    }

    private val batteryOptLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Battery optimization dialog dismissed — now show onboarding
        launchOnboardingIfNeeded()
    }

    private val onboardingLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // Refresh auto-copy state after onboarding
            viewModel.onAutoCopySettingChanged(clipboardSettingsStore.isAutoCopyEnabled())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableLightEdgeToEdge()

        requestRuntimePermissions()
        ensureServiceRunning()
        clipboardSettingsStore = ClipboardSettingsStore(this)

        val pairingStore = PairingStore(this)
        val autoClearEnabled = clipboardSettingsStore.isAutoClearSyncedClipboardEnabled()
        val autoCopyEnabled = clipboardSettingsStore.isAutoCopyEnabled()
        val imageSyncEnabled = pairingStore.isRichMediaEnabled()
        val mediaOverlayEnabled = clipboardSettingsStore.isMediaOverlayEnabled()
        val hideClipboardEnabled = clipboardSettingsStore.isHideSyncedClipboardEnabled()
        viewModel.initState(loadMacsForUi(emptySet()), autoClearEnabled, autoCopyEnabled, imageSyncEnabled, hideClipboardEnabled, mediaOverlayEnabled)

        setContent {
            val state by viewModel.state.collectAsState()
            val autoClearEnabled by viewModel.autoClearEnabled.collectAsState()
            val hideClipboardEnabled by viewModel.hideClipboardEnabled.collectAsState()
            val autoCopyEnabled by viewModel.autoCopyEnabled.collectAsState()
            val autoCopyAccessibilityEnabled by viewModel.autoCopyAccessibilityEnabled.collectAsState()
            val imageSyncEnabled by viewModel.imageSyncEnabled.collectAsState()
            val mediaOverlayEnabled by viewModel.mediaOverlayEnabled.collectAsState()
            val showVersionMismatch by viewModel.showVersionMismatch.collectAsState()
            val pairingFailed by viewModel.pairingFailed.collectAsState()
            var showAccessibilityDisclosure by remember { mutableStateOf(false) }

            ClipboardTheme {
                if (showVersionMismatch) {
                    VersionMismatchDialog(onDismiss = { viewModel.onVersionMismatchDismissed() })
                }

            if (showBlePermissionDialog) {
                BlePermissionDialog(
                    permanentlyDenied = blePermissionPermanentlyDenied,
                    onContinue = {
                        showBlePermissionDialog = false
                        if (blePermissionPermanentlyDenied) {
                            startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        } else {
                            pairPermissionLauncher.launch(
                                BlePermissions.requiredRuntimePermissions().toTypedArray()
                            )
                        }
                    },
                    onCancel = { showBlePermissionDialog = false }
                )
            }

            if (showAccessibilityDisclosure) {
                AccessibilityDisclosureDialog(
                    onAllow = {
                        showAccessibilityDisclosure = false
                        viewModel.onAutoCopySettingChanged(true)
                        clipboardSettingsStore.setAutoCopyEnabled(true)
                        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onDeny = {
                        showAccessibilityDisclosure = false
                        viewModel.onAutoCopySettingChanged(false)
                        clipboardSettingsStore.setAutoCopyEnabled(false)
                    }
                )
            }

                ClipboardScreen(
                    state = state,
                autoClearEnabled = autoClearEnabled,
                hideClipboardEnabled = hideClipboardEnabled,
                autoCopyEnabled = autoCopyEnabled,
                autoCopyAccessibilityEnabled = autoCopyAccessibilityEnabled,
                imageSyncEnabled = imageSyncEnabled,
                mediaOverlayEnabled = mediaOverlayEnabled,
                pairingFailed = pairingFailed,
                onPairingCancelClick = {
                    viewModel.onPairingCancelled()
                    val cancelIntent = Intent(this, ClipboardService::class.java)
                    cancelIntent.action = ClipboardService.ACTION_CANCEL_PAIRING
                    startServiceSafely(cancelIntent)
                },
                onPairingErrorDismiss = {
                    viewModel.onPairingFailedDismissed()
                },
                onPairClick = {
                    if (BlePermissions.hasRequiredRuntimePermissions(this)) {
                        launchQrScanner()
                    } else {
                        blePermissionPermanentlyDenied =
                            BlePermissions.requiredRuntimePermissions()
                                .none { shouldShowRequestPermissionRationale(it) }
                        showBlePermissionDialog = true
                    }
                },
                onForgetMacClick = { macId ->
                    val forgetIntent = Intent(this, ClipboardService::class.java)
                    forgetIntent.action = ClipboardService.ACTION_FORGET_DEVICE
                    forgetIntent.putExtra(ClipboardService.EXTRA_DEVICE_ID, macId)
                    if (!startServiceSafely(forgetIntent)) {
                        // Service unavailable (e.g. missing BLE permissions) — remove directly.
                        val store = PairingStore(this)
                        store.loadPairedMacs().firstOrNull { it.id == macId }
                            ?.let { store.removePairedMac(it.secretHex) }
                    }
                    // The service removes the pairing asynchronously — drop it
                    // from the UI immediately rather than waiting for the broadcast.
                    viewModel.onMacForgotten(loadMacsForUi(emptySet()).filterNot { it.id == macId })
                },
                onAutoClearSettingChanged = { enabled ->
                    viewModel.onAutoClearSettingChanged(enabled)
                    clipboardSettingsStore.setAutoClearSyncedClipboardEnabled(enabled)
                },
                onHideClipboardSettingChanged = { enabled ->
                    viewModel.onHideClipboardSettingChanged(enabled)
                    clipboardSettingsStore.setHideSyncedClipboardEnabled(enabled)
                },
                onAutoCopySettingChanged = { enabled ->
                    if (enabled && !isAccessibilityServiceEnabled()) {
                        showAccessibilityDisclosure = true
                    } else {
                        viewModel.onAutoCopySettingChanged(enabled)
                        clipboardSettingsStore.setAutoCopyEnabled(enabled)
                    }
                },
                onImageSyncSettingChanged = { enabled ->
                    viewModel.onImageSyncSettingChanged(enabled)
                    PairingStore(this).setRichMediaEnabled(enabled, System.currentTimeMillis() / 1000)
                    val configIntent = Intent(this, ClipboardService::class.java)
                    configIntent.action = ClipboardService.ACTION_SEND_CONFIG_UPDATE
                    startServiceSafely(configIntent)
                },
                onMediaOverlaySettingChanged = { enabled ->
                    if (enabled && !Settings.canDrawOverlays(this)) {
                        pendingMediaOverlayEnable = true
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                        viewModel.onMediaOverlaySettingChanged(false)
                    } else {
                        viewModel.onMediaOverlaySettingChanged(enabled)
                        clipboardSettingsStore.setMediaOverlayEnabled(enabled)
                    }
                },
                onAutoCopyFixClick = {
                    showAccessibilityDisclosure = true
                },
                )
            }
        }

        // A clipboard://pair link may have launched us (e.g. from a system QR scanner).
        if (savedInstanceState == null) handlePairingDeepLink(intent)

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(ClipboardService.ACTION_CONNECTION_STATE).also {
            it.addAction(ClipboardService.ACTION_PAIRING_COMPLETE)
            it.addAction(ClipboardService.ACTION_PAIRING_STATUS)
            it.addAction(ClipboardService.ACTION_CLIPBOARD_TRANSFER)
            it.addAction(ClipboardService.ACTION_VERSION_MISMATCH)
            it.addAction(ClipboardService.ACTION_RICH_MEDIA_SETTING_CHANGED)
        }
        ContextCompat.registerReceiver(
            this,
            connectionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        viewModel.onAccessibilityStateChanged(isAccessibilityServiceEnabled())
        viewModel.onImageSyncSettingChanged(PairingStore(this).isRichMediaEnabled())
        if (pendingMediaOverlayEnable) {
            pendingMediaOverlayEnable = false
            val enabled = Settings.canDrawOverlays(this)
            viewModel.onMediaOverlaySettingChanged(enabled)
            clipboardSettingsStore.setMediaOverlayEnabled(enabled)
        }
        val queryIntent = Intent(this, ClipboardService::class.java)
        queryIntent.action = ClipboardService.ACTION_QUERY_CONNECTION
        startServiceSafely(queryIntent)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(connectionReceiver)
    }

    /** Read the paired Macs from the store and apply per-Mac connection flags. */
    private fun loadMacsForUi(connectedIds: Set<String>): List<PairedMacUi> =
        PairingStore(this).loadPairedMacs().map { mac ->
            PairedMacUi(id = mac.id, name = mac.name, connected = mac.id in connectedIds)
        }

    private fun launchQrScanner() {
        scannerLauncher.launch(Intent(this, QrScannerActivity::class.java))
    }

    /**
     * Handle a clipboard://pair?k=…&n=… deep link (e.g. opened from a regular QR scanner).
     * Consumes the intent's data so a config-change recreate doesn't re-trigger pairing.
     */
    private fun handlePairingDeepLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val info = intent.data?.toString()?.let { net.wastu.clipboard.pairing.PairingUriParser.parse(it) }
        intent.data = null
        if (info == null) return
        if (BlePermissions.hasRequiredRuntimePermissions(this)) {
            startPairingFromInfo(info)
        } else {
            pendingDeepLinkInfo = info
            blePermissionPermanentlyDenied = BlePermissions.requiredRuntimePermissions()
                .none { shouldShowRequestPermissionRationale(it) }
            showBlePermissionDialog = true
        }
    }

    /** Store the Mac's key/name and signal the service to begin pairing — same path as the scanner. */
    private fun startPairingFromInfo(info: net.wastu.clipboard.pairing.PairingInfo) {
        getSharedPreferences(ClipboardService.PREFS_NAME, MODE_PRIVATE).edit()
            .putString("pending_pairing_pubkey", info.publicKeyHex)
            .putString(ClipboardService.KEY_PENDING_PAIRING_NAME, info.deviceName ?: "")
            .apply()
        val pairIntent = Intent(this, ClipboardService::class.java).apply {
            action = ClipboardService.ACTION_START_PAIRING
        }
        if (startServiceSafely(pairIntent)) {
            viewModel.onPairingStarted()
            Toast.makeText(this, "Pairing…", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureServiceRunning() {
        startServiceSafely(Intent(this, ClipboardService::class.java))
    }

    private fun startServiceSafely(intent: Intent): Boolean {
        if (!BlePermissions.hasRequiredRuntimePermissions(this)) return false
        val started = runCatching {
            ContextCompat.startForegroundService(this, intent)
        }.isSuccess
        if (!started) {
            Toast.makeText(this, "Could not start BinderClip", Toast.LENGTH_SHORT).show()
        }
        return started
    }

    private fun requestBatteryOptimizationAndOnboarding() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            // Already exempt — go straight to onboarding
            launchOnboardingIfNeeded()
            return
        }

        // Launch battery optimization dialog; onboarding follows in the result callback
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        batteryOptLauncher.launch(intent)
    }

    private fun launchOnboardingIfNeeded() {
        if (clipboardSettingsStore.isAutoCopyOnboardingShown()) return
        onboardingLauncher.launch(Intent(this, AutoCopyOnboardingActivity::class.java))
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "${packageName}/${ClipboardAccessibilityService::class.java.canonicalName}"
        val enabledServices = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(service)
    }

    private fun requestRuntimePermissions() {
        val permissions = BlePermissions.requiredRuntimePermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isEmpty()) return
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

}
