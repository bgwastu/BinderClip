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
        let pasteboardType = NSPasteboard.PasteboardType(contentType)
        return pasteboard.setData(data, forType: pasteboardType)
    }

    func writeImage(_ data: Data, contentType: String) -> Bool {
        writeMedia(data, contentType: contentType.contains("jpeg") ? "public.jpeg" : "public.png")
    }
}
