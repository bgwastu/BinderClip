package net.wastu.clipboard.debug

// BroadcastReceiver for smoke-test intents: import/clear pairing secrets and reset the probe.

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import net.wastu.clipboard.pairing.PairingStore
import net.wastu.clipboard.service.ClipboardService

class DebugSmokeReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_IMPORT_PAIRING = "net.wastu.clipboard.debug.IMPORT_PAIRING"
        const val ACTION_CLEAR_PAIRING = "net.wastu.clipboard.debug.CLEAR_PAIRING"
        const val ACTION_RESET_PROBE = "net.wastu.clipboard.debug.RESET_PROBE"
        const val ACTION_SET_IMAGE_SYNC = "net.wastu.clipboard.debug.SET_IMAGE_SYNC"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_DEVICE_NAME = "device_name"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_IMPORT_PAIRING -> {
                val token = intent.getStringExtra(EXTRA_TOKEN)
                if (token.isNullOrBlank() || !isHexToken(token)) {
                    setResultCode(2)
                    return
                }
                val normalizedToken = token.lowercase()
                val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME)

                runCatching {
                    if (!PairingStore(context).addPairedMac(normalizedToken, deviceName)) {
                        setResultCode(3)
                        return
                    }
                    if (!deviceName.isNullOrBlank()) {
                        context.getSharedPreferences(ClipboardService.PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putString(ClipboardService.KEY_CONNECTED_DEVICE, deviceName)
                            .apply()
                    }
                    DebugSmokeProbe.onPairingImported(context, normalizedToken, deviceName)
                    reloadPairingInService(context)
                }.onFailure {
                    setResultCode(3)
                    return
                }

                setResultCode(1)
            }

            ACTION_CLEAR_PAIRING -> {
                runCatching {
                    val unpairStarted = unpairInService(context)
                    if (!unpairStarted) {
                        PairingStore(context).clear()
                    }
                    context.getSharedPreferences(ClipboardService.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .remove(ClipboardService.KEY_CONNECTED_DEVICE)
                        .apply()
                    DebugSmokeProbe.reset(context)
                }.onFailure {
                    setResultCode(3)
                    return
                }

                setResultCode(1)
            }

            ACTION_RESET_PROBE -> {
                DebugSmokeProbe.reset(context)
                setResultCode(1)
            }

            ACTION_SET_IMAGE_SYNC -> {
                runCatching {
                    PairingStore(context).setRichMediaEnabled(true, System.currentTimeMillis() / 1_000)
                }.onFailure {
                    setResultCode(3)
                    return
                }

                setResultCode(1)
            }

            else -> {
                setResultCode(0)
            }
        }
    }

    private fun isHexToken(token: String): Boolean {
        if (token.length != 64) return false
        return token.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    private fun reloadPairingInService(context: Context) {
        val reloadIntent = Intent(context, ClipboardService::class.java).apply {
            action = ClipboardService.ACTION_RELOAD_PAIRING
        }

        val startedExistingService = runCatching {
            context.startService(reloadIntent)
        }.getOrNull() != null

        if (!startedExistingService) {
            ContextCompat.startForegroundService(context, reloadIntent)
        }
    }

    private fun unpairInService(context: Context): Boolean {
        val unpairIntent = Intent(context, ClipboardService::class.java).apply {
            action = ClipboardService.ACTION_UNPAIR
        }

        val startedExistingService = runCatching {
            context.startService(unpairIntent)
        }.getOrNull() != null

        if (startedExistingService) {
            return true
        }

        return runCatching {
            ContextCompat.startForegroundService(context, unpairIntent)
            true
        }.getOrDefault(false)
    }
}
