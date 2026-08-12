// App entry point: single-instance guard, smoke-test CLI dispatch, and NSApplication bootstrap.

import AppKit
import os

if CustomUpdater.runHelperIfRequested(arguments: CommandLine.arguments) {
    exit(0)
}

// Ignore SIGPIPE globally so broken TCP sockets return EPIPE errors
// instead of killing the process. Without this, a peer resetting a
// connection during image transfer crashes the app.
signal(SIGPIPE, SIG_IGN)

private let bootstrapLogger = Logger(subsystem: "net.wastu.clipboard", category: "Bootstrap")

private func hasAnotherRunningInstance() -> Bool {
    guard let bundleID = Bundle.main.bundleIdentifier else { return false }
    let currentPID = ProcessInfo.processInfo.processIdentifier
    return NSRunningApplication
        .runningApplications(withBundleIdentifier: bundleID)
        .contains { $0.processIdentifier != currentPID }
}

#if DEBUG
if let exitCode = SmokeAutomationCLI.runIfRequested(arguments: CommandLine.arguments) {
    exit(exitCode)
}
#endif

if hasAnotherRunningInstance() {
    bootstrapLogger.error("Another BinderClip instance detected; refusing secondary launch")
    exit(0)
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.setActivationPolicy(.accessory)
app.delegate = delegate
app.run()
