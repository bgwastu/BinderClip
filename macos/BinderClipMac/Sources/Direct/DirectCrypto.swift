import CryptoKit
import Foundation

enum DirectCryptoError: Error { case malformed, authentication }

enum DirectCrypto {
    static func randomBytes(count: Int) -> Data { Data((0..<count).map { _ in UInt8.random(in: .min ... .max) }) }

    static func hmac(key: Data, value: String) -> String {
        Data(HMAC<SHA256>.authenticationCode(for: Data(value.utf8), using: SymmetricKey(data: key))).base64EncodedString()
    }

    static func constantTimeEqual(_ lhs: String, _ rhs: String) -> Bool {
        guard let a = Data(base64Encoded: lhs), let b = Data(base64Encoded: rhs) else { return false }
        let left = [UInt8](a), right = [UInt8](b)
        guard left.count == right.count else { return false }
        var different: UInt8 = 0
        for index in left.indices { different |= left[index] ^ right[index] }
        return different == 0
    }

    static func pairSessionKey(inviteKey: Data, clientNonce: String, serverNonce: String) -> Data {
        let salt = Data("\(clientNonce)|\(serverNonce)".utf8)
        return HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: inviteKey), salt: salt,
            info: Data("binderclip-pairing".utf8), outputByteCount: 32
        ).withUnsafeBytes { Data($0) }
    }

    static func seal(_ object: [String: Any], key: Data) throws -> [String: Any] {
        let plaintext = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        let sealed = try AES.GCM.seal(plaintext, using: SymmetricKey(data: key))
        guard let combined = sealed.combined else { throw DirectCryptoError.malformed }
        return ["type": "encrypted", "payload": combined.base64EncodedString()]
    }

    static func open(_ object: [String: Any], key: Data) throws -> [String: Any] {
        guard object["type"] as? String == "encrypted",
              let encoded = object["payload"] as? String,
              let combined = Data(base64Encoded: encoded) else { throw DirectCryptoError.malformed }
        let box = try AES.GCM.SealedBox(combined: combined)
        let plaintext = try AES.GCM.open(box, using: SymmetricKey(data: key))
        guard let decoded = try JSONSerialization.jsonObject(with: plaintext) as? [String: Any] else {
            throw DirectCryptoError.malformed
        }
        return decoded
    }
}

/// Small owner-only file store for direct pairing secrets. It deliberately
/// avoids Keychain ACL prompts for unsigned local builds and debug installs.
final class PrivateStateStore {
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
        guard let data = try? Data(contentsOf: file), let values = try? JSONDecoder().decode([String: String].self, from: data) else { return [:] }
        return values
    }
}
