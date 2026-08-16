import XCTest
@testable import BinderClip

final class RosterManagerTests: XCTestCase {
    func testRosterTombstonesPreventZombiePeerResurrection() {
        let manager = RosterManager()
        manager.clearChainState()
        let peer1 = Peer(id: "device-aaa", name: "Pixel 8", endpoint: DirectEndpoint(host: "192.168.1.100", port: 39421), connected: true, platform: "Android")
        let peer2 = Peer(id: "device-bbb", name: "Galaxy S24", endpoint: DirectEndpoint(host: "192.168.1.101", port: 39421), connected: true, platform: "Android")

        XCTAssertTrue(manager.addOrUpdatePeer(peer1))
        XCTAssertTrue(manager.addOrUpdatePeer(peer2))
        XCTAssertEqual(manager.peers.count, 2)

        // Remove peer1 -> should be tombstoned
        manager.removePeer(id: "device-aaa")
        XCTAssertNil(manager.peers["device-aaa"])
        XCTAssertTrue(manager.isTombstoned("device-aaa"))

        // Remote peer sends stale roster containing device-aaa
        let staleRoster: [[String: Any]] = [
            ["id": "device-aaa", "name": "Pixel 8", "host": "192.168.1.100", "port": 39421, "platform": "Android", "connected": true],
            ["id": "device-bbb", "name": "Galaxy S24", "host": "192.168.1.101", "port": 39421, "platform": "Android", "connected": true]
        ]
        _ = manager.applyRemoteRoster(staleRoster, fallbackHost: "192.168.1.101", fallbackPort: 39421)

        // Tombstoned peer must NOT be resurrected
        XCTAssertNil(manager.peers["device-aaa"])
        XCTAssertNotNil(manager.peers["device-bbb"])
    }

    func testReAdmitPeerClearsTombstone() {
        let manager = RosterManager()
        let peer = Peer(id: "device-reconnect", name: "MacBook Air", endpoint: DirectEndpoint(host: "192.168.1.50", port: 39421), connected: true, platform: "macOS")

        manager.removePeer(id: "device-reconnect")
        XCTAssertTrue(manager.isTombstoned("device-reconnect"))

        // When device re-authenticates with current group key
        manager.reAdmitPeer(peer)
        XCTAssertFalse(manager.isTombstoned("device-reconnect"))
        XCTAssertNotNil(manager.peers["device-reconnect"])
    }

    func testRotateGroupKeyClearsChainState() {
        let manager = RosterManager()
        let peer = Peer(id: "device-xyz", name: "Tablet", endpoint: DirectEndpoint(host: "192.168.1.75", port: 39421), connected: true, platform: "Android")
        _ = manager.addOrUpdatePeer(peer)
        manager.setHostTarget(HostTarget(id: "host-1", name: "Host", endpoints: [DirectEndpoint(host: "10.0.0.1", port: 39421)]))

        let oldKey = manager.groupKey
        let newKey = manager.rotateGroupKey()

        XCTAssertNotEqual(oldKey, newKey)
        XCTAssertTrue(manager.peers.isEmpty)
        XCTAssertNil(manager.hostTarget)
    }

    func testFrameCodecMultipleFramesInSingleBuffer() throws {
        let payload1 = "First Frame Content".data(using: .utf8)!
        let payload2 = "Second Frame Content With More Bytes".data(using: .utf8)!

        let frame1 = try FrameCodec.encode(payload1)
        let frame2 = try FrameCodec.encode(payload2)

        var combinedBuffer = frame1 + frame2

        let decoded1 = try FrameCodec.decode(from: &combinedBuffer)
        XCTAssertEqual(decoded1, payload1)

        let decoded2 = try FrameCodec.decode(from: &combinedBuffer)
        XCTAssertEqual(decoded2, payload2)

        let decoded3 = try FrameCodec.decode(from: &combinedBuffer)
        XCTAssertNil(decoded3)
        XCTAssertTrue(combinedBuffer.isEmpty)
    }
}
