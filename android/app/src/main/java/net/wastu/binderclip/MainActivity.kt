package net.wastu.binderclip

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {
    private var permissionRevision by mutableIntStateOf(0)
    private val scanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra("uri")?.let(::pair)
    }
    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionRevision += 1
        if (granted) scan()
    }
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { permissionRevision += 1 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); DiagnosticLog.initialize(this); startService(BinderClipService.ACTION_START)
        intent?.dataString?.takeIf { it.startsWith("binderclip://invite") }?.let(::pair)
        setContent {
            BinderClipTheme {
                val state by AppRuntime.state.collectAsState()
                val revision = permissionRevision
                val cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                val notificationsGranted =
                    Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                val power = getSystemService(PowerManager::class.java)
                val batteryOptimizationIgnored =
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.M || power.isIgnoringBatteryOptimizations(packageName)
                val backgroundRestricted =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && getSystemService(ActivityManager::class.java).isBackgroundRestricted
                val autoStartHelpNeeded = !getSharedPreferences("binderclip", MODE_PRIVATE).getBoolean(
                    "auto_start_help_seen",
                    false
                ) || backgroundRestricted
                DisposableEffect(Unit) {
                    startService(BinderClipService.ACTION_UI_VISIBLE, visible = true)
                    onDispose { startService(BinderClipService.ACTION_UI_VISIBLE, visible = false) }
                }
                BinderClipScreen(
                    state = state,
                    cameraGranted = cameraGranted,
                    notificationsGranted = notificationsGranted,
                    batteryOptimizationIgnored = batteryOptimizationIgnored,
                    autoStartHelpNeeded = autoStartHelpNeeded,
                    permissionRevision = revision,
                    onScan = ::scan,
                    onRequestCamera = { requestCamera.launch(Manifest.permission.CAMERA) },
                    onRequestNotifications = { if (Build.VERSION.SDK_INT >= 33) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    onOpenAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onRequestBatteryOptimization = ::requestBatteryOptimization,
                    onOpenAppDetails = ::openAutoStartSettings,
                    onRequestInvite = { startService(BinderClipService.ACTION_REQUEST_INVITE) },
                    onSend = { startService(BinderClipService.ACTION_SEND_CURRENT) },
                    onCopy = { startService(BinderClipService.ACTION_COPY_PENDING) },
                    onReconnect = { startService(BinderClipService.ACTION_SEARCH_RECONNECT) },
                    onToggleRoot = { enabled ->
                        startService(
                            BinderClipService.ACTION_TOGGLE_ROOT_AUTOMATION,
                            enabled = enabled
                        )
                    },
                    onDisableAccessibility = { startService(BinderClipService.ACTION_DISABLE_ACCESSIBILITY) },
                    onRemove = { id -> startService(BinderClipService.ACTION_REMOVE_MEMBER, memberId = id) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionRevision += 1
        startService(BinderClipService.ACTION_REFRESH_CAPABILITIES)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); intent.dataString?.takeIf { it.startsWith("binderclip://invite") }
            ?.let(::pair)
    }

    private fun scan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanner.launch(
            Intent(
                this,
                QrScannerActivity::class.java
            )
        )
        else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun pair(uri: String) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, BinderClipService::class.java).setAction(BinderClipService.ACTION_PAIR)
                .putExtra(BinderClipService.EXTRA_URI, uri)
        )
    }

    private fun requestBatteryOptimization() {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(request) }.getOrElse {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun openAutoStartSettings() {
        getSharedPreferences("binderclip", MODE_PRIVATE).edit().putBoolean("auto_start_help_seen", true).apply()
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        val candidates = mutableListOf<Intent>()
        when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> {
                candidates.add(
                    Intent().setComponent(
                        android.content.ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity"
                        )
                    )
                )
            }

            manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme") -> {
                candidates.add(
                    Intent().setComponent(
                        android.content.ComponentName(
                            "com.coloros.safecenter",
                            "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                        )
                    )
                )
                candidates.add(
                    Intent().setComponent(
                        android.content.ComponentName(
                            "com.oppo.safe",
                            "com.oppo.safe.permission.startup.StartupAppListActivity"
                        )
                    )
                )
            }

            manufacturer.contains("vivo") -> {
                candidates.add(
                    Intent().setComponent(
                        android.content.ComponentName(
                            "com.vivo.permissionmanager",
                            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                        )
                    )
                )
                candidates.add(
                    Intent().setComponent(
                        android.content.ComponentName(
                            "com.iqoo.secure",
                            "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                        )
                    )
                )
            }

            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                candidates.add(
                    Intent().setComponent(
                        android.content.ComponentName(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                        )
                    )
                )
                candidates.add(
                    Intent().setComponent(
                        android.content.ComponentName(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.optimize.process.ProtectActivity"
                        )
                    )
                )
            }
        }
        for (intent in candidates) {
            if (packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                runCatching { startActivity(intent); return }
            }
        }
        // Fallback: standard App Info page
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun startService(
        action: String,
        visible: Boolean? = null,
        enabled: Boolean? = null,
        memberId: String? = null
    ) {
        ContextCompat.startForegroundService(this, Intent(this, BinderClipService::class.java).setAction(action).also {
            if (visible != null) it.putExtra("visible", visible)
            if (enabled != null) it.putExtra("enabled", enabled)
            if (memberId != null) it.putExtra(BinderClipService.EXTRA_MEMBER_ID, memberId)
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BinderClipScreen(
    state: AppState,
    cameraGranted: Boolean,
    notificationsGranted: Boolean,
    batteryOptimizationIgnored: Boolean,
    autoStartHelpNeeded: Boolean,
    permissionRevision: Int,
    onScan: () -> Unit,
    onRequestCamera: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onRequestInvite: () -> Unit,
    onSend: () -> Unit,
    onCopy: () -> Unit,
    onReconnect: () -> Unit,
    onToggleRoot: (Boolean) -> Unit,
    onDisableAccessibility: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var selectedDevice by remember { mutableStateOf<RememberedPeer?>(null) }
    var showLogs by remember { mutableStateOf(false) }
    val pairingUrl by AppRuntime.pairingUrl.collectAsState()
    val diagnosticEvents by DiagnosticLog.events.collectAsState()
    val devices = buildList {
        addAll(state.members)
        state.peer?.let(::add)
        if (state.peer != null && none { it.deviceId == state.localDeviceId }) add(
            RememberedPeer(
                DeviceNames.android(
                    context
                ), "", 39_421, state.localDeviceId, "Android", true
            )
        )
    }.map { device ->
        if (device.deviceId == state.localDeviceId) device.copy(
            name = DeviceNames.android(context),
            platform = "Android"
        ) else device
    }.distinctBy { it.deviceId }
        .sortedWith(compareByDescending<RememberedPeer> { it.deviceId == state.localDeviceId }.thenBy { it.name.lowercase() })
    // Read so the composition updates immediately after Android's permission result.
    permissionRevision.hashCode()
    val missingPermissions = buildList {
        if (!cameraGranted) add(
            PermissionNeed(
                "Camera",
                "Scan pairing codes",
                Icons.Outlined.CameraAlt,
                onRequestCamera
            )
        )
        if (!notificationsGranted) add(
            PermissionNeed(
                "Notifications",
                "Show received clipboard",
                Icons.Outlined.Notifications,
                onRequestNotifications
            )
        )
        if (!state.rootAvailable && !state.accessibilityEnabled) add(
            PermissionNeed(
                "Automatic Sync Clipboard",
                "Enable Accessibility",
                Icons.Outlined.AccessibilityNew,
                onOpenAccessibility
            )
        )
        if (!batteryOptimizationIgnored) add(
            PermissionNeed(
                "Battery Optimization",
                "Allow reliable background sync",
                Icons.Outlined.BatteryChargingFull,
                onRequestBatteryOptimization
            )
        )
        if (autoStartHelpNeeded) add(
            PermissionNeed(
                "Auto Start",
                "Allow background start for reliable sync",
                Icons.Outlined.Settings,
                onOpenAppDetails,
                "Open"
            )
        )
    }
    var sentOverlayMessage by remember { mutableStateOf<String?>(null) }
    var sentOverlayIsSuccess by remember { mutableStateOf(true) }

    LaunchedEffect(state.status) {
        if (state.status.startsWith("Sending") || state.status.startsWith("Offering") || state.status.startsWith("Opening link")) {
            sentOverlayMessage = state.status
            sentOverlayIsSuccess = false
        } else if (state.status == "Sent URL to peer" || state.status == "Image sent" || state.status.startsWith("Received") || state.status.startsWith("Opened URL")) {
            sentOverlayMessage = if (state.status == "Sent URL to peer") "URL sent successfully" else if (state.status == "Image sent") "Image sent successfully" else state.status
            sentOverlayIsSuccess = true
            kotlinx.coroutines.delay(2200)
            sentOverlayMessage = null
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_binder_clip),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("BinderClip", fontWeight = FontWeight.SemiBold)
                }
            },
            actions = {
                IconButton(onClick = { showLogs = true }) {
                    Icon(Icons.Outlined.Description, contentDescription = "Show logs")
                }
            },
        )
    }) { insets ->
        Box(modifier = Modifier.fillMaxSize().padding(insets)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    ChainHeader(status = state.status, onReconnect = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onReconnect()
                    })
                }
                if (devices.isEmpty()) item { EmptyChain() }
                else items(devices.size, key = { devices[it].deviceId }) { index ->
                    val device = devices[index]
                    DeviceRow(
                        device,
                        isCurrentDevice = device.deviceId == state.localDeviceId,
                        onClick = { selectedDevice = device })
                }
            item {
                if (devices.isEmpty()) FilledTonalButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onScan()
                }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(
                        Icons.Outlined.QrCodeScanner,
                        contentDescription = null
                    ); Spacer(Modifier.width(10.dp)); Text("Scan a code")
                } else FilledTonalButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onRequestInvite()
                }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = null
                    ); Spacer(Modifier.width(10.dp)); Text("Add device")
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
            }
            item {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSend()
                    },
                    enabled = devices.any { it.connected && it.deviceId != state.localDeviceId },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Send,
                        contentDescription = null
                    ); Spacer(Modifier.width(10.dp)); Text("Send current clipboard")
                }
            }
            if (missingPermissions.isNotEmpty()) {
                item { SectionTitle("Permissions", topPadding = 16.dp) }
                items(missingPermissions.size, key = { missingPermissions[it].title }) { index ->
                    PermissionRow(missingPermissions[index])
                    if (index != missingPermissions.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                }
            }
            if (state.pendingText || state.pendingImage) item {
                ListItem(
                    headlineContent = { Text(if (state.pendingImage) "Image ready to copy" else "Text ready to copy") },
                    trailingContent = { TextButton(onClick = onCopy) { Text("Copy") } })
            }
            item { SectionTitle("Settings", topPadding = 16.dp) }
            item {
                if (state.rootAvailable) PreferenceToggle(
                    title = "Automatic Sync Clipboard",
                    summary = if (state.automaticClipboardEnabled) "Root syncs text and images in the background." else "Use approved root access for text and images.",
                    checked = state.automaticClipboardEnabled,
                    onChanged = onToggleRoot,
                ) else {
                    Column {
                        PreferenceToggle(
                            title = "Automatic Sync Clipboard",
                            summary = if (state.accessibilityEnabled) "Accessibility syncs copied text in the background." else "Enable Accessibility to sync copied text.",
                            checked = state.accessibilityEnabled,
                            onChanged = { enabled -> if (enabled) onOpenAccessibility() else onDisableAccessibility() },
                        )
                        Text(
                            "Tip: You can also use the notification action or share sheet to send content instantly without root.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

            androidx.compose.animation.AnimatedVisibility(
                visible = sentOverlayMessage != null,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(initialOffsetY = { it / 2 }),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically(targetOffsetY = { it / 2 }),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
            ) {
                sentOverlayMessage?.let { msg ->
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = if (sentOverlayIsSuccess) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shadowElevation = 6.dp,
                        tonalElevation = 4.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (sentOverlayIsSuccess) {
                                Icon(
                                    imageVector = Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (sentOverlayIsSuccess) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
    selectedDevice?.let { target ->
        val isCurrentDevice = target.deviceId == state.localDeviceId
        AlertDialog(
            onDismissRequest = { selectedDevice = null },
            title = { Text(target.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (target.connected) "Connected" else "Waiting to reconnect")
                    Text("IP: ${target.host.takeIf { it.isNotBlank() } ?: "Unavailable"}")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(target.deviceId); selectedDevice = null
                }) { Text("Remove From Chain") }
            },
            dismissButton = { TextButton(onClick = { selectedDevice = null }) { Text("Close") } },
        )
    }
    pairingUrl?.let { url -> PairingCodeDialog(url) { AppRuntime.pairingUrl.value = null } }
    if (showLogs) {
        var logQuery by remember { mutableStateOf("") }
        var selectedFilter by remember { mutableStateOf<DiagnosticLevel?>(null) }
        val filteredEvents = remember(diagnosticEvents, logQuery, selectedFilter) {
            diagnosticEvents.filter { event ->
                (selectedFilter == null || event.level == selectedFilter) &&
                        (logQuery.isBlank() || event.message.contains(logQuery, ignoreCase = true))
            }
        }
        AlertDialog(
            onDismissRequest = { showLogs = false },
            title = { Text("Logs (${filteredEvents.size})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.OutlinedTextField(
                        value = logQuery,
                        onValueChange = { logQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search logs…", style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = "Search") },
                        trailingIcon = {
                            if (logQuery.isNotEmpty()) {
                                IconButton(onClick = { logQuery = "" }) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        singleLine = true,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        androidx.compose.material3.FilterChip(
                            selected = selectedFilter == null,
                            onClick = { selectedFilter = null },
                            label = { Text("All") }
                        )
                        androidx.compose.material3.FilterChip(
                            selected = selectedFilter == DiagnosticLevel.Info,
                            onClick = {
                                selectedFilter =
                                    if (selectedFilter == DiagnosticLevel.Info) null else DiagnosticLevel.Info
                            },
                            label = { Text("Info") }
                        )
                        androidx.compose.material3.FilterChip(
                            selected = selectedFilter == DiagnosticLevel.Warning,
                            onClick = {
                                selectedFilter =
                                    if (selectedFilter == DiagnosticLevel.Warning) null else DiagnosticLevel.Warning
                            },
                            label = { Text("Warning") }
                        )
                        androidx.compose.material3.FilterChip(
                            selected = selectedFilter == DiagnosticLevel.Error,
                            onClick = {
                                selectedFilter =
                                    if (selectedFilter == DiagnosticLevel.Error) null else DiagnosticLevel.Error
                            },
                            label = { Text("Error") }
                        )
                    }
                    if (filteredEvents.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(140.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (logQuery.isBlank()) "No events yet." else "No matching events.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filteredEvents.size, key = { filteredEvents[it].timestamp }) { index ->
                                val event = filteredEvents[filteredEvents.lastIndex - index]
                                Text(
                                    "${
                                        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(event.timestamp))
                                    } · ${event.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when (event.level) {
                                        DiagnosticLevel.Error -> MaterialTheme.colorScheme.error
                                        DiagnosticLevel.Warning -> MaterialTheme.colorScheme.onSurfaceVariant
                                        DiagnosticLevel.Info -> MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLogs = false }) { Text("Close") } },
            dismissButton = { TextButton(onClick = { DiagnosticLog.clear() }) { Text("Clear") } },
        )
    }
}

private data class PermissionNeed(
    val title: String,
    val summary: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val onClick: () -> Unit,
    val actionLabel: String = "Allow"
)

@Composable
private fun PermissionRow(need: PermissionNeed) = ListItem(
    headlineContent = { Text(need.title) },
    supportingContent = { Text(need.summary) },
    leadingContent = { Icon(need.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
    trailingContent = { TextButton(onClick = need.onClick) { Text(need.actionLabel) } },
)

@Composable
private fun EmptyChain() =
    ListItem(headlineContent = { Text("No devices yet") }, supportingContent = { Text("Scan a code to join.") })

@Composable
private fun ChainHeader(status: String, onReconnect: () -> Unit) = Row(
    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
) {
    Text("This chain", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            status,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (status.startsWith("Waiting") || status.startsWith("Searching") || status.startsWith("Connection")) {
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onReconnect, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = "Reconnect",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, topPadding: androidx.compose.ui.unit.Dp = 14.dp) = Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = topPadding, bottom = 2.dp)
)

@Composable
private fun DeviceRow(member: RememberedPeer, isCurrentDevice: Boolean, onClick: () -> Unit) {
    val container = if (isCurrentDevice) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(MaterialTheme.shapes.medium).background(container)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Box(
            Modifier.align(Alignment.CenterStart).size(36.dp).clip(MaterialTheme.shapes.small)
                .background(if (isCurrentDevice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (member.platform == "macOS") Icons.Outlined.LaptopMac else Icons.Outlined.Android,
                contentDescription = if (member.platform == "macOS") "Mac" else "Android",
                tint = if (isCurrentDevice) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(
            Modifier.fillMaxWidth().padding(start = 48.dp).align(Alignment.CenterStart),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                member.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrentDevice) FontWeight.SemiBold else FontWeight.Normal
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(member.connected, 7.dp); Spacer(Modifier.width(8.dp))
                Text(
                    if (isCurrentDevice) "This device" else if (member.connected) "Connected" else "Searching",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusDot(connected: Boolean, size: androidx.compose.ui.unit.Dp) = Box(
    Modifier.size(size).clip(MaterialTheme.shapes.extraLarge)
        .background(if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
)

@Composable
private fun PreferenceToggle(title: String, summary: String, checked: Boolean, onChanged: (Boolean) -> Unit) = ListItem(
    headlineContent = { Text(title) },
    supportingContent = { Text(summary) },
    trailingContent = { Switch(checked = checked, onCheckedChange = onChanged) })

@Composable
private fun PairingCodeDialog(url: String, onDismiss: () -> Unit) {
    val image = remember(url) {
        val matrix = com.google.zxing.MultiFormatWriter().encode(url, com.google.zxing.BarcodeFormat.QR_CODE, 640, 640)
        android.graphics.Bitmap.createBitmap(640, 640, android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
            for (x in 0 until 640) for (y in 0 until 640) bitmap.setPixel(
                x,
                y,
                if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            )
        }.asImageBitmap()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a device") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    image,
                    contentDescription = "BinderClip pairing code",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
