import AppKit
import CryptoKit

final class ClipboardBridge {
    var onLocalText: ((String) -> Void)?
    var onLocalImage: ((ImagePayload) -> Void)?
    private let pasteboard = NSPasteboard.general
    private var changeCount: Int
    private var lastInboundHash: String?
    private var lastInboundImageHash: String?
    private var timer: Timer?

    init() { changeCount = pasteboard.changeCount }

    var isAccessDenied: Bool {
        guard #available(macOS 15.4, *) else { return false }
        return pasteboard.accessBehavior == .alwaysDeny
    }

    func start() {
        timer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: true) { [weak self] _ in self?.poll() }
        RunLoop.main.add(timer!, forMode: .common)
    }
    func stop() { timer?.invalidate(); timer = nil }

    func applyRemote(_ text: String) {
        lastInboundHash = hash(text)
        pasteboard.clearContents(); pasteboard.setString(text, forType: .string)
        changeCount = pasteboard.changeCount
    }
    func applyRemote(_ image: ImagePayload) {
        lastInboundImageHash = image.sha256
        ImageClipboard.write(image, to: pasteboard)
        changeCount = pasteboard.changeCount
    }

    func sendCurrentClipboard() {
        switch ClipboardClassifier.read(from: pasteboard) {
        case .text(let text):
            guard text.utf8.count <= DirectTransport.maximumTextBytes else { DiagnosticLog.shared.error("Clipboard text is too large"); return }
            onLocalText?(text)
        case .image(let image): onLocalImage?(image)
        case .unsupported: DiagnosticLog.shared.warning("Clipboard content is unsupported or unavailable")
        }
    }

    private func poll() {
        guard pasteboard.changeCount != changeCount else { return }
        changeCount = pasteboard.changeCount
        switch ClipboardClassifier.read(from: pasteboard) {
        case .image(let image):
            if image.sha256 == lastInboundImageHash { lastInboundImageHash = nil; return }
            onLocalImage?(image)
        case .text(let text):
            guard text.utf8.count <= DirectTransport.maximumTextBytes else { return }
            let digest = hash(text)
            if digest == lastInboundHash { lastInboundHash = nil; return }
            onLocalText?(text)
        case .unsupported: break
        }
    }
    private func hash(_ text: String) -> String { SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined() }
}
