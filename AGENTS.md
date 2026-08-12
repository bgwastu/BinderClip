# BinderClip Agent Guide

- Keep changes separated by concern: protocol/features, networking, storage, and tooling.
- Update this file when an essential project convention, build command, or architecture detail changes. Keep it concise.
- macOS debug install: `./macos/debug-install.sh`.
- macOS production package: `./macos/package-release.sh`; GitHub Releases load Sparkle secrets from BWS via `BWS_ACCESS_TOKEN`.
- macOS tests: `swift test --package-path macos/BinderClipMac`.
- Android tests: `./android/gradlew -p android test`.
- Android local Gradle verification uses Java 21 (`JAVA_HOME=$HOME/.local/share/mise/installs/java/21.0.2`); Java 26 fails Android SDK JDK-image transforms.
- Store CI credentials in BWS first; GitHub Actions should use only the BWS access token to load project secrets at runtime. Do not commit or print secret values.
- Do not revert unrelated worktree changes.
