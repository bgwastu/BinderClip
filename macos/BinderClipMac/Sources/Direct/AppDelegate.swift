import AppKit
import ServiceManagement
import Sparkle

/*
 THESIS: a private clipboard group is operated from one quiet menu, never a settings maze.
 OWN-WORLD: macOS system surfaces, BinderClip icon, a restrained teal action, semantic state color.
 STORY: users see connection truth, add a device with a time-boxed QR, then copy normally.
 FIRST VIEWPORT: the menu leads with live connection count, peers, then one pairing action.
 FORM: native menu-bar utility; compact operating panel rather than a dashboard.
*/
final class AppDelegate: NSObject, NSApplicationDelegate {
    private let transport = DirectTransport()
    private let clipboard = ClipboardBridge()
    private let pairing = PairingWindow()
    private let logWindow = LogWindowController()
    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private lazy var updaterController = SPUStandardUpdaterController(
        startingUpdater: true,
        updaterDelegate: nil,
        userDriverDelegate: nil
    )
    private var peers: [Peer] = [] { didSet { renderMenu() } }
    private var status = "Listening" { didSet { renderMenu() } }
    private var localNetworkPermissionRequired = false { didSet { renderMenu() } }

    func applicationDidFinishLaunching(_ notification: Notification) {
        _ = updaterController
        statusItem.button?.image = binderClipStatusIcon()
        statusItem.button?.imagePosition = .imageOnly
        transport.onClipboard = { [weak self] text in self?.clipboard.applyRemote(text) }
        transport.onImage = { [weak self] image in self?.clipboard.applyRemote(image) }
        transport.onPeersChanged = { [weak self] peers in self?.peers = peers }
        transport.onLog = { [weak self] message in self?.status = message }
        transport.onTransferStatus = { [weak self] message in self?.status = message }
        transport.onLocalNetworkPermissionRequired = { [weak self] required in
            DispatchQueue.main.async { self?.localNetworkPermissionRequired = required }
        }
        clipboard.onLocalText = { [weak transport] text in transport?.sendClipboard(text) }
        clipboard.onLocalImage = { [weak transport] image in transport?.sendImage(image) }
        transport.start(); clipboard.start(); peers = transport.peersSnapshot(); renderMenu()
    }

    func applicationWillTerminate(_ notification: Notification) { clipboard.stop(); transport.stop() }

    private func renderMenu() {
        let menu = NSMenu()
        renderPendingPermissions(into: menu)
        let chainHeader = NSMenuItem(title: "This Chain", action: nil, keyEquivalent: "")
        chainHeader.isEnabled = false
        chainHeader.indentationLevel = 0
        menu.addItem(chainHeader)
        let thisMac = NSMenuItem(title: "\(transport.localDeviceName) (current)", action: nil, keyEquivalent: "")
        thisMac.image = NSImage(systemSymbolName: "laptopcomputer", accessibilityDescription: "This Mac")
        thisMac.submenu = deviceMenu(for: Peer(id: transport.localDeviceID, name: transport.localDeviceName, endpoint: transport.localEndpoint, connected: true, platform: "macOS")); menu.addItem(thisMac)
        if peers.isEmpty {
            let empty = NSMenuItem(title: "No Devices", action: nil, keyEquivalent: ""); empty.isEnabled = false; menu.addItem(empty)
        } else {
            for peer in peers {
                let item = NSMenuItem(title: peer.name, action: nil, keyEquivalent: "")
                item.image = NSImage(systemSymbolName: peer.platform == "macOS" ? "laptopcomputer" : "iphone", accessibilityDescription: peer.platform)
                item.submenu = deviceMenu(for: peer); menu.addItem(item)
            }
        }
        menu.addItem(.separator())
        let pair = NSMenuItem(title: "Add Device", action: #selector(showPairing), keyEquivalent: "n"); pair.target = self; menu.addItem(pair)
        let send = NSMenuItem(title: "Send Current Clipboard", action: #selector(sendCurrentClipboard), keyEquivalent: ""); send.target = self; send.isEnabled = !peers.filter(\.connected).isEmpty; menu.addItem(send)
        let logs = NSMenuItem(title: "Show Logs", action: #selector(showLogs), keyEquivalent: ""); logs.target = self; menu.addItem(logs)
        let updates = NSMenuItem(title: "Check for Updates…", action: #selector(checkForUpdates), keyEquivalent: ""); updates.target = self; menu.addItem(updates)
        menu.addItem(.separator()); menu.addItem(NSMenuItem(title: "Quit BinderClip", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
        statusItem.menu = menu
    }

    private func binderClipStatusIcon() -> NSImage? {
        let resource = Bundle.main.url(forResource: "BinderClipMenuIcon", withExtension: "svg")
            ?? Bundle.module.url(forResource: "BinderClipMenuIcon", withExtension: "svg")
        guard let resource, let image = NSImage(contentsOf: resource) else { return nil }
        image.size = NSSize(width: 18, height: 18)
        image.isTemplate = true
        return image
    }
    private func renderPendingPermissions(into menu: NSMenu) {
        var hasPendingPermission = false
        let loginStatus = SMAppService.mainApp.status
        if loginStatus != .enabled {
            let needsApproval = loginStatus == .requiresApproval
            let item = NSMenuItem(title: needsApproval ? "Allow Launch At Login" : "Enable Launch At Login", action: #selector(enableLaunchAtLogin), keyEquivalent: "")
            item.image = NSImage(systemSymbolName: "arrow.right.circle", accessibilityDescription: "Launch At Login")
            item.target = self; menu.addItem(item); hasPendingPermission = true
        }
        if localNetworkPermissionRequired {
            let item = NSMenuItem(title: "Allow Local Network", action: #selector(openPrivacySettings), keyEquivalent: "")
            item.image = NSImage(systemSymbolName: "network", accessibilityDescription: "Local Network")
            item.target = self; menu.addItem(item); hasPendingPermission = true
        }
        if clipboard.isAccessDenied {
            let item = NSMenuItem(title: "Allow Clipboard Access", action: #selector(openPrivacySettings), keyEquivalent: "")
            item.image = NSImage(systemSymbolName: "doc.on.clipboard", accessibilityDescription: "Clipboard Access")
            item.target = self; menu.addItem(item); hasPendingPermission = true
        }
        if hasPendingPermission { menu.addItem(.separator()) }
    }

    private func deviceMenu(for peer: Peer) -> NSMenu {
        let details = NSMenu()
        let status = NSMenuItem(title: peer.connected ? "Connected" : "Waiting To Reconnect", action: nil, keyEquivalent: "")
        status.isEnabled = false; details.addItem(status)
        let route = NSMenuItem(title: peer.endpoint.host == "unknown" ? "Route Unknown" : "Route: \(peer.endpoint.host):\(peer.endpoint.port)", action: nil, keyEquivalent: "")
        route.isEnabled = false; details.addItem(route)
        details.addItem(.separator())
        let remove = NSMenuItem(title: "Remove From Chain", action: #selector(removePeer(_:)), keyEquivalent: "")
        remove.target = self; remove.representedObject = peer.id; details.addItem(remove)
        return details
    }

    @objc private func showPairing() { pairing.show { [weak self] in self?.transport.createInvite() } }
    @objc private func sendCurrentClipboard() { clipboard.sendCurrentClipboard() }
    @objc private func showLogs() { logWindow.showWindow(nil) }
    @objc private func removePeer(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String else { return }
        let name = id == transport.localDeviceID ? transport.localDeviceName : peers.first(where: { $0.id == id })?.name
        guard let name else { return }
        let alert = NSAlert(); alert.messageText = "Remove \(name) From This Chain?"
        alert.informativeText = "It will no longer receive new BinderClip updates or be accepted into this chain."
        alert.addButton(withTitle: "Remove"); alert.addButton(withTitle: "Cancel")
        if alert.runModal() == .alertFirstButtonReturn { transport.removeFromChain(id) }
    }
    @objc private func enableLaunchAtLogin() {
        if SMAppService.mainApp.status == .requiresApproval { SMAppService.openSystemSettingsLoginItems(); return }
        do { try SMAppService.mainApp.register() } catch { status = "Could not enable launch at login" }
    }
    @objc private func openPrivacySettings() {
        guard let url = URL(string: "x-apple.systempreferences:com.apple.preference.security") else { return }
        NSWorkspace.shared.open(url)
    }
    @objc private func checkForUpdates() {
        updaterController.checkForUpdates(nil)
    }
}
