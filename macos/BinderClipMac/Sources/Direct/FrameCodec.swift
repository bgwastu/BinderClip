import Foundation

enum FrameCodecError: Error, Equatable {
    case payloadTooLarge
    case malformedFrame
}

/// Length-prefixed framing for the direct transport. The payload is protocol data;
/// framing deliberately has no transport or UI dependencies.
enum FrameCodec {
    static let maximumPayloadBytes = DirectTransport.maximumTextBytes + 65_536

    static func encode(_ payload: Data) throws -> Data {
        guard payload.count <= maximumPayloadBytes else { throw FrameCodecError.payloadTooLarge }
        var length = UInt32(payload.count).bigEndian
        var frame = Data(bytes: &length, count: MemoryLayout<UInt32>.size)
        frame.append(payload)
        return frame
    }

    static func decode(from buffer: inout Data) throws -> Data? {
        guard buffer.count >= 4 else { return nil }
        let length = buffer.prefix(4).withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
        guard length <= maximumPayloadBytes else { throw FrameCodecError.payloadTooLarge }
        let frameLength = 4 + Int(length)
        guard buffer.count >= frameLength else { return nil }
        let payload = buffer.subdata(in: 4..<frameLength)
        buffer.removeSubrange(0..<frameLength)
        return payload
    }
}
