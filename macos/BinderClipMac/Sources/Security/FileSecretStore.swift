// Local pairing storage that avoids macOS Keychain ACL prompts for unsigned builds.

import Foundation

/// Abstraction over keychain storage so tests can use an in-memory store
/// instead of the real login keychain (which triggers ACL password prompts
/// for every freshly-signed test bundle).
protocol SecretStore: AnyObject {
    func data(for account: String) -> Data?
    @discardableResult
    func setData(_ data: Data, for account: String) -> Bool
}

/// In-memory SecretStore for unit tests.
final class InMemorySecretStore: SecretStore {
    private var storage: [String: Data] = [:]

    func data(for account: String) -> Data? {
        storage[account]
    }

    @discardableResult
    func setData(_ data: Data, for account: String) -> Bool {
        storage[account] = data
        return true
    }
}

final class FileSecretStore: SecretStore {
    private let fileURL: URL
    private let lock = NSLock()

    init() {
        let applicationSupport = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first ?? FileManager.default.temporaryDirectory
        let directory = applicationSupport.appendingPathComponent("BinderClip", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        fileURL = directory.appendingPathComponent("pairing-store.json")
    }

    func data(for account: String) -> Data? {
        lock.lock()
        defer { lock.unlock() }
        return load()[account].flatMap { Data(base64Encoded: $0) }
    }

    @discardableResult
    func setData(_ data: Data, for account: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        var values = load()
        values[account] = data.base64EncodedString()
        guard let encoded = try? JSONEncoder().encode(values) else { return false }
        do {
            try encoded.write(to: fileURL, options: .atomic)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: fileURL.path)
            return true
        } catch {
            return false
        }
    }

    private func load() -> [String: String] {
        guard let data = try? Data(contentsOf: fileURL) else { return [:] }
        return (try? JSONDecoder().decode([String: String].self, from: data)) ?? [:]
    }
}
