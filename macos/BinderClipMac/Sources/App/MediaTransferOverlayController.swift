import AppKit

final class MediaTransferOverlayController {
    var onCancel: (() -> Void)?
    private var panel: NSPanel?
    private let label = NSTextField(labelWithString: "Media sync  0%")
    private let progress = NSProgressIndicator()

    func update(fileName: String? = nil, transferred: Int, total: Int) {
        if panel == nil {
            let cancel = NSButton(title: "Cancel", target: self, action: #selector(cancelTransfer))
            let content = NSStackView(views: [label, progress, cancel])
            content.orientation = .vertical
            content.spacing = 8
            content.edgeInsets = NSEdgeInsets(top: 16, left: 20, bottom: 16, right: 20)
            progress.isIndeterminate = false
            progress.minValue = 0
            progress.maxValue = 1
            let newPanel = NSPanel(
                contentRect: NSRect(x: 0, y: 0, width: 280, height: 76),
                styleMask: [.borderless, .nonactivatingPanel],
                backing: .buffered,
                defer: false
            )
            newPanel.isFloatingPanel = true
            newPanel.level = .floating
            newPanel.hidesOnDeactivate = false
            newPanel.backgroundColor = NSColor(calibratedWhite: 0.08, alpha: 0.94)
            newPanel.contentView = content
            panel = newPanel
        }
        let fraction = total > 0 ? min(1, max(0, Double(transferred) / Double(total))) : 0
        label.stringValue = "\(fileName ?? "Media sync")  \(Int(fraction * 100))%"
        progress.doubleValue = fraction
        if let panel, !panel.isVisible {
            panel.center()
            panel.orderFrontRegardless()
        }
    }

    func dismiss() { panel?.orderOut(nil) }

    @objc private func cancelTransfer() {
        onCancel?()
        dismiss()
    }
}
