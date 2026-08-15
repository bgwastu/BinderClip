# BinderClip Agent Guide

- Keep changes separated by concern: protocol/features, networking, storage, and tooling.
- Update this file when an essential project convention, build command, or architecture detail changes. Keep it concise.
- macOS debug install: `./macos/debug-install.sh`.
- macOS production package: `./macos/package-release.sh`; it bundles Sparkle 2 and creates an ad-hoc signed universal ZIP, DMG, and signed appcast.xml using BWS secrets.
- macOS tests: `swift test --package-path macos/BinderClipMac`.
- Android tests: `./android/gradlew -p android test`.
- Android local Gradle verification uses Java 21 (`JAVA_HOME=$HOME/.local/share/mise/installs/java/21.0.2`); Java 26 fails Android SDK JDK-image transforms.
- BinderClip listens for direct encrypted TCP connections on port `39421`; one authenticated session carries text and one-at-a-time PNG/JPEG/WebP/HEIC images (up to 30 MiB) using AES-GCM frames and a four-chunk, 192 KiB cumulative-ACK media window. It uses a one-time QR invitation and has no legacy transport fallback.
- Clipboard classification is fail-closed: file URLs take precedence over TIFF/icons/text, but only supported image files are sent. All other files are ignored and must never be coerced into a filename string.
- The direct roster is shared with connected members. Removal is enforced by roster admission on future sessions; the group key is intentionally not rotated, so removal does not revoke data or keys already received.
- Android’s share target accepts supported images, copies the received payload to the system clipboard first, then makes one direct delivery attempt. Automatic clipboard sync uses the app-owned root bridge for text/images only after KernelSU/Magisk grants `su`; it then grants BinderClip `READ_CLIPBOARD_IN_BACKGROUND` and revokes it when the toggle turns off. Otherwise it uses the user-enabled `ClipboardAccessibilityService` for text only. That service must remain clipboard-only: no window retrieval, gestures, or accessibility-event interpretation.
- Android background help may request the standard battery-optimization allowlist and open App Info for OEM Auto Start controls. Manufacturer-specific auto-start intents (Xiaomi/MIUI, OPPO/ColorOS, Vivo, Huawei/Honor, OnePlus/Realme) are attempted with `resolveActivity` checks and `try-catch` fallback to `ACTION_APPLICATION_DETAILS_SETTINGS`. Do not hardcode private intents without fallback.
- On macOS, list unresolved Launch at Login, Local Network, and (macOS 15.4+) denied Clipboard Access in the menu. Local Network privacy is tracked reliably in distribution only with an Apple-issued signing identity; do not claim a denied/allowed state before Network.framework reports it.
- Store CI credentials in BWS first; GitHub Actions should use only the BWS access token to load project secrets at runtime. Do not commit or print secret values.
- Do not revert unrelated worktree changes.
