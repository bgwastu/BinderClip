import AppKit
import Foundation
import UniformTypeIdentifiers

enum ClipboardContent {
    case text(String)
    case image(ImagePayload)
    case unsupported
}

enum ClipboardClassifier {
    /**
     * Finder puts a file URL, a filename, and a TIFF/ICNS preview on the
     * pasteboard. Only a supported image file URL may be read as an image;
     * every other file is deliberately ignored.
     */
    static func read(from pasteboard: NSPasteboard) -> ClipboardContent {
        if let url = pasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true])?
            .compactMap({ $0 as? URL }).first {
            return image(at: url).map(ClipboardContent.image) ?? .unsupported
        }
        if let image = ImageClipboard.read(from: pasteboard) { return .image(image) }
        guard let text = pasteboard.string(forType: .string), !text.isEmpty else { return .unsupported }
        return .text(text)
    }

    private static func image(at url: URL) -> ImagePayload? {
        guard url.isFileURL,
              let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .contentTypeKey]),
              values.isRegularFile == true,
              let size = values.fileSize, size > 0, size <= ImagePayload.maximumBytes,
              let type = values.contentType ?? UTType(filenameExtension: url.pathExtension),
              let mime = type.preferredMIMEType,
              ImagePayload.allowedMIMETypes.contains(mime),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? ImagePayload(mimeType: mime, data: data)
    }
}
