// Writes received text to the macOS system pasteboard.

import AppKit
import UniformTypeIdentifiers

final class ClipboardWriter {
    @discardableResult
    func writeText(_ text: String) -> Bool {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        return pasteboard.setString(text, forType: .string)
    }

    @discardableResult
    func writeMedia(_ data: Data, contentType: String, fileName: String? = nil) -> Bool {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        if contentType == MediaBundle.mimeType, let items = MediaBundle.decode(data) {
            let pasteboardItems = items.map { item in
                let pasteboardItem = NSPasteboardItem()
                pasteboardItem.setData(item.data, forType: pasteboardType(for: item.mimeType))
                return pasteboardItem
            }
            let success = pasteboard.writeObjects(pasteboardItems)
            if success, let fileName {
                pasteboard.setString(fileName, forType: NSPasteboard.PasteboardType("com.apple.pasteboard.promised-suggested-file-name"))
            }
            return success
        }
        let success = pasteboard.setData(data, forType: pasteboardType(for: contentType))
        if success, let fileName {
            pasteboard.setString(fileName, forType: NSPasteboard.PasteboardType("com.apple.pasteboard.promised-suggested-file-name"))
        }
        return success
    }

    private func pasteboardType(for mimeType: String) -> NSPasteboard.PasteboardType {
        if let type = UTType(mimeType: mimeType) {
            return NSPasteboard.PasteboardType(type.identifier)
        }
        return NSPasteboard.PasteboardType(mimeType)
    }

    func writeImage(_ data: Data, contentType: String) -> Bool {
        writeMedia(data, contentType: contentType.contains("jpeg") ? "public.jpeg" : "public.png")
    }
}
