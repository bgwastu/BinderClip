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
    func writeImage(_ data: Data, contentType: String) -> Bool {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        let pasteboardType: NSPasteboard.PasteboardType = contentType.contains("jpeg")
            ? NSPasteboard.PasteboardType("public.jpeg")
            : .png
        return pasteboard.setData(data, forType: pasteboardType)
    }
}
