import AppKit

final class ToastHUD {
    static let shared = ToastHUD()
    private var window: NSPanel?
    private var dismissWorkItem: DispatchWorkItem?

    func show(message: String, icon: String = "checkmark.circle.fill") {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.dismissWorkItem?.cancel()

            if self.window == nil {
                let panel = NSPanel(
                    contentRect: NSRect(x: 0, y: 0, width: 280, height: 64),
                    styleMask: [.borderless, .nonactivatingPanel],
                    backing: .buffered,
                    defer: false
                )
                panel.level = .floating
                panel.isOpaque = false
                panel.backgroundColor = .clear
                panel.hasShadow = true
                panel.ignoresMouseEvents = true
                panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
                self.window = panel
            }

            guard let window = self.window else { return }

            let visualEffect = NSVisualEffectView()
            visualEffect.material = .hudWindow
            visualEffect.blendingMode = .behindWindow
            visualEffect.state = .active
            visualEffect.wantsLayer = true
            visualEffect.layer?.cornerRadius = 18
            visualEffect.layer?.masksToBounds = true

            let image = NSImageView()
            image.image = NSImage(systemSymbolName: icon, accessibilityDescription: message)
            image.contentTintColor = NSColor(calibratedRed: 0.2, green: 0.8, blue: 0.5, alpha: 1.0)
            image.translatesAutoresizingMaskIntoConstraints = false
            image.widthAnchor.constraint(equalToConstant: 24).isActive = true
            image.heightAnchor.constraint(equalToConstant: 24).isActive = true

            let label = NSTextField(labelWithString: message)
            label.font = .systemFont(ofSize: 14, weight: .semibold)
            label.textColor = .white
            label.alignment = .left
            label.lineBreakMode = .byTruncatingTail
            label.translatesAutoresizingMaskIntoConstraints = false

            let stack = NSStackView(views: [image, label])
            stack.orientation = .horizontal
            stack.alignment = .centerY
            stack.spacing = 12
            stack.edgeInsets = NSEdgeInsets(top: 12, left: 18, bottom: 12, right: 18)
            stack.translatesAutoresizingMaskIntoConstraints = false

            visualEffect.addSubview(stack)
            NSLayoutConstraint.activate([
                stack.leadingAnchor.constraint(equalTo: visualEffect.leadingAnchor),
                stack.trailingAnchor.constraint(equalTo: visualEffect.trailingAnchor),
                stack.topAnchor.constraint(equalTo: visualEffect.topAnchor),
                stack.bottomAnchor.constraint(equalTo: visualEffect.bottomAnchor),
            ])

            window.contentView = visualEffect
            window.layoutIfNeeded()

            let contentSize = stack.fittingSize
            let finalWidth = max(220, min(contentSize.width + 40, 480))
            let finalHeight = max(52, contentSize.height + 20)

            if let screen = NSScreen.main {
                let screenRect = screen.visibleFrame
                let originX = screenRect.midX - (finalWidth / 2)
                let originY = screenRect.minY + 90
                window.setFrame(NSRect(x: originX, y: originY, width: finalWidth, height: finalHeight), display: true)
            }

            window.alphaValue = 0
            window.orderFront(nil)

            NSAnimationContext.runAnimationGroup { context in
                context.duration = 0.2
                window.animator().alphaValue = 1.0
            }

            let workItem = DispatchWorkItem { [weak self, weak window] in
                guard let window else { return }
                NSAnimationContext.runAnimationGroup({ context in
                    context.duration = 0.35
                    window.animator().alphaValue = 0.0
                }, completionHandler: {
                    window.orderOut(nil)
                    self?.dismissWorkItem = nil
                })
            }
            self.dismissWorkItem = workItem
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.8, execute: workItem)
        }
    }
}
