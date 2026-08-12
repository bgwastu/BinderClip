// Writes received text to the macOS system pasteboard.

import AppKit

final class ClipboardWriter {
    @discardableResult
    func writeText(_ text: String) -> Bool {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        return pasteboard.setString(text, forType: .string)
    }

    @discardableResult
    func writeMedia(_ data: Data, contentType: String) -> Bool {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        if contentType == MediaBundle.mimeType, let items = MediaBundle.decode(data) {
            let pasteboardItems = items.map { item in
                let pasteboardItem = NSPasteboardItem()
                pasteboardItem.setData(item.data, forType: NSPasteboard.PasteboardType(item.mimeType))
                return pasteboardItem
            }
            return pasteboard.writeObjects(pasteboardItems)
        }
        let pasteboardType = NSPasteboard.PasteboardType(contentType)
        return pasteboard.setData(data, forType: pasteboardType)
    }

    func writeImage(_ data: Data, contentType: String) -> Bool {
        writeMedia(data, contentType: contentType.contains("jpeg") ? "public.jpeg" : "public.png")
    }
}
