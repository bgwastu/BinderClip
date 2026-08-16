import Foundation

/// Manages identity, peer roster, host target, and tombstoned devices to ensure
/// deterministic chain state and prevent zombie peer resurrection.
final class RosterManager {
    private let secureStore = PrivateStateStore()
    private(set) var localID: String
    private(set) var localName: String
    private(set) var groupKey: Data
    private(set) var hostTarget: HostTarget?
    private(set) var peers: [String: Peer] = [:]
    private var tombstones: Set<String> = []

    init() {
        if let savedName = UserDefaults.standard.string(forKey: "device.custom_name")?.trimmingCharacters(in: .whitespacesAndNewlines),
           !savedName.isEmpty {
            self.localName = savedName
        } else {
            self.localName = Host.current().localizedName ?? "Mac"
        }

        if let savedID = UserDefaults.standard.string(forKey: "device.id"), !savedID.isEmpty {
            self.localID = savedID
        } else {
            let id = UUID().uuidString
            UserDefaults.standard.set(id, forKey: "device.id")
            self.localID = id
        }

        if let existingKey = secureStore.data(account: "group-key"), existingKey.count == 32 {
            self.groupKey = existingKey
        } else {
            let generated = DirectCrypto.randomBytes(count: 32)
            secureStore.set(generated, account: "group-key")
            self.groupKey = generated
        }

        loadPeers()
        loadHostTarget()
        loadTombstones()
    }

    func setLocalName(_ name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let updated = trimmed.isEmpty ? (Host.current().localizedName ?? "Mac") : trimmed
        self.localName = updated
        if trimmed.isEmpty {
            UserDefaults.standard.removeObject(forKey: "device.custom_name")
        } else {
            UserDefaults.standard.set(trimmed, forKey: "device.custom_name")
        }
        return updated
    }

    func rotateGroupKey() -> Data {
        let fresh = DirectCrypto.randomBytes(count: 32)
        self.groupKey = fresh
        secureStore.set(fresh, account: "group-key")
        self.peers.removeAll()
        self.tombstones.removeAll()
        self.hostTarget = nil
        clearHostTargetStorage()
        clearTombstonesStorage()
        persistPeers()
        return fresh
    }

    func adoptGroupKey(_ key: Data) {
        self.groupKey = key
        secureStore.set(key, account: "group-key")
        self.tombstones.removeAll()
        clearTombstonesStorage()
    }

    func clearChainState() {
        self.peers.removeAll()
        self.tombstones.removeAll()
        self.hostTarget = nil
        clearHostTargetStorage()
        clearTombstonesStorage()
        persistPeers()
    }

    func setHostTarget(_ target: HostTarget) {
        self.hostTarget = target
        persistHostTarget()
    }

    func updateHostTargetEndpoints(_ endpoints: [DirectEndpoint]) {
        guard var target = hostTarget else { return }
        var current = target.endpoints
        for ep in endpoints where !current.contains(ep) {
            current.append(ep)
        }
        target.endpoints = current
        self.hostTarget = target
        persistHostTarget()
    }

    func clearHostTarget() {
        self.hostTarget = nil
        clearHostTargetStorage()
    }

    func isTombstoned(_ peerID: String) -> Bool {
        tombstones.contains(peerID)
    }

    func clearTombstones() {
        tombstones.removeAll()
        clearTombstonesStorage()
    }

    func addOrUpdatePeer(_ peer: Peer) -> Bool {
        guard peer.id != localID else { return false }
        if tombstones.contains(peer.id) {
            // Tombstoned peer cannot be added back via passive sync
            return false
        }
        peers[peer.id] = peer
        persistPeers()
        return true
    }

    func reAdmitPeer(_ peer: Peer) {
        guard peer.id != localID else { return }
        tombstones.remove(peer.id)
        persistTombstones()
        peers[peer.id] = peer
        persistPeers()
    }

    func setPeerConnected(_ peerID: String, connected: Bool, endpoint: DirectEndpoint? = nil) {
        guard var peer = peers[peerID] else { return }
        peer.connected = connected
        if let endpoint {
            peer.endpoint = endpoint
        }
        peers[peerID] = peer
        persistPeers()
    }

    func renamePeer(id: String, newName: String) -> Bool {
        let trimmed = newName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, var peer = peers[id] else { return false }
        peer.name = trimmed
        peers[id] = peer
        persistPeers()
        return true
    }

    func removePeer(id: String) {
        guard id != localID else {
            clearChainState()
            return
        }
        peers.removeValue(forKey: id)
        tombstones.insert(id)
        if hostTarget?.id == id {
            clearHostTarget()
        }
        persistPeers()
        persistTombstones()
    }

    func peerSnapshot() -> [Peer] {
        peers.values.sorted { $0.name < $1.name }
    }

    func rosterPayload(localAddresses: [String], port: UInt16) -> [[String: Any]] {
        var result: [[String: Any]] = [[
            "id": localID,
            "name": localName,
            "host": localAddresses.first ?? "",
            "port": Int(port),
            "platform": "macOS",
            "connected": true
        ]]
        result += peers.values.sorted { $0.name < $1.name }.map { peer in
            [
                "id": peer.id,
                "name": peer.name,
                "host": peer.endpoint.host,
                "port": Int(peer.endpoint.port),
                "platform": peer.platform,
                "connected": peer.connected
            ]
        }
        return result
    }

    func applyRemoteRoster(_ members: [[String: Any]], fallbackHost: String, fallbackPort: UInt16) -> [Peer] {
        var changed = false
        for member in members {
            guard let mid = member["id"] as? String, mid != localID else { continue }
            if tombstones.contains(mid) { continue }

            let name = member["name"] as? String ?? peers[mid]?.name ?? "Device"
            let hostStr = (member["host"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? peers[mid]?.endpoint.host ?? fallbackHost
            let portVal = (member["port"] as? Int).map { UInt16($0) } ?? peers[mid]?.endpoint.port ?? fallbackPort
            let platform = member["platform"] as? String ?? peers[mid]?.platform ?? "Android"
            let isConnected = (member["connected"] as? Bool) ?? (peers[mid]?.connected ?? false)

            let peer = Peer(
                id: mid,
                name: name,
                endpoint: DirectEndpoint(host: hostStr, port: portVal),
                connected: isConnected,
                platform: platform
            )
            peers[mid] = peer
            changed = true

            if hostTarget?.id == mid && !hostStr.isEmpty && hostStr != "unknown" {
                let ep = DirectEndpoint(host: hostStr, port: portVal)
                if !(hostTarget?.endpoints.contains(ep) ?? false) {
                    hostTarget?.endpoints.append(ep)
                    persistHostTarget()
                }
            }
        }
        if changed {
            persistPeers()
        }
        return peerSnapshot()
    }

    // MARK: - Persistence

    private func loadPeers() {
        guard let data = UserDefaults.standard.data(forKey: "peers"),
              let saved = try? JSONDecoder().decode([Peer].self, from: data) else { return }
        peers = Dictionary(uniqueKeysWithValues: saved.map { ($0.id, $0) })
    }

    private func persistPeers() {
        UserDefaults.standard.set(try? JSONEncoder().encode(Array(peers.values)), forKey: "peers")
    }

    private func loadHostTarget() {
        guard let data = UserDefaults.standard.data(forKey: "host-target"),
              let saved = try? JSONDecoder().decode(HostTarget.self, from: data) else { return }
        hostTarget = saved
    }

    private func persistHostTarget() {
        UserDefaults.standard.set(try? JSONEncoder().encode(hostTarget), forKey: "host-target")
    }

    private func clearHostTargetStorage() {
        UserDefaults.standard.removeObject(forKey: "host-target")
    }

    private func loadTombstones() {
        if let array = UserDefaults.standard.stringArray(forKey: "tombstoned-peers") {
            tombstones = Set(array)
        }
    }

    private func persistTombstones() {
        UserDefaults.standard.set(Array(tombstones), forKey: "tombstoned-peers")
    }

    private func clearTombstonesStorage() {
        UserDefaults.standard.removeObject(forKey: "tombstoned-peers")
    }
}
