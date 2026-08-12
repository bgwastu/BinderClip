import AppKit
import CryptoKit
import Foundation
import os

enum CustomUpdaterError: LocalizedError {
    case invalidFeed
    case missingPublicKey
    case invalidSignature
    case invalidArchive
    case updateAlreadyRunning
    case replacementFailed

    var errorDescription: String? {
        switch self {
        case .invalidFeed: return "The BinderClip update feed is invalid."
        case .missingPublicKey: return "The BinderClip update key is missing."
        case .invalidSignature: return "The BinderClip update signature is invalid."
        case .invalidArchive: return "The BinderClip update archive is invalid."
        case .updateAlreadyRunning: return "An update is already being installed."
        case .replacementFailed: return "BinderClip could not replace the installed app."
        }
    }
}

private struct UpdateFeedItem {
    let version: String
    let shortVersion: String
    let build: Int
    let url: URL
    let length: Int
    let signature: Data
}

/// Verifies Sparkle-signed archives, then delegates replacement to a separate
/// copy of the app so the running bundle is never modified by its own process.
final class CustomUpdater {
    static let feedURL = URL(string: "https://github.com/bgwastu/BinderClip/releases/latest/download/appcast.xml")!
    static let publicKeyDefaultsKey = "SUPublicEDKey"
    private static let helperArgument = "--install-update"
    private static let logger = Logger(subsystem: "net.wastu.clipboard", category: "Updater")

    private var isUpdating = false

    func checkAndInstall(interactive: Bool) {
        guard !isUpdating else {
            if interactive { presentError(CustomUpdaterError.updateAlreadyRunning) }
            return
        }
        isUpdating = true
        Task { [weak self] in
            do {
                guard let self else { return }
                guard let item = try await self.fetchUpdateItem(), self.isNewer(item) else {
                    self.isUpdating = false
                    return
                }
                try await self.downloadAndInstall(item)
            } catch {
                self?.isUpdating = false
                Self.logger.error("Update failed: \(error.localizedDescription, privacy: .public)")
                guard interactive else { return }
                let message = error.localizedDescription
                await MainActor.run {
                    self?.presentErrorMessage(message)
                }
            }
        }
    }

    private func fetchUpdateItem() async throws -> UpdateFeedItem? {
        let (data, response) = try await URLSession.shared.data(from: Self.feedURL)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw CustomUpdaterError.invalidFeed }
        let parser = FeedParser()
        parser.parse(data)
        return try parser.item()
    }

    private func downloadAndInstall(_ item: UpdateFeedItem) async throws {
        let (archive, response) = try await URLSession.shared.data(from: item.url)
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              archive.count == item.length else { throw CustomUpdaterError.invalidArchive }
        guard let keyString = Bundle.main.object(forInfoDictionaryKey: Self.publicKeyDefaultsKey) as? String,
              let keyData = Data(base64Encoded: keyString),
              let signature = try? Curve25519.Signing.PublicKey(rawRepresentation: keyData),
              signature.isValidSignature(item.signature, for: archive) else {
            throw CustomUpdaterError.invalidSignature
        }

        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("BinderClip-update-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let archiveURL = root.appendingPathComponent("update.zip")
        try archive.write(to: archiveURL, options: .atomic)
        try run("/usr/bin/ditto", arguments: ["-x", "-k", archiveURL.path, root.path])
        let candidate = root.appendingPathComponent("BinderClip.app")
        guard let bundle = Bundle(url: candidate),
              bundle.bundleIdentifier == Bundle.main.bundleIdentifier,
              bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String == item.shortVersion,
              Int(bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0") == item.build,
              FileManager.default.fileExists(atPath: candidate.appendingPathComponent("Contents/MacOS/BinderClip").path)
        else { throw CustomUpdaterError.invalidArchive }
        try run("/usr/bin/codesign", arguments: ["--verify", "--deep", "--strict", candidate.path])

        let helper = Process()
        helper.executableURL = Bundle.main.executableURL
        helper.arguments = [Self.helperArgument, candidate.path, Bundle.main.bundlePath,
                            String(ProcessInfo.processInfo.processIdentifier)]
        try helper.run()
        await MainActor.run { NSApp.terminate(nil) }
    }

    private func isNewer(_ item: UpdateFeedItem) -> Bool {
        let current = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
        let versionOrder = item.shortVersion.compare(current, options: .numeric)
        if versionOrder != .orderedSame { return versionOrder == .orderedDescending }
        let currentBuild = Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0") ?? 0
        return item.build > currentBuild
    }

    private func presentError(_ error: Error) {
        presentErrorMessage(error.localizedDescription)
    }

    private func presentErrorMessage(_ message: String) {
        let alert = NSAlert()
        alert.messageText = "BinderClip Update Failed"
        alert.informativeText = message
        alert.alertStyle = .warning
        alert.addButton(withTitle: "OK")
        alert.runModal()
    }

    private func run(_ executable: String, arguments: [String]) throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: executable)
        process.arguments = arguments
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { throw CustomUpdaterError.invalidArchive }
    }

    static func runHelperIfRequested(arguments: [String]) -> Bool {
        guard arguments.count == 5, arguments[1] == helperArgument else { return false }
        let candidate = URL(fileURLWithPath: arguments[2], isDirectory: true)
        let destination = URL(fileURLWithPath: arguments[3], isDirectory: true)
        guard let pid = pid_t(arguments[4]) else { exit(1) }
        do {
            let deadline = Date().addingTimeInterval(30)
            while kill(pid, 0) == 0, Date() < deadline { usleep(100_000) }
            guard kill(pid, 0) != 0 else { throw CustomUpdaterError.replacementFailed }
            let fm = FileManager.default
            let backup = destination.deletingLastPathComponent()
                .appendingPathComponent(".BinderClip-backup-\(UUID().uuidString).app")
            try fm.moveItem(at: destination, to: backup)
            do {
                try fm.moveItem(at: candidate, to: destination)
                let launch = Process()
                launch.executableURL = URL(fileURLWithPath: "/usr/bin/open")
                launch.arguments = [destination.path]
                try launch.run()
                try? fm.removeItem(at: backup)
                try? fm.removeItem(at: rootURL(for: candidate))
            } catch {
                try? fm.moveItem(at: backup, to: destination)
                throw error
            }
            exit(0)
        } catch {
            logger.error("Replacement failed: \(error.localizedDescription, privacy: .public)")
            exit(1)
        }
    }

    private static func rootURL(for candidate: URL) -> URL {
        candidate.deletingLastPathComponent()
    }
}

private final class FeedParser: NSObject, XMLParserDelegate {
    private var currentElement = ""
    private var currentAttributes: [String: String] = [:]
    private var values: [String: String] = [:]
    private var parsedItem = false

    func parse(_ data: Data) {
        let parser = XMLParser(data: data)
        parser.delegate = self
        parser.parse()
    }

    func item() throws -> UpdateFeedItem? {
        guard parsedItem,
              let version = (values["sparkle:version"] ?? values["version"])?.trimmingCharacters(in: .whitespacesAndNewlines),
              let shortVersion = (values["sparkle:shortVersionString"] ?? values["shortVersionString"])?.trimmingCharacters(in: .whitespacesAndNewlines),
              let build = Int(version), build > 0,
              let urlString = values["url"], let url = URL(string: urlString),
              let lengthString = values["length"], let length = Int(lengthString),
              let signatureString = (values["sparkle:edSignature"] ?? values["edSignature"])?.trimmingCharacters(in: .whitespacesAndNewlines),
              let signature = Data(base64Encoded: signatureString) else { throw CustomUpdaterError.invalidFeed }
        return UpdateFeedItem(version: version, shortVersion: shortVersion, build: build, url: url,
                              length: length, signature: signature)
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String,
                namespaceURI: String?, qualifiedName qName: String?,
                attributes attributeDict: [String: String] = [:]) {
        currentElement = qName ?? elementName
        currentAttributes = attributeDict
        if currentElement == "item" { parsedItem = true }
        if currentElement == "enclosure" { values.merge(attributeDict) { _, new in new } }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        guard !currentElement.isEmpty else { return }
        values[currentElement, default: ""] += string
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String,
                namespaceURI: String?, qualifiedName qName: String?) {
        currentElement = ""
        currentAttributes = [:]
    }
}
