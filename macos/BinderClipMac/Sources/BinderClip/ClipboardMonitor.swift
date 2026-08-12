// Polls the macOS pasteboard for changes and fires a callback when new text or image is detected.

import AppKit
import CryptoKit

final class ClipboardMonitor {
    static let defaultPollInterval: TimeInterval = {
        guard
            let value = ProcessInfo.processInfo.environment["CLIPBOARD_POLL_INTERVAL_MS"],
            let milliseconds = Double(value),
            milliseconds >= 100
        else {
            return 0.5
        }
        return milliseconds / 1000
    }()

    /// De-facto standard marker password managers (Bitwarden, 1Password, …) put on the
    /// pasteboard for secret copies. See nspasteboard.org and issue #70.
    static let concealedType = NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")

    static let skipSecretsDefaultsKey = "skipPasswordsAndSecrets"

    /// When on, concealed (password-manager) copies are not synced. Defaults to on.
    static var skipSecretsEnabled: Bool {
        get { UserDefaults.standard.object(forKey: skipSecretsDefaultsKey) as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: skipSecretsDefaultsKey) }
    }

    /// True if the pasteboard carries the concealed marker. Pure for testability.
    static func isConcealed(_ types: [NSPasteboard.PasteboardType]?) -> Bool {
        types?.contains(concealedType) ?? false
    }

    private let pasteboard = NSPasteboard.general
    private let onChange: (String) -> Void
    private let pollInterval: TimeInterval
    private var timer: Timer?
    private var lastChangeCount: Int
    private var lastHash: String?

    /// Callback for image changes: (imageData, contentType, hash)
    var onImageChange: ((Data, String, String, String?) -> Void)?

    init(pollInterval: TimeInterval = ClipboardMonitor.defaultPollInterval, onChange: @escaping (String) -> Void) {
        self.pollInterval = pollInterval
        self.onChange = onChange
        self.lastChangeCount = pasteboard.changeCount
    }

    func start() {
        timer = Timer.scheduledTimer(withTimeInterval: pollInterval, repeats: true) { [weak self] _ in
            self?.poll()
        }
        RunLoop.main.add(timer!, forMode: .common)
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    private func poll() {
        guard pasteboard.changeCount != lastChangeCount else { return }
        lastChangeCount = pasteboard.changeCount

        // Don't sync password-manager / secret copies that mark themselves concealed (#70).
        // Check every item's types: pasteboard.types only reflects the first item.
        if Self.skipSecretsEnabled,
           Self.isConcealed((pasteboard.types ?? []) + (pasteboard.pasteboardItems ?? []).flatMap(\.types)) {
            // Clear the dedup hash so re-copying the previous content still syncs.
            lastHash = nil
            return
        }

        // Media takes priority over text.
        if let (mediaData, contentType) = pasteboardMedia(pasteboard) {
            let digest = SHA256.hash(data: mediaData)
            let hash = digest.map { String(format: "%02x", $0) }.joined()
            guard hash != lastHash else { return }
            lastHash = hash
            let fileName = pasteboard.string(forType: NSPasteboard.PasteboardType("public.file-url"))
                .flatMap { URL(string: $0)?.lastPathComponent }
            onImageChange?(mediaData, contentType, hash, fileName)
            return
        }

        guard let text = pasteboard.string(forType: .string), !text.isEmpty else { return }
        guard text.utf8.count <= 102_400 else { return }

        let digest = SHA256.hash(data: Data(text.utf8))
        let hash = digest.map { String(format: "%02x", $0) }.joined()
        guard hash != lastHash else { return }
        lastHash = hash
        onChange(text)
    }

    /// Returns raw pasteboard media bytes. Multi-resource media is bundled losslessly.
    private func pasteboardMedia(_ pasteboard: NSPasteboard) -> (Data, String)? {
        let maxSize = 20_971_520 // 20 MB
        let candidates: [(NSPasteboard.PasteboardType, String)] = [
            (.png, "image/png"), (NSPasteboard.PasteboardType("public.jpeg"), "image/jpeg"),
            (.tiff, "image/tiff"),
            (NSPasteboard.PasteboardType("public.heic"), "image/heic"),
            (NSPasteboard.PasteboardType("public.heif"), "image/heif"),
            (NSPasteboard.PasteboardType("public.movie"), "video/quicktime"),
            (NSPasteboard.PasteboardType("public.mpeg-4"), "video/mp4"),
            (NSPasteboard.PasteboardType("com.apple.quicktime-movie"), "video/quicktime"),
            (NSPasteboard.PasteboardType("public.image"), "image/*"),
            (NSPasteboard.PasteboardType("public.video"), "video/*"),
            (NSPasteboard.PasteboardType("public.audio"), "audio/mpeg"),
            (NSPasteboard.PasteboardType("com.adobe.pdf"), "application/pdf")
        ]
        var items: [MediaBundle.Item] = []
        for pasteboardItem in pasteboard.pasteboardItems ?? [] {
            let hasMotion = pasteboardItem.types.contains(NSPasteboard.PasteboardType("public.movie"))
                || pasteboardItem.types.contains(NSPasteboard.PasteboardType("com.apple.quicktime-movie"))
            let matches = hasMotion
                ? candidates.filter { pasteboardItem.types.contains($0.0) }
                : Array(candidates.first(where: { pasteboardItem.types.contains($0.0) }).map { [$0] } ?? [])
            for match in matches {
                if let data = pasteboardItem.data(forType: match.0), !data.isEmpty {
                    items.append(.init(mimeType: match.1, data: data))
                }
            }
        }
        if items.isEmpty, let match = candidates.first(where: { pasteboard.types?.contains($0.0) == true }),
           let data = pasteboard.data(forType: match.0), !data.isEmpty {
            items = [.init(mimeType: match.1, data: data)]
        }
        guard !items.isEmpty else { return nil }
        if items.count == 1, items[0].data.count <= maxSize {
            return (items[0].data, items[0].mimeType)
        }
        let bundled = MediaBundle.encode(items)
        return bundled.count <= maxSize ? (bundled, MediaBundle.mimeType) : nil
    }
}
