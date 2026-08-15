package net.wastu.binderclip

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

class DirectClient(
    private val store: DeviceStore,
    private val deviceName: String,
    private val onText: (String) -> Unit,
    private val onOpenUrl: (String) -> Unit,
    private val onImage: (ImagePayload) -> Unit,
    private val onTransferStatus: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onFailure: (String) -> Unit,
    private val onPeerIdentity: (String, String) -> Unit,
    private val onRosterChanged: (List<RememberedPeer>) -> Unit,
    private val onInvite: (String) -> Unit,
    private val onDisconnected: () -> Unit,
) {
    companion object {
        private const val MAX_IN_FLIGHT_IMAGE_CHUNKS = 4
    }

    private val writeLock = Any()
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private var readThread: Thread? = null
    private val connected = AtomicBoolean(false)
    @Volatile
    private var inboundImage: IncomingImage? = null
    @Volatile
    private var outboundImage: ImagePayload? = null
    @Volatile
    private var outboundNextIndex = 0
    @Volatile
    private var outboundAcknowledgedIndex = -1
    private val transferScheduler = Executors.newScheduledThreadPool(2)
    @Volatile
    private var outboundTimeout: ScheduledFuture<*>? = null
    @Volatile
    private var watchdogTask: ScheduledFuture<*>? = null
    @Volatile
    private var lastReceivedAt = 0L
    private val recentMessageIds = java.util.Collections.synchronizedList(java.util.LinkedList<String>())

    private class IncomingImage(val id: String, val mimeType: String, val bytes: Int, val sha256: String) {
        val data = java.io.ByteArrayOutputStream(bytes)
        var nextIndex = 0
    }

    fun pair(uri: String) {
        val parsed = android.net.Uri.parse(uri)
        require(parsed.scheme == "binderclip" && parsed.host == "invite") { "Not a BinderClip pairing code" }
        val hosts = parsed.getQueryParameters("host").distinct().filter { it.isNotBlank() }
        require(hosts.isNotEmpty()) { "Pairing code has no host" }
        val port = parsed.getQueryParameter("port")?.toIntOrNull() ?: error("Pairing code has no port")
        val id = parsed.getQueryParameter("id") ?: error("Pairing code has no id")
        val inviteKey =
            Base64.getUrlDecoder().decode(parsed.getQueryParameter("key") ?: error("Pairing code has no key"))
        close(); onStatus("Pairing with Mac…")
        var failure: Exception? = null
        for (host in hosts) {
            try {
                pair(host, port, id, inviteKey)
                return
            } catch (error: Exception) {
                Log.w("BinderClip", "Direct route $host failed", error)
                DiagnosticLog.warning("Pairing route failed")
                failure = error
                close()
            }
        }
        val message = failure?.message ?: "No direct route to this Mac"
        DiagnosticLog.error("Pairing failed: $message")
        throw failure ?: IllegalStateException(message)
    }

    private fun pair(host: String, port: Int, id: String, inviteKey: ByteArray) {
        val newSocket = Socket().apply {
            tcpNoDelay = true
            keepAlive = true
            connect(InetSocketAddress(host, port), 10_000)
            soTimeout = 15_000
        }
        val input = DataInputStream(newSocket.getInputStream());
        val newOutput = DataOutputStream(newSocket.getOutputStream())
        val clientNonce = DeviceStore.nonce()
        DirectProtocol.write(
            newOutput,
            JSONObject().put("type", "invite").put("id", id).put("nonce", clientNonce)
                .put("proof", DirectProtocol.hmac(inviteKey, "client|$id|$clientNonce"))
        )
        val accepted = DirectProtocol.read(input)
        require(accepted.optString("type") == "inviteAccepted") { "Mac rejected the invitation" }
        val serverNonce = accepted.getString("nonce")
        require(
            DirectProtocol.constantTimeEquals(
                accepted.getString("proof"),
                DirectProtocol.hmac(inviteKey, "server|$id|$clientNonce|$serverNonce")
            )
        ) { "Mac authentication failed" }
        val welcome = DirectProtocol.open(
            DirectProtocol.read(input),
            DirectProtocol.pairSessionKey(inviteKey, clientNonce, serverNonce)
        )
        require(welcome.optString("type") == "welcome") { "Invalid pairing reply" }
        val groupKey = android.util.Base64.decode(welcome.getString("groupKey"), android.util.Base64.NO_WRAP)
        val pairedMac =
            RememberedPeer(welcome.optString("name", "Mac"), host, port, welcome.optString("deviceID"), "macOS", true)
        store.groupKey = groupKey; store.peer = pairedMac
        val members = welcome.optJSONArray("members")?.let(::decodeMembers).orEmpty()
        store.upsertMembers(members + pairedMac)
        onRosterChanged(store.members)
        // Pairing has a short read deadline; the established connection must not
        // silently time out while the user is simply not copying anything.
        newSocket.soTimeout = 0
        attach(newSocket, input, newOutput, groupKey); sendHello(); onStatus("Paired and connected")
    }

    fun reconnect() {
        val primaryPeer = store.peer ?: store.members.firstOrNull { it.platform == "macOS" } ?: return
        val key = store.groupKey ?: return
        close(); onStatus("Connecting to ${primaryPeer.name}…")

        // Build unique candidate targets from peer and stored members
        val candidateHosts = buildList {
            add(primaryPeer.host)
            store.members.filter { it.platform == "macOS" }.forEach { add(it.host) }
        }.distinct().filter { it.isNotBlank() }

        if (candidateHosts.isEmpty()) {
            onStatus("Waiting for ${primaryPeer.name}")
            onDisconnected()
            return
        }

        // Happy Eyeballs parallel connection racing
        val winner = java.util.concurrent.atomic.AtomicReference<Pair<Socket, String>?>(null)
        val latch = java.util.concurrent.CountDownLatch(candidateHosts.size)
        val racePool = Executors.newFixedThreadPool(candidateHosts.size.coerceAtMost(4))

        for (host in candidateHosts) {
            racePool.execute {
                var candidateSocket: Socket? = null
                try {
                    val sock = Socket().apply {
                        tcpNoDelay = true
                        keepAlive = true
                        connect(InetSocketAddress(host, primaryPeer.port), 3_500)
                        soTimeout = 0
                    }
                    candidateSocket = sock
                    if (winner.compareAndSet(null, Pair(sock, host))) {
                        // Winner secured
                    } else {
                        runCatching { sock.close() }
                    }
                } catch (e: Exception) {
                    runCatching { candidateSocket?.close() }
                } finally {
                    latch.countDown()
                }
            }
        }

        try {
            latch.await(4, TimeUnit.SECONDS)
        } catch (ignored: InterruptedException) {
        }
        racePool.shutdownNow()

        val won = winner.get()
        if (won != null) {
            val (sock, activeHost) = won
            if (activeHost != primaryPeer.host) {
                store.peer = primaryPeer.copy(host = activeHost)
            }
            attach(sock, DataInputStream(sock.getInputStream()), DataOutputStream(sock.getOutputStream()), key)
            sendHello()
            onStatus("Connected")
        } else {
            Log.w("BinderClip", "Reconnect failed across ${candidateHosts.size} routes")
            DiagnosticLog.warning("Reconnect failed across ${candidateHosts.size} routes")
            onStatus("Waiting for ${primaryPeer.name}")
            onDisconnected()
        }
    }

    fun sendText(text: String, targetDeviceId: String? = null) {
        val key = store.groupKey ?: run { fail("Clipboard not sent — device unavailable"); return }
        val out = output ?: run { fail("Clipboard not sent — device unavailable"); return }
        if (text.isEmpty()) {
            fail("Clipboard is empty"); return
        }
        if (text.toByteArray().size > DirectProtocol.MAXIMUM_TEXT_BYTES) {
            fail("Clipboard text is too large"); return
        }
        val msgId = java.util.UUID.randomUUID().toString()
        recentMessageIds.add(msgId)
        if (recentMessageIds.size > 64) recentMessageIds.removeAt(0)
        val json = JSONObject().put("type", "clipboard").put("id", msgId).put("origin", store.deviceId)
            .put("timestamp", System.currentTimeMillis()).put("text", text)
        if (!targetDeviceId.isNullOrBlank()) json.put("targetDeviceId", targetDeviceId)
        write(out, DirectProtocol.seal(json, key))
        Log.i("BinderClip", "Sent clipboard text")
        DiagnosticLog.info("Sent clipboard text")
    }

    fun sendOpenUrl(url: String, targetDeviceId: String? = null) {
        val key = store.groupKey ?: run { fail("URL not sent — device unavailable"); return }
        val out = output ?: run { fail("URL not sent — device unavailable"); return }
        if (url.isEmpty()) {
            fail("URL is empty"); return
        }
        if (url.toByteArray().size > DirectProtocol.MAXIMUM_TEXT_BYTES) {
            fail("URL is too large"); return
        }
        val msgId = java.util.UUID.randomUUID().toString()
        recentMessageIds.add(msgId)
        if (recentMessageIds.size > 64) recentMessageIds.removeAt(0)
        val json = JSONObject().put("type", "openUrl").put("id", msgId).put("origin", store.deviceId)
            .put("timestamp", System.currentTimeMillis()).put("url", url)
        if (!targetDeviceId.isNullOrBlank()) json.put("targetDeviceId", targetDeviceId)
        write(out, DirectProtocol.seal(json, key))
        Log.i("BinderClip", "Sent URL to open")
        DiagnosticLog.info("Sent URL to open")
    }

    fun sendImage(image: ImagePayload) {
        val key = store.groupKey ?: run { fail("Image not sent — device unavailable"); return }
        val out = output ?: run { fail("Image not sent — device unavailable"); return }
        if (outboundImage != null) {
            fail("An image is already sending"); return
        }
        outboundImage = image; outboundNextIndex = 0; outboundAcknowledgedIndex = -1
        write(
            out,
            DirectProtocol.seal(
                JSONObject().put("type", "mediaOffer").put("id", image.id).put("mime", image.mimeType)
                    .put("bytes", image.data.size).put("sha256", image.sha256), key
            )
        )
        onTransferStatus("Offering image")
    }

    fun removeMember(deviceId: String) {
        val key = store.groupKey ?: return;
        val out = output ?: run { onStatus("Connect to remove a device"); return }
        write(out, DirectProtocol.seal(JSONObject().put("type", "rosterRemove").put("id", deviceId), key))
        if (deviceId == store.deviceId) {
            store.reset(); close(); onRosterChanged(emptyList()); onStatus("You left the BinderClip chain")
        } else {
            store.removeMember(deviceId)
            if (store.peer?.deviceId == deviceId) {
                store.peer = null
            }
            onRosterChanged(store.members)
            onStatus("Removed device from chain")
        }
    }

    fun requestInvite() {
        val key = store.groupKey ?: return;
        val out = output ?: run { onStatus("Connect to add a device"); return }
        write(out, DirectProtocol.seal(JSONObject().put("type", "inviteRequest"), key))
    }

    fun isConnected(): Boolean = connected.get()
    fun close() {
        outboundTimeout?.cancel(false); outboundTimeout = null
        watchdogTask?.cancel(false); watchdogTask = null
        inboundImage = null; outboundImage = null
        outboundNextIndex = 0; outboundAcknowledgedIndex = -1
        connected.set(false); runCatching { socket?.close() }; socket = null; output = null
    }

    fun shutdown() {
        close(); transferScheduler.shutdownNow()
    }

    private fun startWatchdog() {
        watchdogTask?.cancel(false)
        watchdogTask = transferScheduler.scheduleWithFixedDelay({
            if (connected.get() && lastReceivedAt > 0 && System.currentTimeMillis() - lastReceivedAt > 50_000) {
                Log.w("BinderClip", "Dead connection detected by watchdog")
                DiagnosticLog.warning("Connection lost: no response from peer")
                close()
                onStatus("Connection lost")
                onDisconnected()
            }
        }, 15, 15, TimeUnit.SECONDS)
    }

    private fun attach(newSocket: Socket, input: DataInputStream, newOutput: DataOutputStream, key: ByteArray) {
        socket = newSocket; output = newOutput; connected.set(true)
        lastReceivedAt = System.currentTimeMillis()
        startWatchdog()
        readThread = Thread {
            try {
                while (!newSocket.isClosed) {
                    val message = DirectProtocol.open(DirectProtocol.read(input), key)
                    lastReceivedAt = System.currentTimeMillis()
                    when (message.optString("type")) {
                        "clipboard" -> {
                            val target = message.optString("targetDeviceId")
                            if (target.isNotBlank() && target != store.deviceId) continue
                            val msgId = message.optString("id")
                            if (msgId.isNotBlank()) {
                                if (recentMessageIds.contains(msgId)) continue
                                recentMessageIds.add(msgId)
                                if (recentMessageIds.size > 64) recentMessageIds.removeAt(0)
                            }
                            message.optString("text").takeIf { it.isNotEmpty() }?.let(onText)
                        }

                        "openUrl" -> {
                            val target = message.optString("targetDeviceId")
                            if (target.isNotBlank() && target != store.deviceId) continue
                            val msgId = message.optString("id")
                            if (msgId.isNotBlank()) {
                                if (recentMessageIds.contains(msgId)) continue
                                recentMessageIds.add(msgId)
                                if (recentMessageIds.size > 64) recentMessageIds.removeAt(0)
                            }
                            message.optString("url").takeIf { it.isNotEmpty() }?.let(onOpenUrl)
                        }

                        "ping" -> {
                            output?.let { write(it, DirectProtocol.seal(JSONObject().put("type", "pong"), key)) }
                        }

                        "pong" -> {
                            // Activity timestamp refreshed
                        }

                        "hello" -> onPeerIdentity(message.optString("deviceID"), message.optString("name", "Mac"))
                        "roster" -> {
                            val members = decodeMembers(message.optJSONArray("members") ?: JSONArray())
                            store.members = members
                            store.peer?.let { current ->
                                members.firstOrNull { it.deviceId == current.deviceId }?.let { remote ->
                                    store.peer = if (remote.host.isBlank()) current.copy(
                                        name = remote.name,
                                        platform = remote.platform,
                                        connected = true
                                    )
                                    else remote.copy(connected = true)
                                }
                            }
                            onRosterChanged(store.members)
                        }

                        "rosterRemove" -> {
                            val id = message.optString("id")
                            if (id == store.deviceId) {
                                store.reset(); close(); onRosterChanged(emptyList()); onStatus("You left the BinderClip chain")
                            } else {
                                store.removeMember(id); onRosterChanged(store.members)
                            }
                        }

                        "invite" -> message.optString("url").takeIf { it.startsWith("binderclip://invite") }
                            ?.let(onInvite)

                        "mediaOffer" -> handleImageOffer(message, key)
                        "mediaAccept" -> handleImageAccept(message, key)
                        "mediaChunk" -> handleImageChunk(message, key)
                        "mediaAck" -> handleImageAck(message, key)
                        "mediaComplete" -> handleImageComplete(message)
                        "mediaReject" -> {
                            outboundTimeout?.cancel(false); outboundTimeout = null; outboundImage =
                                null; fail("Image rejected by Mac")
                        }

                        "mediaAbort" -> {
                            outboundTimeout?.cancel(false); outboundTimeout = null; inboundImage = null; outboundImage =
                                null; fail("Image transfer cancelled")
                        }
                    }
                }
            } catch (error: Exception) {
                Log.w("BinderClip", "Direct receiver ended", error)
                if (!newSocket.isClosed) {
                    DiagnosticLog.warning("Connection lost: ${error.message ?: "network error"}")
                    onStatus("Connection lost")
                }
            } finally {
                if (socket === newSocket) {
                    connected.set(false); output = null; onDisconnected()
                }
            }
        }.apply { name = "BinderClip direct receiver"; isDaemon = true; start() }
    }

    private fun sendHello() {
        val key = store.groupKey ?: return;
        val out = output ?: return
        write(
            out,
            DirectProtocol.seal(
                JSONObject().put("type", "hello").put("deviceID", store.deviceId).put("name", deviceName), key
            )
        )
    }

    private fun decodeMembers(array: JSONArray): List<RememberedPeer> = buildList {
        for (index in 0 until array.length()) {
            val member = array.optJSONObject(index) ?: continue
            val id = member.optString("id")
            if (id.isBlank()) continue
            add(
                RememberedPeer(
                    name = member.optString("name", "Device"), host = member.optString("host"),
                    port = member.optInt("port", 39_421), deviceId = id,
                    platform = member.optString("platform", "Android"), connected = member.optBoolean("connected"),
                )
            )
        }
    }

    private fun write(stream: DataOutputStream, message: JSONObject) {
        runCatching { synchronized(writeLock) { DirectProtocol.write(stream, message) } }
            .onFailure {
                Log.w("BinderClip", "Direct send failed", it)
                DiagnosticLog.error("Send failed: ${it.message ?: "network error"}")
                close(); fail("Send failed — connection lost")
            }
    }

    private fun handleImageOffer(message: JSONObject, key: ByteArray) {
        val id = message.optString("id")
        val mime = message.optString("mime")
        val bytes = message.optInt("bytes", -1)
        val hash = message.optString("sha256")
        val out = output ?: return
        if (inboundImage != null || id.isBlank() || mime !in ImagePayload.ALLOWED_MIME_TYPES || bytes !in 1..ImagePayload.MAXIMUM_BYTES || hash.length != 64) {
            write(
                out,
                DirectProtocol.seal(JSONObject().put("type", "mediaReject").put("reason", "Unsupported image"), key)
            ); return
        }
        inboundImage = IncomingImage(id, mime, bytes, hash)
        write(out, DirectProtocol.seal(JSONObject().put("type", "mediaAccept").put("id", id), key))
        onTransferStatus("Receiving image")
    }

    private fun handleImageAccept(message: JSONObject, key: ByteArray) {
        val image = outboundImage ?: return
        if (message.optString("id") != image.id) return
        sendImageWindow(image, key)
    }

    private fun handleImageChunk(message: JSONObject, key: ByteArray) {
        val incoming = inboundImage ?: return
        val id = message.optString("id")
        val index = message.optInt("index", -1)
        val chunk = runCatching { Base64.getDecoder().decode(message.getString("data")) }.getOrNull()
        val out = output ?: return
        if (id != incoming.id || index != incoming.nextIndex || chunk == null || chunk.isEmpty() || chunk.size > ImagePayload.CHUNK_BYTES || incoming.data.size() + chunk.size > incoming.bytes) {
            inboundImage = null; write(
                out,
                DirectProtocol.seal(JSONObject().put("type", "mediaAbort"), key)
            ); fail("Image transfer failed"); return
        }
        incoming.data.write(chunk); incoming.nextIndex += 1
        write(out, DirectProtocol.seal(JSONObject().put("type", "mediaAck").put("id", id).put("index", index), key))
        onTransferStatus("Receiving image ${incoming.data.size() * 100 / incoming.bytes}%")
    }

    private fun handleImageAck(message: JSONObject, key: ByteArray) {
        val image = outboundImage ?: return
        val acknowledged = message.optInt("index", -1)
        if (message.optString("id") != image.id || acknowledged <= outboundAcknowledgedIndex || acknowledged >= outboundNextIndex) return
        outboundTimeout?.cancel(false); outboundTimeout = null
        outboundAcknowledgedIndex = acknowledged
        sendImageWindow(image, key)
    }

    private fun handleImageComplete(message: JSONObject) {
        val incoming = inboundImage ?: return
        if (message.optString("id") != incoming.id || incoming.data.size() != incoming.bytes) return
        val bytes = incoming.data.toByteArray()
        val image = runCatching { ImagePayload(incoming.id, incoming.mimeType, bytes) }.getOrNull()
        inboundImage = null
        if (image == null || image.sha256 != incoming.sha256) {
            fail("Image verification failed"); return
        }
        onImage(image); onTransferStatus("Received image")
    }

    /** TCP preserves ordering, so acknowledgements can safely be cumulative. */
    private fun sendImageWindow(image: ImagePayload, key: ByteArray) {
        val out = output ?: return
        val totalChunks = (image.data.size + ImagePayload.CHUNK_BYTES - 1) / ImagePayload.CHUNK_BYTES
        while (outboundNextIndex < totalChunks && outboundNextIndex <= outboundAcknowledgedIndex + MAX_IN_FLIGHT_IMAGE_CHUNKS) {
            val offset = outboundNextIndex * ImagePayload.CHUNK_BYTES
            val end = minOf(offset + ImagePayload.CHUNK_BYTES, image.data.size)
            write(
                out,
                DirectProtocol.seal(
                    JSONObject().put("type", "mediaChunk").put("id", image.id).put("index", outboundNextIndex)
                        .put("data", Base64.getEncoder().encodeToString(image.data.copyOfRange(offset, end))), key
                )
            )
            outboundNextIndex += 1
        }
        if (outboundAcknowledgedIndex + 1 >= totalChunks) {
            write(out, DirectProtocol.seal(JSONObject().put("type", "mediaComplete").put("id", image.id), key))
            outboundTimeout?.cancel(false); outboundTimeout = null; outboundImage =
                null; onTransferStatus("Image sent"); return
        }
        scheduleOutboundTimeout(image.id, outboundAcknowledgedIndex + 1, key)
        onTransferStatus("Sending image ${(outboundAcknowledgedIndex + 1) * 100 / totalChunks}%")
    }

    private fun scheduleOutboundTimeout(imageId: String, index: Int, key: ByteArray) {
        outboundTimeout?.cancel(false)
        outboundTimeout = transferScheduler.schedule({
            if (outboundImage?.id != imageId || outboundAcknowledgedIndex + 1 != index) return@schedule
            output?.let {
                write(
                    it,
                    DirectProtocol.seal(JSONObject().put("type", "mediaAbort").put("id", imageId), key)
                )
            }
            outboundImage = null
            fail("Image transfer timed out")
        }, 15, TimeUnit.SECONDS)
    }

    private fun fail(message: String) {
        DiagnosticLog.error(message)
        onTransferStatus(message)
        onFailure(message)
    }
}
