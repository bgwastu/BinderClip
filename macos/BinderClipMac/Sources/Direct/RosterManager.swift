import Foundation
import Security

public struct DirectEndpoint: Codable, Hashable, Sendable {
    public let host: String
    public let port: UInt16

    public init(host: String, port: UInt16) {
        self.host = host
        self.port = port
    }
}

public struct Peer: Codable, Hashable, Identifiable, Sendable {
    public let id: String
    public var name: String
    public var endpoint: DirectEndpoint
    public var connected: Bool
    public var platform: String

    public init(id: String, name: String, endpoint: DirectEndpoint, connected: Bool, platform: String = "Android") {
        self.id = id
        self.name = name
        self.endpoint = endpoint
        self.connected = connected
        self.platform = platform
    }

    enum CodingKeys: String, CodingKey { case id, name, endpoint, connected, platform }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        name = try values.decode(String.self, forKey: .name)
        endpoint = try values.decode(DirectEndpoint.self, forKey: .endpoint)
        connected = try values.decode(Bool.self, forKey: .connected)
        platform = try values.decodeIfPresent(String.self, forKey: .platform) ?? "Android"
    }
}

/// Small owner-only file store for pairing secrets. Avoids Keychain ACL prompts.
final class PrivateStateStore: @unchecked Sendable {
    private let file: URL

    init() {
        let manager = FileManager.default
        let root = manager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("net.wastu.binderclip", isDirectory: true)
        try? manager.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try? manager.setAttributes([.posixPermissions: 0o700], ofItemAtPath: root.path)
        file = root.appendingPathComponent("direct-secrets.json")
    }

    func data(account: String) -> Data? {
        guard let stored = contents()[account] else { return nil }
        return Data(base64Encoded: stored)
    }

    func set(_ data: Data, account: String) {
        var values = contents()
        values[account] = data.base64EncodedString()
        guard let encoded = try? JSONEncoder().encode(values) else { return }
        try? encoded.write(to: file, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
    }

    private func contents() -> [String: String] {
        guard let data = try? Data(contentsOf: file),
              let values = try? JSONDecoder().decode([String: String].self, from: data) else {
            return [:]
        }
        return values
    }
}

/// Manages identity, peer roster, and the shared pairing key.
final class RosterManager: @unchecked Sendable {
    private let secureStore = PrivateStateStore()
    private(set) var localID: String
    private(set) var localName: String
    private(set) var groupKey: Data
    private(set) var peers: [String: Peer] = [:]

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
            let generated = Self.generateRandomBytes(count: 32)
            secureStore.set(generated, account: "group-key")
            self.groupKey = generated
        }

        loadPeers()
        markAllDisconnected()
        UserDefaults.standard.removeObject(forKey: "host-target")
        UserDefaults.standard.removeObject(forKey: "tombstoned-peers")
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
        let fresh = Self.generateRandomBytes(count: 32)
        self.groupKey = fresh
        secureStore.set(fresh, account: "group-key")
        self.peers.removeAll()
        persistPeers()
        return fresh
    }

    func clearPairingState() {
        self.peers.removeAll()
        persistPeers()
    }

    func addOrUpdatePeer(_ peer: Peer) -> Bool {
        guard peer.id != localID else { return false }
        peers[peer.id] = peer
        persistPeers()
        return true
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

    func markAllDisconnected() {
        for key in peers.keys {
            peers[key]?.connected = false
        }
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
        peers.removeValue(forKey: id)
        persistPeers()
    }

    func peerSnapshot() -> [Peer] {
        peers.values.sorted { $0.name < $1.name }
    }

    private static func generateRandomBytes(count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        _ = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        return Data(bytes)
    }

    private func loadPeers() {
        guard let data = UserDefaults.standard.data(forKey: "peers"),
              let saved = try? JSONDecoder().decode([Peer].self, from: data) else { return }
        peers = Dictionary(uniqueKeysWithValues: saved.map { ($0.id, $0) })
    }

    private func persistPeers() {
        let stored = Array(peers.values).map { peer in
            Peer(id: peer.id, name: peer.name, endpoint: peer.endpoint, connected: false, platform: peer.platform)
        }
        UserDefaults.standard.set(try? JSONEncoder().encode(stored), forKey: "peers")
    }
}
