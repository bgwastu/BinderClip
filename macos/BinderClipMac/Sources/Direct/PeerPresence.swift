import Foundation

/// Live presence is derived from authenticated, usable sessions — never from disk.
enum PeerPresence {
    static func isConnected(peerID: String, authenticatedPeerIDs: [String]) -> Bool {
        authenticatedPeerIDs.contains(peerID)
    }

    static func shouldReplace(existingPeerID: String?, incomingPeerID: String) -> Bool {
        existingPeerID == incomingPeerID
    }
}
