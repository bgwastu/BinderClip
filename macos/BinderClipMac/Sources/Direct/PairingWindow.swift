import AppKit
import CoreImage

final class PairingWindow: NSObject, NSWindowDelegate {
    private var window: NSWindow?
    private var imageView: NSImageView?
    private var countdownLabel: NSTextField?
    private var statusLabel: NSTextField?
    private var invitationProvider: (() -> URL?)?
    private var expiresAt = Date()
    private var timer: Timer?

    func show(statusText: String = "Scan with BinderClip", invitationProvider: @escaping () -> URL?) {
        self.invitationProvider = invitationProvider
        if window == nil { buildWindow() }
        statusLabel?.stringValue = statusText
        refreshInvite()
        window?.center()
        window?.makeKeyAndOrderFront(nil)
        window?.orderFrontRegardless()
        NSApp.activate(ignoringOtherApps: true)
    }

    func closeWithSuccess() {
        statusLabel?.stringValue = "✓ Device connected!"
        statusLabel?.textColor = .systemGreen
        stopTimer()
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in
            self?.window?.close()
            self?.statusLabel?.textColor = .secondaryLabelColor
        }
    }

    var isVisible: Bool { window?.isVisible ?? false }

    func windowWillClose(_ notification: Notification) { stopTimer() }

    private func buildWindow() {
        let title = NSTextField(labelWithString: "Add Device")
        title.font = .systemFont(ofSize: 20, weight: .semibold)
        title.alignment = .center
        let detail = NSTextField(labelWithString: "Scan with BinderClip")
        detail.textColor = .secondaryLabelColor
        detail.alignment = .center
        statusLabel = detail
        let countdown = NSTextField(labelWithString: "5:00")
        countdown.font = .monospacedDigitSystemFont(ofSize: 13, weight: .medium)
        countdown.textColor = .secondaryLabelColor
        countdown.alignment = .center
        countdownLabel = countdown

        let image = NSImageView()
        image.imageScaling = .scaleNone
        image.wantsLayer = true
        image.layer?.backgroundColor = NSColor.white.cgColor
        image.translatesAutoresizingMaskIntoConstraints = false
        imageView = image
        let qrSurface = NSView()
        qrSurface.wantsLayer = true
        qrSurface.layer?.cornerRadius = 12
        qrSurface.layer?.masksToBounds = true
        qrSurface.layer?.backgroundColor = NSColor.white.cgColor
        qrSurface.translatesAutoresizingMaskIntoConstraints = false
        qrSurface.addSubview(image)
        NSLayoutConstraint.activate([
            image.leadingAnchor.constraint(equalTo: qrSurface.leadingAnchor, constant: 14),
            image.trailingAnchor.constraint(equalTo: qrSurface.trailingAnchor, constant: -14),
            image.topAnchor.constraint(equalTo: qrSurface.topAnchor, constant: 14),
            image.bottomAnchor.constraint(equalTo: qrSurface.bottomAnchor, constant: -14),
            qrSurface.widthAnchor.constraint(equalToConstant: 300),
            qrSurface.heightAnchor.constraint(equalToConstant: 300),
        ])

        let stack = NSStackView(views: [title, detail, qrSurface, countdown])
        stack.orientation = .vertical
        stack.alignment = .centerX
        stack.spacing = 10
        stack.edgeInsets = NSEdgeInsets(top: 24, left: 24, bottom: 22, right: 24)
        let panel = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 348, height: 440), styleMask: [.titled, .closable], backing: .buffered, defer: false)
        panel.title = "BinderClip"
        panel.level = .floating
        panel.contentView = stack
        panel.delegate = self
        window = panel
    }

    private func refreshInvite() {
        guard let url = invitationProvider?(), let image = qr(url.absoluteString) else { return }
        imageView?.image = image
        expiresAt = Date().addingTimeInterval(300)
        updateCountdown()
        stopTimer()
        let timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.updateCountdown() }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    private func updateCountdown() {
        let seconds = max(0, Int(expiresAt.timeIntervalSinceNow.rounded(.up)))
        if seconds == 0 { refreshInvite(); return }
        countdownLabel?.stringValue = String(format: "%d:%02d", seconds / 60, seconds % 60)
    }

    private func stopTimer() { timer?.invalidate(); timer = nil }

    private func qr(_ content: String) -> NSImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(content.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        // Keep QR modules at an integer scale. The panel has enough white quiet
        // zone around this size, while a larger image would be clipped or blurred.
        guard let output = filter.outputImage?.transformed(by: .init(scaleX: 5, y: 5)) else { return nil }
        let representation = NSCIImageRep(ciImage: output)
        let image = NSImage(size: representation.size)
        image.addRepresentation(representation)
        return image
    }
}

