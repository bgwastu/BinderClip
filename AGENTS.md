# BinderClip Agent Guide

- Keep changes separated by concern: protocol/features, networking, storage, and tooling.
- Update this file when an essential project convention, build command, or architecture detail changes. Keep it concise.
- macOS debug install: `./macos/debug-install.sh`.
- macOS tests: `swift test --package-path macos/BinderClipMac`.
- Android tests: `./android/gradlew -p android test`.
- Do not revert unrelated worktree changes.
