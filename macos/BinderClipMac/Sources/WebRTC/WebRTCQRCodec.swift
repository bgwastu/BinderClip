import CryptoKit
import Foundation

/// Compact binary QR payload for serverless WebRTC signaling (QWBP-style).
///
/// Payload layout (all network byte order):
///   magic  (1 byte, 0x51)
///   flags  (1 byte: 0b000TTTPA, A=af(2) P=protocol T=type(2) ... see below)
///   fingerprint (32 bytes: SHA-256 of the DTLS certificate)
///   ufrag  (4 bytes raw, base64url-encoded when read)
///   pwd    (18 bytes raw, base64url-encoded when read)
///   then 0..4 ICE candidates:
///     IPv4 candidate: 1 flag byte + 4-byte IPv4 + 2-byte port
///     IPv6 candidate: 1 flag byte + 16-byte IPv6 + 2-byte port
///     mDNS candidate: 1 flag byte + 16-byte UUID + 2-byte port
///
/// Candidate flag byte: bits 0-1 address family (00 IPv4, 01 IPv6, 10 mDNS),
/// bit 2 protocol (0 UDP, 1 TCP), bit 3 type (0 host, 1 srflx),
/// bits 4-5 TCP type (00 passive, 01 active, 10 so) when protocol == TCP.
struct WebRTCQRCodec {
    static let magic: UInt8 = 0x51
    static let version: UInt8 = 0x00
    static let fingerprintLength = 32
    static let ufragLength = 4
    static let pwdLength = 18
    static let maxCandidates = 4

    enum QRCodecError: Error, Equatable {
        case tooShort
        case badMagic
        case unknownAddressFamily
        case unknownCandidateType
        case tooManyCandidates
        case malformedCandidate
    }

    struct Candidate: Equatable {
        enum AddressFamily: UInt8 { case ipv4 = 0, ipv6 = 1, mdns = 2 }
        enum ProtocolKind: UInt8 { case udp = 0, tcp = 1 }
        enum CandidateType: UInt8 { case host = 0, srflx = 1 }
        enum TCPType: UInt8 { case passive = 0, active = 1, simultaneous = 2 }

        var addressFamily: AddressFamily
        var kind: ProtocolKind
        var type: CandidateType
        var tcpType: TCPType
        /// Raw 4-byte IPv4, 16-byte IPv6, or 16-byte mDNS UUID bytes.
        var address: Data
        var port: UInt16
    }

    struct Packet: Equatable {
        var fingerprint: Data
        var ufrag: String
        var pwd: String
        var candidates: [Candidate]
    }

    // MARK: - Encode

    static func encode(_ packet: Packet) throws -> Data {
        guard packet.fingerprint.count == fingerprintLength else { throw QRCodecError.malformedCandidate }
        guard packet.candidates.count <= maxCandidates else { throw QRCodecError.tooManyCandidates }

        var out = Data()
        out.append(magic)
        out.append(version)

        out.append(packet.fingerprint)
        // Variable-length ufrag/pwd with an explicit length byte each so the
        // peer's real ICE credentials are carried exactly (they are base64url
        // of the raw credential bytes; length fits in a byte for our sizes).
        let ufragBytes = urlSafeBase64Decode(packet.ufrag)
        let pwdBytes = urlSafeBase64Decode(packet.pwd)
        guard ufragBytes.count <= 255, pwdBytes.count <= 255 else { throw QRCodecError.malformedCandidate }
        out.append(UInt8(ufragBytes.count))
        out.append(ufragBytes)
        out.append(UInt8(pwdBytes.count))
        out.append(pwdBytes)

        for candidate in packet.candidates {
            try appendCandidate(candidate, to: &out)
        }
        return out
    }

    private static func appendCandidate(_ candidate: Candidate, to out: inout Data) throws {
        let flag: UInt8 =
            candidate.addressFamily.rawValue |
            (candidate.kind.rawValue << 2) |
            (candidate.type.rawValue << 3) |
            (candidate.tcpType.rawValue << 4)
        out.append(flag)
        switch candidate.addressFamily {
        case .ipv4:
            guard candidate.address.count == 4 else { throw QRCodecError.malformedCandidate }
        case .ipv6, .mdns:
            guard candidate.address.count == 16 else { throw QRCodecError.malformedCandidate }
        }
        out.append(candidate.address)
        out.append(UInt8(candidate.port >> 8))
        out.append(UInt8(candidate.port & 0xFF))
    }

    // MARK: - Decode

    static func decode(_ data: Data) throws -> Packet {
        guard data.count >= 2 + fingerprintLength + 1 + 1 else { throw QRCodecError.tooShort }
        guard data[0] == magic else { throw QRCodecError.badMagic }

        let fingerprint = data.subdata(in: 2..<(2 + fingerprintLength))
        var offset = 2 + fingerprintLength
        guard offset + 1 <= data.count else { throw QRCodecError.tooShort }
        let ufragLength = Int(data[offset]); offset += 1
        guard offset + ufragLength + 1 <= data.count else { throw QRCodecError.tooShort }
        let ufrag = urlSafeBase64Encode(data.subdata(in: offset..<(offset + ufragLength))); offset += ufragLength
        guard offset + 1 <= data.count else { throw QRCodecError.tooShort }
        let pwdLength = Int(data[offset]); offset += 1
        guard offset + pwdLength <= data.count else { throw QRCodecError.tooShort }
        let pwd = urlSafeBase64Encode(data.subdata(in: offset..<(offset + pwdLength))); offset += pwdLength

        var candidates: [Candidate] = []
        while offset < data.count {
            guard candidates.count < maxCandidates else { throw QRCodecError.tooManyCandidates }
            let candidate = try decodeCandidate(data, at: &offset)
            candidates.append(candidate)
        }
        return Packet(fingerprint: fingerprint, ufrag: ufrag, pwd: pwd, candidates: candidates)
    }

    private static func decodeCandidate(_ data: Data, at offset: inout Int) throws -> Candidate {
        guard offset + 1 < data.count else { throw QRCodecError.malformedCandidate }
        let flag = data[offset]
        offset += 1

        guard let family = Candidate.AddressFamily(rawValue: flag & 0b11) else { throw QRCodecError.unknownAddressFamily }
        let kind = Candidate.ProtocolKind(rawValue: (flag >> 2) & 0b1) ?? .udp
        let type = Candidate.CandidateType(rawValue: (flag >> 3) & 0b1) ?? .host
        let tcpType = Candidate.TCPType(rawValue: (flag >> 4) & 0b11) ?? .passive

        let addressLength: Int
        switch family {
        case .ipv4: addressLength = 4
        case .ipv6, .mdns: addressLength = 16
        }
        guard offset + addressLength + 2 <= data.count else { throw QRCodecError.malformedCandidate }
        let address = data.subdata(in: offset..<(offset + addressLength))
        offset += addressLength
        let port = UInt16(data[offset]) << 8 | UInt16(data[offset + 1])
        offset += 2

        return Candidate(addressFamily: family, kind: kind, type: type, tcpType: tcpType, address: address, port: port)
    }

    // MARK: - Candidate <-> SDP

    /// Build the `a=candidate:` line that WebRTC's SDP parser accepts, using
    /// deterministic foundations (RFC 8445 style).
    static func candidateSDPLine(_ candidate: Candidate, index: Int, priority: UInt32) -> String {
        let foundation = String(format: "%08x", (candidate.address.prefix(4) as NSData).hashValue & 0xFFFF_FFFF)
        let protocolName = candidate.kind == .tcp ? "tcp" : "udp"
        let ip: String
        switch candidate.addressFamily {
        case .ipv4:
            ip = candidate.address.map { String($0) }.joined(separator: ".")
        case .ipv6:
            ip = candidate.address.enumerated()
                .map { index, byte in String(format: "%02x", byte) }
                .joined(separator: ":")
        case .mdns:
            let hex = candidate.address.map { String(format: "%02x", $0) }.joined()
            ip = "\(hex.prefix(8))-\(hex.dropFirst(8).prefix(4))-\(hex.dropFirst(12).prefix(4))-\(hex.dropFirst(16).prefix(4))-\(hex.dropFirst(20)).local"
        }
        let typeString = candidate.type == .host ? "host" : "srflx"
        var line = "a=candidate:\(foundation) \(index) \(protocolName) \(priority) \(ip) \(candidate.port) typ \(typeString)"
        if candidate.kind == .tcp { line += " tcptype \(tcpTypeSDP(candidate.tcpType))" }
        return line
    }

    private static func tcpTypeSDP(_ tcpType: Candidate.TCPType) -> String {
        switch tcpType {
        case .passive: return "passive"
        case .active: return "active"
        case .simultaneous: return "so"
        }
    }

    // MARK: - ICE credential derivation

    /// Derive fixed-size ICE credentials (ufrag 4 bytes, pwd 18 bytes) from the
    /// DTLS fingerprint, as in the QWBP spec, so both peers agree on credentials
    /// without transmitting them and the payload length is always predictable.
    static func deriveCredentials(fingerprint: Data) -> (ufrag: String, pwd: String) {
        let prk = HMAC<SHA256>.authenticationCode(for: fingerprint, using: SymmetricKey(data: Data()))
        let ufragBytes = Data(HMAC<SHA256>.authenticationCode(
            for: Data("QWBP-ICE-UFRAG-v1".utf8),
            using: SymmetricKey(data: Data(prk))
        )).prefix(ufragLength)
        let pwdBytes = Data(HMAC<SHA256>.authenticationCode(
            for: Data("QWBP-ICE-PWD-v1".utf8),
            using: SymmetricKey(data: Data(prk))
        )).prefix(pwdLength)
        return (urlSafeBase64Encode(Data(ufragBytes)), urlSafeBase64Encode(Data(pwdBytes)))
    }

    // MARK: - Base64url

    static func urlSafeBase64Encode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func urlSafeBase64Decode(_ string: String) -> Data {
        let padded = string
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = padded.count % 4
        let withPadding = remainder == 0 ? padded : padded + String(repeating: "=", count: 4 - remainder)
        return Data(base64Encoded: withPadding) ?? Data()
    }
}
