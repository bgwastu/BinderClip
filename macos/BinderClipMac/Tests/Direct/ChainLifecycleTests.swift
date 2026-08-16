import XCTest
@testable import BinderClip

final class ChainLifecycleTests: XCTestCase {
    func testAtomicFlagThreadSafety() {
        let flag = AtomicFlag()
        XCTAssertFalse(flag.get())
        
        let wonFirst = flag.compareAndSet(expected: false, newValue: true)
        XCTAssertTrue(wonFirst)
        XCTAssertTrue(flag.get())
        
        let wonSecond = flag.compareAndSet(expected: false, newValue: true)
        XCTAssertFalse(wonSecond)
    }

    func testHostTargetCandidateEndpointsSerialization() throws {
        let ep1 = DirectEndpoint(host: "192.168.1.10", port: 39421)
        let ep2 = DirectEndpoint(host: "100.64.0.1", port: 39421)
        let target = HostTarget(id: "test-host", name: "Host Mac", endpoints: [ep1, ep2])
        
        let encoded = try JSONEncoder().encode(target)
        let decoded = try JSONDecoder().decode(HostTarget.self, from: encoded)
        
        XCTAssertEqual(decoded.id, "test-host")
        XCTAssertEqual(decoded.name, "Host Mac")
        XCTAssertEqual(decoded.endpoints.count, 2)
        XCTAssertTrue(decoded.endpoints.contains(ep1))
        XCTAssertTrue(decoded.endpoints.contains(ep2))
    }

    func testPeerRosterMaintainsOfflinePeersWithoutEviction() throws {
        let ep = DirectEndpoint(host: "192.168.1.50", port: 39421)
        var peer = Peer(id: "device-123", name: "Pixel 9", endpoint: ep, connected: true, platform: "Android")
        XCTAssertTrue(peer.connected)
        
        peer.connected = false
        XCTAssertFalse(peer.connected)
        XCTAssertEqual(peer.id, "device-123")
        XCTAssertEqual(peer.name, "Pixel 9")
        
        let encoded = try JSONEncoder().encode([peer])
        let decoded = try JSONDecoder().decode([Peer].self, from: encoded)
        XCTAssertEqual(decoded.count, 1)
        XCTAssertFalse(decoded[0].connected)
        XCTAssertEqual(decoded[0].id, "device-123")
    }
}
