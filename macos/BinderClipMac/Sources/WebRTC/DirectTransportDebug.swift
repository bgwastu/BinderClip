import Foundation

/// Shared debug logger for the WebRTC transport (DEBUG builds only).
enum DirectTransportDebug {
    #if DEBUG
    static func log(_ message: String) {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("net.wastu.binderclip", isDirectory: true)
            .appendingPathComponent("webrtc-debug.log")
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        if let handle = try? FileHandle(forWritingTo: url) {
            handle.seekToEndOfFile()
            handle.write(Data("[\(Date())] \(message)\n".utf8))
            try? handle.close()
        } else {
            try? Data("[\(Date())] \(message)\n".utf8).write(to: url)
        }
    }
    #else
    static func log(_ message: String) {}
    #endif
}
