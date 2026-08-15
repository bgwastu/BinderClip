import AppKit
import CryptoKit
import Foundation
import UniformTypeIdentifiers

struct ImagePayload: Sendable {
    static let maximumBytes = 30 * 1_024 * 1_024
    // Allow room for JSON, AES-GCM, and base64 inside a framed encrypted message.
    static let chunkBytes = 192 * 1_024

    let id: UUID
    let mimeType: String
    let data: Data
    let sha256: String

    init(id: UUID = UUID(), mimeType: String, data: Data) throws {
        guard Self.allowedMIMETypes.contains(mimeType), data.count > 0, data.count <= Self.maximumBytes else {
            throw ImageTransferError.invalidImage
        }
        self.id = id
        self.mimeType = mimeType
        self.data = data
        sha256 = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    static let allowedMIMETypes: Set<String> = ["image/png", "image/jpeg", "image/webp", "image/heic"]
}

enum ImageTransferError: Error { case invalidImage, hashMismatch, protocolViolation }

enum ImageClipboard {
    static func read(from pasteboard: NSPasteboard) -> ImagePayload? {
        let candidates: [(NSPasteboard.PasteboardType, String)] = [
            (.png, "image/png"),
            (NSPasteboard.PasteboardType("public.jpeg"), "image/jpeg"),
            (NSPasteboard.PasteboardType("org.webmproject.webp"), "image/webp"),
            (NSPasteboard.PasteboardType("public.heic"), "image/heic"),
        ]
        for (type, mime) in candidates where pasteboard.types?.contains(type) == true {
            if let data = pasteboard.data(forType: type), let image = try? ImagePayload(mimeType: mime, data: data) { return image }
        }
        guard let tiff = pasteboard.data(forType: .tiff), let image = NSImage(data: tiff),
              let converted = image.pngData(), let payload = try? ImagePayload(mimeType: "image/png", data: converted) else { return nil }
        return payload
    }

    static func write(_ payload: ImagePayload, to pasteboard: NSPasteboard) {
        let type: NSPasteboard.PasteboardType = switch payload.mimeType {
        case "image/png": .png
        case "image/jpeg": NSPasteboard.PasteboardType("public.jpeg")
        case "image/webp": NSPasteboard.PasteboardType("org.webmproject.webp")
        case "image/heic": NSPasteboard.PasteboardType("public.heic")
        default: .png
        }
        pasteboard.clearContents(); pasteboard.setData(payload.data, forType: type)
    }
}

private extension NSImage {
    func pngData() -> Data? {
        guard let tiffRepresentation, let bitmap = NSBitmapImageRep(data: tiffRepresentation) else { return nil }
        return bitmap.representation(using: .png, properties: [:])
    }
}
