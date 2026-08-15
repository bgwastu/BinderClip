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

/// Single-purpose direct TCP listener. Every post-pairing payload is AES-GCM
/// encrypted with a group key received only through a one-time QR invitation.
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

    private final class Context {
        var buffer = Data()
        var peerID: String?
        var pairKey: Data?
        var inboundImage: InboundImage?
        var outboundImage: OutboundImage?
        var outboundTimeout: DispatchWorkItem?
        var receiveStarted = false
        var lastActivity = Date()
    }
    private final class InboundImage {
        let id: UUID; let wireID: String; let mimeType: String; let expectedBytes: Int; let expectedHash: String
        var data = Data(); var nextIndex = 0
        init(id: UUID, wireID: String, mimeType: String, expectedBytes: Int, expectedHash: String) {
            self.id = id; self.wireID = wireID; self.mimeType = mimeType; self.expectedBytes = expectedBytes; self.expectedHash = expectedHash
        }
    }
    private final class OutboundImage {
        let image: ImagePayload
        var nextIndex = 0
        var acknowledgedIndex = -1
        init(_ image: ImagePayload) { self.image = image }
    }
    private struct Invite { let key: Data; let expiresAt: Date }

    private let queue = DispatchQueue(label: "net.wastu.binderclip.transport")
    private let maximumInFlightImageChunks = 4
    private let secureStore = PrivateStateStore()
    private let localID: String
    private let localName: String
    private var listener: NWListener?
    private var pathMonitor: NWPathMonitor?
    private var heartbeatTimer: DispatchSourceTimer?
    private var recentMessageIDs: [String] = []
    private var contexts: [ObjectIdentifier: Context] = [:]
    private var connections: [ObjectIdentifier: NWConnection] = [:]
    private var peers: [String: Peer] = [:]
    private var invites: [UUID: Invite] = [:]
    private let groupKey: Data
    var localDeviceID: String { localID }
    var localDeviceName: String { localName }
    var localEndpoint: DirectEndpoint { DirectEndpoint(host: Self.localAddresses().first ?? "unknown", port: Self.port) }

    init(localName: String = Host.current().localizedName ?? "Mac") {
        self.localName = localName
        if let saved = UserDefaults.standard.string(forKey: "device.id") { localID = saved }
        else { let id = UUID().uuidString; UserDefaults.standard.set(id, forKey: "device.id"); localID = id }
        if let existing = secureStore.data(account: "group-key") { groupKey = existing }
        else { let generated = DirectCrypto.randomBytes(count: 32); secureStore.set(generated, account: "group-key"); groupKey = generated }
        loadPeers()
    }

    func start() {
        queue.async { [weak self] in
            guard let self, self.listener == nil else { return }
            self.startListener(includeBonjour: true)
        }
    }

    private func startListener(includeBonjour: Bool) {
        do {
            let listener = try NWListener(using: .tcp, on: NWEndpoint.Port(rawValue: Self.port)!)
            if includeBonjour {
                let txtData = "id=\(self.localID)".data(using: .utf8)
                listener.service = NWListener.Service(name: self.localName, type: "_binderclip._tcp", domain: nil, txtRecord: txtData)
            }
            listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
            listener.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                switch state {
                case .ready:
                    self.onLocalNetworkPermissionRequired?(false)
                case .waiting(let error), .failed(let error):
                    self.onLocalNetworkPermissionRequired?(Self.isPermissionError(error))
                    if case .failed = state {
                        self.log("Listener failed: \(error.localizedDescription)")
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
            self.listener = listener
            listener.start(queue: self.queue)
            self.startPathMonitor()
            self.startHeartbeats()
            self.log(includeBonjour ? "Listening for direct connections with Bonjour discovery" : "Listening for direct connections")
        } catch { self.log("Could not listen: \(error.localizedDescription)") }
    }

    private func startPathMonitor() {
        guard pathMonitor == nil else { return }
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            self.queue.async {
                if path.status == .satisfied {
                    self.sendRoster(only: nil)
                }
            }
        }
        monitor.start(queue: queue)
        pathMonitor = monitor
    }

    private func startHeartbeats() {
        guard heartbeatTimer == nil else { return }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 20, repeating: 20)
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            let now = Date()
            for (id, connection) in self.connections {
                guard let context = self.contexts[id], context.peerID != nil else { continue }
                if now.timeIntervalSince(context.lastActivity) > 45 {
                    self.log("Closing unresponsive peer connection", level: .warning)
                    self.close(connection)
                } else {
                    self.sendEncrypted(["type": "ping", "timestamp": UInt64(now.timeIntervalSince1970 * 1000)], only: connection)
                }
            }
        }
        timer.resume()
        heartbeatTimer = timer
    }

    private static func isPermissionError(_ error: NWError) -> Bool {
        if case .posix(let code) = error { return code == .EACCES || code == .EPERM }
        return error.localizedDescription.localizedCaseInsensitiveContains("permission")
    }

    func stop() {
        queue.async { [weak self] in
            self?.heartbeatTimer?.cancel(); self?.heartbeatTimer = nil
            self?.pathMonitor?.cancel(); self?.pathMonitor = nil
            self?.connections.values.forEach { $0.cancel() }
            self?.connections.removeAll(); self?.contexts.removeAll(); self?.listener?.cancel(); self?.listener = nil
        }
    }

    func createInvite() -> URL? {
        queue.sync { createInviteLocked() }
    }

    private func createInviteLocked() -> URL? {
        let endpoints = Self.localAddresses().map { DirectEndpoint(host: $0, port: Self.port) }
        guard !endpoints.isEmpty else { return nil }
        let id = UUID(); let key = DirectCrypto.randomBytes(count: 32)
        invites[id] = Invite(key: key, expiresAt: Date().addingTimeInterval(300))
        var components = URLComponents()
        components.scheme = "binderclip"; components.host = "invite"
        components.queryItems = endpoints.prefix(4).map { URLQueryItem(name: "host", value: $0.host) } + [
            .init(name: "port", value: String(Self.port)),
            .init(name: "id", value: id.uuidString), .init(name: "key", value: Self.urlSafeBase64(key)),
        ]
        return components.url
    }

    func sendClipboard(_ text: String, targetDeviceId: String? = nil) {
        queue.async { [weak self] in
            guard let self else { return }
            guard !text.isEmpty, text.utf8.count <= Self.maximumTextBytes else {
                self.transferStatus("Clipboard not sent — unsupported content")
                return
            }
            let targetConnections = self.connections.values.filter { connection in
                guard let peerID = self.contexts[ObjectIdentifier(connection)]?.peerID else { return false }
                return targetDeviceId == nil || peerID == targetDeviceId
            }
            guard !targetConnections.isEmpty else {
                self.transferStatus("Clipboard not sent — no connected device")
                return
            }
            var operation: [String: Any] = ["type": "clipboard", "id": UUID().uuidString, "origin": self.localID,
                                            "timestamp": UInt64(Date().timeIntervalSince1970 * 1_000), "text": text]
            if let targetDeviceId { operation["targetDeviceId"] = targetDeviceId }
            for connection in targetConnections {
                self.sendEncrypted(operation, only: connection)
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
            let targetConnections = self.connections.values.filter { connection in
                guard let peerID = self.contexts[ObjectIdentifier(connection)]?.peerID else { return false }
                return targetDeviceId == nil || peerID == targetDeviceId
            }
            guard !targetConnections.isEmpty else {
                self.transferStatus("URL not sent — no connected device")
                return
            }
            var operation: [String: Any] = ["type": "openUrl", "id": UUID().uuidString, "origin": self.localID,
                                            "timestamp": UInt64(Date().timeIntervalSince1970 * 1_000), "url": urlString]
            if let targetDeviceId { operation["targetDeviceId"] = targetDeviceId }
            for connection in targetConnections {
                self.sendEncrypted(operation, only: connection)
            }
            self.transferStatus("Sent URL to peer")
        }
    }

    func sendImage(_ image: ImagePayload) {
        queue.async { [weak self] in
            guard let self else { return }
            let targets = self.connections.values.filter { self.contexts[ObjectIdentifier($0)]?.peerID != nil }
            guard !targets.isEmpty else { self.transferStatus("Image not sent — device unavailable"); return }
            var started = false
            for connection in targets {
                guard let context = self.contexts[ObjectIdentifier(connection)], context.outboundImage == nil else { continue }
                context.outboundImage = OutboundImage(image)
                self.sendEncrypted(["type": "mediaOffer", "id": image.id.uuidString, "mime": image.mimeType,
                                    "bytes": image.data.count, "sha256": image.sha256], only: connection)
                self.transferStatus("Offering image")
                started = true
            }
            if !started { self.transferStatus("Image not sent — another image is sending") }
        }
    }

    func peersSnapshot() -> [Peer] { queue.sync { peers.values.sorted { $0.name < $1.name } } }

    private func accept(_ connection: NWConnection) {
        let id = ObjectIdentifier(connection)
        contexts[id] = Context(); connections[id] = connection
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection else { return }
            switch state {
            case .failed, .cancelled:
                self.close(connection)
            default:
                break
            }
        }
        connection.start(queue: queue)
        // Register the stream read after the connection has been scheduled on its queue.
        queue.async { [weak self, weak connection] in
            guard let self, let connection else { return }
            let id = ObjectIdentifier(connection)
            guard self.contexts[id]?.receiveStarted == false else { return }
            self.contexts[id]?.receiveStarted = true
            self.receive(connection)
        }
    }

    private func receive(_ connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: FrameCodec.maximumPayloadBytes + 4) { [weak self, weak connection] data, _, complete, error in
            guard let self, let connection else { return }
            self.queue.async {
                let id = ObjectIdentifier(connection)
                guard let context = self.contexts[id] else { return }
                if let data { context.buffer.append(data) }
                do {
                    while let frame = try FrameCodec.decode(from: &context.buffer) { try self.handle(frame, connection: connection, context: context) }
                } catch { self.log("Rejected peer frame: \(error)", level: .error); self.close(connection); return }
                if complete || error != nil {
                    self.log("Peer closed connection\(error.map { ": \($0.localizedDescription)" } ?? "")", level: error == nil ? .info : .warning)
                    self.close(connection)
                } else { self.receive(connection) }
            }
        }
    }

    private func handle(_ frame: Data, connection: NWConnection, context: Context) throws {
        guard let object = try JSONSerialization.jsonObject(with: frame) as? [String: Any] else { throw DirectCryptoError.malformed }
        if object["type"] as? String == "invite" { try handleInvite(object, connection: connection, context: context); return }
        let message = try DirectCrypto.open(object, key: groupKey)
        context.lastActivity = Date()
        switch message["type"] as? String {
        case "hello":
            guard let id = message["deviceID"] as? String, let name = message["name"] as? String,
                  peers[id] != nil || context.pairKey != nil else { throw DirectCryptoError.authentication }
            let firstHello = context.peerID == nil
            context.peerID = id
            let endpoint: DirectEndpoint
            if let remote = connection.currentPath?.remoteEndpoint,
               case let .hostPort(host, port) = remote {
                endpoint = DirectEndpoint(host: host.debugDescription, port: port.rawValue)
            } else {
                endpoint = DirectEndpoint(host: "unknown", port: Self.port)
            }
            peers[id] = Peer(id: id, name: name, endpoint: endpoint, connected: true, platform: message["platform"] as? String ?? "Android")
            persistPeers(); publishPeers()
            if firstHello {
                sendEncrypted(["type": "hello", "deviceID": localID, "name": localName, "platform": "macOS"], only: connection)
                sendRoster(only: nil)
            }
        case "clipboard":
            guard let text = message["text"] as? String, text.utf8.count <= Self.maximumTextBytes else { throw DirectCryptoError.malformed }
            if let target = message["targetDeviceId"] as? String, !target.isEmpty, target != localID {
                return
            }
            if let msgID = message["id"] as? String {
                if recentMessageIDs.contains(msgID) { return }
                recentMessageIDs.append(msgID)
                if recentMessageIDs.count > 64 { recentMessageIDs.removeFirst() }
            }
            log("Received clipboard text")
            onClipboard?(text)
        case "openUrl":
            guard let urlString = message["url"] as? String, urlString.utf8.count <= Self.maximumTextBytes,
                  let url = URL(string: urlString), let scheme = url.scheme?.lowercased(),
                  scheme == "http" || scheme == "https" else { throw DirectCryptoError.malformed }
            if let target = message["targetDeviceId"] as? String, !target.isEmpty, target != localID {
                return
            }
            if let msgID = message["id"] as? String {
                if recentMessageIDs.contains(msgID) { return }
                recentMessageIDs.append(msgID)
                if recentMessageIDs.count > 64 { recentMessageIDs.removeFirst() }
            }
            log("Received URL to open")
            onOpenURL?(url)
        case "ping":
            sendEncrypted(["type": "pong"], only: connection)
        case "pong":
            break
        case "mediaOffer": try handleMediaOffer(message, connection: connection, context: context)
        case "mediaAccept": try handleMediaAccept(message, connection: connection, context: context)
        case "mediaChunk": try handleMediaChunk(message, connection: connection, context: context)
        case "mediaAck": try handleMediaAck(message, connection: connection, context: context)
        case "mediaComplete": try handleMediaComplete(message, connection: connection, context: context)
        case "mediaReject":
            context.outboundTimeout?.cancel(); context.outboundTimeout = nil; context.outboundImage = nil
            transferStatus("Image rejected by peer")
        case "mediaAbort":
            context.outboundTimeout?.cancel(); context.outboundTimeout = nil; context.inboundImage = nil; context.outboundImage = nil
            transferStatus("Image transfer cancelled")
        case "rosterRemove":
            guard let target = message["id"] as? String else { throw DirectCryptoError.malformed }
            removeFromChain(target, requestedBy: connection)
        case "inviteRequest":
            guard context.peerID != nil, let invite = createInviteLocked() else { throw DirectCryptoError.authentication }
            sendEncrypted(["type": "invite", "url": invite.absoluteString], only: connection)
        default: throw DirectCryptoError.malformed
        }
    }

    private func handleMediaOffer(_ message: [String: Any], connection: NWConnection, context: Context) throws {
        guard context.inboundImage == nil, let rawID = message["id"] as? String, let id = UUID(uuidString: rawID),
              let mime = message["mime"] as? String, let bytes = message["bytes"] as? Int,
              let hash = message["sha256"] as? String, ImagePayload.allowedMIMETypes.contains(mime),
              bytes > 0, bytes <= ImagePayload.maximumBytes, hash.count == 64 else {
            sendEncrypted(["type": "mediaReject", "reason": "Unsupported image"], only: connection); return
        }
        context.inboundImage = InboundImage(id: id, wireID: rawID, mimeType: mime, expectedBytes: bytes, expectedHash: hash)
        sendEncrypted(["type": "mediaAccept", "id": rawID], only: connection)
        transferStatus("Receiving image")
    }
    private func handleMediaAccept(_ message: [String: Any], connection: NWConnection, context: Context) throws {
        guard let outbound = context.outboundImage, message["id"] as? String == outbound.image.id.uuidString else { throw ImageTransferError.protocolViolation }
        sendImageWindow(outbound, connection: connection)
    }
    private func handleMediaChunk(_ message: [String: Any], connection: NWConnection, context: Context) throws {
        guard let inbound = context.inboundImage else { log("Rejected image chunk without an offer"); throw ImageTransferError.protocolViolation }
        guard let rawID = message["id"] as? String, UUID(uuidString: rawID) == inbound.id,
              let index = message["index"] as? Int, index == inbound.nextIndex,
              let encoded = message["data"] as? String, let chunk = Data(base64Encoded: encoded),
              !chunk.isEmpty, chunk.count <= ImagePayload.chunkBytes, inbound.data.count + chunk.count <= inbound.expectedBytes else {
            log("Rejected image chunk at index \(message["index"] ?? "missing")")
            throw ImageTransferError.protocolViolation
        }
        inbound.data.append(chunk); inbound.nextIndex += 1
        sendEncrypted(["type": "mediaAck", "id": inbound.wireID, "index": index], only: connection)
        let percent = inbound.expectedBytes > 0 ? (inbound.data.count * 100 / inbound.expectedBytes) : 0
        transferStatus("Receiving image \(percent)%")
    }
    private func handleMediaAck(_ message: [String: Any], connection: NWConnection, context: Context) throws {
        guard let outbound = context.outboundImage, message["id"] as? String == outbound.image.id.uuidString,
              let acknowledged = message["index"] as? Int,
              acknowledged > outbound.acknowledgedIndex, acknowledged < outbound.nextIndex else { log("Rejected image acknowledgement"); throw ImageTransferError.protocolViolation }
        context.outboundTimeout?.cancel(); context.outboundTimeout = nil
        outbound.acknowledgedIndex = acknowledged
        sendImageWindow(outbound, connection: connection)
    }
    private func handleMediaComplete(_ message: [String: Any], connection: NWConnection, context: Context) throws {
        guard let inbound = context.inboundImage, let rawID = message["id"] as? String, UUID(uuidString: rawID) == inbound.id,
              inbound.data.count == inbound.expectedBytes,
              SHA256.hash(data: inbound.data).map({ String(format: "%02x", $0) }).joined() == inbound.expectedHash,
              let image = try? ImagePayload(id: inbound.id, mimeType: inbound.mimeType, data: inbound.data) else { throw ImageTransferError.hashMismatch }
        context.inboundImage = nil; onImage?(image); transferStatus("Received image")
    }
    /// TCP preserves ordering; cumulative ACKs keep a small direct-transfer window full.
    private func sendImageWindow(_ outbound: OutboundImage, connection: NWConnection) {
        let totalChunks = (outbound.image.data.count + ImagePayload.chunkBytes - 1) / ImagePayload.chunkBytes
        while outbound.nextIndex < totalChunks && outbound.nextIndex <= outbound.acknowledgedIndex + maximumInFlightImageChunks {
            let offset = outbound.nextIndex * ImagePayload.chunkBytes
            let end = min(offset + ImagePayload.chunkBytes, outbound.image.data.count)
            sendEncrypted(["type": "mediaChunk", "id": outbound.image.id.uuidString, "index": outbound.nextIndex,
                           "data": outbound.image.data.subdata(in: offset..<end).base64EncodedString()], only: connection)
            outbound.nextIndex += 1
        }
        guard outbound.acknowledgedIndex + 1 < totalChunks else {
            sendEncrypted(["type": "mediaComplete", "id": outbound.image.id.uuidString], only: connection)
            let context = contexts[ObjectIdentifier(connection)]
            context?.outboundTimeout?.cancel(); context?.outboundTimeout = nil; context?.outboundImage = nil
            transferStatus("Image sent"); return
        }
        scheduleOutboundTimeout(for: connection, imageID: outbound.image.id, index: outbound.acknowledgedIndex + 1)
        transferStatus("Sending image \((outbound.acknowledgedIndex + 1) * 100 / totalChunks)%")
    }

    private func scheduleOutboundTimeout(for connection: NWConnection, imageID: UUID, index: Int) {
        let id = ObjectIdentifier(connection)
        let context = contexts[id]
        context?.outboundTimeout?.cancel()
        let timeout = DispatchWorkItem { [weak self, weak connection] in
            guard let self, let connection, let current = self.contexts[ObjectIdentifier(connection)]?.outboundImage,
                  current.image.id == imageID, current.acknowledgedIndex + 1 == index else { return }
            self.sendEncrypted(["type": "mediaAbort", "id": imageID.uuidString], only: connection)
            self.contexts[ObjectIdentifier(connection)]?.outboundImage = nil
            self.transferStatus("Image transfer timed out")
        }
        context?.outboundTimeout = timeout
        queue.asyncAfter(deadline: .now() + 15, execute: timeout)
    }

    private func handleInvite(_ object: [String: Any], connection: NWConnection, context: Context) throws {
        guard let rawID = object["id"] as? String, let id = UUID(uuidString: rawID), let invite = invites.removeValue(forKey: id),
              invite.expiresAt > Date(), let nonce = object["nonce"] as? String, let proof = object["proof"] as? String,
              DirectCrypto.constantTimeEqual(proof, DirectCrypto.hmac(key: invite.key, value: "client|\(rawID)|\(nonce)")) else { throw DirectCryptoError.authentication }
        let serverNonce = DirectCrypto.randomBytes(count: 16).base64EncodedString()
        let serverProof = DirectCrypto.hmac(key: invite.key, value: "server|\(rawID)|\(nonce)|\(serverNonce)")
        context.pairKey = DirectCrypto.pairSessionKey(inviteKey: invite.key, clientNonce: nonce, serverNonce: serverNonce)
        send(["type": "inviteAccepted", "nonce": serverNonce, "proof": serverProof], to: connection)
        let welcome = try DirectCrypto.seal(["type": "welcome", "groupKey": groupKey.base64EncodedString(), "deviceID": localID, "name": localName, "members": rosterPayload()], key: context.pairKey!)
        send(welcome, to: connection)
    }

    private func sendEncrypted(_ message: [String: Any], only connection: NWConnection?) {
        guard let encrypted = try? DirectCrypto.seal(message, key: groupKey) else {
            log("Could not encrypt outbound message", level: .error)
            transferStatus("Send failed — encryption error")
            return
        }
        if let connection { send(encrypted, to: connection) }
        else { connections.values.forEach { connection in if contexts[ObjectIdentifier(connection)]?.peerID != nil { send(encrypted, to: connection) } } }
    }

    func removeFromChain(_ peerID: String) {
        queue.async { [weak self] in self?.removeFromChain(peerID, requestedBy: nil) }
    }

    private func removeFromChain(_ peerID: String, requestedBy connection: NWConnection?) {
        guard peerID != localID else {
            sendEncrypted(["type": "rosterRemove", "id": localID], only: nil)
            peers.removeAll(); persistPeers(); publishPeers()
            connections.values.forEach { close($0) }
            transferStatus("Left the BinderClip chain")
            return
        }
        guard peers.removeValue(forKey: peerID) != nil else { return }
        persistPeers(); publishPeers()
        // Broadcast removal to ALL authenticated peers so everyone drops the target.
        sendEncrypted(["type": "rosterRemove", "id": peerID], only: nil)
        // Then close the removed peer's connection.
        for candidate in connections.values {
            if contexts[ObjectIdentifier(candidate)]?.peerID == peerID {
                close(candidate)
            }
        }
        sendRoster(only: nil)
    }

    private func rosterPayload() -> [[String: Any]] {
        var result: [[String: Any]] = [["id": localID, "name": localName, "host": Self.localAddresses().first ?? "", "port": Int(Self.port), "platform": "macOS", "connected": true]]
        result += peers.values.sorted { $0.name < $1.name }.map { peer in
            ["id": peer.id, "name": peer.name, "host": peer.endpoint.host, "port": Int(peer.endpoint.port), "platform": peer.platform, "connected": peer.connected]
        }
        return result
    }

    private func sendRoster(only connection: NWConnection?) {
        sendEncrypted(["type": "roster", "members": rosterPayload()], only: connection)
    }

    private func send(_ object: [String: Any], to connection: NWConnection) {
        guard let body = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]), let frame = try? FrameCodec.encode(body) else {
            log("Could not encode outbound message", level: .error)
            transferStatus("Send failed — encoding error")
            return
        }
        connection.send(content: frame, completion: .contentProcessed { [weak self] error in
            if let error {
                self?.log("Send failed: \(error.localizedDescription)", level: .error)
                self?.transferStatus("Send failed — connection lost")
                self?.close(connection)
            }
        })
    }

    private func close(_ connection: NWConnection) {
        queue.async { [weak self] in
            guard let self else { return }; let id = ObjectIdentifier(connection)
            self.contexts[id]?.outboundTimeout?.cancel()
            if let peerID = self.contexts[id]?.peerID, var peer = self.peers[peerID] { peer.connected = false; self.peers[peerID] = peer; self.persistPeers(); self.publishPeers() }
            self.contexts.removeValue(forKey: id); self.connections.removeValue(forKey: id); connection.cancel()
        }
    }

    private func loadPeers() {
        guard let data = UserDefaults.standard.data(forKey: "peers"), let saved = try? JSONDecoder().decode([Peer].self, from: data) else { return }
        peers = Dictionary(uniqueKeysWithValues: saved.map { ($0.id, $0) })
    }
    private func persistPeers() { UserDefaults.standard.set(try? JSONEncoder().encode(Array(peers.values)), forKey: "peers") }
    private func publishPeers() {
        let snapshot = peers.values.sorted { $0.name < $1.name }
        DispatchQueue.main.async { self.onPeersChanged?(snapshot) }
    }
    private func log(_ message: String, level: DiagnosticLevel = .info) {
        switch level {
        case .info: DiagnosticLog.shared.info(message)
        case .warning: DiagnosticLog.shared.warning(message)
        case .error: DiagnosticLog.shared.error(message)
        }
        DispatchQueue.main.async { self.onLog?(message) }
    }
    private func transferStatus(_ message: String) {
        if !message.hasPrefix("Sending image") && !message.hasPrefix("Receiving image") {
            let level: DiagnosticLevel = message.localizedCaseInsensitiveContains("not sent") || message.localizedCaseInsensitiveContains("rejected") || message.localizedCaseInsensitiveContains("failed") || message.localizedCaseInsensitiveContains("timed out") || message.localizedCaseInsensitiveContains("cancelled") ? .error : .info
            switch level {
            case .info: DiagnosticLog.shared.info(message)
            case .warning: DiagnosticLog.shared.warning(message)
            case .error: DiagnosticLog.shared.error(message)
            }
        }
        DispatchQueue.main.async { self.onTransferStatus?(message) }
    }

    private static func localAddresses() -> [String] {
        var result: [String] = []; var pointer: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&pointer) == 0, let first = pointer else { return [] }
        defer { freeifaddrs(pointer) }
        for entry in sequence(first: first, next: { $0.pointee.ifa_next }) where entry.pointee.ifa_addr.pointee.sa_family == UInt8(AF_INET) {
            var address = entry.pointee.ifa_addr.pointee; var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(&address, socklen_t(entry.pointee.ifa_addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                let value = String(cString: host); if !value.hasPrefix("127.") { result.append(value) }
            }
        }
        return Array(Set(result)).sorted {
            func priority(_ address: String) -> Int {
                if address.hasPrefix("100.") { return 0 } // common mesh/VPN range
                if address.hasPrefix("10.") || address.hasPrefix("172.") || address.hasPrefix("192.168.") { return 1 }
                return 2
            }
            let left = priority($0), right = priority($1)
            return left == right ? $0 < $1 : left < right
        }
    }

    private static func urlSafeBase64(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
    }
}
