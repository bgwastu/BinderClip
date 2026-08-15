package net.wastu.binderclip

import android.content.Context
import net.wastu.binderclip.webrtc.WebRTCTransport
import net.wastu.binderclip.webrtc.WebRTCTransportEvent
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the invisible WebRTC upgrade for one TCP peer. After pairing, both sides
 * bring up a WebRTC DataChannel and exchange identity cards over the encrypted
 * TCP control channel (the same length-prefixed JSON framing); clipboard text
 * and URLs then sync over WebRTC so they work across networks. TCP remains the
 * silent fallback for control/roster/media.
 *
 * Used identically by [DirectClient] (joiner) and [DirectServer] (host) so a
 * Mac and an Android device interop out of the box.
 */
class WebRTCUpgrade(
    private val context: Context,
    private val seal: (JSONObject) -> JSONObject,
    private val sendControl: (JSONObject) -> Unit,
    private val onFrame: (ByteArray) -> Unit,
) {
    private val started = AtomicBoolean(false)
    private var transport: WebRTCTransport? = null
    /** True when this device should send the SDP offer (smaller device ID). */
    var amOfferer: Boolean = true

    companion object {
        /** Wrap an already-connected transport (pure-WebRTC pairing path). */
        fun fromExisting(context: Context, existing: WebRTCTransport, onFrame: (ByteArray) -> Unit): WebRTCUpgrade {
            val upgrade = WebRTCUpgrade(context, { it }, { _ -> }, onFrame)
            upgrade.transport = existing
            upgrade.started.set(true)
            existing.onFrame = onFrame
            return upgrade
        }
    }

    /** Start the upgrade (idempotent). */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        val t = WebRTCTransport(
            context = this.context,
            onEvent = { event ->
                when (event) {
                    is WebRTCTransportEvent.Log -> DiagnosticLog.info(event.message)
                    is WebRTCTransportEvent.Connecting -> DiagnosticLog.info("WebRTC connecting: ${event.message}")
                    is WebRTCTransportEvent.Disconnected -> DiagnosticLog.warning("WebRTC disconnected: ${event.message}")
                    is WebRTCTransportEvent.Connected -> DiagnosticLog.info("WebRTC connected")
                    else -> {}
                }
            },
        )
        transport = t
        t.onLocalOffer = { sdp ->
            if (amOfferer) {
                DiagnosticLog.info("WebRTC sending offer over control channel")
                val sealed = seal(JSONObject().put("type", "webrtcOffer").put("sdp", sdp))
                sendControl(sealed)
            }
        }
        t.onAnswerReady = { sdp ->
            DiagnosticLog.info("WebRTC sending answer over control channel")
            val sealed = seal(JSONObject().put("type", "webrtcAnswer").put("sdp", sdp))
            sendControl(sealed)
        }
        t.onFrame = { frame -> onFrame(frame) }
        t.beginSDPSession(createOffer = amOfferer)
    }

    /** Feed a peer `webrtcOffer` SDP. */
    fun processOffer(sdp: String) {
        DiagnosticLog.info("WebRTC received offer, feeding session")
        transport?.processRemoteOffer(sdp)
    }

    /** Feed a peer `webrtcAnswer` SDP. */
    fun processAnswer(sdp: String) {
        DiagnosticLog.info("WebRTC received answer, feeding session")
        transport?.processRemoteAnswer(sdp)
    }

    /** Feed a peer `webrtcCard` (legacy QR path). */
    fun processCard(cardBase64: String) {
        val payload = runCatching { android.util.Base64.decode(cardBase64, android.util.Base64.NO_WRAP) }.getOrNull()
        if (payload == null) {
            DiagnosticLog.error("WebRTC card decode failed")
            return
        }
        transport?.processScannedPayload(payload)
    }

    /** True when the underlying WebRTC DataChannel is open. */
    val isLive: Boolean
        get() = transport?.isOpen == true

    /** Send an already-framed encrypted payload over WebRTC when the channel is open. */
    fun sendFrame(frame: ByteArray): Boolean {
        val t = transport ?: return false
        if (!t.isOpen) return false
        t.send(frame)
        return true
    }

    fun close() {
        transport?.close()
        transport = null
        started.set(false)
    }
}
