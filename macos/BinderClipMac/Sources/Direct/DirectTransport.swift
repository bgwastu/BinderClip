import CryptoKit
import Foundation
import Network

struct DirectEndpoint: Codable, Hashable {
    let host: String
    let port: UInt16
}

struct Peer: Codable, Hashable, Identifiable {
    let id: String
    var name: String
    var endpoint: DirectEndpoint
    var connected: Bool
    var platform: String

    init(id: String, name: String, endpoint: DirectEndpoint, connected: Bool, platform: String = "Android") {
        self.id = id; self.name = name; self.endpoint = endpoint; self.connected = connected; self.platform = platform
    }

    enum CodingKeys: String, CodingKey { case id, name, endpoint, connected, platform }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        name = try values.decode(String.self, forKey: .name)
        endpoint = try values.decode(DirectEndpoint.self, forKey: .endpoint)
        connected = try values.decode(Bool.self, forKey: .connected)
        platform = try values.decodeIfPresent(String.self, forKey: .platform) ?? "Android"
    }
}

/// Durable record of the chain host this Mac joined, so it can reconnect
/// automatically when the connection drops without needing a fresh QR invite.
struct HostTarget: Codable {
    let id: String
    var name: String
    var endpoints: [DirectEndpoint]

    enum CodingKeys: String, CodingKey { case id, name, endpoints }

    init(id: String, name: String, endpoints: [DirectEndpoint]) {
        self.id = id; self.name = name; self.endpoints = endpoints
    }
}

final class AtomicFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var flag = false

    func get() -> Bool {
        lock.lock(); defer { lock.unlock() }
        return flag
    }

    func compareAndSet(expected: Bool, newValue: Bool) -> Bool {
        lock.lock(); defer { lock.unlock() }
        if flag == expected { flag = newValue; return true }
        return false
    }

    func set(_ value: Bool) {
        lock.lock(); defer { lock.unlock() }
        flag = value
    }
}

/// Robust direct TCP transport and chain engine.
/// All internal state modifications execute sequentially on a dedicated dispatch queue.
final class DirectTransport {
    static let port: UInt16 = 39_421
    static let maximumTextBytes = 1_048_576

    var onClipboard: ((String) -> Void)?
    var onOpenURL: ((URL) -> Void)?
    var onImage: ((ImagePayload) -> Void)?
    var onTransferStatus: ((String) -> Void)?
    var onPeersChanged: (([Peer]) -> Void)?
    var onLog: ((String) -> Void)?
    var onLocalNetworkPermissionRequired: ((Bool) -> Void)?

    private struct Invite {
        let key: Data
        let expiresAt: Date
    }

    private let queue = DispatchQueue(label: "net.wastu.binderclip.transport")
    private let rosterManager = RosterManager()
    private let maximumInFlightImageChunks = 4

    private var listener: NWListener?
    private var pathMonitor: NWPathMonitor?
    private var pathDebounceWorkItem: DispatchWorkItem?
    private var heartbeatTimer: DispatchSourceTimer?
    private var rosterRefreshTimer: DispatchSourceTimer?
    private var reconnectTimer: DispatchSourceTimer?
    private var reconnectBackoffSeconds: Double = 2.0
    private var isReconnecting = false

    private var activeConnections: [ObjectIdentifier: DirectConnection] = [:]
    private var candidateConnections: [ObjectIdentifier: NWConnection] = [:]
    private var invites: [UUID: Invite] = [:]
    private var recentMessageIDs: [String] = []

    // Thread-safe cached properties for fast AppKit UI reads
    private let stateLock = NSLock()
    private var cachedDeviceName: String
    private var cachedPeersSnapshot: [Peer] = []

    var localDeviceID: String {
        rosterManager.localID
    }

    var localDeviceName: String {
        stateLock.lock(); defer { stateLock.unlock() }
        return cachedDeviceName
    }

    var localEndpoint: DirectEndpoint {
        DirectEndpoint(host: Self.localAddresses().first ?? "unknown", port: Self.port)
    }

    init() {
        self.cachedDeviceName = rosterManager.localName
        self.cachedPeersSnapshot = rosterManager.peerSnapshot()
    }

    func peersSnapshot() -> [Peer] {
        stateLock.lock(); defer { stateLock.unlock() }
        return cachedPeersSnapshot
    }

    func setLocalDeviceName(_ name: String) {
        queue.async { [weak self] in
            guard let self else { return }
            let updated = self.rosterManager.setLocalName(name)
            self.updateCachedState()
            self.broadcastEncrypted(["type": "rename", "id": self.rosterManager.localID, "name": updated])
            self.publishPeers()
            self.broadcastRoster()
        }
    }

    func renamePeer(id: String, newName: String) {
        if id == localDeviceID {
            setLocalDeviceName(newName)
            return
        }
        queue.async { [weak self] in
            guard let self else { return }
            if self.rosterManager.renamePeer(id: id, newName: newName) {
                self.updateCachedState()
                self.publishPeers()
                self.broadcastEncrypted(["type": "rename", "id": id, "name": newName.trimmingCharacters(in: .whitespacesAndNewlines)])
                self.broadcastRoster()
            }
        }
    }

    func start() {
        queue.async { [weak self] in
            guard let self, self.listener == nil else { return }
            self.startListener(includeBonjour: true)
            self.startPathMonitor()
            self.startHeartbeats()
            self.startReconnectMonitoring()
            if self.rosterManager.hostTarget != nil {
                self.triggerReconnect(reason: "launch")
            }
        }
    }

    func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            self.heartbeatTimer?.cancel(); self.heartbeatTimer = nil
            self.rosterRefreshTimer?.cancel(); self.rosterRefreshTimer = nil
            self.reconnectTimer?.cancel(); self.reconnectTimer = nil
            self.pathMonitor?.cancel(); self.pathMonitor = nil
            self.pathDebounceWorkItem?.cancel(); self.pathDebounceWorkItem = nil

            self.candidateConnections.values.forEach { $0.cancel() }
            self.candidateConnections.removeAll()

            self.activeConnections.values.forEach { $0.cancel() }
            self.activeConnections.removeAll()

            self.listener?.cancel(); self.listener = nil
            self.isReconnecting = false
        }
    }

    func startNewChain() {
        queue.async { [weak self] in
            guard let self else { return }
            _ = self.rosterManager.rotateGroupKey()
            self.invites.removeAll()
            self.activeConnections.values.forEach { $0.cancel() }
            self.activeConnections.removeAll()
            self.candidateConnections.values.forEach { $0.cancel() }
            self.candidateConnections.removeAll()
            self.updateCachedState()
            self.publishPeers()
            self.transferStatus("Created a new chain")
            self.log("Created a new chain — group key rotated")
        }
    }

    func leaveChain() {
        queue.async { [weak self] in
            guard let self else { return }
            self.broadcastEncrypted(["type": "rosterRemove", "id": self.rosterManager.localID])
            self.rosterManager.clearChainState()
            self.invites.removeAll()
            self.activeConnections.values.forEach { $0.cancel() }
            self.activeConnections.removeAll()
            self.candidateConnections.values.forEach { $0.cancel() }
            self.candidateConnections.removeAll()
            self.updateCachedState()
            self.publishPeers()
            self.transferStatus("Left the BinderClip chain")
            self.log("Left the BinderClip chain")
        }
    }

    func removeFromChain(_ peerID: String) {
        queue.async { [weak self] in
            guard let self else { return }
            if peerID == self.rosterManager.localID {
                self.leaveChain()
                return
            }
            self.rosterManager.removePeer(id: peerID)
            self.updateCachedState()
            self.publishPeers()

            // Broadcast removal
            self.broadcastEncrypted(["type": "rosterRemove", "id": peerID])

            // Disconnect candidate
            for (id, conn) in self.activeConnections where conn.peerID == peerID {
                conn.cancel()
                self.activeConnections.removeValue(forKey: id)
            }
            self.broadcastRoster()
        }
    }

    func createInvite() -> URL? {
        stateLock.lock()
        let endpoints = Self.localAddresses().map { DirectEndpoint(host: $0, port: Self.port) }
        stateLock.unlock()

        guard !endpoints.isEmpty else { return nil }
        let id = UUID()
        let key = DirectCrypto.randomBytes(count: 32)

        queue.async { [weak self] in
            self?.invites[id] = Invite(key: key, expiresAt: Date().addingTimeInterval(300))
        }

        var components = URLComponents()
        components.scheme = "binderclip"
        components.host = "invite"
        components.queryItems = endpoints.prefix(4).map { URLQueryItem(name: "host", value: $0.host) } + [
            URLQueryItem(name: "port", value: String(Self.port)),
            URLQueryItem(name: "id", value: id.uuidString),
            URLQueryItem(name: "key", value: Self.urlSafeBase64(key)),
        ]
        return components.url
    }

    func joinChain(inviteURL: String) {
        queue.async { [weak self] in
            guard let self else { return }
            self.joinChainInternal(inviteURL: inviteURL)
        }
    }

    func sendClipboard(_ text: String, targetDeviceId: String? = nil) {
        queue.async { [weak self] in
            guard let self else { return }
            guard !text.isEmpty, text.utf8.count <= Self.maximumTextBytes else {
                self.transferStatus("Clipboard not sent — unsupported content")
                return
            }
            let targets = self.activeConnections.values.filter { conn in
                guard let pid = conn.peerID else { return false }
                return targetDeviceId == nil || pid == targetDeviceId
            }
            guard !targets.isEmpty else {
                self.transferStatus("Clipboard not sent — no connected device")
                return
            }
            let msgID = UUID().uuidString
            self.recentMessageIDs.append(msgID)
            if self.recentMessageIDs.count > 256 { self.recentMessageIDs.removeFirst() }

            var payload: [String: Any] = [
                "type": "clipboard",
                "id": msgID,
                "origin": self.rosterManager.localID,
                "timestamp": UInt64(Date().timeIntervalSince1970 * 1000),
                "text": text
            ]
            if let targetDeviceId { payload["targetDeviceId"] = targetDeviceId }

            for conn in targets {
                conn.sendEncrypted(payload, key: self.rosterManager.groupKey)
            }
        }
    }

    func sendOpenURL(_ url: URL, targetDeviceId: String? = nil) {
        queue.async { [weak self] in
            guard let self else { return }
            let urlString = url.absoluteString
            guard !urlString.isEmpty, urlString.utf8.count <= Self.maximumTextBytes else {
                self.transferStatus("URL not sent — invalid content")
                return
            }
            let targets = self.activeConnections.values.filter { conn in
                guard let pid = conn.peerID else { return false }
                return targetDeviceId == nil || pid == targetDeviceId
            }
            guard !targets.isEmpty else {
                self.transferStatus("URL not sent — no connected device")
                return
            }
            let msgID = UUID().uuidString
            self.recentMessageIDs.append(msgID)
            if self.recentMessageIDs.count > 256 { self.recentMessageIDs.removeFirst() }

            var payload: [String: Any] = [
                "type": "openUrl",
                "id": msgID,
                "origin": self.rosterManager.localID,
                "timestamp": UInt64(Date().timeIntervalSince1970 * 1000),
                "url": urlString
            ]
            if let targetDeviceId { payload["targetDeviceId"] = targetDeviceId }

            for conn in targets {
                conn.sendEncrypted(payload, key: self.rosterManager.groupKey)
            }
            self.transferStatus("Sent URL to peer")
        }
    }

    func sendImage(_ image: ImagePayload) {
        queue.async { [weak self] in
            guard let self else { return }
            let targets = self.activeConnections.values.filter { $0.peerID != nil }
            guard !targets.isEmpty else {
                self.transferStatus("Image not sent — device unavailable")
                return
            }
            var started = false
            for conn in targets where conn.outboundImage == nil {
                conn.outboundImage = OutboundImageTransfer(image)
                conn.sendEncrypted([
                    "type": "mediaOffer",
                    "id": image.id.uuidString,
                    "mime": image.mimeType,
                    "bytes": image.data.count,
                    "sha256": image.sha256
                ], key: self.rosterManager.groupKey)
                started = true
            }
            if started {
                self.transferStatus("Offering image")
            } else {
                self.transferStatus("Image not sent — another image is sending")
            }
        }
    }

    // MARK: - Internal Network Handling

    private func startListener(includeBonjour: Bool) {
        guard let port = NWEndpoint.Port(rawValue: Self.port) else { return }
        do {
            let listener = try NWListener(using: .tcp, on: port)
            if includeBonjour {
                let txtData = "id=\(self.rosterManager.localID)".data(using: .utf8)
                listener.service = NWListener.Service(
                    name: self.rosterManager.localName,
                    type: "_binderclip._tcp",
                    domain: nil,
                    txtRecord: txtData
                )
            }
            listener.newConnectionHandler = { [weak self] connection in
                self?.acceptIncoming(connection)
            }
            listener.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                self.queue.async {
                    switch state {
                    case .ready:
                        self.onLocalNetworkPermissionRequired?(false)
                    case .waiting(let error), .failed(let error):
                        self.onLocalNetworkPermissionRequired?(Self.isPermissionError(error))
                        if case .failed = state {
                            self.log("Listener failed: \(error.localizedDescription)", level: .warning)
                            if includeBonjour {
                                self.log("Retrying listener without Bonjour registration")
                                self.listener?.cancel()
                                self.listener = nil
                                self.startListener(includeBonjour: false)
                            }
                        }
                    default:
                        break
                    }
                }
            }
            self.listener = listener
            listener.start(queue: queue)
            log(includeBonjour ? "Listening for direct connections with Bonjour discovery" : "Listening for direct connections")
        } catch {
            log("Could not listen: \(error.localizedDescription)", level: .error)
        }
    }

    private func acceptIncoming(_ nwConnection: NWConnection) {
        queue.async { [weak self] in
            guard let self else { return }
            let directConn = DirectConnection(connection: nwConnection, queue: self.queue)
            self.setupConnectionHandlers(directConn)
            self.activeConnections[directConn.id] = directConn
            directConn.start()
        }
    }

    private func setupConnectionHandlers(_ conn: DirectConnection) {
        conn.onFrame = { [weak self] frameData, directConn in
            guard let self else { return }
            self.handleFrame(frameData, from: directConn)
        }
        conn.onClosed = { [weak self] directConn in
            guard let self else { return }
            self.handleConnectionClosed(directConn)
        }
    }

    private func handleConnectionClosed(_ conn: DirectConnection) {
        activeConnections.removeValue(forKey: conn.id)
        if let peerID = conn.peerID {
            // Mark peer disconnected without evicting from roster
            rosterManager.setPeerConnected(peerID, connected: false)
            updateCachedState()
            publishPeers()

            if peerID == rosterManager.hostTarget?.id {
                // If we lost connection to the host, schedule auto-reconnect with backoff
                scheduleReconnectBackoff()
            }
        }
    }

    private func handleFrame(_ frame: Data, from conn: DirectConnection) {
        do {
            guard let object = try JSONSerialization.jsonObject(with: frame) as? [String: Any] else {
                throw DirectCryptoError.malformed
            }

            if object["type"] as? String == "invite" {
                try handleInviteMessage(object, connection: conn)
                return
            }

            let message = try DirectCrypto.open(object, key: rosterManager.groupKey)
            conn.lastActivity = Date()

            switch message["type"] as? String {
            case "hello":
                try handleHelloMessage(message, connection: conn)
            case "clipboard":
                try handleClipboardMessage(message, connection: conn)
            case "openUrl":
                try handleOpenURLMessage(message, connection: conn)
            case "ping":
                conn.sendEncrypted(["type": "pong"], key: rosterManager.groupKey)
            case "pong":
                break
            case "mediaOffer":
                try handleMediaOffer(message, connection: conn)
                relayEncrypted(message, except: conn, targetDeviceId: nil)
            case "mediaAccept":
                try handleMediaAccept(message, connection: conn)
            case "mediaChunk":
                try handleMediaChunk(message, connection: conn)
            case "mediaAck":
                try handleMediaAck(message, connection: conn)
            case "mediaComplete":
                try handleMediaComplete(message, connection: conn)
            case "mediaReject":
                conn.outboundTimeout?.cancel(); conn.outboundTimeout = nil; conn.outboundImage = nil
                transferStatus("Image rejected by peer")
            case "mediaAbort":
                conn.outboundTimeout?.cancel(); conn.outboundTimeout = nil; conn.inboundImage = nil; conn.outboundImage = nil
                transferStatus("Image transfer cancelled")
            case "rosterRemove":
                guard let targetID = message["id"] as? String else { throw DirectCryptoError.malformed }
                if targetID == rosterManager.localID {
                    leaveChain()
                } else {
                    rosterManager.removePeer(id: targetID)
                    updateCachedState()
                    publishPeers()
                    for (cid, c) in activeConnections where c.peerID == targetID {
                        c.cancel()
                        activeConnections.removeValue(forKey: cid)
                    }
                    relayEncrypted(message, except: conn, targetDeviceId: nil)
                }
            case "roster":
                guard let members = message["members"] as? [[String: Any]] else { throw DirectCryptoError.malformed }
                _ = rosterManager.applyRemoteRoster(
                    members,
                    fallbackHost: conn.resolvedEndpoint.host,
                    fallbackPort: conn.resolvedEndpoint.port
                )
                updateCachedState()
                publishPeers()
            case "rename":
                guard let targetID = message["id"] as? String, let newName = message["name"] as? String else {
                    throw DirectCryptoError.malformed
                }
                let trimmed = newName.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty else { throw DirectCryptoError.malformed }
                if targetID == rosterManager.localID {
                    _ = rosterManager.setLocalName(trimmed)
                } else {
                    _ = rosterManager.renamePeer(id: targetID, newName: trimmed)
                }
                updateCachedState()
                publishPeers()
                relayEncrypted(message, except: conn, targetDeviceId: nil)
            case "inviteRequest":
                if let invite = createInvite() {
                    conn.sendEncrypted(["type": "invite", "url": invite.absoluteString], key: rosterManager.groupKey)
                }
            default:
                throw DirectCryptoError.malformed
            }
        } catch {
            log("Rejected peer frame: \(error.localizedDescription)", level: .warning)
            conn.cancel()
        }
    }

    private func handleHelloMessage(_ message: [String: Any], connection: DirectConnection) throws {
        guard let id = message["deviceID"] as? String,
              let name = message["name"] as? String,
              id != rosterManager.localID, !id.isEmpty else {
            throw DirectCryptoError.authentication
        }

        let isFirstHello = (connection.peerID == nil)
        connection.peerID = id

        let endpoint = connection.resolvedEndpoint
        let peer = Peer(
            id: id,
            name: name,
            endpoint: endpoint,
            connected: true,
            platform: message["platform"] as? String ?? "Android"
        )

        // If peer was previously tombstoned but is now connecting with valid group key, re-admit
        rosterManager.reAdmitPeer(peer)

        if let hostList = message["hosts"] as? [String] {
            let candidateEndpoints = hostList.filter { !$0.isEmpty }.map { DirectEndpoint(host: $0, port: endpoint.port) }
            rosterManager.updateHostTargetEndpoints(candidateEndpoints)
        }

        updateCachedState()
        publishPeers()

        // Deduplicate connections from same peer
        for (cid, otherConn) in activeConnections where cid != connection.id {
            if otherConn.peerID == id {
                otherConn.cancel()
                activeConnections.removeValue(forKey: cid)
            }
        }

        if isFirstHello {
            connection.sendEncrypted([
                "type": "hello",
                "deviceID": rosterManager.localID,
                "name": rosterManager.localName,
                "platform": "macOS",
                "hosts": Self.localAddresses()
            ], key: rosterManager.groupKey)
            broadcastRoster()
        }
    }

    private func handleClipboardMessage(_ message: [String: Any], connection: DirectConnection) throws {
        guard let text = message["text"] as? String, text.utf8.count <= Self.maximumTextBytes else {
            throw DirectCryptoError.malformed
        }
        let msgID = message["id"] as? String ?? UUID().uuidString
        if recentMessageIDs.contains(msgID) { return }
        recentMessageIDs.append(msgID)
        if recentMessageIDs.count > 256 { recentMessageIDs.removeFirst() }

        let target = message["targetDeviceId"] as? String
        if target == nil || target == rosterManager.localID {
            log("Received clipboard text")
            DispatchQueue.main.async { [weak self] in
                self?.onClipboard?(text)
            }
        }
        relayEncrypted(message, except: connection, targetDeviceId: target)
    }

    private func handleOpenURLMessage(_ message: [String: Any], connection: DirectConnection) throws {
        guard let urlString = message["url"] as? String, urlString.utf8.count <= Self.maximumTextBytes,
              let url = URL(string: urlString), let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https" else {
            throw DirectCryptoError.malformed
        }
        let msgID = message["id"] as? String ?? UUID().uuidString
        if recentMessageIDs.contains(msgID) { return }
        recentMessageIDs.append(msgID)
        if recentMessageIDs.count > 256 { recentMessageIDs.removeFirst() }

        let target = message["targetDeviceId"] as? String
        if target == nil || target == rosterManager.localID {
            log("Received URL to open")
            DispatchQueue.main.async { [weak self] in
                self?.onOpenURL?(url)
            }
        }
        relayEncrypted(message, except: connection, targetDeviceId: target)
    }

    private func handleInviteMessage(_ object: [String: Any], connection: DirectConnection) throws {
        guard let rawID = object["id"] as? String,
              let id = UUID(uuidString: rawID),
              let invite = invites.removeValue(forKey: id),
              invite.expiresAt > Date(),
              let nonce = object["nonce"] as? String,
              let proof = object["proof"] as? String,
              DirectCrypto.constantTimeEqual(proof, DirectCrypto.hmac(key: invite.key, value: "client|\(rawID)|\(nonce)")) else {
            throw DirectCryptoError.authentication
        }

        let serverNonce = DirectCrypto.randomBytes(count: 16).base64EncodedString()
        let serverProof = DirectCrypto.hmac(key: invite.key, value: "server|\(rawID)|\(nonce)|\(serverNonce)")
        let pairKey = DirectCrypto.pairSessionKey(inviteKey: invite.key, clientNonce: nonce, serverNonce: serverNonce)
        connection.pairKey = pairKey

        let acceptedPayload: [String: Any] = [
            "type": "inviteAccepted",
            "nonce": serverNonce,
            "proof": serverProof
        ]
        let acceptedData = try JSONSerialization.data(withJSONObject: acceptedPayload, options: [.sortedKeys])
        let acceptedFrame = try FrameCodec.encode(acceptedData)
        connection.sendRaw(frame: acceptedFrame)

        let welcome: [String: Any] = [
            "type": "welcome",
            "groupKey": rosterManager.groupKey.base64EncodedString(),
            "deviceID": rosterManager.localID,
            "name": rosterManager.localName,
            "members": rosterManager.rosterPayload(localAddresses: Self.localAddresses(), port: Self.port)
        ]
        connection.sendEncrypted(welcome, key: pairKey)
    }

    // MARK: - Image Transfer Flow Control

    private func handleMediaOffer(_ message: [String: Any], connection: DirectConnection) throws {
        guard connection.inboundImage == nil,
              let rawID = message["id"] as? String, let id = UUID(uuidString: rawID),
              let mime = message["mime"] as? String, let bytes = message["bytes"] as? Int,
              let hash = message["sha256"] as? String, ImagePayload.allowedMIMETypes.contains(mime),
              bytes > 0, bytes <= ImagePayload.maximumBytes, hash.count == 64 else {
            connection.sendEncrypted(["type": "mediaReject", "reason": "Unsupported image"], key: rosterManager.groupKey)
            return
        }
        connection.inboundImage = InboundImageTransfer(id: id, wireID: rawID, mimeType: mime, expectedBytes: bytes, expectedHash: hash)
        connection.sendEncrypted(["type": "mediaAccept", "id": rawID], key: rosterManager.groupKey)
        transferStatus("Receiving image")
    }

    private func handleMediaAccept(_ message: [String: Any], connection: DirectConnection) throws {
        guard let outbound = connection.outboundImage, message["id"] as? String == outbound.image.id.uuidString else {
            throw ImageTransferError.protocolViolation
        }
        sendImageWindow(outbound, connection: connection)
    }

    private func handleMediaChunk(_ message: [String: Any], connection: DirectConnection) throws {
        guard let inbound = connection.inboundImage else {
            log("Rejected image chunk without an offer", level: .warning)
            throw ImageTransferError.protocolViolation
        }
        guard let rawID = message["id"] as? String, UUID(uuidString: rawID) == inbound.id,
              let index = message["index"] as? Int, index == inbound.nextIndex,
              let encoded = message["data"] as? String, let chunk = Data(base64Encoded: encoded),
              !chunk.isEmpty, chunk.count <= ImagePayload.chunkBytes,
              inbound.data.count + chunk.count <= inbound.expectedBytes else {
            log("Rejected image chunk at index \(message["index"] ?? "missing")", level: .warning)
            throw ImageTransferError.protocolViolation
        }
        inbound.data.append(chunk)
        inbound.nextIndex += 1
        connection.sendEncrypted(["type": "mediaAck", "id": inbound.wireID, "index": index], key: rosterManager.groupKey)
        let percent = inbound.expectedBytes > 0 ? (inbound.data.count * 100 / inbound.expectedBytes) : 0
        transferStatus("Receiving image \(percent)%")
    }

    private func handleMediaAck(_ message: [String: Any], connection: DirectConnection) throws {
        guard let outbound = connection.outboundImage,
              message["id"] as? String == outbound.image.id.uuidString,
              let acknowledged = message["index"] as? Int,
              acknowledged > outbound.acknowledgedIndex, acknowledged < outbound.nextIndex else {
            log("Rejected image acknowledgement", level: .warning)
            throw ImageTransferError.protocolViolation
        }
        connection.outboundTimeout?.cancel()
        connection.outboundTimeout = nil
        outbound.acknowledgedIndex = acknowledged
        sendImageWindow(outbound, connection: connection)
    }

    private func handleMediaComplete(_ message: [String: Any], connection: DirectConnection) throws {
        guard let inbound = connection.inboundImage,
              let rawID = message["id"] as? String, UUID(uuidString: rawID) == inbound.id,
              inbound.data.count == inbound.expectedBytes,
              SHA256.hash(data: inbound.data).map({ String(format: "%02x", $0) }).joined() == inbound.expectedHash,
              let image = try? ImagePayload(id: inbound.id, mimeType: inbound.mimeType, data: inbound.data) else {
            throw ImageTransferError.hashMismatch
        }
        connection.inboundImage = nil
        DispatchQueue.main.async { [weak self] in
            self?.onImage?(image)
        }
        transferStatus("Received image")
    }

    private func sendImageWindow(_ outbound: OutboundImageTransfer, connection: DirectConnection) {
        let totalChunks = (outbound.image.data.count + ImagePayload.chunkBytes - 1) / ImagePayload.chunkBytes
        while outbound.nextIndex < totalChunks && outbound.nextIndex <= outbound.acknowledgedIndex + maximumInFlightImageChunks {
            let offset = outbound.nextIndex * ImagePayload.chunkBytes
            let end = min(offset + ImagePayload.chunkBytes, outbound.image.data.count)
            let chunkData = outbound.image.data.subdata(in: offset..<end)
            connection.sendEncrypted([
                "type": "mediaChunk",
                "id": outbound.image.id.uuidString,
                "index": outbound.nextIndex,
                "data": chunkData.base64EncodedString()
            ], key: rosterManager.groupKey)
            outbound.nextIndex += 1
        }
        guard outbound.acknowledgedIndex + 1 < totalChunks else {
            connection.sendEncrypted(["type": "mediaComplete", "id": outbound.image.id.uuidString], key: rosterManager.groupKey)
            connection.outboundTimeout?.cancel()
            connection.outboundTimeout = nil
            connection.outboundImage = nil
            transferStatus("Image sent")
            return
        }
        scheduleOutboundTimeout(for: connection, imageID: outbound.image.id, index: outbound.acknowledgedIndex + 1)
        transferStatus("Sending image \((outbound.acknowledgedIndex + 1) * 100 / totalChunks)%")
    }

    private func scheduleOutboundTimeout(for connection: DirectConnection, imageID: UUID, index: Int) {
        connection.outboundTimeout?.cancel()
        let timeout = DispatchWorkItem { [weak self, weak connection] in
            guard let self, let connection,
                  let current = connection.outboundImage,
                  current.image.id == imageID,
                  current.acknowledgedIndex + 1 == index else { return }
            connection.sendEncrypted(["type": "mediaAbort", "id": imageID.uuidString], key: self.rosterManager.groupKey)
            connection.outboundImage = nil
            self.transferStatus("Image transfer timed out")
        }
        connection.outboundTimeout = timeout
        queue.asyncAfter(deadline: .now() + 15, execute: timeout)
    }

    // MARK: - Join Chain Handshake (Asynchronous, Semaphore-Free)

    private func joinChainInternal(inviteURL: String) {
        guard let url = URL(string: inviteURL), url.scheme == "binderclip", url.host == "invite",
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            transferStatus("Not a valid BinderClip pairing code")
            return
        }

        let hosts = components.queryItems?.filter { $0.name == "host" }.compactMap(\.value).filter { !$0.isEmpty } ?? []
        guard let portStr = components.queryItems?.first(where: { $0.name == "port" })?.value,
              let port = UInt16(portStr),
              let inviteID = components.queryItems?.first(where: { $0.name == "id" })?.value,
              let keyStr = components.queryItems?.first(where: { $0.name == "key" })?.value,
              let inviteKey = Data(base64Encoded: keyStr.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")),
              !hosts.isEmpty else {
            transferStatus("Not a valid BinderClip pairing code")
            return
        }

        transferStatus("Joining chain…")
        let candidates = hosts.map { DirectEndpoint(host: $0, port: port) }
        let winnerClaimed = AtomicFlag()

        for (index, candidate) in candidates.enumerated() {
            let delay = Double(index) * 0.15
            queue.asyncAfter(deadline: .now() + delay) { [weak self] in
                guard let self, !winnerClaimed.get() else { return }
                self.attemptJoinCandidate(
                    candidate: candidate,
                    inviteID: inviteID,
                    inviteKey: inviteKey,
                    allCandidates: candidates,
                    winnerClaimed: winnerClaimed
                )
            }
        }
    }

    private func attemptJoinCandidate(
        candidate: DirectEndpoint,
        inviteID: String,
        inviteKey: Data,
        allCandidates: [DirectEndpoint],
        winnerClaimed: AtomicFlag
    ) {
        guard let port = NWEndpoint.Port(rawValue: candidate.port) else { return }
        let nwConn = NWConnection(host: NWEndpoint.Host(candidate.host), port: port, using: .tcp)
        let candidateID = ObjectIdentifier(nwConn)
        candidateConnections[candidateID] = nwConn

        let timeoutWorkItem = DispatchWorkItem { [weak self, weak nwConn] in
            guard let self, let nwConn else { return }
            self.candidateConnections.removeValue(forKey: ObjectIdentifier(nwConn))
            nwConn.cancel()
        }
        queue.asyncAfter(deadline: .now() + 5.0, execute: timeoutWorkItem)

        nwConn.stateUpdateHandler = { [weak self, weak nwConn] state in
            guard let self, let nwConn else { return }
            self.queue.async {
                guard self.candidateConnections[ObjectIdentifier(nwConn)] != nil else { return }
                if case .ready = state {
                    timeoutWorkItem.cancel()
                    if winnerClaimed.get() {
                        self.candidateConnections.removeValue(forKey: ObjectIdentifier(nwConn))
                        nwConn.cancel()
                        return
                    }
                    self.performJoinHandshake(
                        nwConn: nwConn,
                        candidate: candidate,
                        inviteID: inviteID,
                        inviteKey: inviteKey,
                        allCandidates: allCandidates,
                        winnerClaimed: winnerClaimed
                    )
                } else if case .failed = state {
                    timeoutWorkItem.cancel()
                    self.candidateConnections.removeValue(forKey: ObjectIdentifier(nwConn))
                    nwConn.cancel()
                }
            }
        }
        nwConn.start(queue: queue)
    }

    private func performJoinHandshake(
        nwConn: NWConnection,
        candidate: DirectEndpoint,
        inviteID: String,
        inviteKey: Data,
        allCandidates: [DirectEndpoint],
        winnerClaimed: AtomicFlag
    ) {
        let clientNonce = DirectCrypto.randomBytes(count: 16).base64EncodedString()
        let proof = DirectCrypto.hmac(key: inviteKey, value: "client|\(inviteID)|\(clientNonce)")

        do {
            let invitePayload: [String: Any] = [
                "type": "invite",
                "id": inviteID,
                "nonce": clientNonce,
                "proof": proof
            ]
            let body = try JSONSerialization.data(withJSONObject: invitePayload, options: [.sortedKeys])
            let frame = try FrameCodec.encode(body)

            nwConn.send(content: frame, completion: .contentProcessed { [weak self, weak nwConn] error in
                guard let self, let nwConn, error == nil else {
                    nwConn?.cancel()
                    return
                }
                self.queue.async {
                    self.readJoinReply(
                        nwConn: nwConn,
                        candidate: candidate,
                        inviteID: inviteID,
                        inviteKey: inviteKey,
                        clientNonce: clientNonce,
                        allCandidates: allCandidates,
                        winnerClaimed: winnerClaimed,
                        buffer: Data(),
                        sessionKey: nil
                    )
                }
            })
        } catch {
            nwConn.cancel()
        }
    }

    private func readJoinReply(
        nwConn: NWConnection,
        candidate: DirectEndpoint,
        inviteID: String,
        inviteKey: Data,
        clientNonce: String,
        allCandidates: [DirectEndpoint],
        winnerClaimed: AtomicFlag,
        buffer: Data,
        sessionKey: Data?
    ) {
        var currentBuffer = buffer
        var currentSessionKey = sessionKey

        nwConn.receive(minimumIncompleteLength: 1, maximumLength: FrameCodec.maximumPayloadBytes + 4) { [weak self, weak nwConn] data, _, complete, error in
            guard let self, let nwConn else { return }
            self.queue.async {
                if error != nil || winnerClaimed.get() {
                    self.candidateConnections.removeValue(forKey: ObjectIdentifier(nwConn))
                    nwConn.cancel()
                    return
                }
                if let data {
                    currentBuffer.append(data)
                }

                do {
                    while let frame = try FrameCodec.decode(from: &currentBuffer) {
                        guard let obj = try JSONSerialization.jsonObject(with: frame) as? [String: Any] else { continue }

                        if currentSessionKey == nil {
                            // Expecting inviteAccepted
                            guard obj["type"] as? String == "inviteAccepted",
                                  let serverNonce = obj["nonce"] as? String,
                                  let serverProof = obj["proof"] as? String else {
                                throw DirectCryptoError.malformed
                            }
                            let expectedProof = DirectCrypto.hmac(key: inviteKey, value: "server|\(inviteID)|\(clientNonce)|\(serverNonce)")
                            guard DirectCrypto.constantTimeEqual(serverProof, expectedProof) else {
                                throw DirectCryptoError.authentication
                            }
                            currentSessionKey = DirectCrypto.pairSessionKey(inviteKey: inviteKey, clientNonce: clientNonce, serverNonce: serverNonce)
                        } else if let key = currentSessionKey {
                            // Expecting welcome frame
                            let welcome = try DirectCrypto.open(obj, key: key)
                            guard welcome["type"] as? String == "welcome",
                                  let groupKeyBase64 = welcome["groupKey"] as? String,
                                  let adoptedGroupKey = Data(base64Encoded: groupKeyBase64),
                                  let hostID = welcome["deviceID"] as? String,
                                  let hostName = welcome["name"] as? String else {
                                throw DirectCryptoError.malformed
                            }

                            if winnerClaimed.compareAndSet(expected: false, newValue: true) {
                                self.adoptJoinedChain(
                                    nwConn: nwConn,
                                    candidate: candidate,
                                    hostID: hostID,
                                    hostName: hostName,
                                    groupKey: adoptedGroupKey,
                                    allCandidates: allCandidates,
                                    members: welcome["members"] as? [[String: Any]] ?? [],
                                    remainingBuffer: currentBuffer
                                )
                                return
                            } else {
                                self.candidateConnections.removeValue(forKey: ObjectIdentifier(nwConn))
                                nwConn.cancel()
                                return
                            }
                        }
                    }

                    if !complete {
                        self.readJoinReply(
                            nwConn: nwConn,
                            candidate: candidate,
                            inviteID: inviteID,
                            inviteKey: inviteKey,
                            clientNonce: clientNonce,
                            allCandidates: allCandidates,
                            winnerClaimed: winnerClaimed,
                            buffer: currentBuffer,
                            sessionKey: currentSessionKey
                        )
                    }
                } catch {
                    self.candidateConnections.removeValue(forKey: ObjectIdentifier(nwConn))
                    nwConn.cancel()
                }
            }
        }
    }

    private func adoptJoinedChain(
        nwConn: NWConnection,
        candidate: DirectEndpoint,
        hostID: String,
        hostName: String,
        groupKey: Data,
        allCandidates: [DirectEndpoint],
        members: [[String: Any]],
        remainingBuffer: Data
    ) {
        // Clean up other candidate connections
        candidateConnections.removeValue(forKey: ObjectIdentifier(nwConn))
        for (cid, c) in candidateConnections {
            c.cancel()
            candidateConnections.removeValue(forKey: cid)
        }

        // Close any old active connections
        activeConnections.values.forEach { $0.cancel() }
        activeConnections.removeAll()

        rosterManager.adoptGroupKey(groupKey)
        let endpoints = (allCandidates.isEmpty ? [candidate] : allCandidates) + [candidate]
        rosterManager.setHostTarget(HostTarget(id: hostID, name: hostName, endpoints: Array(Set(endpoints))))

        let hostPeer = Peer(id: hostID, name: hostName, endpoint: candidate, connected: true, platform: "Android")
        _ = rosterManager.addOrUpdatePeer(hostPeer)
        _ = rosterManager.applyRemoteRoster(members, fallbackHost: candidate.host, fallbackPort: candidate.port)

        let directConn = DirectConnection(connection: nwConn, queue: queue)
        directConn.peerID = hostID
        setupConnectionHandlers(directConn)
        activeConnections[directConn.id] = directConn
        directConn.start(alreadyStarted: true, initialBuffer: remainingBuffer)

        updateCachedState()
        publishPeers()
        reconnectBackoffSeconds = 2.0
        isReconnecting = false

        transferStatus("Joined chain with \(hostName)")
        log("Joined chain hosted by \(hostName)")

        directConn.sendEncrypted([
            "type": "hello",
            "deviceID": rosterManager.localID,
            "name": rosterManager.localName,
            "platform": "macOS",
            "hosts": Self.localAddresses()
        ], key: rosterManager.groupKey)
    }

    // MARK: - Reconnect (Single-Flight Happy Eyeballs with Cancellation)

    private func startReconnectMonitoring() {
        reconnectTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 8, repeating: 8)
        timer.setEventHandler { [weak self] in
            self?.triggerReconnect(reason: "periodic")
        }
        timer.resume()
        reconnectTimer = timer
    }

    private func triggerReconnect(reason: String) {
        guard let target = rosterManager.hostTarget, !target.endpoints.isEmpty else { return }
        if activeConnections.values.contains(where: { $0.peerID == target.id }) {
            reconnectBackoffSeconds = 2.0
            isReconnecting = false
            return
        }
        guard !isReconnecting else { return }
        isReconnecting = true

        let winnerClaimed = AtomicFlag()
        let sortedEndpoints = target.endpoints.sorted { (a, b) -> Bool in
            let pa = Self.endpointPriority(a.host)
            let pb = Self.endpointPriority(b.host)
            return pa == pb ? a.host < b.host : pa < pb
        }

        for (index, endpoint) in sortedEndpoints.enumerated() {
            let priority = Self.endpointPriority(endpoint.host)
            let stagger = Double(index) * 0.25 + (priority == 2 ? 0.25 : (priority > 2 ? 0.50 : 0.0))
            queue.asyncAfter(deadline: .now() + stagger) { [weak self] in
                guard let self, !winnerClaimed.get(), self.isReconnecting else { return }
                self.attemptReconnectCandidate(
                    endpoint: endpoint,
                    target: target,
                    winnerClaimed: winnerClaimed
                )
            }
        }
    }

    private func candidateFinished(nwConn: NWConnection, winnerClaimed: AtomicFlag) {
        candidateConnections.removeValue(forKey: ObjectIdentifier(nwConn))
        nwConn.cancel()
        if candidateConnections.isEmpty && !winnerClaimed.get() {
            isReconnecting = false
            scheduleReconnectBackoff()
        }
    }

    private func attemptReconnectCandidate(
        endpoint: DirectEndpoint,
        target: HostTarget,
        winnerClaimed: AtomicFlag
    ) {
        log("Reconnecting to \(target.name) via \(endpoint.host):\(endpoint.port)…")
        guard let port = NWEndpoint.Port(rawValue: endpoint.port) else { return }
        let nwConn = NWConnection(host: NWEndpoint.Host(endpoint.host), port: port, using: .tcp)
        let candidateID = ObjectIdentifier(nwConn)
        candidateConnections[candidateID] = nwConn

        let timeoutWorkItem = DispatchWorkItem { [weak self, weak nwConn] in
            guard let self, let nwConn else { return }
            self.candidateFinished(nwConn: nwConn, winnerClaimed: winnerClaimed)
        }
        queue.asyncAfter(deadline: .now() + 4.0, execute: timeoutWorkItem)

        nwConn.stateUpdateHandler = { [weak self, weak nwConn] state in
            guard let self, let nwConn else { return }
            self.queue.async {
                guard self.candidateConnections[ObjectIdentifier(nwConn)] != nil else { return }
                if case .ready = state {
                    timeoutWorkItem.cancel()
                    if winnerClaimed.get() {
                        self.candidateFinished(nwConn: nwConn, winnerClaimed: winnerClaimed)
                        return
                    }
                    self.performReconnectHandshake(
                        nwConn: nwConn,
                        endpoint: endpoint,
                        target: target,
                        winnerClaimed: winnerClaimed
                    )
                } else if case .failed = state {
                    timeoutWorkItem.cancel()
                    self.candidateFinished(nwConn: nwConn, winnerClaimed: winnerClaimed)
                }
            }
        }
        nwConn.start(queue: queue)
    }

    private func performReconnectHandshake(
        nwConn: NWConnection,
        endpoint: DirectEndpoint,
        target: HostTarget,
        winnerClaimed: AtomicFlag
    ) {
        do {
            let helloPayload = try DirectCrypto.seal([
                "type": "hello",
                "deviceID": rosterManager.localID,
                "name": rosterManager.localName,
                "platform": "macOS",
                "hosts": Self.localAddresses()
            ], key: rosterManager.groupKey)
            let frame = try FrameCodec.encode(try JSONSerialization.data(withJSONObject: helloPayload))

            nwConn.send(content: frame, completion: .contentProcessed { [weak self, weak nwConn] error in
                guard let self, let nwConn, error == nil else {
                    if let self, let nwConn {
                        self.queue.async {
                            self.candidateFinished(nwConn: nwConn, winnerClaimed: winnerClaimed)
                        }
                    }
                    return
                }
                self.queue.async {
                    self.readReconnectReply(
                        nwConn: nwConn,
                        endpoint: endpoint,
                        target: target,
                        winnerClaimed: winnerClaimed,
                        buffer: Data()
                    )
                }
            })
        } catch {
            candidateFinished(nwConn: nwConn, winnerClaimed: winnerClaimed)
        }
    }

    private func readReconnectReply(
        nwConn: NWConnection,
        endpoint: DirectEndpoint,
        target: HostTarget,
        winnerClaimed: AtomicFlag,
        buffer: Data
    ) {
        var currentBuffer = buffer
        nwConn.receive(minimumIncompleteLength: 1, maximumLength: FrameCodec.maximumPayloadBytes + 4) { [weak self, weak nwConn] data, _, complete, error in
            guard let self, let nwConn else { return }
            self.queue.async {
                if error != nil || winnerClaimed.get() {
                    self.candidateFinished(nwConn: nwConn, winnerClaimed: winnerClaimed)
                    return
                }
                if let data {
                    currentBuffer.append(data)
                }
                do {
                    while let frame = try FrameCodec.decode(from: &currentBuffer) {
                        guard let obj = try JSONSerialization.jsonObject(with: frame) as? [String: Any] else { continue }
                        let opened = try DirectCrypto.open(obj, key: self.rosterManager.groupKey)
                        if opened["type"] as? String == "hello" {
                            if winnerClaimed.compareAndSet(expected: false, newValue: true) {
                                self.adoptReconnectedHost(
                                    nwConn: nwConn,
                                    endpoint: endpoint,
                                    target: target,
                                    remainingBuffer: currentBuffer
                                )
                                return
                            } else {
                                self.candidateFinished(nwConn: nwConn, winnerClaimed: winnerClaimed)
                                return
                            }
                        }
                    }
                    if !complete {
                        self.readReconnectReply(
                            nwConn: nwConn,
                            endpoint: endpoint,
                            target: target,
                            winnerClaimed: winnerClaimed,
                            buffer: currentBuffer
                        )
                    } else {
                        self.candidateFinished(nwConn: nwConn, winnerClaimed: winnerClaimed)
                    }
                } catch {
                    self.candidateFinished(nwConn: nwConn, winnerClaimed: winnerClaimed)
                }
            }
        }
    }

    private func adoptReconnectedHost(
        nwConn: NWConnection,
        endpoint: DirectEndpoint,
        target: HostTarget,
        remainingBuffer: Data
    ) {
        candidateConnections.removeValue(forKey: ObjectIdentifier(nwConn))
        for (cid, c) in candidateConnections {
            c.cancel()
            candidateConnections.removeValue(forKey: cid)
        }

        for (cid, oldConn) in activeConnections where oldConn.peerID == target.id {
            oldConn.cancel()
            activeConnections.removeValue(forKey: cid)
        }

        let directConn = DirectConnection(connection: nwConn, queue: queue)
        directConn.peerID = target.id
        setupConnectionHandlers(directConn)
        activeConnections[directConn.id] = directConn
        directConn.start(alreadyStarted: true, initialBuffer: remainingBuffer)

        rosterManager.setPeerConnected(target.id, connected: true, endpoint: endpoint)
        updateCachedState()
        publishPeers()

        reconnectBackoffSeconds = 2.0
        isReconnecting = false

        transferStatus("Reconnected to \(target.name)")
        log("Reconnected to \(target.name) via \(endpoint.host)")
        broadcastRoster()
    }

    private func scheduleReconnectBackoff() {
        guard !isReconnecting else { return }
        let delay = reconnectBackoffSeconds
        reconnectBackoffSeconds = min(30.0, reconnectBackoffSeconds * 1.5)
        queue.asyncAfter(deadline: .now() + delay) { [weak self] in
            self?.triggerReconnect(reason: "backoff")
        }
    }

    // MARK: - Relaying & Roster Broadcasting

    private func broadcastEncrypted(_ message: [String: Any]) {
        for conn in activeConnections.values where conn.peerID != nil {
            conn.sendEncrypted(message, key: rosterManager.groupKey)
        }
    }

    private func relayEncrypted(_ message: [String: Any], except senderConn: DirectConnection, targetDeviceId: String?) {
        for (cid, conn) in activeConnections where cid != senderConn.id {
            guard let peerID = conn.peerID else { continue }
            if targetDeviceId == nil || targetDeviceId == peerID {
                conn.sendEncrypted(message, key: rosterManager.groupKey)
            }
        }
    }

    private func broadcastRoster() {
        let members = rosterManager.rosterPayload(localAddresses: Self.localAddresses(), port: Self.port)
        broadcastEncrypted(["type": "roster", "members": members])
    }

    private func updateCachedState() {
        stateLock.lock()
        cachedDeviceName = rosterManager.localName
        cachedPeersSnapshot = rosterManager.peerSnapshot()
        stateLock.unlock()
    }

    private func publishPeers() {
        let snapshot = peersSnapshot()
        DispatchQueue.main.async { [weak self] in
            self?.onPeersChanged?(snapshot)
        }
    }

    private func log(_ message: String, level: DiagnosticLevel = .info) {
        switch level {
        case .info: DiagnosticLog.shared.info(message)
        case .warning: DiagnosticLog.shared.warning(message)
        case .error: DiagnosticLog.shared.error(message)
        }
        DispatchQueue.main.async { [weak self] in
            self?.onLog?(message)
        }
    }

    private func transferStatus(_ message: String) {
        if !message.hasPrefix("Sending image") && !message.hasPrefix("Receiving image") {
            let isErr = message.localizedCaseInsensitiveContains("not sent") ||
                message.localizedCaseInsensitiveContains("rejected") ||
                message.localizedCaseInsensitiveContains("failed") ||
                message.localizedCaseInsensitiveContains("timed out") ||
                message.localizedCaseInsensitiveContains("cancelled")
            log(message, level: isErr ? .error : .info)
        }
        DispatchQueue.main.async { [weak self] in
            self?.onTransferStatus?(message)
        }
    }

    // MARK: - Heartbeats & Network Path Monitoring

    private func startHeartbeats() {
        guard heartbeatTimer == nil else { return }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 25, repeating: 25)
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            let now = Date()
            for conn in self.activeConnections.values where conn.peerID != nil {
                if now.timeIntervalSince(conn.lastActivity) > 60 {
                    self.log("Peer connection inactive — closing to reconnect", level: .warning)
                    conn.cancel()
                } else {
                    conn.sendEncrypted([
                        "type": "ping",
                        "timestamp": UInt64(now.timeIntervalSince1970 * 1000)
                    ], key: self.rosterManager.groupKey)
                }
            }
        }
        timer.resume()
        heartbeatTimer = timer
    }

    private func startPathMonitor() {
        guard pathMonitor == nil else { return }
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            self.queue.async {
                self.pathDebounceWorkItem?.cancel()
                let item = DispatchWorkItem { [weak self] in
                    guard let self else { return }
                    if path.status == .satisfied {
                        self.broadcastRoster()
                        self.triggerReconnect(reason: "network_satisfied")
                    } else {
                        for (id, _) in self.rosterManager.peers where self.rosterManager.peers[id]?.connected == true {
                            self.rosterManager.setPeerConnected(id, connected: false)
                        }
                        self.updateCachedState()
                        self.publishPeers()
                    }
                }
                self.pathDebounceWorkItem = item
                self.queue.asyncAfter(deadline: .now() + 0.5, execute: item)
            }
        }
        monitor.start(queue: queue)
        pathMonitor = monitor

        rosterRefreshTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 60, repeating: 60)
        timer.setEventHandler { [weak self] in
            self?.broadcastRoster()
        }
        timer.resume()
        rosterRefreshTimer = timer
    }

    // MARK: - Utilities

    private static func isPermissionError(_ error: NWError) -> Bool {
        if case .posix(let code) = error { return code == .EACCES || code == .EPERM }
        return error.localizedDescription.localizedCaseInsensitiveContains("permission")
    }

    private static func isPrivate172(_ host: String) -> Bool {
        let parts = host.split(separator: ".")
        guard parts.count == 4, let second = Int(parts[1]) else { return false }
        return second >= 16 && second <= 31
    }

    static func endpointPriority(_ address: String) -> Int {
        if address.hasPrefix("192.168.") || (address.hasPrefix("172.") && isPrivate172(address)) {
            return 0 // Local Wi-Fi subnet first
        } else if address.hasPrefix("10.") {
            return 1 // Private / Corp LAN / Cellular
        } else if address.hasPrefix("100.") {
            return 2 // Tailscale / Mesh VPN
        } else {
            return 3 // Fallback
        }
    }

    static func localAddresses() -> [String] {
        var result: [String] = []
        var pointer: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&pointer) == 0, let first = pointer else { return [] }
        defer { freeifaddrs(pointer) }
        for entry in sequence(first: first, next: { $0.pointee.ifa_next }) where entry.pointee.ifa_addr.pointee.sa_family == UInt8(AF_INET) {
            var address = entry.pointee.ifa_addr.pointee
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(&address, socklen_t(entry.pointee.ifa_addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                let value = String(cString: host)
                if !value.hasPrefix("127.") { result.append(value) }
            }
        }
        return Array(Set(result)).sorted {
            let left = endpointPriority($0), right = endpointPriority($1)
            return left == right ? $0 < $1 : left < right
        }
    }

    private static func urlSafeBase64(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
    }
}
