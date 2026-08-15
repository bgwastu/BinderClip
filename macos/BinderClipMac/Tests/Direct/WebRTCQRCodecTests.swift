import XCTest
@testable import BinderClip

final class WebRTCQRCodecTests: XCTestCase {
    func testEncodeDecodeRoundTripIPv4() throws {
        let packet = WebRTCQRCodec.Packet(
            fingerprint: Data(repeating: 7, count: 32),
            ufrag: WebRTCQRCodec.urlSafeBase64Encode(Data([0x12, 0x34, 0x56, 0x78])),
            pwd: WebRTCQRCodec.urlSafeBase64Encode(Data(repeating: 0xAB, count: 18)),
            candidates: [
                WebRTCQRCodec.Candidate(
                    addressFamily: .ipv4, kind: .udp, type: .host, tcpType: .passive,
                    address: Data([192, 168, 1, 5]), port: 54321
                ),
            ]
        )
        let encoded = try WebRTCQRCodec.encode(packet)
        let decoded = try WebRTCQRCodec.decode(encoded)
        XCTAssertEqual(decoded, packet)
    }

    func testEncodeDecodeRoundTripMixedCandidates() throws {
        var ipv6Bytes = Data()
        for value in stride(from: 0x20, to: 0x30, by: 1) { ipv6Bytes.append(UInt8(value)) }
        while ipv6Bytes.count < 16 { ipv6Bytes.append(0) }
        let packet = WebRTCQRCodec.Packet(
            fingerprint: Data(repeating: 3, count: 32),
            ufrag: WebRTCQRCodec.urlSafeBase64Encode(Data([0x01, 0x02, 0x03, 0x04])),
            pwd: WebRTCQRCodec.urlSafeBase64Encode(Data(repeating: 0x42, count: 18)),
            candidates: [
                WebRTCQRCodec.Candidate(addressFamily: .ipv4, kind: .udp, type: .host, tcpType: .passive, address: Data([10, 0, 0, 1]), port: 1111),
                WebRTCQRCodec.Candidate(addressFamily: .ipv6, kind: .udp, type: .srflx, tcpType: .passive, address: ipv6Bytes, port: 2222),
            ]
        )
        let encoded = try WebRTCQRCodec.encode(packet)
        XCTAssertLessThan(encoded.count, 110, "Typical QR payload stays small")
        let decoded = try WebRTCQRCodec.decode(encoded)
        XCTAssertEqual(decoded, packet)
    }

    func testDecodeRejectsBadMagic() {
        var data = Data(repeating: 0, count: 2 + 32 + 4 + 18)
        data[0] = 0x99
        XCTAssertThrowsError(try WebRTCQRCodec.decode(data)) { error in
            XCTAssertEqual(error as? WebRTCQRCodec.QRCodecError, .badMagic)
        }
    }

    func testDecodeRejectsShortPayload() {
        XCTAssertThrowsError(try WebRTCQRCodec.decode(Data([0x51]))) { error in
            XCTAssertEqual(error as? WebRTCQRCodec.QRCodecError, .tooShort)
        }
    }

    func testUrlSafeBase64RoundTrip() {
        let data = Data(repeating: 0xEF, count: 17)
        let encoded = WebRTCQRCodec.urlSafeBase64Encode(data)
        XCTAssertFalse(encoded.contains("+") || encoded.contains("/") || encoded.contains("="))
        XCTAssertEqual(WebRTCQRCodec.urlSafeBase64Decode(encoded), data)
    }

    func testCandidateSDPLine() {
        let candidate = WebRTCQRCodec.Candidate(
            addressFamily: .ipv4, kind: .udp, type: .host, tcpType: .passive,
            address: Data([192, 168, 1, 5]), port: 54321
        )
        let line = WebRTCQRCodec.candidateSDPLine(candidate, index: 1, priority: 2_118_130_432)
        XCTAssertTrue(line.contains("192.168.1.5 54321"))
        XCTAssertTrue(line.contains("typ host"))
        XCTAssertTrue(line.hasPrefix("a=candidate:"))
    }

    func testQRPayloadFitsTypicalQRCapacity() throws {
        // 4 candidates (3 host + 1 srflx) with IPv4 — should fit in a QR v4-5.
        let packet = WebRTCQRCodec.Packet(
            fingerprint: Data(repeating: 1, count: 32),
            ufrag: WebRTCQRCodec.urlSafeBase64Encode(Data([1, 2, 3, 4])),
            pwd: WebRTCQRCodec.urlSafeBase64Encode(Data(repeating: 9, count: 18)),
            candidates: [
                WebRTCQRCodec.Candidate(addressFamily: .ipv4, kind: .udp, type: .host, tcpType: .passive, address: Data([192, 168, 1, 1]), port: 1),
                WebRTCQRCodec.Candidate(addressFamily: .ipv4, kind: .udp, type: .host, tcpType: .passive, address: Data([10, 0, 0, 1]), port: 2),
                WebRTCQRCodec.Candidate(addressFamily: .ipv4, kind: .udp, type: .host, tcpType: .passive, address: Data([172, 16, 0, 1]), port: 3),
                WebRTCQRCodec.Candidate(addressFamily: .ipv4, kind: .udp, type: .srflx, tcpType: .passive, address: Data([203, 0, 113, 50]), port: 4),
            ]
        )
        let encoded = try WebRTCQRCodec.encode(packet)
        XCTAssertLessThanOrEqual(encoded.count, 100, "Typical payload must fit a small QR")
    }

    /// Cross-platform interop: decode a real payload produced by the Android
    /// WebRTCTransport (captured from the on-device diagnostics log). This proves
    /// the Swift and Kotlin codecs agree on the wire format.
    func testDecodeRealAndroidPayload() throws {
        // base64url of the payload logged by the Android device: variable-length
        // ufrag/pwd, 3 ICE candidates (mesh host, public srflx, LAN host).
        let b64 = "UQAZnnFxEHlfHVn0PDek6uQkvGgRsOWX1pxXQ9pHthcPKQRYNWgrGHNZOGtKS0lPeW5yL1BGdWp3RWc0VWhCWgBkYAAfuZUIaByiEG8hBMCoMuAACQ=="
        let data = Data(base64Encoded: b64)!
        let packet = try WebRTCQRCodec.decode(data)
        XCTAssertEqual(packet.fingerprint.count, 32)
        XCTAssertEqual(packet.candidates.count, 3, "The real Android payload carried 3 ICE candidates")
        XCTAssertEqual(packet.candidates[0].type, .host)
        XCTAssertEqual(packet.candidates[0].address.count, 4)
        XCTAssertEqual(packet.candidates[0].port, 47_509)
        print("Decoded Android payload: ufrag=\(packet.ufrag) pwd=\(packet.pwd) candidates=\(packet.candidates.count)")
    }
}
