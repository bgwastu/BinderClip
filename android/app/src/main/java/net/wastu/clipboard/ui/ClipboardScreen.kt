package net.wastu.clipboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.wastu.clipboard.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardScreen(
    state: AppState,
    autoClearEnabled: Boolean,
    hideClipboardEnabled: Boolean,
    autoCopyEnabled: Boolean,
    autoCopyAccessibilityEnabled: Boolean,
    imageSyncEnabled: Boolean,
    pairingFailed: Boolean,
    onPairingCancelClick: () -> Unit,
    onPairingErrorDismiss: () -> Unit,
    onPairClick: () -> Unit,
    onForgetMacClick: (String) -> Unit,
    onAutoClearSettingChanged: (Boolean) -> Unit,
    onHideClipboardSettingChanged: (Boolean) -> Unit,
    onAutoCopySettingChanged: (Boolean) -> Unit,
    onImageSyncSettingChanged: (Boolean) -> Unit,
    onAutoCopyFixClick: () -> Unit,
) {
    val macs = (state as? AppState.Paired)?.macs.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BinderClip") },
                navigationIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_binder_clip),
                        contentDescription = null,
                        modifier = Modifier.padding(start = 16.dp).size(28.dp)
                    )
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { SectionLabel("Connection") }
            if (state !is AppState.Paired) {
                item {
                ConnectionStateRow(
                    state = state,
                    onCancel = onPairingCancelClick
                )
                }
            }

            if (pairingFailed) {
                item {
                    PairingErrorRow(
                        onTryAgain = onPairClick,
                        onDismiss = onPairingErrorDismiss
                    )
                }
            }

            when (state) {
                AppState.Unpaired -> item {
                    Button(onClick = onPairClick, modifier = Modifier.fillMaxWidth()) {
                        Text("Pair a Mac")
                    }
                }
                is AppState.Pairing -> Unit
                is AppState.Paired -> {
                    items(macs.size) { index ->
                        MacRow(macs[index], onForgetMacClick)
                    }
                    item {
                        FilledTonalButton(onClick = onPairClick, modifier = Modifier.fillMaxWidth()) {
                            Text("Pair another Mac")
                        }
                    }
                }
            }

            if (state is AppState.Paired) {
                item { Spacer(Modifier.size(4.dp)) }
                item { SectionLabel("Sync") }
                item {
                    PreferenceSwitch(
                        title = stringResource(R.string.auto_clear_setting_title),
                        summary = stringResource(R.string.auto_clear_setting_subtitle),
                        checked = autoClearEnabled,
                        onCheckedChange = onAutoClearSettingChanged
                    )
                }
                item { HorizontalDivider() }
                item {
                    PreferenceSwitch(
                        title = stringResource(R.string.hide_clipboard_setting_title),
                        summary = stringResource(R.string.hide_clipboard_setting_subtitle),
                        checked = hideClipboardEnabled,
                        onCheckedChange = onHideClipboardSettingChanged
                    )
                }
                item { HorizontalDivider() }
                item {
                    PreferenceSwitch(
                        title = stringResource(R.string.media_sync_setting_title),
                        summary = stringResource(R.string.media_sync_setting_subtitle),
                        checked = imageSyncEnabled,
                        onCheckedChange = onImageSyncSettingChanged
                    )
                }
                item { HorizontalDivider() }
                item {
                    PreferenceSwitch(
                        title = stringResource(R.string.auto_copy_setting_title),
                        summary = when {
                            autoCopyEnabled && !autoCopyAccessibilityEnabled ->
                                stringResource(R.string.auto_copy_needs_accessibility)
                            autoCopyEnabled -> stringResource(R.string.auto_copy_setting_subtitle_on)
                            else -> stringResource(R.string.auto_copy_setting_subtitle_off)
                        },
                        checked = autoCopyEnabled,
                        onCheckedChange = onAutoCopySettingChanged,
                        actionLabel = if (autoCopyEnabled && !autoCopyAccessibilityEnabled) "Fix" else null,
                        onAction = onAutoCopyFixClick
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun ConnectionStateRow(state: AppState, onCancel: () -> Unit) {
    when (state) {
        AppState.Unpaired -> ListItem(
            headlineContent = { Text("No Mac paired") },
            supportingContent = { Text("Pair a Mac to start syncing your clipboard.") }
        )
        is AppState.Pairing -> ListItem(
            headlineContent = {
                Text(
                    when (state.stage) {
                        PairingStage.Connecting -> "Connecting to your Mac"
                        PairingStage.ExchangingKeys -> "Securing the connection"
                    }
                )
            },
            supportingContent = { Text("Keep both devices nearby until pairing is complete.") },
            leadingContent = { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) },
            trailingContent = { TextButton(onClick = onCancel) { Text("Cancel") } }
        )
        is AppState.Paired -> {
            val connected = state.macs.count { it.connected }
            ListItem(
                headlineContent = {
                    ConnectionStatus(
                        text = if (connected > 0) "$connected Mac${if (connected == 1) "" else "s"} connected" else "Looking for your Mac",
                        connected = connected > 0
                    )
                },
                supportingContent = {
                    Text(if (connected > 0) "BinderClip is ready." else "BinderClip will reconnect automatically.")
                }
            )
        }
    }
}

@Composable
private fun MacRow(mac: PairedMacUi, onForgetMacClick: (String) -> Unit) {
    ListItem(
        headlineContent = { Text(mac.name ?: "Mac") },
        supportingContent = { ConnectionStatus(if (mac.connected) "Connected" else "Searching", mac.connected) },
        trailingContent = {
            TextButton(onClick = { onForgetMacClick(mac.id) }) { Text("Forget") }
        }
    )
}

@Composable
private fun ConnectionStatus(text: String, connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val dotColor = if (connected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        }
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

@Composable
private fun PairingErrorRow(onTryAgain: () -> Unit, onDismiss: () -> Unit) {
    ListItem(
        headlineContent = { Text("Couldn’t reach your Mac", color = MaterialTheme.colorScheme.error) },
        supportingContent = { Text("Check that Bluetooth is on and BinderClip is open on your Mac.") },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onTryAgain) { Text("Try again") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    )
}

@Composable
private fun PreferenceSwitch(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (actionLabel != null) {
                    TextButton(onClick = onAction) { Text(actionLabel) }
                }
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange
                )
            }
        }
    )
}

@Composable
fun AccessibilityDisclosureDialog(onAllow: () -> Unit, onDeny: () -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text(stringResource(R.string.accessibility_disclosure_title)) },
        text = { Text(stringResource(R.string.accessibility_disclosure_body)) },
        confirmButton = { TextButton(onClick = onAllow) { Text(stringResource(R.string.accessibility_disclosure_allow)) } },
        dismissButton = { TextButton(onClick = onDeny) { Text(stringResource(R.string.accessibility_disclosure_deny)) } },
    )
}

@Composable
fun BlePermissionDialog(
    permanentlyDenied: Boolean,
    onContinue: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.ble_permission_title)) },
        text = {
            Text(
                stringResource(
                    if (permanentlyDenied) R.string.ble_permission_denied_body
                    else R.string.ble_permission_body
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(if (permanentlyDenied) R.string.ble_permission_open_settings else R.string.ble_permission_continue))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.ble_permission_cancel)) } },
    )
}

@Composable
fun VersionMismatchDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connection compatibility issue") },
        text = { Text("The Mac and Android BinderClip protocol is incompatible. Reconnect after both apps are updated.") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}
