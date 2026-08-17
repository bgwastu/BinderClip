import XCTest
@testable import BinderClip

final class RosterManagerTests: XCTestCase {
    private func isolatedManager() -> RosterManager {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("binderclip-roster-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let defaults = UserDefaults(suiteName: "binderclip-test-\(UUID().uuidString)")!
        return RosterManager(stateDirectory: directory, defaults: defaults)
    }

    func testRemovePeerDoesNotBlockReAdd() {
        let manager = isolatedManager()
        manager.clearPairingState()
        let peer1 = Peer(id: "device-aaa", name: "Pixel 8", endpoint: DirectEndpoint(host: "192.168.1.100", port: 39421), connected: true, platform: "Android")
        let peer2 = Peer(id: "device-bbb", name: "Galaxy S24", endpoint: DirectEndpoint(host: "192.168.1.101", port: 39421), connected: true, platform: "Android")

        XCTAssertTrue(manager.addOrUpdatePeer(peer1))
        XCTAssertTrue(manager.addOrUpdatePeer(peer2))
        XCTAssertEqual(manager.peers.count, 2)

        manager.removePeer(id: "device-aaa")
        XCTAssertNil(manager.peers["device-aaa"])
        XCTAssertTrue(manager.addOrUpdatePeer(peer1))
        XCTAssertNotNil(manager.peers["device-aaa"])
        XCTAssertNotNil(manager.peers["device-bbb"])
    }

    func testMarkAllDisconnectedClearsLivePresence() {
        let manager = isolatedManager()
        manager.clearPairingState()
        let peer = Peer(id: "device-aaa", name: "Pixel 8", endpoint: DirectEndpoint(host: "192.168.1.100", port: 39421), connected: true, platform: "Android")
        XCTAssertTrue(manager.addOrUpdatePeer(peer))
        XCTAssertTrue(manager.peers["device-aaa"]?.connected == true)
        manager.markAllDisconnected()
        XCTAssertFalse(manager.peers["device-aaa"]?.connected ?? true)
        XCTAssertNotNil(manager.peers["device-aaa"])
    }

    func testRotateGroupKeyClearsPeersAndChangesKey() {
        let manager = isolatedManager()
        let peer = Peer(id: "device-xyz", name: "Tablet", endpoint: DirectEndpoint(host: "192.168.1.75", port: 39421), connected: true, platform: "Android")
        _ = manager.addOrUpdatePeer(peer)

        let oldKey = manager.groupKey
        let newKey = manager.rotateGroupKey()

        XCTAssertNotEqual(oldKey, newKey)
        XCTAssertTrue(manager.peers.isEmpty)
    }

    func testRotateGroupKeyDoesNotTouchApplicationSupportSecrets() {
        let live = PrivateStateStore.applicationSupportDirectory().appendingPathComponent("direct-secrets.json")
        let before = try? Data(contentsOf: live)
        _ = isolatedManager().rotateGroupKey()
        let after = try? Data(contentsOf: live)
        XCTAssertEqual(before, after)
    }
}
