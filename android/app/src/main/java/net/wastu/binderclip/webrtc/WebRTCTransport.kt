package net.wastu.binderclip.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import net.wastu.binderclip.DiagnosticLog
import net.wastu.binderclip.DirectFrameCodec
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.SessionDescription
import org.webrtc.SdpObserver
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A single WebRTC peer session established entirely through a QR-tango
 * handshake (no signaling server). Mirrors the macOS WebRTCTransport so a Mac
 * and an Android phone can pair by each scanning the other's code.
 *
 * This is the WAN/different-network path and, because ICE prefers host
 * candidates, also a robust same-network/mesh path. The existing DirectServer
 * / DirectClient raw-TCP transport is left untouched as a fallback.
 */
class WebRTCTransport(
    private val context: Context,
    private val onEvent: (WebRTCTransportEvent) -> Unit,
) {
    /** Called once ICE gathering completes and a local QR payload is ready. */
    var onLocalPayloadReady: ((ByteArray) -> Unit)? = null
    /** Called for each application frame (length-prefixed encrypted JSON) received. */
    var onFrame: ((ByteArray) -> Unit)? = null
    /** Called when the DataChannel transitions to open. */
    var onDataChannelOpen: (() -> Unit)? = null
    /** SDP-signaling path: our local offer SDP. */
    var onLocalOffer: ((String) -> Unit)? = null
    /** SDP-signaling path: our answer SDP. */
    var onAnswerReady: ((String) -> Unit)? = null

    private val receiveBuffer = java.io.ByteArrayOutputStream()

    enum class State { NEW, GATHERING, READY, CONNECTING, CONNECTED, DISCONNECTED, CLOSED }

    var state: State = State.NEW
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateQueue: ExecutorService = Executors.newSingleThreadExecutor()
    private val transferScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val iceRestart = AtomicBoolean(false)

    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var pendingRemote: WebRTCQRCodec.Packet? = null
    private var didNotifyReady = false
    private var watchdog: java.util.concurrent.ScheduledFuture<*>? = null
    private val localCandidates = java.util.concurrent.ConcurrentLinkedQueue<WebRTCQRCodec.Candidate>()
    @Volatile
    private var candidateWaitStarted = false

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
    }

    // MARK: - QR payload

    /** Generate the local identity card: DTLS fingerprint + ICE candidates. */
    fun currentQRPayload(): ByteArray? {
        val pc = peerConnection ?: return null
        val sdp = pc.localDescription?.description ?: return null
        val fingerprint = sdp.extractFingerprint() ?: return null
        val ufrag = sdp.extractIceUfrag() ?: return null
        val pwd = sdp.extractIcePwd() ?: return null
        // mDNS candidates only resolve on the same LAN and are useless for the
        // QR-tango cross-network handshake, so keep host/srflx with real IPs.
        val candidates = buildList {
            addAll(localCandidates)
            addAll(sdp.extractCandidates())
        }.distinctBy { it.port }.filter { it.addressFamily != 2 }.take(WebRTCQRCodec.MAX_CANDIDATES)
        val candidateSummary = candidates.joinToString(",") { "af=${it.addressFamily},sz=${it.address.size},p=${it.port}" }
        DiagnosticLog.info("WebRTC candidates: ${candidates.size} $candidateSummary")
        return try {
            WebRTCQRCodec.encode(
                WebRTCQRCodec.Packet(
                    fingerprint = fingerprint,
                    ufrag = WebRTCQRCodec.urlSafeBase64Encode(ufrag.toByteArray(Charsets.UTF_8)),
                    pwd = WebRTCQRCodec.urlSafeBase64Encode(pwd.toByteArray(Charsets.UTF_8)),
                    candidates = candidates,
                )
            )
        } catch (error: Exception) {
            DiagnosticLog.error("Could not build pairing payload: ${error.message}")
            null
        }
    }

    // MARK: - Session lifecycle

    /** Begin a fresh QR-tango session and gather ICE. */
    fun beginSession() {
        stateQueue.execute {
            teardown()
            val pc = createPeerConnection()
            peerConnection = pc
            pendingRemote = null
            didNotifyReady = false
            iceRestart.set(false)
            localCandidates.clear()
            candidateWaitStarted = false

            val init = DataChannel.Init().apply {
                ordered = true
                negotiated = true
                id = 0
            }
            val dc = pc.createDataChannel("binderclip-data", init)
            attachDataChannel(dc)
            dataChannel = dc

            pc.createOffer(sdpObserver(
                onSuccess = { sdp ->
                    stateQueue.execute {
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(sdp: SessionDescription?) {}
                            override fun onCreateFailure(reason: String?) {}
                            override fun onSetSuccess() { gatherOrNotify(pc) }
                            override fun onSetFailure(reason: String?) {
                                DiagnosticLog.error("setLocalDescription failed: $reason")
                            }
                        }, sdp)
                    }
                },
                onFailure = { DiagnosticLog.error("createOffer failed: $it") }
            ), MediaConstraints())
        }
    }

    /** Call when the user has scanned the remote QR payload. */
    fun processScannedPayload(payload: ByteArray) {
        stateQueue.execute {
            try {
                val remote = WebRTCQRCodec.decode(payload)
                DiagnosticLog.info("WebRTC received card: ${remote.candidates.size} candidates")
                pendingRemote = remote
                completeTango(remote)
            } catch (error: Exception) {
                emit(WebRTCTransportEvent.Connecting("Invalid pairing code"))
                DiagnosticLog.error("Invalid pairing code: ${error.message}")
            }
        }
    }

    // MARK: - SDP-based signaling (robust path)

    fun beginSDPSession(createOffer: Boolean = true) {
        stateQueue.execute {
            teardown()
            val pc = createPeerConnection()
            peerConnection = pc
            pendingRemote = null
            didNotifyReady = false
            iceRestart.set(false)
            localCandidates.clear()

            val init = DataChannel.Init().apply {
                ordered = true
                negotiated = true
                id = 0
            }
            val dc = pc.createDataChannel("binderclip-data", init)
            attachDataChannel(dc)
            dataChannel = dc

            if (!createOffer) return@execute
            pc.createOffer(sdpObserver(
                onSuccess = { sdp ->
                    stateQueue.execute {
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(sdp: SessionDescription?) {}
                            override fun onCreateFailure(reason: String?) {}
                            override fun onSetSuccess() {
                                // Wait for ICE gathering to finish so the offer
                                // carries candidates, then expose it.
                                mainHandler.postDelayed({ stateQueue.execute {
                                    val finalSDP = pc.localDescription?.description ?: sdp.description
                                    onLocalOffer?.invoke(finalSDP)
                                } }, 3000)
                            }
                            override fun onSetFailure(reason: String?) {
                                DiagnosticLog.error("setLocalDescription failed: $reason")
                            }
                        }, sdp)
                    }
                },
                onFailure = { DiagnosticLog.error("createOffer failed: $it") }
            ), MediaConstraints())
        }
    }

    fun processRemoteOffer(sdp: String) {
        stateQueue.execute {
            val pc = peerConnection ?: return@execute
            pc.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onCreateFailure(reason: String?) {}
                override fun onSetSuccess() {
                    pc.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(answer: SessionDescription?) {
                            if (answer != null) {
                                stateQueue.execute {
                                    pc.setLocalDescription(object : SdpObserver {
                                        override fun onCreateSuccess(sdp: SessionDescription?) {}
                                        override fun onCreateFailure(reason: String?) {}
                                        override fun onSetSuccess() {
                                            scheduleWatchdog()
                                            onAnswerReady?.invoke(answer.description)
                                        }
                                        override fun onSetFailure(reason: String?) {
                                            DiagnosticLog.error("setLocalDescription answer failed: $reason")
                                        }
                                    }, answer)
                                }
                            }
                        }
                        override fun onCreateFailure(reason: String?) {
                            DiagnosticLog.error("createAnswer failed: $reason")
                        }
                        override fun onSetSuccess() {}
                        override fun onSetFailure(reason: String?) {}
                    }, MediaConstraints())
                }
                override fun onSetFailure(reason: String?) {
                    DiagnosticLog.error("setRemoteDescription offer failed: $reason")
                }
            }, SessionDescription(SessionDescription.Type.OFFER, sdp))
        }
    }

    fun processRemoteAnswer(sdp: String) {
        stateQueue.execute {
            val pc = peerConnection ?: return@execute
            pc.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onCreateFailure(reason: String?) {}
                override fun onSetSuccess() { scheduleWatchdog() }
                override fun onSetFailure(reason: String?) {
                    DiagnosticLog.error("setRemoteDescription answer failed: $reason")
                }
            }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
        }
    }

    private fun completeTango(remote: WebRTCQRCodec.Packet) {
        val pc = peerConnection ?: return
        val local = currentQRPayload()
        if (local == null) {
            // Local card not ready (gathering not finished); retry shortly.
            DiagnosticLog.info("completeTango waiting for local card")
            mainHandler.postDelayed({ stateQueue.execute { completeTango(remote) } }, 1000)
            return
        }
        val localPacket = runCatching { WebRTCQRCodec.decode(local) }.getOrNull() ?: return
        val isAnswerer = compareFingerprints(localPacket.fingerprint, remote.fingerprint) < 0

        emit(WebRTCTransportEvent.Connecting("Establishing secure link…"))
        if (isAnswerer) {
            // Rollback the pending local offer, then adopt the remote offer.
            pc.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onCreateFailure(reason: String?) {}
                override fun onSetSuccess() {
                    val offer = SessionDescription(SessionDescription.Type.OFFER, remoteOfferSDP(remote, "actpass"))
                    pc.setRemoteDescription(object : SdpObserver {
                        override fun onCreateSuccess(sdp: SessionDescription?) {}
                        override fun onCreateFailure(reason: String?) {}
                        override fun onSetSuccess() {
                            pc.createAnswer(object : SdpObserver {
                                override fun onCreateSuccess(answer: SessionDescription?) {
                                    if (answer != null) {
                                        stateQueue.execute {
                                            pc.setLocalDescription(object : SdpObserver {
                                                override fun onCreateSuccess(sdp: SessionDescription?) {}
                                                override fun onCreateFailure(reason: String?) {}
                                                override fun onSetSuccess() { connectRemote(answer, pc) }
                                                override fun onSetFailure(reason: String?) {
                                                    DiagnosticLog.error("setLocalDescription answer failed: $reason")
                                                }
                                            }, answer)
                                        }
                                    }
                                }

                                override fun onCreateFailure(reason: String?) {
                                    DiagnosticLog.error("createAnswer failed: $reason")
                                }

                                override fun onSetSuccess() {}
                                override fun onSetFailure(reason: String?) {}
                            }, MediaConstraints())
                        }

                        override fun onSetFailure(reason: String?) {
                            DiagnosticLog.error("setRemoteDescription failed: $reason")
                        }
                    }, offer)
                }

                override fun onSetFailure(reason: String?) {
                    DiagnosticLog.error("rollback failed: $reason")
                }
            }, SessionDescription(SessionDescription.Type.ROLLBACK, ""))
        } else {
            // We hold a valid local offer; reconstruct the remote answer.
            val answer = SessionDescription(
                SessionDescription.Type.ANSWER,
                remoteAnswerSDP(remote, "active"),
            )
            connectRemote(answer, pc)
        }
    }

    private fun connectRemote(remote: SessionDescription, pc: PeerConnection) {
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(reason: String?) {}
            override fun onSetSuccess() { scheduleWatchdog() }
            override fun onSetFailure(reason: String?) {
                DiagnosticLog.error("setRemoteDescription failed: $reason")
            }
        }, remote)
    }

    private fun gatherOrNotify(pc: PeerConnection) {
        if (pc.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
            notifyReady(pc)
            return
        }
        mainHandler.postDelayed({ stateQueue.execute { if (pc.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) notifyReady(pc) } }, 5000)
    }

    private fun notifyReady(pc: PeerConnection) {
        if (didNotifyReady) return
        // Require at least one usable (non-mDNS) candidate so the peer can dial us.
        val usable = localCandidates.any { it.addressFamily != 2 } || sdpCandidatesUsable(pc)
        if (!usable) {
            // Give ICE a moment to deliver candidates, then give up gracefully.
            if (!candidateWaitStarted) {
                candidateWaitStarted = true
                mainHandler.postDelayed({
                    stateQueue.execute {
                        if (!didNotifyReady) {
                            candidateWaitStarted = false
                            if (pc.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) notifyReady(pc)
                        }
                    }
                }, 4000)
            }
            return
        }
        didNotifyReady = true
        currentQRPayload()?.let { payload ->
            val b64 = android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP)
            DiagnosticLog.info("WebRTC payload ready: $b64")
            onLocalPayloadReady?.invoke(payload)
            emit(WebRTCTransportEvent.Log("Pairing code ready"))
            emit(WebRTCTransportEvent.ReceivedRoster(b64))
        }
    }

    private fun sdpCandidatesUsable(pc: PeerConnection): Boolean {
        val sdp = pc.localDescription?.description ?: return false
        return sdp.extractCandidates().any { it.addressFamily != 2 }
    }
    private fun scheduleWatchdog() {
        watchdog?.cancel(false)
        watchdog = transferScheduler.schedule({
            stateQueue.execute {
                if (state != State.CONNECTED) {
                    emit(WebRTCTransportEvent.Disconnected("Waiting for peer…"))
                    maybeIceRestart()
                }
            }
        }, 30, TimeUnit.SECONDS)
    }

    private fun maybeIceRestart() {
        val pc = peerConnection ?: return
        if (iceRestart.getAndSet(true)) return
        emit(WebRTCTransportEvent.Connecting("Reconnecting…"))
        pc.restartIce()
        pc.createOffer(sdpObserver(
            onSuccess = { sdp ->
                stateQueue.execute {
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(sdp: SessionDescription?) {}
                        override fun onCreateFailure(reason: String?) {}
                        override fun onSetSuccess() { scheduleWatchdog() }
                        override fun onSetFailure(reason: String?) {
                            DiagnosticLog.error("ICE restart setLocalDescription failed: $reason")
                        }
                    }, sdp)
                }
            },
            onFailure = { DiagnosticLog.error("ICE restart offer failed: $it") }
        ), MediaConstraints())
    }

    // MARK: - Send

    val isOpen: Boolean
        get() = dataChannel?.state() == DataChannel.State.OPEN

    /** Send an application frame (length-prefixed encrypted JSON) over the DataChannel. */
    fun send(frame: ByteArray) {
        stateQueue.execute {
            val dc = dataChannel ?: return@execute
            if (dc.state() == DataChannel.State.OPEN) {
                dc.send(DataChannel.Buffer(ByteBuffer.wrap(frame), true))
            }
        }
    }

    fun close() {
        stateQueue.execute { teardown() }
    }

    fun shutdown() {
        stateQueue.shutdownNow()
        transferScheduler.shutdownNow()
    }

    private fun teardown() {
        watchdog?.cancel(false); watchdog = null
        dataChannel?.unregisterObserver(); dataChannel?.close(); dataChannel = null
        peerConnection?.close(); peerConnection = null
        pendingRemote = null
        didNotifyReady = false
        iceRestart.set(false)
        localCandidates.clear()
        candidateWaitStarted = false
        state = State.CLOSED
    }

    private fun createPeerConnection(): PeerConnection {
        val config = PeerConnection.RTCConfiguration(mutableListOf())
        config.iceServers = iceServers()
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        config.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        config.iceCandidatePoolSize = 0
        val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                stateQueue.execute {
                    when (newState) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            watchdog?.cancel(false); watchdog = null
                            iceRestart.set(false)
                            state = State.CONNECTED
                            emit(WebRTCTransportEvent.Connected("Connected"))
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            state = State.DISCONNECTED
                            emit(WebRTCTransportEvent.Disconnected("Waiting for peer…"))
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            state = State.DISCONNECTED
                            emit(WebRTCTransportEvent.Disconnected("Connection lost"))
                            maybeIceRestart()
                        }
                        PeerConnection.IceConnectionState.CLOSED -> {
                            state = State.CLOSED
                            emit(WebRTCTransportEvent.Disconnected("Closed"))
                        }
                        else -> {}
                    }
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
                if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                    stateQueue.execute { notifyReady(peerConnection ?: return@execute) }
                }
            }
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                parseIceCandidate(candidate)?.let { localCandidates.add(it) }
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>?) {}
            override fun onAddStream(stream: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {
                stateQueue.execute {
                    channel?.let { attachDataChannel(it); dataChannel = it }
                }
            }
            override fun onRenegotiationNeeded() {}
        }
        return factory.createPeerConnection(config, observer) ?: error("Could not create peer connection")
    }

    private fun attachDataChannel(dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (dc.state() == DataChannel.State.OPEN) {
                    emit(WebRTCTransportEvent.Log("Secure channel open"))
                    onDataChannelOpen?.invoke()
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                // Accumulate into the frame buffer and hand complete frames up.
                receiveBuffer.write(bytes)
                try {
                    while (true) {
                        val payload = DirectFrameCodec.nextFrame(receiveBuffer) ?: break
                        onFrame?.invoke(payload)
                    }
                } catch (error: Exception) {
                    DiagnosticLog.error("Rejected WebRTC frame: ${error.message}")
                    receiveBuffer.reset()
                }
            }
        })
    }

    // MARK: - ICE servers

    private fun iceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf<PeerConnection.IceServer>()
        listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302").forEach {
            servers.add(PeerConnection.IceServer.builder(it).createIceServer())
        }
        servers.add(
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer()
        )
        servers.add(
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer()
        )
        return servers
    }

    private fun parseIceCandidate(candidate: IceCandidate): WebRTCQRCodec.Candidate? {
        // candidate.sdp is a single line: "candidate:foundation comp-id transport priority ip port typ ..."
        val sdp = candidate.sdp ?: return null
        val parts = sdp.split(" ")
        if (parts.size < 8) return null
        val protocol = if (parts[2] == "tcp") 1 else 0
        val ip = parts[4]
        val port = parts[5].toIntOrNull() ?: return null
        val type = if (parts[7].contains("srflx")) 1 else 0
        val address: ByteArray
        val family: Int
        if (ip.contains(":")) {
            family = 1
            address = parseIpv6(ip)
        } else if (ip.endsWith(".local")) {
            family = 2
            address = ByteArray(16)
        } else {
            family = 0
            address = ip.split(".").mapNotNull { it.toIntOrNull()?.toByte() }.toByteArray()
        }
        if (address.size != (if (family == 0) 4 else 16)) return null
        return WebRTCQRCodec.Candidate(family, protocol, type, 0, address, port)
    }

    private fun sdpObserver(onSuccess: (SessionDescription) -> Unit, onFailure: (String) -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) { if (sdp != null) onSuccess(sdp) }
        override fun onCreateFailure(reason: String?) { onFailure(reason ?: "unknown") }
        override fun onSetSuccess() {}
        override fun onSetFailure(reason: String?) {}
    }

    private fun compareFingerprints(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val cmp = (a[i].toInt() and 0xFF).compareTo(b[i].toInt() and 0xFF)
            if (cmp != 0) return cmp
        }
        return a.size.compareTo(b.size)
    }

    private fun emit(event: WebRTCTransportEvent) {
        mainHandler.post { onEvent(event) }
    }

    // MARK: - SDP reconstruction

    private fun remoteOfferSDP(remote: WebRTCQRCodec.Packet, setup: String): String {
        val fingerprint = remote.fingerprint.joinToString(":") { "%02X".format(it) }
        val candidateLines = remote.candidates.mapIndexed { index, c ->
            candidateSDPLine(c, index + 1)
        }.joinToString("\n")
        var sessionID = 0uL
        remote.fingerprint.take(8).forEach { sessionID = (sessionID shl 8) or (it.toULong() and 0xFFu) }
        return buildString {
            appendLine("v=0")
            appendLine("o=- $sessionID 2 IN IP4 127.0.0.1")
            appendLine("s=-")
            appendLine("t=0 0")
            appendLine("a=group:BUNDLE 0")
            appendLine("a=ice-ufrag:${remote.ufrag}")
            appendLine("a=ice-pwd:${remote.pwd}")
            appendLine("m=application 9 UDP/DTLS/SCTP webrtc-datachannel")
            appendLine("c=IN IP4 0.0.0.0")
            appendLine("a=ice-options:trickle")
            appendLine("a=fingerprint:sha-256 $fingerprint")
            appendLine("a=setup:$setup")
            appendLine("a=mid:0")
            appendLine("a=sctp-port:5000")
            if (candidateLines.isNotEmpty()) appendLine(candidateLines)
        }.trimEnd()
    }

    private fun remoteAnswerSDP(remote: WebRTCQRCodec.Packet, setup: String): String =
        remoteOfferSDP(remote, setup)

    private fun candidateSDPLine(candidate: WebRTCQRCodec.Candidate, index: Int): String {
        val protocol = if (candidate.protocol == 1) "tcp" else "udp"
        val type = if (candidate.type == 1) "srflx" else "host"
        val ip = when (candidate.addressFamily) {
            0 -> candidate.address.joinToString(".") { (it.toInt() and 0xFF).toString() }
            1 -> candidate.address.joinToString(":") { "%02x".format(it) }
            else -> {
                val hex = candidate.address.joinToString("") { "%02x".format(it) }
                "${hex.take(8)}-${hex.drop(8).take(4)}-${hex.drop(12).take(4)}-${hex.drop(16).take(4)}-${hex.drop(20)}.local"
            }
        }
        val priority = if (candidate.type == 0) 2_118_130_432 else 1_695_370_752
        return "a=candidate:$index $index $protocol $priority $ip ${candidate.port} typ $type"
    }
}

sealed class WebRTCTransportEvent {
    data class Connecting(val message: String) : WebRTCTransportEvent()
    data class Connected(val message: String) : WebRTCTransportEvent()
    data class Disconnected(val message: String) : WebRTCTransportEvent()
    data class ReceivedText(val text: String) : WebRTCTransportEvent()
    data class ReceivedOpenURL(val url: String) : WebRTCTransportEvent()
    data class ReceivedImage(val bytes: ByteArray, val mimeType: String) : WebRTCTransportEvent()
    data class ReceivedRoster(val payloadBase64: String) : WebRTCTransportEvent()
    data class TransferStatus(val message: String) : WebRTCTransportEvent()
    data class Log(val message: String) : WebRTCTransportEvent()
}

// MARK: - SDP parsing

private fun String.extractFingerprint(): ByteArray? {
    val match = Regex("""a=fingerprint:sha-256 ([0-9A-Fa-f:]+)""").find(this) ?: return null
    return match.groupValues[1].replace(":", "").chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
}

private fun String.extractIceUfrag(): String? = extractAttr("a=ice-ufrag:")
private fun String.extractIcePwd(): String? = extractAttr("a=ice-pwd:")

private fun String.extractAttr(prefix: String): String? {
    val lines = split("\n")
    return lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim()
}

private fun String.extractCandidates(): List<WebRTCQRCodec.Candidate> {
    return split("\n").filter { it.startsWith("a=candidate:") }.mapNotNull { line ->
        val parts = line.split(" ")
        if (parts.size < 8) return@mapNotNull null
        val protocol = if (parts[2] == "tcp") 1 else 0
        val ip = parts[4]
        val port = parts[5].toIntOrNull() ?: return@mapNotNull null
        val type = if (parts[7].contains("srflx")) 1 else 0
        val address: ByteArray
        val family: Int
        if (ip.contains(":")) {
            family = 1
            address = parseIpv6(ip)
        } else if (ip.endsWith(".local")) {
            family = 2
            address = ByteArray(16)
        } else {
            family = 0
            address = ip.split(".").mapNotNull { it.toIntOrNull()?.toByte() }.toByteArray()
        }
        WebRTCQRCodec.Candidate(family, protocol, type, 0, address, port)
    }
}

private fun parseIpv6(ip: String): ByteArray {
    val bytes = ByteArray(16)
    val parts = ip.split("::")
    val leading = parts[0].split(":").filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull(16) ?: 0 }
    var index = 0
    leading.forEach { group ->
        bytes[index++] = (group ushr 8).toByte()
        bytes[index++] = (group and 0xFF).toByte()
    }
    return bytes
}
