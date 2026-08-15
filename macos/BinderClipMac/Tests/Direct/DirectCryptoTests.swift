import XCTest
@testable import BinderClip

final class DirectCryptoTests: XCTestCase {
    func testPairingKeyAndSealedPayloadRoundTrip() throws {
        let invitationKey = Data(repeating: 7, count: 32)
        let key = DirectCrypto.pairSessionKey(inviteKey: invitationKey, clientNonce: "client", serverNonce: "server")
        XCTAssertEqual(key.count, 32)
        let sealed = try DirectCrypto.seal(["type": "clipboard", "text": "hello"], key: key)
        let opened = try DirectCrypto.open(sealed, key: key)
        XCTAssertEqual(opened["type"] as? String, "clipboard")
        XCTAssertEqual(opened["text"] as? String, "hello")
    }

    func testTamperedPayloadIsRejected() throws {
        let key = Data(repeating: 9, count: 32)
        var sealed = try DirectCrypto.seal(["type": "hello"], key: key)
        sealed["payload"] = Data(repeating: 0, count: 32).base64EncodedString()
        XCTAssertThrowsError(try DirectCrypto.open(sealed, key: key))
    }
}
