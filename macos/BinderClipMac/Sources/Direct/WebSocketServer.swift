import AppKit
import CryptoKit
import Darwin
import Foundation
import Network
import SystemConfiguration

public final class WebSocketServer: @unchecked Sendable {
    public var onClipboard: ((String) -> Void)?
    public var onOpenURL: ((URL) -> Void)?
    public var onImage: ((ImagePayload) -> Void)?
    public var onTransferStatus: ((String) -> Void)?
    public var onPeersChanged: (([Peer]) -> Void)?
    public var onLog: ((String) -> Void)?
    public var onLocalNetworkPermissionRequired: ((Bool) -> Void)?

    private let queue = DispatchQueue(label: "net.wastu.binderclip.websocket.server", qos: .userInitiated)
    private let rosterManager = RosterManager()
    private var listener: NWListener?
    private var pathMonitor: NWPathMonitor?
    private var pathDebounce: DispatchWorkItem?
    private var lastLocalAddresses: [String] = []
    private var lastPathSatisfied = false
    private var dynamicStore: SCDynamicStore?
    private var dynamicStoreSource: CFRunLoopSource?
    private var addressSampler: DispatchSourceTimer?
    private var addressDebounce: DispatchWorkItem?
    private var heartbeatTimer: DispatchSourceTimer?

    private var activeSessions: [ObjectIdentifier: WebSocketSession] = [:]
    private var lastProcessedHash: String = ""

    private let stateLock = NSLock()
    private var cachedDeviceName: String
    private var cachedPeersSnapshot: [Peer] = []

    public var localDeviceID: String { rosterManager.localID }
    public var localDeviceName: String {
        stateLock.lock(); defer { stateLock.unlock() }
        return cachedDeviceName
    }

    public var localEndpoint: DirectEndpoint {
        DirectEndpoint(host: Self.localAddresses().first ?? "unknown", port: SyncProtocol.defaultPort)
    }

    public init() {
        self.cachedDeviceName = rosterManager.localName
        self.cachedPeersSnapshot = rosterManager.peerSnapshot()
    }

    public func peersSnapshot() -> [Peer] {
        stateLock.lock(); defer { stateLock.unlock() }
        return cachedPeersSnapshot
    }

    public func setLocalDeviceName(_ name: String) {
        queue.async { [weak self] in
            guard let self else { return }
            let updated = self.rosterManager.setLocalName(name)
            self.updateCachedState()
            self.broadcastText(["type": "rename", "id": self.rosterManager.localID, "name": updated])
            self.publishPeers()
        }
    }

    public func renamePeer(id: String, newName: String) {
        if id == localDeviceID {
            setLocalDeviceName(newName)
            return
        }
        queue.async { [weak self] in
            guard let self else { return }
            _ = self.rosterManager.renamePeer(id: id, newName: newName)
            self.updateCachedState()
            self.publishPeers()
        }
    }

    public func start() {
        queue.async { [weak self] in
            guard let self else { return }
            self.rosterManager.markAllDisconnected()
            self.updateCachedState()
            self.publishPeers()
            self.startListener()
            self.startPathMonitor()
            self.startAddressWatch()
            self.startHeartbeat()
        }
    }

    public func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            self.pathDebounce?.cancel()
            self.pathDebounce = nil
            self.pathMonitor?.cancel()
            self.pathMonitor = nil
            self.stopAddressWatch()
            self.heartbeatTimer?.cancel()
            self.heartbeatTimer = nil
            self.listener?.cancel()
            self.listener = nil
            for session in self.activeSessions.values {
                session.connection.cancel()
            }
            self.activeSessions.removeAll()
            self.rosterManager.markAllDisconnected()
            self.updateCachedState()
            self.publishPeers()
        }
    }

    public func resetPairingKey() {
        queue.async { [weak self] in
            guard let self else { return }
            _ = self.rosterManager.rotateGroupKey()
            for session in self.activeSessions.values {
                session.connection.cancel()
            }
            self.activeSessions.removeAll()
            self.updateCachedState()
            self.publishPeers()
            self.onLog?("New pairing key generated")
        }
    }

    public func unpairAll() {
        queue.async { [weak self] in
            guard let self else { return }
            self.rosterManager.clearPairingState()
            for session in self.activeSessions.values {
                session.connection.cancel()
            }
            self.activeSessions.removeAll()
            self.updateCachedState()
            self.publishPeers()
            self.onLog?("Unpaired from all devices")
        }
    }

    public func removePeer(_ peerID: String) {
        queue.async { [weak self] in
            guard let self else { return }
            self.rosterManager.removePeer(id: peerID)
            for (id, session) in self.activeSessions where session.peerID == peerID {
                session.connection.cancel()
                self.activeSessions.removeValue(forKey: id)
            }
            self.updateCachedState()
            self.publishPeers()
            self.onLog?("Removed peer")
        }
    }

    public func createInvite() -> URL? {
        queue.sync {
            let endpoints = SyncProtocol.advertisedEndpoints(from: Self.localAddresses())
            guard !endpoints.isEmpty else { return nil }
            let psk = SyncProtocol.urlSafeBase64(rosterManager.groupKey)
            return SyncProtocol.createPairingURL(
                deviceId: localDeviceID,
                deviceName: localDeviceName,
                psk: psk,
                endpoints: endpoints
            )
        }
    }

    public func sendClipboard(_ text: String, targetDeviceId: String? = nil) {
        let hash = SyncProtocol.sha256Hex(text)
        queue.async { [weak self] in
            guard let self else { return }
            guard hash != self.lastProcessedHash else { return }
            self.lastProcessedHash = hash

            var payload: [String: Any] = [
                "type": "clipboard",
                "eventId": UUID().uuidString,
                "originId": self.localDeviceID,
                "text": text,
                "hash": hash,
                "timestamp": Int64(Date().timeIntervalSince1970 * 1000)
            ]
            if let targetDeviceId {
                payload["targetDeviceId"] = targetDeviceId
            }
            let authedCount = self.activeSessions.values.filter { $0.isAuthenticated }.count
            print("[WebSocketServer] Broadcasting clipboard to \(authedCount) authenticated sessions: \(text.prefix(30))")
            self.broadcastText(payload, targetPeerId: targetDeviceId)
        }
    }

    public func sendOpenURL(_ url: URL, targetDeviceId: String? = nil) {
        queue.async { [weak self] in
            guard let self else { return }
            var payload: [String: Any] = [
                "type": "openUrl",
                "eventId": UUID().uuidString,
                "originId": self.localDeviceID,
                "url": url.absoluteString
            ]
            if let targetDeviceId {
                payload["targetDeviceId"] = targetDeviceId
            }
            self.broadcastText(payload, targetPeerId: targetDeviceId)
        }
    }

    public func sendImage(_ image: ImagePayload, targetDeviceId: String? = nil) {
        queue.async { [weak self] in
            guard let self else { return }
            guard image.sha256 != self.lastProcessedHash else { return }
            self.lastProcessedHash = image.sha256

            let packet = SyncProtocol.packImage(image: image, originId: self.localDeviceID)
            guard !packet.isEmpty else { return }
            self.broadcastBinary(packet, targetPeerId: targetDeviceId)
            self.onTransferStatus?("Sent image (\(image.mimeType))")
        }
    }

    private func startListener() {
        do {
            let tcpOptions = NWProtocolTCP.Options()
            tcpOptions.enableKeepalive = true
            tcpOptions.keepaliveIdle = 15

            let wsOptions = NWProtocolWebSocket.Options()
            wsOptions.autoReplyPing = true
            wsOptions.maximumMessageSize = SyncProtocol.maximumImageBytes
            wsOptions.setClientRequestHandler(queue) { [weak self] _, _ in
                self?.onLog?("Accepted WebSocket handshake")
                return NWProtocolWebSocket.Response(status: .accept, subprotocol: nil)
            }

            let params = NWParameters(tls: nil, tcp: tcpOptions)
            params.defaultProtocolStack.applicationProtocols.insert(wsOptions, at: 0)
            params.allowLocalEndpointReuse = true

            let port = NWEndpoint.Port(rawValue: SyncProtocol.defaultPort) ?? .any
            let listener = try NWListener(using: params, on: port)

            listener.service = NWListener.Service(name: localDeviceName, type: "_binderclip._tcp")

            listener.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                switch state {
                case .ready:
                    self.onLog?("Listening on port \(SyncProtocol.defaultPort)")
                    self.onLocalNetworkPermissionRequired?(false)
                case .failed(let error):
                    self.onLog?("Listener failed: \(error.localizedDescription)")
                    if case .posix(let code) = error, code == POSIXErrorCode.EPERM {
                        self.onLocalNetworkPermissionRequired?(true)
                    }
                default:
                    break
                }
            }

            listener.newConnectionHandler = { [weak self] connection in
                self?.acceptIncoming(connection)
            }

            listener.start(queue: queue)
            self.listener = listener
        } catch {
            onLog?("Could not start listener: \(error.localizedDescription)")
        }
    }

    private func acceptIncoming(_ nwConnection: NWConnection) {
        let session = WebSocketSession(connection: nwConnection)
        let id = ObjectIdentifier(nwConnection)
        activeSessions[id] = session
        print("[WebSocketServer] Accepting incoming connection: \(nwConnection.endpoint)")
        fflush(stdout)

        nwConnection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            print("[WebSocketServer] Connection \(nwConnection.endpoint) state: \(state)")
            fflush(stdout)
            switch state {
            case .ready:
                self.bindSession(session)
                self.readNextMessage(from: session)
                self.publishPresence()
            case .waiting:
                self.publishPresence()
            case .failed(let error):
                print("[WebSocketServer] Incoming connection failed: \(error)")
                self.handleConnectionClosed(session: session)
            case .cancelled:
                self.handleConnectionClosed(session: session)
            default:
                break
            }
        }

        nwConnection.start(queue: queue)
    }

    private func handleConnectionClosed(session: WebSocketSession) {
        let id = ObjectIdentifier(session.connection)
        guard activeSessions.removeValue(forKey: id) != nil else { return }
        session.connection.cancel()
        publishPresence()
    }

    private func usableAuthenticatedPeerIDs() -> [String] {
        let now = Date()
        let locals = Self.localAddresses()
        return activeSessions.values.compactMap { session in
            guard session.isAuthenticated, let peerID = session.peerID else { return nil }
            guard SessionLiveness.isAlive(
                boundLocal: session.boundLocalAddress,
                currentLocals: locals,
                lastHeard: session.lastHeard,
                now: now
            ) else { return nil }
            if case .ready = session.connection.state { return peerID }
            return nil
        }
    }

    private func publishPresence() {
        for peerID in rosterManager.peers.keys {
            let connected = PeerPresence.isConnected(peerID: peerID, authenticatedPeerIDs: usableAuthenticatedPeerIDs())
            rosterManager.setPeerConnected(peerID, connected: connected)
        }
        updateCachedState()
        publishPeers()
    }

    private func readNextMessage(from session: WebSocketSession) {
        session.connection.receiveMessage { [weak self, weak session] data, context, isComplete, error in
            guard let self, let session else { return }
            if error != nil {
                self.handleConnectionClosed(session: session)
                return
            }

            if let data, !data.isEmpty {
                let isBinary = (context?.protocolMetadata(definition: NWProtocolWebSocket.definition) as? NWProtocolWebSocket.Metadata)?.opcode == .binary
                if isBinary {
                    self.handleBinaryMessage(data, from: session)
                } else {
                    self.handleTextMessage(data, from: session)
                }
            }

            self.readNextMessage(from: session)
        }
    }

    private func handleTextMessage(_ data: Data, from session: WebSocketSession) {
        guard let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let type = json["type"] as? String else {
            return
        }
        session.lastHeard = Date()

        switch type {
        case "auth", "hello":
            handleAuthMessage(json, session: session)

        case "clipboard":
            guard session.isAuthenticated else { return }
            if let text = json["text"] as? String {
                let hash = json["hash"] as? String ?? SyncProtocol.sha256Hex(text)
                guard hash != lastProcessedHash else { return }
                lastProcessedHash = hash
                DispatchQueue.main.async { [weak self] in
                    self?.onClipboard?(text)
                }
                broadcastText(json, excludeSessionId: ObjectIdentifier(session.connection))
            }

        case "openUrl":
            guard session.isAuthenticated else { return }
            if let urlStr = json["url"] as? String, let url = URL(string: urlStr) {
                DispatchQueue.main.async { [weak self] in
                    self?.onOpenURL?(url)
                }
            }

        case "rename":
            guard session.isAuthenticated else { return }
            if let peerID = json["id"] as? String, let name = json["name"] as? String {
                _ = rosterManager.renamePeer(id: peerID, newName: name)
                updateCachedState()
                publishPeers()
            }

        case "ping":
            session.sendText(["type": "pong", "t": json["t"] as Any])

        case "pong":
            break

        default:
            break
        }
    }

    private func handleAuthMessage(_ json: [String: Any], session: WebSocketSession) {
        let token = json["token"] as? String ?? json["psk"] as? String
        guard let token,
              let clientID = json["deviceId"] as? String,
              let clientName = json["deviceName"] as? String else {
            print("[WebSocketServer] Auth failed: missing token/deviceId/deviceName")
            session.connection.cancel()
            return
        }

        let expectedPsk = SyncProtocol.urlSafeBase64(rosterManager.groupKey)
        let standardPsk = rosterManager.groupKey.base64EncodedString()
        let match = token == expectedPsk || token == standardPsk || SyncProtocol.decodeBase64(token) == rosterManager.groupKey
        print("[WebSocketServer] Auth check: token=\(token.prefix(10))... match=\(match)")
        fflush(stdout)
        guard match else {
            onLog?("Unauthorized peer connection rejected")
            print("[WebSocketServer] PSK mismatch! Rejecting \(clientName)")
            session.connection.cancel()
            return
        }

        session.isAuthenticated = true
        session.peerID = clientID
        session.peerName = clientName

        let keepID = ObjectIdentifier(session.connection)
        for (id, other) in activeSessions where id != keepID && PeerPresence.shouldReplace(existingPeerID: other.peerID, incomingPeerID: clientID) {
            other.connection.cancel()
            activeSessions.removeValue(forKey: id)
        }

        let platform = json["platform"] as? String ?? "Android"
        let remoteHost = session.remoteHostString() ?? "unknown"
        let endpoint = DirectEndpoint(host: remoteHost, port: SyncProtocol.defaultPort)
        let peer = Peer(id: clientID, name: clientName, endpoint: endpoint, connected: true, platform: platform)

        _ = rosterManager.addOrUpdatePeer(peer)
        bindSession(session)
        publishPresence()

        session.sendText([
            "type": "auth_ok",
            "deviceId": localDeviceID,
            "deviceName": localDeviceName,
            "version": SyncProtocol.version,
            "endpoints": advertisedEndpointList()
        ])
        onLog?("Connected to \(clientName)")
    }

    private func handleBinaryMessage(_ data: Data, from session: WebSocketSession) {
        guard session.isAuthenticated else { return }
        session.lastHeard = Date()
        guard let (meta, imgData) = SyncProtocol.unpackImage(data),
              let image = try? ImagePayload(id: UUID(uuidString: meta.id) ?? UUID(), mimeType: meta.mimeType, data: imgData) else {
            return
        }

        guard image.sha256 != lastProcessedHash else { return }
        lastProcessedHash = image.sha256

        DispatchQueue.main.async { [weak self] in
            self?.onImage?(image)
            self?.onTransferStatus?("Received image (\(image.mimeType))")
        }

        broadcastBinary(data, excludeSessionId: ObjectIdentifier(session.connection))
    }

    private func broadcastText(_ object: [String: Any], targetPeerId: String? = nil, excludeSessionId: ObjectIdentifier? = nil) {
        let locals = Self.localAddresses()
        for (id, session) in activeSessions where session.isAuthenticated {
            if let excludeSessionId, id == excludeSessionId { continue }
            if let targetPeerId, session.peerID != targetPeerId { continue }
            if SessionLiveness.shouldEvict(boundLocal: session.boundLocalAddress, currentLocals: locals) { continue }
            session.sendText(object)
        }
    }

    private func broadcastBinary(_ data: Data, targetPeerId: String? = nil, excludeSessionId: ObjectIdentifier? = nil) {
        let locals = Self.localAddresses()
        for (id, session) in activeSessions where session.isAuthenticated {
            if let excludeSessionId, id == excludeSessionId { continue }
            if let targetPeerId, session.peerID != targetPeerId { continue }
            if SessionLiveness.shouldEvict(boundLocal: session.boundLocalAddress, currentLocals: locals) { continue }
            session.sendBinary(data)
        }
    }

    private func startPathMonitor() {
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            self.pathDebounce?.cancel()
            let work = DispatchWorkItem { [weak self] in
                self?.handlePathUpdate(path)
            }
            self.pathDebounce = work
            self.queue.asyncAfter(deadline: .now() + 0.2, execute: work)
        }
        monitor.start(queue: queue)
        self.pathMonitor = monitor
    }

    private func handlePathUpdate(_ path: NWPath) {
        let satisfied = path.status == .satisfied
        let regained = satisfied && !lastPathSatisfied
        lastPathSatisfied = satisfied
        if !satisfied {
            for session in activeSessions.values {
                session.connection.cancel()
            }
            publishPresence()
            onLog?("Network unavailable")
            return
        }
        if regained {
            refreshAddresses(reason: "path-regained")
        }
    }

    private func startAddressWatch() {
        lastLocalAddresses = Self.localAddresses()
        var context = SCDynamicStoreContext(
            version: 0,
            info: Unmanaged.passUnretained(self).toOpaque(),
            retain: nil,
            release: nil,
            copyDescription: nil
        )
        if let store = SCDynamicStoreCreate(
            nil,
            "net.wastu.binderclip.addresses" as CFString,
            { _, _, info in
                guard let info else { return }
                Unmanaged<WebSocketServer>.fromOpaque(info).takeUnretainedValue().scheduleAddressRefresh()
            },
            &context
        ) {
            let patterns = ["State:/Network/Interface/.*/IPv4", "State:/Network/Global/IPv4"] as CFArray
            SCDynamicStoreSetNotificationKeys(store, nil, patterns)
            if let source = SCDynamicStoreCreateRunLoopSource(nil, store, 0) {
                CFRunLoopAddSource(CFRunLoopGetMain(), source, .commonModes)
                dynamicStoreSource = source
            }
            dynamicStore = store
        }
        let sampler = DispatchSource.makeTimerSource(queue: queue)
        sampler.schedule(deadline: .now() + 2, repeating: 2)
        sampler.setEventHandler { [weak self] in
            self?.refreshAddresses(reason: "sample")
        }
        sampler.resume()
        addressSampler = sampler
    }

    private func stopAddressWatch() {
        addressDebounce?.cancel()
        addressDebounce = nil
        addressSampler?.cancel()
        addressSampler = nil
        let source = dynamicStoreSource
        dynamicStoreSource = nil
        dynamicStore = nil
        if let source {
            DispatchQueue.main.async {
                CFRunLoopRemoveSource(CFRunLoopGetMain(), source, .commonModes)
            }
        }
    }

    private func scheduleAddressRefresh() {
        addressDebounce?.cancel()
        let work = DispatchWorkItem { [weak self] in
            self?.refreshAddresses(reason: "interfaces")
        }
        addressDebounce = work
        queue.asyncAfter(deadline: .now() + 0.2, execute: work)
    }

    private func refreshAddresses(reason: String) {
        let addresses = Self.localAddresses()
        guard addresses != lastLocalAddresses else { return }
        lastLocalAddresses = addresses
        refreshBonjour()
        broadcastCurrentEndpoints()
        evictSessionsMissingBind(currentLocals: addresses)
        publishPresence()
        onLog?("Addresses changed (\(reason)): \(addresses.joined(separator: ", "))")
    }

    private func evictSessionsMissingBind(currentLocals: [String]) {
        for session in activeSessions.values where SessionLiveness.shouldEvict(boundLocal: session.boundLocalAddress, currentLocals: currentLocals) {
            onLog?("Evicting session bound to \(session.boundLocalAddress ?? "unknown")")
            session.connection.cancel()
        }
    }

    private func bindSession(_ session: WebSocketSession) {
        let remote = session.remoteHostString() ?? ""
        session.boundLocalAddress = SessionLiveness.boundLocalAddress(remote: remote, localAddresses: Self.localAddresses())
        session.lastHeard = Date()
    }

    private func startHeartbeat() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + SyncProtocol.heartbeatInterval, repeating: SyncProtocol.heartbeatInterval)
        timer.setEventHandler { [weak self] in
            self?.tickHeartbeat()
        }
        timer.resume()
        heartbeatTimer = timer
    }

    private func tickHeartbeat() {
        let now = Date()
        let locals = Self.localAddresses()
        var evicted = false
        for session in activeSessions.values {
            guard session.isAuthenticated else { continue }
            if !SessionLiveness.isAlive(
                boundLocal: session.boundLocalAddress,
                currentLocals: locals,
                lastHeard: session.lastHeard,
                now: now
            ) {
                session.connection.cancel()
                evicted = true
                continue
            }
            session.sendText([
                "type": "ping",
                "t": Int64(now.timeIntervalSince1970 * 1000)
            ])
        }
        if evicted {
            publishPresence()
        }
    }

    private func advertisedEndpointList() -> [String] {
        SyncProtocol.advertisedEndpoints(from: Self.localAddresses())
    }

    private func broadcastCurrentEndpoints() {
        let endpoints = advertisedEndpointList()
        guard !endpoints.isEmpty else { return }
        broadcastText(["type": "endpoints", "endpoints": endpoints])
    }

    private func refreshBonjour() {
        listener?.service = NWListener.Service(name: localDeviceName, type: "_binderclip._tcp")
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

    public static func localAddresses() -> [String] {
        var addresses: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return [] }
        defer { freeifaddrs(ifaddr) }

        var ptr = first
        while true {
            let flags = Int32(ptr.pointee.ifa_flags)
            let isUp = (flags & IFF_UP) != 0
            let isLoopback = (flags & IFF_LOOPBACK) != 0
            let isRunning = (flags & IFF_RUNNING) != 0

            if isUp && isRunning && !isLoopback, let addr = ptr.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) {
                var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &hostname, socklen_t(hostname.count), nil, 0, NI_NUMERICHOST) == 0 {
                    let ip = String(cString: hostname)
                    if !ip.hasPrefix("169.254.") && ip != "127.0.0.1" {
                        addresses.append(ip)
                    }
                }
            }
            guard let next = ptr.pointee.ifa_next else { break }
            ptr = next
        }
        return addresses
    }
}

private final class WebSocketSession: @unchecked Sendable {
    let connection: NWConnection
    var isAuthenticated: Bool = false
    var peerID: String?
    var peerName: String?
    var boundLocalAddress: String?
    var lastHeard: Date?

    init(connection: NWConnection) {
        self.connection = connection
    }

    func sendText(_ object: [String: Any]) {
        do {
            let data = try JSONSerialization.data(withJSONObject: object)
            let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
            let context = NWConnection.ContentContext(identifier: "text", metadata: [metadata])
            connection.send(content: data, contentContext: context, isComplete: true, completion: .contentProcessed { error in
                if let error {
                    print("[WebSocketSession] sendText error: \(error)")
                }
            })
        } catch {
            print("[WebSocketSession] JSONSerialization failed: \(error)")
        }
    }

    func sendBinary(_ data: Data) {
        let metadata = NWProtocolWebSocket.Metadata(opcode: .binary)
        let context = NWConnection.ContentContext(identifier: "binary", metadata: [metadata])
        connection.send(content: data, contentContext: context, isComplete: true, completion: .contentProcessed { error in
            if let error {
                print("[WebSocketSession] sendBinary error: \(error)")
            }
        })
    }

    func remoteHostString() -> String? {
        if case .hostPort(let host, _) = connection.endpoint {
            switch host {
            case .ipv4(let ip): return "\(ip)"
            case .ipv6(let ip): return "\(ip)"
            case .name(let name, _): return name
            @unknown default: return nil
            }
        }
        return nil
    }
}
