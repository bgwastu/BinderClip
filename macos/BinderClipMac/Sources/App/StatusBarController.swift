// Manages the macOS menu bar icon, status dot, and dropdown menu for peer management.

import AppKit
import Foundation
import QuartzCore

final class StatusBarController {
    var onPairNewDeviceRequested: (() -> Void)?
    var onForgetDeviceRequested: ((String) -> Void)?
    var onToggleLaunchAtLogin: (() -> Void)?
    var isLaunchAtLoginEnabled: (() -> Bool)?
    var onToggleImageSync: (() -> Void)?
    /// Fired when "Don't Sync Passwords & Secrets" flips to ON.
    var onSkipSecretsEnabled: (() -> Void)?
    var isImageSyncEnabled: (() -> Bool)?
    var isDeviceConnected: (() -> Bool)?

    private let statusItem = NSStatusBar.system.statusItem(withLength: 18)
    private let menu = NSMenu()

    private var connectedPeers: [PeerSummary] = []
    private var trustedPeers: [PeerSummary] = []
    private var bluetoothWarning: String?
    private var bluetoothWarningAction: (() -> Void)?

    private lazy var connectedDot: NSImage = makeStatusDot(color: .controlAccentColor)
    private lazy var disconnectedDot: NSImage = makeStatusDot(color: .tertiaryLabelColor)

    private var binderStatusBarImage: NSImage?
    private var syncPulseTimer: Timer?

    init() {
        binderStatusBarImage = loadStatusBarIcon(named: "StatusBarBinderFilled")
        statusItem.button?.imagePosition = .imageOnly
        statusItem.button?.imageScaling = .scaleProportionallyDown
        updateStatusBarIcon()
        renderMenu()
    }

    private func loadStatusBarIcon(named name: String) -> NSImage? {
        let image = NSImage(size: NSSize(width: 18, height: 18))
        for resourceName in [name, "\(name)@2x"] {
            guard let path = Bundle.main.path(forResource: resourceName, ofType: "png"),
                  let source = NSImage(contentsOfFile: path) else {
                continue
            }
            source.representations.forEach(image.addRepresentation)
        }
        return image.representations.isEmpty ? nil : image
    }

    private func updateStatusBarIcon() {
        guard let button = statusItem.button,
              let binder = binderStatusBarImage else {
            statusItem.button?.title = "BC"
            return
        }
        if bluetoothWarning != nil {
            // A Bluetooth problem means the app is inactive. Keep the normal template glyph
            // (so it renders the same soft gray as idle, not a heavier solid black) and
            // overlay a yellow warning badge on top.
            let template = binder.copy() as! NSImage
            template.isTemplate = true
            button.image = template
            showWarningBadge(on: button)
        } else if !connectedPeers.isEmpty {
            hideWarningBadge(on: button)
            let template = binder.copy() as! NSImage
            template.isTemplate = true
            button.image = template
        } else {
            hideWarningBadge(on: button)
            let template = dimmedTemplate(from: binder)
            template.isTemplate = true
            button.image = template
        }
    }

    private static let warningBadgeLayerName = "clipboardWarningBadge"

    /// Overlays a small yellow "!" badge on the top-right of the status item. Drawn as a
    /// layer (not composited into the image) so the glyph keeps its template appearance and
    /// the badge never intercepts the menu click.
    private func showWarningBadge(on button: NSStatusBarButton) {
        button.wantsLayer = true
        guard let host = button.layer else { return }

        let badge: CALayer
        if let existing = host.sublayers?.first(where: { $0.name == Self.warningBadgeLayerName }) {
            badge = existing
        } else {
            badge = CALayer()
            badge.name = Self.warningBadgeLayerName
            host.addSublayer(badge)
        }

        let pointSize: CGFloat = 9
        let scale = button.window?.backingScaleFactor ?? 2
        badge.contents = makeWarningBadgeImage(pixelDiameter: pointSize * scale)
            .cgImage(forProposedRect: nil, context: nil, hints: nil)
        badge.contentsGravity = .resizeAspect
        badge.contentsScale = scale

        // Sit over the top-right corner of the centered 18pt glyph. The button's backing
        // layer is flipped (top-left origin), so the glyph's top edge is the smaller y.
        let bounds = button.bounds
        let glyph: CGFloat = 18
        let glyphMaxX = (bounds.width + glyph) / 2
        let glyphMinY = (bounds.height - glyph) / 2
        badge.frame = CGRect(x: glyphMaxX - pointSize + 1, y: glyphMinY - 1,
                             width: pointSize, height: pointSize)
    }

    private func hideWarningBadge(on button: NSStatusBarButton) {
        button.layer?.sublayers?
            .first { $0.name == Self.warningBadgeLayerName }?
            .removeFromSuperlayer()
    }

    /// A yellow disc with a dark "!" — drawn proportionally so it stays crisp at any scale.
    private func makeWarningBadgeImage(pixelDiameter: CGFloat) -> NSImage {
        NSImage(size: NSSize(width: pixelDiameter, height: pixelDiameter), flipped: false) { rect in
            let w = rect.width
            NSColor.systemYellow.setFill()
            NSBezierPath(ovalIn: rect).fill()

            NSColor.black.setFill()
            let stemW = w * 0.16
            let stem = NSRect(x: rect.midX - stemW / 2, y: rect.midY - w * 0.03, width: stemW, height: w * 0.32)
            NSBezierPath(roundedRect: stem, xRadius: stemW / 2, yRadius: stemW / 2).fill()
            let dotD = w * 0.18
            let dot = NSRect(x: rect.midX - dotD / 2, y: rect.midY - w * 0.30, width: dotD, height: dotD)
            NSBezierPath(ovalIn: dot).fill()
            return true
        }
    }

    func setConnectedPeers(_ peers: [PeerSummary]) {
        connectedPeers = peers
        updateStatusBarIcon()
        renderMenu()
    }

    func setTrustedPeers(_ peers: [PeerSummary]) {
        trustedPeers = peers
        renderMenu()
    }

    func setBluetoothWarning(_ warning: String?, action: (() -> Void)? = nil) {
        bluetoothWarning = warning
        bluetoothWarningAction = action
        updateStatusBarIcon()
        renderMenu()
    }


    /// Briefly pulses the status bar icon to indicate a clipboard sync.
    func flashSyncIndicator() {
        guard let button = statusItem.button, let binder = binderStatusBarImage else { return }

        // Cancel any in-progress pulse
        syncPulseTimer?.invalidate()

        // Show bright highlight icon
        let template = binder.copy() as! NSImage
        template.isTemplate = true
        button.image = template

        // Enable layer-backed view for Core Animation
        button.wantsLayer = true
        if let layer = button.layer {
            let pulse = CAKeyframeAnimation(keyPath: "transform.scale")
            pulse.values = [1.0, 1.3, 1.0]
            pulse.keyTimes = [0, 0.4, 1.0]
            pulse.duration = 0.35
            pulse.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
            layer.add(pulse, forKey: "syncPulse")
        }

        // Restore normal icon after the animation completes
        syncPulseTimer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: false) { [weak self] _ in
            self?.updateStatusBarIcon()
        }
    }

    // MARK: - Menu rendering

    /// Shows the status menu at the mouse location — escape hatch for when the
    /// menu bar icon is inaccessible (e.g. hidden under the notch).
    func popUpMenuAtMouse() {
        renderMenu()
        menu.popUp(positioning: nil, at: NSEvent.mouseLocation, in: nil)
    }

    private func renderMenu() {
        menu.removeAllItems()

        if let bluetoothWarning {
            let hasAction = bluetoothWarningAction != nil
            let warningItem = NSMenuItem(
                title: bluetoothWarning,
                action: hasAction ? #selector(handleBluetoothWarningSelected) : nil,
                keyEquivalent: ""
            )
            let warningSymbol = NSImage(systemSymbolName: "exclamationmark.triangle.fill", accessibilityDescription: "warning")
            // Two palette colors. The symbol's first layer is the exclamation, second is the
            // triangle, so order them black then yellow for a yellow triangle + black mark.
            warningItem.image = warningSymbol?.withSymbolConfiguration(
                NSImage.SymbolConfiguration(paletteColors: [.black, .systemYellow])
            )
            warningItem.target = self
            warningItem.isEnabled = hasAction
            menu.addItem(warningItem)
            menu.addItem(NSMenuItem.separator())
        }

        renderTrustedDevicesSection()
        menu.addItem(NSMenuItem.separator())

        let pairItem = NSMenuItem(
            title: "Pair New Device\u{2026}",
            action: #selector(handlePairNewDevice),
            keyEquivalent: "n"
        )
        pairItem.target = self
        menu.addItem(pairItem)

        menu.addItem(NSMenuItem.separator())

        let launchItem = NSMenuItem(
            title: "Launch at Login",
            action: #selector(handleToggleLaunchAtLogin),
            keyEquivalent: ""
        )
        launchItem.target = self
        if isLaunchAtLoginEnabled?() == true {
            launchItem.state = .on
        }
        menu.addItem(launchItem)

        let deviceConnected = isDeviceConnected?() ?? false
        let imageSyncItem = NSMenuItem(
            title: "Image Sync (experimental)",
            action: deviceConnected ? #selector(handleToggleImageSync) : nil,
            keyEquivalent: ""
        )
        imageSyncItem.target = self
        if !deviceConnected {
            imageSyncItem.isEnabled = false
        } else if isImageSyncEnabled?() == true {
            imageSyncItem.state = .on
        }
        menu.addItem(imageSyncItem)

        let skipSecretsItem = NSMenuItem(
            title: "Don\u{2019}t Sync Passwords & Secrets",
            action: #selector(handleToggleSkipSecrets),
            keyEquivalent: ""
        )
        skipSecretsItem.target = self
        skipSecretsItem.toolTip = """
        When on, clipboard copies that an app marks as secret (concealed) are not sent to \
        your phone — so passwords stay on this Mac. Works with password managers that flag \
        copies as concealed, such as Bitwarden, 1Password and KeePassXC. \
        Note: copies made from browser extensions aren't flagged and will still sync.
        """
        if ClipboardMonitor.skipSecretsEnabled {
            skipSecretsItem.state = .on
        }
        menu.addItem(skipSecretsItem)

        menu.addItem(NSMenuItem.separator())

        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let versionItem = NSMenuItem(title: "BinderClip v\(version)", action: nil, keyEquivalent: "")
        versionItem.isEnabled = false
        menu.addItem(versionItem)

        menu.addItem(NSMenuItem.separator())

        menu.addItem(NSMenuItem(
            title: "Quit BinderClip",
            action: #selector(NSApplication.terminate(_:)),
            keyEquivalent: "q"
        ))

        statusItem.menu = menu
    }

    private func renderTrustedDevicesSection() {
        let header = NSMenuItem(title: "Paired Devices", action: nil, keyEquivalent: "")
        header.isEnabled = false
        menu.addItem(header)

        if trustedPeers.isEmpty {
            let empty = NSMenuItem(title: "  No paired devices", action: nil, keyEquivalent: "")
            empty.isEnabled = false
            menu.addItem(empty)
            return
        }

        let connectedIDs = Set(connectedPeers.map(\.id))

        for peer in trustedPeers {
            let isConnected = connectedIDs.contains(peer.id)

            let title: String
            if let tag = peer.deviceTagHex {
                title = "\(peer.description)  [Pairing: \(tag)]"
            } else {
                title = peer.description
            }
            let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
            item.image = isConnected ? connectedDot : disconnectedDot
            item.isEnabled = true

            let submenu = NSMenu()
            let forgetItem = NSMenuItem(
                title: "Forget Device",
                action: #selector(handleForgetDevice(_:)),
                keyEquivalent: ""
            )
            forgetItem.target = self
            forgetItem.representedObject = peer.secret
            submenu.addItem(forgetItem)

            item.submenu = submenu
            menu.addItem(item)
        }
    }

    // MARK: - Status dot

    private func makeStatusDot(color: NSColor) -> NSImage {
        let size = NSSize(width: 8, height: 8)
        let image = NSImage(size: size, flipped: false) { rect in
            color.setFill()
            NSBezierPath(ovalIn: rect.insetBy(dx: 0.5, dy: 0.5)).fill()
            return true
        }
        image.isTemplate = false
        return image
    }

    private func dimmedTemplate(from image: NSImage) -> NSImage {
        let dimmed = NSImage(size: image.size, flipped: false) { rect in
            image.draw(in: rect, from: .zero, operation: .sourceOver, fraction: 0.42)
            return true
        }
        dimmed.isTemplate = true
        return dimmed
    }

    // MARK: - Actions

    @objc
    private func handlePairNewDevice() {
        onPairNewDeviceRequested?()
    }

    @objc
    private func handleBluetoothWarningSelected() {
        bluetoothWarningAction?()
    }

    @objc
    private func handleToggleLaunchAtLogin() {
        onToggleLaunchAtLogin?()
        renderMenu()
    }

    @objc
    private func handleToggleImageSync() {
        onToggleImageSync?()
        renderMenu()
    }

    @objc
    private func handleToggleSkipSecrets() {
        ClipboardMonitor.skipSecretsEnabled.toggle()
        if ClipboardMonitor.skipSecretsEnabled {
            onSkipSecretsEnabled?()
        }
        renderMenu()
    }


    @objc
    private func handleForgetDevice(_ sender: NSMenuItem) {
        guard let token = sender.representedObject as? String else { return }
        onForgetDeviceRequested?(token)
    }
}
