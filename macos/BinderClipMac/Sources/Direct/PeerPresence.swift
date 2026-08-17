import Foundation

/// Live presence is derived from authenticated, usable sessions — never from disk.
enum PeerPresence {
    static func isConnected(peerID: String, authenticatedPeerIDs: [String]) -> Bool {
        authenticatedPeerIDs.contains(peerID)
    }

    static func shouldReplace(existingPeerID: String?, incomingPeerID: String) -> Bool {
        existingPeerID == incomingPeerID
    }

    /// Drop handshake leftovers and the previous session for this phone.
    /// Unauthenticated sockets have no peerID, so same-peer replace alone leaves LAN+mesh twins.
    static func shouldCancelExtra(isAuthenticated: Bool, existingPeerID: String?, incomingPeerID: String) -> Bool {
        if !isAuthenticated { return true }
        return shouldReplace(existingPeerID: existingPeerID, incomingPeerID: incomingPeerID)
    }

    /// Ignore auth that arrives after this socket was already cancelled or accepted.
    /// A leftover twin's queued auth must not cancel the winner.
    static func shouldProcessAuth(isStillActive: Bool, alreadyAuthenticated: Bool) -> Bool {
        isStillActive && !alreadyAuthenticated
    }

    /// An unpaired phone may return only by scanning the QR (`pairing` on auth).
    static func shouldAcceptReturningPeer(wasUnpaired: Bool, isPairingScan: Bool) -> Bool {
        !wasUnpaired || isPairingScan
    }
}
