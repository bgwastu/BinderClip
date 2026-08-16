package net.wastu.binderclip

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Android host side of a BinderClip chain. Mirrors the wire handshake the
 * macOS DirectTransport already implements so a Mac (or another Android) can
 * "join" a chain hosted here. Runs only while the app is in the foreground.
 */
class DirectServer(
    private val store: DeviceStore,
    private val deviceNameProvider: () -> String,
    private val onText: (String) -> Unit,
    private val onOpenUrl: (String) -> Unit,
    private val onImage: (ImagePayload) -> Unit,
    private val onTransferStatus: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onRosterChanged: (List<RememberedPeer>) -> Unit,
    private val onInvite: (String) -> Unit,
) {
    companion object {
        const val PORT = 39_421
        private const val MAX_IN_FLIGHT_IMAGE_CHUNKS = 4
        private const val INVITE_TTL_MS = 300_000L

        /** Pure per-space port derivation (testable). */
        fun portForUserId(base: Int, userId: Int): Int = (base + userId).coerceAtMost(65_535)

        /**
         * Per-space port so multiple Android spaces (users / work profile /
         * app clones) can each host a chain on the same device without an
         * EADDRINUSE conflict. User 0 keeps the canonical 39421; each extra
         * user gets a distinct port derived from its userId.
         */
        fun portForSpace(): Int {
            val userId = android.os.Process.myUid() / 100000
            return portForUserId(PORT, userId)
        }
    }

    private val acceptLock = Any()
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val writeLock = Any()
    @Volatile
    private var transferScheduler: ScheduledThreadPoolExecutor? = null

    private fun transferPool(): ScheduledThreadPoolExecutor {
        var pool = transferScheduler
        if (pool == null || pool.isShutdown || pool.isTerminated) {
            synchronized(acceptLock) {
                pool = transferScheduler
                if (pool == null || pool.isShutdown || pool.isTerminated) {
                    pool = ScheduledThreadPoolExecutor(2)
                    transferScheduler = pool
                }
            }
        }
        return pool!!
    }

    private val invites = Collections.synchronizedMap(LinkedHashMap<String, Pair<ByteArray, Long>>())
    private val memberSockets = Collections.synchronizedMap(LinkedHashMap<String, Socket>())
    private val memberNames = Collections.synchronizedMap(LinkedHashMap<String, String>())
    private val states = Collections.synchronizedMap(LinkedHashMap<Socket, MemberState>())

    private class IncomingImage(val id: String, val mimeType: String, val bytes: Int, val sha256: String) {
        val data = java.io.ByteArrayOutputStream(bytes)
        var nextIndex = 0
    }

    private class MemberState {
        @Volatile
        var outboundImage: ImagePayload? = null
        @Volatile
        var inboundImage: IncomingImage? = null
        var outboundNextIndex = 0
        var outboundAcknowledgedIndex = -1
        var outboundTimeout: ScheduledFuture<*>? = null
        @Volatile
        var lastActivity = System.currentTimeMillis()
    }

    val isRunning: Boolean get() = running.get()

    private var memberWatchdog: ScheduledFuture<*>? = null

    fun start() {
        synchronized(acceptLock) {
            if (running.get()) return
            runCatching {
                val server = ServerSocket(portForSpace()).apply { reuseAddress = true }
                serverSocket = server
                running.set(true)
            }.onFailure {
                Log.w("BinderClip", "Could not start host server", it)
                DiagnosticLog.error("Could not start hosting: ${it.message ?: "port busy"}")
                onStatus("Could not start hosting")
                return
            }
        }
        Thread { acceptLoop() }.apply { name = "BinderClip host accept"; isDaemon = true; start() }
        startMemberWatchdog()
        onStatus("Hosting chain — scanning adds devices")
    }

    fun stop() {
        synchronized(acceptLock) {
            running.set(false)
            serverSocket?.close()
            serverSocket = null
        }
        memberWatchdog?.cancel(true); memberWatchdog = null
        val sockets = synchronized(memberSockets) { memberSockets.values.toList() }
        sockets.forEach { runCatching { it.close() } }
        memberSockets.clear()
        memberNames.clear()
        states.clear()
        runCatching { transferScheduler?.shutdownNow() }
        transferScheduler = null
    }

    /** Ping members periodically and drop any that don't respond, so a dead
     *  route (mesh VPN toggle, network switch) doesn't leave the roster showing
     *  a stale "connected" member. */
    private fun startMemberWatchdog() {
        memberWatchdog?.cancel(true)
        memberWatchdog = transferPool().scheduleWithFixedDelay({
            try {
                val key = store.groupKey ?: return@scheduleWithFixedDelay
                val now = System.currentTimeMillis()
                val stale = synchronized(memberSockets) { memberSockets.entries.toList() }.filter { (_, socket) ->
                    val state = states[socket]
                    state != null && now - state.lastActivity > 45_000
                }
                stale.forEach { (_, socket) ->
                    Log.w("BinderClip", "Member unresponsive — disconnecting")
                    runCatching { socket.close() }
                    cleanup(socket)
                }
                // Ping all still-connected members.
                synchronized(memberSockets) { memberSockets.entries.toList() }.forEach { (_, socket) ->
                    val output = runCatching { DataOutputStream(socket.getOutputStream()) }.getOrNull()
                    if (output != null && key.isNotEmpty()) {
                        write(output, DirectProtocol.seal(JSONObject().put("type", "ping"), key))
                    }
                }
            } catch (error: Exception) {
                Log.w("BinderClip", "Host watchdog error", error)
            }
        }, 15, 15, TimeUnit.SECONDS)
    }

    fun createInvite(): String? {
        val hosts = localIPv4Addresses()
        if (hosts.isEmpty()) {
            onStatus("No network route to this device")
            return null
        }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val id = UUID.randomUUID().toString()
        invites[id] = key to (System.currentTimeMillis() + INVITE_TTL_MS)
        val hostQuery = hosts.take(4).joinToString("&") { "host=${android.net.Uri.encode(it)}" }
        val url = "binderclip://invite?$hostQuery&port=${portForSpace()}&id=$id&key=${Base64.getUrlEncoder().encodeToString(key)}"
        onInvite(url)
        return url
    }

    fun broadcastText(text: String, targetDeviceId: String? = null) {
        val key = store.groupKey ?: return
        val json = JSONObject()
            .put("type", "clipboard")
            .put("id", UUID.randomUUID().toString())
            .put("origin", store.deviceId)
            .put("timestamp", System.currentTimeMillis())
            .put("text", text)
        if (!targetDeviceId.isNullOrBlank()) json.put("targetDeviceId", targetDeviceId)
        sendToMembers(key, json, targetDeviceId)
    }

    fun broadcastOpenUrl(url: String, targetDeviceId: String? = null) {
        val key = store.groupKey ?: return
        val json = JSONObject()
            .put("type", "openUrl")
            .put("id", UUID.randomUUID().toString())
            .put("origin", store.deviceId)
            .put("timestamp", System.currentTimeMillis())
            .put("url", url)
        if (!targetDeviceId.isNullOrBlank()) json.put("targetDeviceId", targetDeviceId)
        sendToMembers(key, json, targetDeviceId)
    }

    fun sendImage(image: ImagePayload) {
        val key = store.groupKey ?: return
        val targets = synchronized(memberSockets) {
            memberSockets.entries.mapNotNull { (id, socket) ->
                val state = states[socket] ?: return@mapNotNull null
                if (state.outboundImage != null) null else Triple(id, socket, state)
            }
        }
        if (targets.isEmpty()) {
            onTransferStatus("Image not sent — no connected device")
            return
        }
        var offered = false
        targets.forEach { (id, socket, state) ->
            val output = runCatching { DataOutputStream(socket.getOutputStream()) }.getOrNull() ?: return@forEach
            state.outboundImage = image
            state.outboundNextIndex = 0
            state.outboundAcknowledgedIndex = -1
            write(output, DirectProtocol.seal(
                JSONObject().put("type", "mediaOffer").put("id", image.id).put("mime", image.mimeType)
                    .put("bytes", image.data.size).put("sha256", image.sha256), key
            ))
            offered = true
        }
        if (offered) onTransferStatus("Offering image")
    }

    fun removeMember(deviceId: String) {
        if (deviceId == store.deviceId) {
            leaveChain()
            return
        }
        val key = store.groupKey ?: return
        val socket = synchronized(memberSockets) { memberSockets.remove(deviceId) } ?: return
        memberNames.remove(deviceId)
        states.remove(socket)
        store.removeMember(deviceId)
        synchronized(writeLock) {
            val remove = DirectProtocol.seal(JSONObject().put("type", "rosterRemove").put("id", deviceId), key)
            // Notify the removed device itself so it leaves cleanly, then the rest.
            val remaining = synchronized(memberSockets) { memberSockets.values.toList() }
            runCatching { DataOutputStream(socket.getOutputStream()) }.getOrNull()?.let { out -> write(out, remove) }
            remaining.filter { it !== socket }.forEach {
                runCatching { DataOutputStream(it.getOutputStream()) }.getOrNull()?.let { out -> write(out, remove) }
            }
        }
        runCatching { socket.close() }
        broadcastRoster()
        onRosterChanged(store.members)
        onStatus("Removed device from chain")
    }

    fun leaveChain() {
        val key = store.groupKey
        if (key != null) {
            synchronized(writeLock) {
                val leave = DirectProtocol.seal(JSONObject().put("type", "rosterRemove").put("id", store.deviceId), key)
                synchronized(memberSockets) { memberSockets.values.toList() }.forEach {
                    runCatching { DataOutputStream(it.getOutputStream()) }.getOrNull()?.let { out -> write(out, leave) }
                }
            }
        }
        stop()
        store.leaveChain()
        onRosterChanged(emptyList())
        onStatus("You left the BinderClip chain")
    }

    fun renameMember(deviceId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        val key = store.groupKey ?: return
        if (deviceId == store.deviceId) {
            store.customDeviceName = trimmed
            onRosterChanged(store.members)
        } else {
            memberNames[deviceId] = trimmed
            store.members = store.members.map {
                if (it.deviceId == deviceId) it.copy(name = trimmed) else it
            }
            onRosterChanged(store.members)
        }
        synchronized(writeLock) {
            val sealed = DirectProtocol.seal(
                JSONObject().put("type", "rename").put("id", deviceId).put("name", trimmed), key
            )
            synchronized(memberSockets) { memberSockets.values.toList() }.forEach {
                runCatching { DataOutputStream(it.getOutputStream()) }.getOrNull()?.let { out -> write(out, sealed) }
            }
        }
    }

    private fun sendToMembers(key: ByteArray, json: JSONObject, targetDeviceId: String? = null) {
        synchronized(writeLock) {
            val targets = synchronized(memberSockets) { memberSockets.entries.toList() }.filter { (id, _) ->
                targetDeviceId == null || targetDeviceId == id
            }
            val sealed = DirectProtocol.seal(json, key)
            targets.forEach { (_, socket) ->
                runCatching { DataOutputStream(socket.getOutputStream()) }.getOrNull()?.let { out -> write(out, sealed) }
            }
        }
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = runCatching { serverSocket?.accept() }.getOrNull() ?: continue
            runCatching {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.soTimeout = 0
            }
            states[socket] = MemberState()
            val input = runCatching { DataInputStream(socket.getInputStream()) }.getOrNull()
            val output = runCatching { DataOutputStream(socket.getOutputStream()) }.getOrNull()
            if (input == null || output == null) {
                runCatching { socket.close() }
                states.remove(socket)
                continue
            }
            Thread { handleMember(socket, input, output) }.apply {
                name = "BinderClip host member"
                isDaemon = true
                start()
            }
        }
    }

    private fun handleMember(socket: Socket, input: DataInputStream, output: DataOutputStream) {
        try {
            val key = store.groupKey
            val first = DirectProtocol.read(input)
            when {
                first.optString("type") == "invite" -> {
                    val rawId = first.optString("id")
                    val entry = invites.remove(rawId) ?: return
                    if (entry.second < System.currentTimeMillis()) return
                    val nonce = first.optString("nonce")
                    val expected = DirectProtocol.hmac(entry.first, "client|$rawId|$nonce")
                    if (!DirectProtocol.constantTimeEquals(expected, first.optString("proof"))) return
                    val serverNonce = DeviceStore.nonce()
                    val serverProof = DirectProtocol.hmac(entry.first, "server|$rawId|$nonce|$serverNonce")
                    val pairKey = DirectProtocol.pairSessionKey(entry.first, nonce, serverNonce)
                    DirectProtocol.write(
                        output,
                        JSONObject().put("type", "inviteAccepted").put("nonce", serverNonce).put("proof", serverProof)
                    )
                    if (key == null) { socket.close(); return }
                    val welcome = JSONObject()
                        .put("type", "welcome")
                        .put("groupKey", Base64.getEncoder().encodeToString(key))
                        .put("deviceID", store.deviceId)
                        .put("name", deviceNameProvider())
                        .put("members", rosterPayload())
                    DirectProtocol.write(output, DirectProtocol.seal(welcome, pairKey))
                    readLoop(socket, input, output, key)
                }

                // A returning member reconnecting without a fresh invite (the
                // connection dropped after a network/VPN change). It proves
                // membership by encrypting its hello with the group key.
                key != null && first.optString("type") == "encrypted" -> {
                    val hello = runCatching { DirectProtocol.open(first, key) }.getOrNull() ?: return
                    if (hello.optString("type") != "hello") return
                    acceptReturningMember(socket, output, key, hello, pairKey = null)
                    readLoop(socket, input, output, key)
                }

                else -> return
            }
        } catch (error: Exception) {
            Log.w("BinderClip", "Host handshake failed", error)
        } finally {
            cleanup(socket)
        }
    }

    private fun acceptReturningMember(socket: Socket, output: DataOutputStream, key: ByteArray, hello: JSONObject, pairKey: ByteArray?) {
        val id = hello.optString("deviceID")
        if (id.isBlank()) return
        replaceMemberSocket(id, socket)
        memberSockets[id] = socket
        memberNames[id] = hello.optString("name", "Device")
        val host = runCatching { socket.inetAddress?.hostAddress ?: "" }.getOrDefault("")
        val platform = hello.optString("platform", "Android")
        store.upsertMembers(listOf(RememberedPeer(hello.optString("name", "Device"), host, portForSpace(), id, platform, true)))
        onRosterChanged(store.members)
        broadcastRoster()
        onStatus("Connected to ${store.members.size} device${if (store.members.size == 1) "" else "s"}")
        // Respond with our identity so the reconnecting member can confirm the host.
        val identity = JSONObject().put("type", "hello")
            .put("deviceID", store.deviceId)
            .put("name", deviceNameProvider())
            .put("platform", "Android")
        if (pairKey != null) DirectProtocol.write(output, DirectProtocol.seal(identity, pairKey))
        else write(output, DirectProtocol.seal(identity, key))
    }

    /** When a member reconnects, drop any previous connection from the same
     *  device so duplicate sockets don't linger and echo messages twice. */
    private fun replaceMemberSocket(id: String, newSocket: Socket) {
        val previous = synchronized(memberSockets) { memberSockets[id] }
        if (previous != null && previous !== newSocket) {
            runCatching { previous.close() }
            states.remove(previous)
        }
    }

    private fun readLoop(socket: Socket, input: DataInputStream, output: DataOutputStream, key: ByteArray) {
        while (!socket.isClosed) {
            val message = DirectProtocol.open(DirectProtocol.read(input), key)
            states[socket]?.lastActivity = System.currentTimeMillis()
            when (message.optString("type")) {
                "hello" -> {
                    val id = message.optString("deviceID")
                    val name = message.optString("name", "Device")
                    val platform = message.optString("platform", "Android")
                    if (id.isNotBlank()) {
                        replaceMemberSocket(id, socket)
                        memberSockets[id] = socket
                        memberNames[id] = name
                        val host = runCatching { socket.inetAddress?.hostAddress ?: "" }.getOrDefault("")
                        store.upsertMembers(listOf(RememberedPeer(name, host, portForSpace(), id, platform, true)))
                        onRosterChanged(store.members)
                        broadcastRoster()
                        onStatus("Connected to ${store.members.size} device${if (store.members.size == 1) "" else "s"}")
                    }
                }

                "clipboard" -> {
                    val target = message.optString("targetDeviceId")
                    if (target.isNotBlank() && target != store.deviceId) continue
                    message.optString("text").takeIf { it.isNotEmpty() }?.let(onText)
                }

                "openUrl" -> {
                    val target = message.optString("targetDeviceId")
                    if (target.isNotBlank() && target != store.deviceId) continue
                    message.optString("url").takeIf { it.isNotEmpty() }?.let(onOpenUrl)
                }

                "ping" -> {
                    write(output, DirectProtocol.seal(JSONObject().put("type", "pong"), key))
                }

                "pong" -> Unit

                "rename" -> {
                    val id = message.optString("id")
                    val newName = message.optString("name").trim()
                    if (id.isNotBlank() && newName.isNotBlank()) {
                        memberNames[id] = newName
                        store.members = store.members.map {
                            if (it.deviceId == id) it.copy(name = newName) else it
                        }
                        onRosterChanged(store.members)
                        broadcastRoster()
                    }
                }

                "rosterRemove" -> {
                    val id = message.optString("id")
                    if (id.isNotBlank() && id != store.deviceId) removeMember(id)
                }

                "inviteRequest" -> createInvite()

                "mediaOffer" -> handleInboundOffer(message, key, socket)
                "mediaAccept" -> handleOutboundAccept(message, key, socket)
                "mediaChunk" -> handleInboundChunk(message, key, socket)
                "mediaAck" -> handleOutboundAck(message, key, socket)
                "mediaComplete" -> handleInboundComplete(message, socket)
                "mediaReject" -> {
                    states[socket]?.let { state ->
                        state.outboundTimeout?.cancel(false)
                        state.outboundTimeout = null
                        state.outboundImage = null
                    }
                    onTransferStatus("Image rejected by device")
                }

                "mediaAbort" -> {
                    states[socket]?.let { state ->
                        state.outboundTimeout?.cancel(false)
                        state.outboundTimeout = null
                        state.inboundImage = null
                        state.outboundImage = null
                    }
                    onTransferStatus("Image transfer cancelled")
                }
            }
        }
    }

    private fun handleInboundOffer(message: JSONObject, key: ByteArray, socket: Socket) {
        val id = message.optString("id")
        val mime = message.optString("mime")
        val bytes = message.optInt("bytes", -1)
        val hash = message.optString("sha256")
        val output = runCatching { DataOutputStream(socket.getOutputStream()) }.getOrNull() ?: return
        val state = states[socket] ?: return
        if (state.inboundImage != null || id.isBlank() || mime !in ImagePayload.ALLOWED_MIME_TYPES ||
            bytes !in 1..ImagePayload.MAXIMUM_BYTES || hash.length != 64
        ) {
            write(output, DirectProtocol.seal(JSONObject().put("type", "mediaReject").put("reason", "Unsupported image"), key))
            return
        }
        state.inboundImage = IncomingImage(id, mime, bytes, hash)
        write(output, DirectProtocol.seal(JSONObject().put("type", "mediaAccept").put("id", id), key))
        onTransferStatus("Receiving image")
    }

    private fun handleInboundChunk(message: JSONObject, key: ByteArray, socket: Socket) {
        val state = states[socket] ?: return
        val incoming = state.inboundImage ?: return
        val id = message.optString("id")
        val index = message.optInt("index", -1)
        val chunk = runCatching { Base64.getDecoder().decode(message.getString("data")) }.getOrNull()
        val output = runCatching { DataOutputStream(socket.getOutputStream()) }.getOrNull() ?: return
        if (id != incoming.id || index != incoming.nextIndex || chunk == null || chunk.isEmpty() ||
            chunk.size > ImagePayload.CHUNK_BYTES || incoming.data.size() + chunk.size > incoming.bytes
        ) {
            state.inboundImage = null
            write(output, DirectProtocol.seal(JSONObject().put("type", "mediaAbort"), key))
            onTransferStatus("Image transfer failed")
            return
        }
        incoming.data.write(chunk)
        incoming.nextIndex += 1
        write(output, DirectProtocol.seal(JSONObject().put("type", "mediaAck").put("id", id).put("index", index), key))
        onTransferStatus("Receiving image ${incoming.data.size() * 100 / incoming.bytes}%")
    }

    private fun handleInboundComplete(message: JSONObject, socket: Socket) {
        val state = states[socket] ?: return
        val incoming = state.inboundImage ?: return
        if (message.optString("id") != incoming.id || incoming.data.size() != incoming.bytes) return
        val bytes = incoming.data.toByteArray()
        val image = runCatching { ImagePayload(incoming.id, incoming.mimeType, bytes) }.getOrNull()
        state.inboundImage = null
        if (image == null || image.sha256 != incoming.sha256) {
            onTransferStatus("Image verification failed")
            return
        }
        onImage(image)
        onTransferStatus("Received image")
    }

    private fun handleOutboundAccept(message: JSONObject, key: ByteArray, socket: Socket) {
        val state = states[socket] ?: return
        val image = state.outboundImage ?: return
        if (message.optString("id") != image.id) return
        sendImageWindow(image, key, socket)
    }

    private fun handleOutboundAck(message: JSONObject, key: ByteArray, socket: Socket) {
        val state = states[socket] ?: return
        val image = state.outboundImage ?: return
        val acknowledged = message.optInt("index", -1)
        if (message.optString("id") != image.id || acknowledged <= state.outboundAcknowledgedIndex ||
            acknowledged >= state.outboundNextIndex
        ) return
        state.outboundTimeout?.cancel(false)
        state.outboundTimeout = null
        state.outboundAcknowledgedIndex = acknowledged
        sendImageWindow(image, key, socket)
    }

    private fun sendImageWindow(image: ImagePayload, key: ByteArray, socket: Socket) {
        val output = runCatching { DataOutputStream(socket.getOutputStream()) }.getOrNull() ?: return
        val state = states[socket] ?: return
        val totalChunks = (image.data.size + ImagePayload.CHUNK_BYTES - 1) / ImagePayload.CHUNK_BYTES
        while (state.outboundNextIndex < totalChunks &&
            state.outboundNextIndex <= state.outboundAcknowledgedIndex + MAX_IN_FLIGHT_IMAGE_CHUNKS
        ) {
            val offset = state.outboundNextIndex * ImagePayload.CHUNK_BYTES
            val end = minOf(offset + ImagePayload.CHUNK_BYTES, image.data.size)
            write(
                output,
                DirectProtocol.seal(
                    JSONObject().put("type", "mediaChunk").put("id", image.id).put("index", state.outboundNextIndex)
                        .put("data", Base64.getEncoder().encodeToString(image.data.copyOfRange(offset, end))), key
                )
            )
            state.outboundNextIndex += 1
        }
        if (state.outboundAcknowledgedIndex + 1 >= totalChunks) {
            write(output, DirectProtocol.seal(JSONObject().put("type", "mediaComplete").put("id", image.id), key))
            state.outboundTimeout?.cancel(false)
            state.outboundTimeout = null
            state.outboundImage = null
            onTransferStatus("Image sent")
            return
        }
        scheduleOutboundTimeout(image.id, state.outboundAcknowledgedIndex + 1, key, socket)
        onTransferStatus("Sending image ${(state.outboundAcknowledgedIndex + 1) * 100 / totalChunks}%")
    }

    private fun scheduleOutboundTimeout(imageId: String, index: Int, key: ByteArray, socket: Socket) {
        val state = states[socket] ?: return
        state.outboundTimeout?.cancel(false)
        state.outboundTimeout = transferPool().schedule({
            if (state.outboundImage?.id != imageId || state.outboundAcknowledgedIndex + 1 != index) return@schedule
            runCatching { DataOutputStream(socket.getOutputStream()) }.getOrNull()?.let {
                write(it, DirectProtocol.seal(JSONObject().put("type", "mediaAbort").put("id", imageId), key))
            }
            state.outboundImage = null
            onTransferStatus("Image transfer timed out")
        }, 15, TimeUnit.SECONDS)
    }

    private fun cleanup(socket: Socket) {
        states.remove(socket)
        val removedIds = synchronized(memberSockets) {
            val ids = memberSockets.filterValues { it === socket }.keys.toList()
            ids.forEach { id ->
                memberSockets.remove(id)
                memberNames.remove(id)
                store.removeMember(id)
            }
            ids
        }
        if (removedIds.isNotEmpty()) {
            onRosterChanged(store.members)
            broadcastRoster()
        }
        runCatching { socket.close() }
    }

    private fun broadcastRoster() {
        val key = store.groupKey ?: return
        val payload = rosterPayload()
        synchronized(writeLock) {
            val sealed = DirectProtocol.seal(JSONObject().put("type", "roster").put("members", payload), key)
            synchronized(memberSockets) { memberSockets.values.toList() }.forEach {
                runCatching { DataOutputStream(it.getOutputStream()) }.getOrNull()?.let { out -> write(out, sealed) }
            }
        }
    }

    private fun rosterPayload(): JSONArray = JSONArray().apply {
        put(
            JSONObject()
                .put("id", store.deviceId)
                .put("name", deviceNameProvider())
                .put("host", localIPv4Addresses().firstOrNull() ?: "")
                .put("port", portForSpace())
                .put("platform", "Android")
                .put("connected", true)
        )
        synchronized(memberSockets) { memberSockets.entries.toList() }.forEach { (id, _) ->
            put(
                JSONObject()
                    .put("id", id)
                    .put("name", memberNames[id] ?: "Device")
                    .put("host", runCatching { memberSockets[id]?.inetAddress?.hostAddress ?: "" }.getOrDefault(""))
                    .put("port", portForSpace())
                    .put("platform", "Android")
                    .put("connected", true)
            )
        }
    }

    private fun write(stream: DataOutputStream, message: JSONObject) {
        synchronized(writeLock) {
            runCatching { DirectProtocol.write(stream, message) }.onFailure {
                Log.w("BinderClip", "Host send failed", it)
            }
        }
    }

    private fun localIPv4Addresses(): List<String> = runCatching {
        val result = mutableListOf<String>()
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { networkInterface ->
            if (networkInterface.isUp && !networkInterface.isLoopback) {
                networkInterface.inetAddresses.toList().forEach { address ->
                    if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                        val ip = address.hostAddress ?: ""
                        if (ip.isNotBlank() && !ip.startsWith("127.")) result.add(ip)
                    }
                }
            }
        }
        result
    }.getOrDefault(emptyList())
}
