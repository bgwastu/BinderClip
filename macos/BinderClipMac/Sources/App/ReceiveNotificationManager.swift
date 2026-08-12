// Posts macOS user notifications when clipboard text is received from Android.

import Foundation
import UserNotifications

final class ReceiveNotificationManager {
    func requestAuthorization(completion: (() -> Void)? = nil) {
        // UNUserNotificationCenter requires a valid bundle identifier and crashes
        // with an NSAssertion if one is absent (e.g. when running the raw debug
        // binary outside an .app bundle).
        guard Bundle.main.bundleIdentifier != nil else { return }
        DispatchQueue.main.async {
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in
                DispatchQueue.main.async {
                    completion?()
                }
            }
        }
    }

    func refreshPermissionStatus(_ completion: @escaping (UNAuthorizationStatus) -> Void) {
        guard Bundle.main.bundleIdentifier != nil else { return }
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            DispatchQueue.main.async {
                completion(settings.authorizationStatus)
            }
        }
    }

    func postClipboardReceived(text: String) {
        guard Bundle.main.bundleIdentifier != nil else { return }
        let preview = String(text.prefix(80))
        let content = UNMutableNotificationContent()
        content.title = "BinderClip received from Android"
        content.body = preview
        content.sound = .default

        let request = UNNotificationRequest(
            identifier: "clipboard-received",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }
}
