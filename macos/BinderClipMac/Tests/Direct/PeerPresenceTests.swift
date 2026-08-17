import XCTest
@testable import BinderClip

final class PeerPresenceTests: XCTestCase {
    func testPresenceIgnoresClosedDuplicateSession() {
        let peerID = "phone-1"
        XCTAssertTrue(PeerPresence.isConnected(peerID: peerID, authenticatedPeerIDs: ["phone-1"]))
        XCTAssertTrue(PeerPresence.isConnected(peerID: peerID, authenticatedPeerIDs: ["phone-1", "phone-1"]))
        XCTAssertFalse(PeerPresence.isConnected(peerID: peerID, authenticatedPeerIDs: []))
        XCTAssertFalse(PeerPresence.isConnected(peerID: peerID, authenticatedPeerIDs: ["phone-2"]))
    }

    func testShouldReplaceSamePeerOnly() {
        XCTAssertTrue(PeerPresence.shouldReplace(existingPeerID: "phone-1", incomingPeerID: "phone-1"))
        XCTAssertFalse(PeerPresence.shouldReplace(existingPeerID: "phone-2", incomingPeerID: "phone-1"))
        XCTAssertFalse(PeerPresence.shouldReplace(existingPeerID: nil, incomingPeerID: "phone-1"))
    }
}
