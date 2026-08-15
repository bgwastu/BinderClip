package net.wastu.binderclip

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeviceHub
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

sealed interface SharedPayload {
    data class Image(val value: ImagePayload) : SharedPayload
    data class Text(val value: String) : SharedPayload
}

object SharedPayloadCache {
    @Volatile
    var value: SharedPayload? = null
}

/** Native Android share-sheet endpoint with interactive target device selection. */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLog.initialize(this)

        val payload = when (intent.action) {
            Intent.ACTION_SEND -> {
                val stream = (if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(
                    Intent.EXTRA_STREAM,
                    Uri::class.java
                )
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                    ?: intent.clipData?.getItemAt(0)?.uri
                when {
                    stream != null -> ImageClipboard.readUri(this, stream, intent.type)?.let(SharedPayload::Image)
                    intent.type == "text/plain" -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                        ?.takeIf { it.isNotBlank() }?.let(SharedPayload::Text)

                    else -> null
                }
            }

            else -> null
        }

        if (payload == null) {
            Log.w("BinderClip", "Share sheet did not provide supported content")
            DiagnosticLog.error("Could not read shared content")
            Toast.makeText(this, "Couldn’t send this content", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val store = DeviceStore(this)
        val candidateDevices = buildList {
            store.peer?.let(::add)
            addAll(store.members)
        }.distinctBy { it.deviceId }.filter { it.deviceId != store.deviceId }

        val isUrl = payload is SharedPayload.Text && (
                (payload.value.trim().lowercase().startsWith("http://") || payload.value.trim().lowercase()
                    .startsWith("https://")) &&
                        android.util.Patterns.WEB_URL.matcher(payload.value.trim()).matches()
                )

        // If no paired remote peers or only 1 remote peer and not a URL, we can send immediately.
        // If it is a URL or has candidate devices, show the device picker so the user can choose which device to open/send to.
        if (candidateDevices.isEmpty()) {
            sendPayload(payload, targetDeviceId = null)
            return
        }

        setContent {
            BinderClipTheme {
                ShareDevicePickerScreen(
                    payload = payload,
                    isUrl = isUrl,
                    devices = candidateDevices,
                    onSelectDevice = { targetId ->
                        sendPayload(payload, targetDeviceId = targetId)
                    },
                    onDismiss = { finish() }
                )
            }
        }
    }

    private fun sendPayload(payload: SharedPayload, targetDeviceId: String?) {
        Log.i(
            "BinderClip",
            "Accepted shared ${if (payload is SharedPayload.Image) "image" else "text"} for target: $targetDeviceId"
        )
        SharedPayloadCache.value = payload
        val serviceIntent = Intent(this, BinderClipService::class.java).apply {
            action = BinderClipService.ACTION_SEND_SHARED
            if (!targetDeviceId.isNullOrBlank()) putExtra(BinderClipService.EXTRA_TARGET_DEVICE_ID, targetDeviceId)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        Toast.makeText(
            this,
            if (payload is SharedPayload.Text && (payload.value.startsWith("http://") || payload.value.startsWith("https://"))) "Sending link…" else "Sending…",
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }
}

@Composable
private fun ShareDevicePickerScreen(
    payload: SharedPayload,
    isUrl: Boolean,
    devices: List<RememberedPeer>,
    onSelectDevice: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isUrl) Icons.Outlined.OpenInBrowser else Icons.Outlined.DeviceHub,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (isUrl) "Open Link on Device" else "Share to Device",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            val subtitle = when (payload) {
                                is SharedPayload.Text -> payload.value.trim().lines().firstOrNull()?.take(40)
                                    ?: "Text content"

                                is SharedPayload.Image -> "Image (${payload.value.mimeType})"
                            }
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    HorizontalDivider()

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            ListItem(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSelectDevice(null) },
                                headlineContent = { Text("All Connected Devices", fontWeight = FontWeight.Medium) },
                                supportingContent = { Text(if (isUrl) "Opens in browser on all devices" else "Sends to all devices") },
                                leadingContent = {
                                    Icon(
                                        imageVector = Icons.Outlined.DeviceHub,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                                        alpha = 0.5f
                                    )
                                )
                            )
                        }

                        items(devices, key = { it.deviceId }) { device ->
                            ListItem(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSelectDevice(device.deviceId) },
                                headlineContent = { Text(device.name, fontWeight = FontWeight.Medium) },
                                supportingContent = {
                                    Text(
                                        if (isUrl) "Opens browser on ${device.name}"
                                        else if (device.connected) "Connected" else "Direct route"
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        imageVector = if (device.platform.contains(
                                                "mac",
                                                ignoreCase = true
                                            )
                                        ) Icons.Outlined.LaptopMac else Icons.Outlined.Smartphone,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                                        alpha = 0.3f
                                    )
                                )
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}

