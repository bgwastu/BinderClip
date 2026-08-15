import AppKit
import ServiceManagement
import Sparkle
import UserNotifications

/*
 THESIS: a private clipboard group is operated from one quiet menu, never a settings maze.
 OWN-WORLD: macOS system surfaces, BinderClip icon, a restrained teal action, semantic state color.
 STORY: users see connection truth, add a device with a time-boxed QR, then copy normally.
 FIRST VIEWPORT: the menu leads with live connection count, peers, then one pairing action.
 FORM: native menu-bar utility; compact operating panel rather than a dashboard.
*/
final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate, SPUUpdaterDelegate {
    private let transport = DirectTransport()
    private let clipboard = ClipboardBridge()
    private let pairing = PairingWindow()
    private let logWindow = LogWindowController()
    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private lazy var updaterController = SPUStandardUpdaterController(
        startingUpdater: true,
        updaterDelegate: self,
        userDriverDelegate: nil
    )
    private var peerCountBeforePairing: Int?
    private var peers: [Peer] = [] { didSet { renderMenu(); updateStatusIcon(); checkPairingCompletion() } }
    private var status = "Listening" { didSet { renderMenu(); updateStatusIcon() } }
    private var localNetworkPermissionRequired = false { didSet { renderMenu() } }
    private var automationPermissionRequired = false { didSet { renderMenu() } }
    private var cachedActiveTab: (browser: String, url: URL)?
    private var tabPollTimer: Timer?
    private let statusMenu = NSMenu()

    func applicationDidFinishLaunching(_ notification: Notification) {
        _ = updaterController
        statusMenu.delegate = self
        statusMenu.autoenablesItems = false
        statusItem.menu = statusMenu
        updateStatusIcon()
        startTabPolling()
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
        transport.onClipboard = { [weak self] text in
            self?.clipboard.applyRemote(text)
            self?.notifyIncoming(title: "BinderClip", body: "Received text")
            ToastHUD.shared.show(message: "Received text", icon: "doc.on.clipboard.fill")
        }
        transport.onOpenURL = { [weak self] url in
            NSWorkspace.shared.open(url)
            self?.notifyIncoming(title: "BinderClip", body: "Opened link in browser")
            ToastHUD.shared.show(message: "Opened link in browser", icon: "safari.fill")
        }
        transport.onImage = { [weak self] image in
            self?.clipboard.applyRemote(image)
            self?.notifyIncoming(title: "BinderClip", body: "Received image (\(image.mimeType))")
            ToastHUD.shared.show(message: "Received image (\(image.mimeType))", icon: "photo.fill")
        }
        transport.onPeersChanged = { [weak self] peers in self?.peers = peers }
        transport.onLog = { [weak self] message in self?.status = message; self?.checkPairingCompletion() }
        transport.onTransferStatus = { [weak self] message in self?.status = message }
        transport.onLocalNetworkPermissionRequired = { [weak self] required in
            DispatchQueue.main.async { self?.localNetworkPermissionRequired = required }
        }
        clipboard.onLocalText = { [weak transport] text in transport?.sendClipboard(text) }
        clipboard.onLocalImage = { [weak transport] image in transport?.sendImage(image) }
        transport.start(); clipboard.start(); peers = transport.peersSnapshot(); renderMenu(); updateStatusIcon()
        #if DEBUG
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            if let invite = self?.transport.createInvite() {
                let debugDir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("net.wastu.binderclip", isDirectory: true)
                try? FileManager.default.createDirectory(at: debugDir, withIntermediateDirectories: true)
                try? invite.absoluteString.write(to: debugDir.appendingPathComponent("debug-invite.txt"), atomically: true, encoding: .utf8)
                print("[BinderClip Debug] Ready pairing code: \(invite.absoluteString)")
            }
            if self?.peers.isEmpty == true {
                self?.showPairing()
            }
        }
        #endif
    }

    func applicationWillTerminate(_ notification: Notification) { clipboard.stop(); transport.stop() }

    func menuWillOpen(_ menu: NSMenu) {
        renderMenu()
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        renderMenu()
    }

    private func notifyIncoming(title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    private func updateStatusIcon() {
        statusItem.button?.image = binderClipStatusIcon()
        statusItem.button?.imagePosition = .imageOnly
        let hasConnectedPeers = peers.contains(where: \.connected)
        if status.contains("%") || status.hasPrefix("Sending image") || status.hasPrefix("Receiving image") {
            statusItem.button?.toolTip = "BinderClip: \(status)"
        } else {
            statusItem.button?.toolTip = hasConnectedPeers ? "BinderClip: Connected" : "BinderClip: \(status)"
        }
    }

    private func renderMenu() {
        let menu = statusMenu
        menu.removeAllItems()
        renderPendingPermissions(into: menu)
        if status.contains("%") || status.hasPrefix("Sending image") || status.hasPrefix("Receiving image") {
            let progressItem = NSMenuItem(title: "⚡ \(status)", action: nil, keyEquivalent: "")
            progressItem.isEnabled = false
            menu.addItem(progressItem)
            menu.addItem(.separator())
        }
        if #available(macOS 14.0, *) {
            menu.addItem(.sectionHeader(title: "This Chain"))
        } else {
            let chainHeader = NSMenuItem(title: "This Chain", action: nil, keyEquivalent: "")
            chainHeader.isEnabled = false
            chainHeader.indentationLevel = 0
            menu.addItem(chainHeader)
        }
        let thisMac = NSMenuItem(title: "\(transport.localDeviceName) (current)", action: nil, keyEquivalent: "")
        thisMac.image = NSImage(systemSymbolName: "laptopcomputer", accessibilityDescription: "This Mac")
        thisMac.submenu = deviceMenu(for: Peer(id: transport.localDeviceID, name: transport.localDeviceName, endpoint: transport.localEndpoint, connected: true, platform: "macOS"))
        menu.addItem(thisMac)
        for peer in peers {
            let item = NSMenuItem(title: peer.name, action: nil, keyEquivalent: "")
            item.image = NSImage(systemSymbolName: peer.platform == "macOS" ? "laptopcomputer" : "iphone", accessibilityDescription: peer.platform)
            item.submenu = deviceMenu(for: peer)
            menu.addItem(item)
        }
        menu.addItem(.separator())
        if peers.isEmpty {
            let create = NSMenuItem(title: "Create New Chain", action: #selector(createNewChain), keyEquivalent: "n")
            create.target = self; menu.addItem(create)
            let join = NSMenuItem(title: "Join Chain…", action: #selector(joinChain), keyEquivalent: "j")
            join.target = self; menu.addItem(join)
        }
        let pair = NSMenuItem(title: "Add Device", action: #selector(showPairing), keyEquivalent: "n"); pair.target = self; menu.addItem(pair)
        let send = NSMenuItem(title: "Send Current Clipboard", action: #selector(sendCurrentClipboard), keyEquivalent: "s"); send.target = self; send.isEnabled = !peers.filter(\.connected).isEmpty; menu.addItem(send)

        let browserTab = cachedActiveTab
        let sendURLItem = NSMenuItem(
            title: browserTab != nil ? "Send Current Browser Tab" : "Send Current Browser Tab (No active tab)",
            action: nil,
            keyEquivalent: "u"
        )
        if let browserTab {
            sendURLItem.isEnabled = true
            let urlSubmenu = NSMenu()
            urlSubmenu.autoenablesItems = false
            
            // Header showing the active URL purely as a disabled preview
            let truncatedUrlString = browserTab.url.absoluteString.count > 45 ? String(browserTab.url.absoluteString.prefix(42)) + "…" : browserTab.url.absoluteString
            let urlHeader = NSMenuItem(title: truncatedUrlString, action: nil, keyEquivalent: "")
            urlHeader.isEnabled = false
            urlSubmenu.addItem(urlHeader)
            urlSubmenu.addItem(.separator())

            let allItem = NSMenuItem(title: "All Connected Devices", action: #selector(sendBrowserTabToTarget(_:)), keyEquivalent: "")
            allItem.target = self
            allItem.representedObject = ["url": browserTab.url, "peerId": nil as String? as Any]
            allItem.isEnabled = !peers.filter(\.connected).isEmpty
            urlSubmenu.addItem(allItem)

            if !peers.isEmpty {
                urlSubmenu.addItem(.separator())
                for peer in peers {
                    let peerItem = NSMenuItem(title: peer.name, action: #selector(sendBrowserTabToTarget(_:)), keyEquivalent: "")
                    peerItem.image = NSImage(systemSymbolName: peer.platform == "macOS" ? "laptopcomputer" : "iphone", accessibilityDescription: peer.platform)
                    peerItem.target = self
                    peerItem.representedObject = ["url": browserTab.url, "peerId": peer.id as String? as Any]
                    peerItem.isEnabled = peer.connected
                    urlSubmenu.addItem(peerItem)
                }
            }
            sendURLItem.submenu = urlSubmenu
        } else {
            sendURLItem.isEnabled = false
        }
        menu.addItem(sendURLItem)

        let logs = NSMenuItem(title: "Show Logs", action: #selector(showLogs), keyEquivalent: "l"); logs.target = self; menu.addItem(logs)
        let rawVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        #if DEBUG
        let versionString = rawVersion.map { $0.contains("debug") ? $0 : "\($0)-debug" } ?? "debug"
        let updatesTitle = "BinderClip Debug\tv\(versionString)"
        #else
        let versionString = rawVersion
        let updatesTitle = versionString.map { "Check for Updates…\tv\($0)" } ?? "Check for Updates…"
        #endif
        let updates = NSMenuItem(title: updatesTitle, action: #selector(checkForUpdates), keyEquivalent: ""); updates.target = self; menu.addItem(updates)
        menu.addItem(.separator()); menu.addItem(NSMenuItem(title: "Quit BinderClip", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
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
            item.target = self; menu.addItem(item); hasPendingPermission = true
        }
        if localNetworkPermissionRequired {
            let item = NSMenuItem(title: "Allow Local Network", action: #selector(openPrivacySettings), keyEquivalent: "")
            item.target = self; menu.addItem(item); hasPendingPermission = true
        }
        if clipboard.isAccessDenied {
            let item = NSMenuItem(title: "Allow Clipboard Access", action: #selector(openPrivacySettings), keyEquivalent: "")
            item.target = self; menu.addItem(item); hasPendingPermission = true
        }
        if automationPermissionRequired {
            let item = NSMenuItem(title: "Allow Browser Automation", action: #selector(openAutomationPrivacySettings), keyEquivalent: "")
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
        if peer.id != transport.localDeviceID {
            details.addItem(.separator())
            let sendToDevice = NSMenuItem(title: "Send Clipboard to \(peer.name)", action: #selector(sendClipboardToPeer(_:)), keyEquivalent: "")
            sendToDevice.target = self
            sendToDevice.representedObject = peer.id
            sendToDevice.isEnabled = peer.connected
            details.addItem(sendToDevice)
        }
        details.addItem(.separator())
        let rename = NSMenuItem(title: peer.id == transport.localDeviceID ? "Rename This Mac…" : "Rename Device…", action: #selector(renameDevice(_:)), keyEquivalent: "")
        rename.target = self; rename.representedObject = peer.id; details.addItem(rename)
        details.addItem(.separator())
        let remove = NSMenuItem(title: "Remove From Chain", action: #selector(removePeer(_:)), keyEquivalent: "")
        remove.target = self; remove.representedObject = peer.id; details.addItem(remove)
        return details
    }

    @objc private func showPairing() {
        peerCountBeforePairing = peers.count
        pairing.onPeerCard = { [weak self] code in self?.transport.feedPeerAnswer(code) }
        pairing.show(statusText: "Waiting for device…") { [weak self] in self?.transport.createInvite() }
    }
    @objc private func createNewChain() {
        let alert = NSAlert()
        alert.messageText = "Create a New Chain?"
        alert.informativeText = "This rotates the group key, clears the device list, and lets you share a fresh pairing code. Existing devices will no longer be accepted."
        alert.addButton(withTitle: "Create")
        alert.addButton(withTitle: "Cancel")
        if alert.runModal() == .alertFirstButtonReturn {
            transport.startNewChain()
            showPairing()
        }
    }
    @objc private func joinChain() {
        let alert = NSAlert()
        alert.messageText = "Join a Chain"
        alert.informativeText = "Paste a BinderClip pairing code from another device's 'Create New Chain' or 'Add Device' window:"
        let input = NSTextField(frame: NSRect(x: 0, y: 0, width: 320, height: 24))
        input.placeholderString = "binderclip://invite?..."
        alert.accessoryView = input
        alert.addButton(withTitle: "Join")
        alert.addButton(withTitle: "Cancel")
        alert.window.initialFirstResponder = input
        if alert.runModal() == .alertFirstButtonReturn {
            let code = input.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
            if !code.isEmpty {
                transport.joinChain(inviteURL: code)
            }
        }
    }
    private func checkPairingCompletion() {
        guard let before = peerCountBeforePairing else { return }
        let connectedCount = peers.filter(\.connected).count
        let hadConnected = before
        if connectedCount > hadConnected {
            peerCountBeforePairing = nil
            pairing.closeWithSuccess()
        }
    }
    private func startTabPolling() {
        refreshActiveBrowserTabAsync()
        tabPollTimer = Timer.scheduledTimer(withTimeInterval: 3.0, repeats: true) { [weak self] _ in
            self?.refreshActiveBrowserTabAsync()
        }
    }

    private func refreshActiveBrowserTabAsync() {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self else { return }
            let tab = self.activeBrowserTab()
            DispatchQueue.main.async {
                self.cachedActiveTab = tab
            }
        }
    }

    private func activeBrowserTab() -> (browser: String, url: URL)? {
        let supportedBrowsers: [(bundleId: String, name: String, isChromium: Bool)] = [
            ("com.brave.Browser", "Brave", true),
            ("com.google.Chrome", "Google Chrome", true),
            ("com.google.Chrome.canary", "Google Chrome Canary", true),
            ("company.thebrowser.Browser", "Arc", true),
            ("com.microsoft.edgemac", "Microsoft Edge", true),
            ("com.vivaldi.Vivaldi", "Vivaldi", true),
            ("com.operasoftware.Opera", "Opera", true),
            ("com.kagi.kagisafari", "Orion", true),
            ("com.apple.Safari", "Safari", false),
            ("com.apple.SafariTechnologyPreview", "Safari Technology Preview", false),
        ]

        let runningApps = NSWorkspace.shared.runningApplications
        let runningBundleIDs = Set(runningApps.compactMap(\.bundleIdentifier))

        let runningBrowsers = supportedBrowsers.filter { runningBundleIDs.contains($0.bundleId) }
        guard !runningBrowsers.isEmpty else { return nil }

        let frontmostBundleID = NSWorkspace.shared.frontmostApplication?.bundleIdentifier

        let sortedBrowsers = runningBrowsers.sorted { a, b in
            if a.bundleId == frontmostBundleID { return true }
            if b.bundleId == frontmostBundleID { return false }
            return false
        }

        for browser in sortedBrowsers {
            let script: String
            if browser.isChromium {
                script = """
                tell application id "\(browser.bundleId)"
                    try
                        set u to URL of active tab of front window
                        if u is not "" then return u
                    end try
                    repeat with w in windows
                        try
                            set u to URL of active tab of w
                            if u is not "" then return u
                        end try
                    end repeat
                end tell
                """
            } else {
                script = """
                tell application id "\(browser.bundleId)"
                    try
                        set u to URL of front document
                        if u is not "" then return u
                    end try
                    repeat with d in documents
                        try
                            set u to URL of d
                            if u is not "" then return u
                        end try
                    end repeat
                end tell
                """
            }
            
            var error: NSDictionary?
            if let appleScript = NSAppleScript(source: script) {
                let result = appleScript.executeAndReturnError(&error)
                if let errNumber = error?[NSAppleScript.errorNumber] as? Int, errNumber == -1743 {
                    automationPermissionRequired = true
                } else if error == nil {
                    automationPermissionRequired = false
                }
                if let str = result.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines),
                   let url = URL(string: str),
                   let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" {
                    return (browser.name, url)
                }
            }

            // Process-level fallback to ensure execution even if in-process AppleEvent sandbox denies execution
            let proc = Process()
            proc.launchPath = "/usr/bin/osascript"
            proc.arguments = ["-e", script]
            let pipe = Pipe()
            let errPipe = Pipe()
            proc.standardOutput = pipe
            proc.standardError = errPipe
            try? proc.run()
            proc.waitUntilExit()
            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            let errData = errPipe.fileHandleForReading.readDataToEndOfFile()
            let errStr = String(data: errData, encoding: .utf8) ?? ""
            if errStr.contains("-1743") || errStr.contains("Not authorized to send Apple events") {
                automationPermissionRequired = true
            } else if !errStr.isEmpty && !errStr.contains("error") {
                automationPermissionRequired = false
            }
            if let str = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines),
               let url = URL(string: str),
               let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" {
                return (browser.name, url)
            }
        }
        return nil
    }

    @objc private func sendCurrentClipboard() {
        clipboard.sendCurrentClipboard()
        ToastHUD.shared.show(message: "Sent clipboard to chain", icon: "doc.on.clipboard.fill")
    }

    @objc private func copyURLToClipboard(_ sender: NSMenuItem) {
        guard let url = sender.representedObject as? URL else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(url.absoluteString, forType: .string)
        ToastHUD.shared.show(message: "Copied URL", icon: "doc.on.doc.fill")
    }

    @objc private func sendBrowserTabToTarget(_ sender: NSMenuItem) {
        guard let dict = sender.representedObject as? [String: Any?],
              let url = dict["url"] as? URL else { return }
        let peerID = dict["peerId"] as? String
        transport.sendOpenURL(url, targetDeviceId: peerID)
        let peerName = peerID != nil ? (peers.first(where: { $0.id == peerID })?.name ?? "device") : "all devices"
        ToastHUD.shared.show(message: "Sent URL to \(peerName)", icon: "safari.fill")
    }

    @objc private func sendClipboardToPeer(_ sender: NSMenuItem) {
        guard let peerID = sender.representedObject as? String else { return }
        let peerName = peers.first(where: { $0.id == peerID })?.name ?? "device"
        switch ClipboardClassifier.read(from: NSPasteboard.general) {
        case .text(let text):
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if let url = URL(string: trimmed), let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" {
                transport.sendOpenURL(url, targetDeviceId: peerID)
                ToastHUD.shared.show(message: "Sent URL to \(peerName)", icon: "safari.fill")
            } else {
                transport.sendClipboard(text, targetDeviceId: peerID)
                ToastHUD.shared.show(message: "Sent clipboard to \(peerName)", icon: "doc.on.clipboard.fill")
            }
        case .image(let image):
            transport.sendImage(image)
            ToastHUD.shared.show(message: "Sent image to \(peerName)", icon: "photo.fill")
        case .unsupported:
            break
        }
    }
    @objc private func showLogs() { logWindow.showWindow(nil) }
    @objc private func renameDevice(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String else { return }
        let currentName = id == transport.localDeviceID ? transport.localDeviceName : peers.first(where: { $0.id == id })?.name
        guard let currentName else { return }
        let alert = NSAlert()
        alert.messageText = id == transport.localDeviceID ? "Rename This Mac in Chain" : "Rename Device in Chain"
        alert.informativeText = "Enter a new name for this device across the BinderClip chain:"
        let input = NSTextField(frame: NSRect(x: 0, y: 0, width: 260, height: 24))
        input.stringValue = currentName
        alert.accessoryView = input
        alert.addButton(withTitle: "Save")
        alert.addButton(withTitle: "Cancel")
        alert.window.initialFirstResponder = input
        if alert.runModal() == .alertFirstButtonReturn {
            let newName = input.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
            if !newName.isEmpty {
                transport.renamePeer(id: id, newName: newName)
            }
        }
    }
    @objc private func removePeer(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String else { return }
        let name = id == transport.localDeviceID ? transport.localDeviceName : peers.first(where: { $0.id == id })?.name
        guard let name else { return }
        if id == transport.localDeviceID {
            let alert = NSAlert()
            alert.messageText = "Leave This Chain?"
            alert.informativeText = "You will leave the chain. Recreate a new chain or join another with a pairing code."
            alert.addButton(withTitle: "Leave"); alert.addButton(withTitle: "Cancel")
            if alert.runModal() == .alertFirstButtonReturn { transport.leaveChain() }
            return
        }
        let alert = NSAlert(); alert.messageText = "Remove \(name) From This Chain?"
        alert.informativeText = "It will no longer receive new BinderClip updates or be accepted into this chain."
        alert.addButton(withTitle: "Remove"); alert.addButton(withTitle: "Cancel")
        if alert.runModal() == .alertFirstButtonReturn { transport.removeFromChain(id) }
    }
    @objc private func enableLaunchAtLogin() {
        let currentStatus = SMAppService.mainApp.status
        if currentStatus == .requiresApproval {
            SMAppService.openSystemSettingsLoginItems()
            renderMenu()
            return
        }
        do {
            try SMAppService.mainApp.register()
        } catch {
            DiagnosticLog.shared.error("Failed to register launch at login: \(error.localizedDescription)")
            SMAppService.openSystemSettingsLoginItems()
        }
        renderMenu()
    }
    @objc private func openPrivacySettings() {
        guard let url = URL(string: "x-apple.systempreferences:com.apple.preference.security") else { return }
        NSWorkspace.shared.open(url)
    }
    @objc private func openAutomationPrivacySettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Automation") {
            NSWorkspace.shared.open(url)
        } else {
            openPrivacySettings()
        }
    }
    @objc private func checkForUpdates() {
        updaterController.checkForUpdates(nil)
    }

    func feedURLString(for updater: SPUUpdater) -> String? {
        let base = Bundle.main.object(forInfoDictionaryKey: "SUFeedURL") as? String
            ?? "https://github.com/bgwastu/BinderClip/releases/latest/download/appcast.xml"
        let timestamp = Int(Date().timeIntervalSince1970)
        return "\(base)?t=\(timestamp)"
    }
}
