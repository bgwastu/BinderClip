import Foundation

/// Lossless container for multi-resource media such as Live Photos.
enum MediaBundle {
    static let mimeType = "application/x-binderclip-media-bundle"
    private static let magic = Data("BCMEDIA1".utf8)

    struct Item {
        let mimeType: String
        let data: Data
    }

    static func encode(_ items: [Item]) -> Data {
        precondition(!items.isEmpty)
        precondition(items.reduce(0) { $0 + $1.data.count } <= 20_971_520)
        var output = magic
        output.appendUInt32(UInt32(items.count))
        for item in items {
            let mime = Data(item.mimeType.utf8)
            output.appendUInt32(UInt32(mime.count))
            output.appendUInt64(UInt64(item.data.count))
            output.append(mime)
            output.append(item.data)
        }
        return output
    }

    static func decode(_ data: Data) -> [Item]? {
        var cursor = DataCursor(data)
        guard cursor.read(magic.count) == magic,
              let count = cursor.readUInt32(), count > 0, count <= 32 else { return nil }

        var items: [Item] = []
        var totalBytes = 0
        for _ in 0..<count {
            guard let mimeLength = cursor.readUInt32(), mimeLength > 0, mimeLength <= 1024,
                  let dataLength = cursor.readUInt64(), dataLength > 0, dataLength <= 20_971_520,
                  let mimeData = cursor.read(Int(mimeLength)),
                  let itemData = cursor.read(Int(dataLength)),
                  let mimeType = String(data: mimeData, encoding: .utf8) else { return nil }
            totalBytes += itemData.count
            guard totalBytes <= 20_971_520 else { return nil }
            items.append(Item(mimeType: mimeType, data: itemData))
        }
        return items
    }
}

private struct DataCursor {
    let data: Data
    var offset = 0

    init(_ data: Data) { self.data = data }

    mutating func read(_ count: Int) -> Data? {
        guard count >= 0, offset <= data.count, count <= data.count - offset else { return nil }
        defer { offset += count }
        return data.subdata(in: offset..<(offset + count))
    }

    mutating func readUInt32() -> UInt32? {
        guard let bytes = read(4) else { return nil }
        return bytes.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
    }

    mutating func readUInt64() -> UInt64? {
        guard let bytes = read(8) else { return nil }
        return bytes.reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
    }
}

private extension Data {
    mutating func appendUInt32(_ value: UInt32) {
        append(contentsOf: [
            UInt8((value >> 24) & 0xff), UInt8((value >> 16) & 0xff),
            UInt8((value >> 8) & 0xff), UInt8(value & 0xff)
        ])
    }

    mutating func appendUInt64(_ value: UInt64) {
        append(contentsOf: (0..<8).map { UInt8((value >> UInt64(56 - $0 * 8)) & 0xff) })
    }
}
