# macOS Development

## Persistent debug install

Run this from the repository root whenever you want to test the newest macOS
code:

```sh
./macos/debug-install.sh
```

The script builds the debug executable, creates `~/Applications/BinderClip
Debug.app`, atomically replaces the previous debug bundle, and launches the
new one. The installed app is separate from any `BinderClip.app` in
`/Applications`.

Updating the bundle does not remove application data. Pairings and settings
remain in the normal macOS Application Support and Keychain locations.

To build without launching:

```sh
./macos/debug-install.sh --no-launch
```

## Production releases and updates

Production releases are built by `.github/workflows/macos-release.yml` when a
GitHub Release is published. The workflow creates `BinderClip-<version>.dmg`,
an update ZIP, and a Sparkle `appcast.xml`; Sparkle checks the appcast and can
download and install newer releases automatically.

Before publishing the first release, create one Sparkle Ed25519 key pair with
Sparkle's `generate_keys`. CI installs the pinned BWS CLI and loads both values
at release time from the BWS `Sparkle` project using only the
`BWS_ACCESS_TOKEN` GitHub Actions secret. Keep the private key in BWS and never
commit or print it.

Android releases are built by `.github/workflows/android-release.yml` from the
same GitHub Release tag. CI loads `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, and `ANDROID_KEYSTORE_ALIAS` from the BWS
`Android Signing` project. The key
password defaults to the keystore password, so no separate
`ANDROID_KEY_PASSWORD` or `ANDROID_KEY_ALIAS` secret is required. In
Obtainium, add `https://github.com/bgwastu/BinderClip`; it will find the APK
attached to each release and use its increasing Android version code.

This workflow intentionally does not notarize or Developer ID sign the app.
It ad-hoc signs the app and Sparkle signs update archives, so Gatekeeper may
block a fresh download. Users must use the existing Gatekeeper exception or
right-click the app and choose Open once. Sparkle updates can then be applied
without reinstalling manually. This is less safe and less convenient than
Developer ID signing plus notarization.

The release build is universal for Apple silicon and Intel Macs. Release tags
must be normal semantic versions, for example `v1.0.0`.

The debug executable is signed ad hoc for local use. For live debugging, run
the script with `--no-launch`, then start `~/Applications/BinderClip Debug.app`
from Xcode/LLDB or attach to its process. Diagnostic logs are available from
`~/Library/Application Support/BinderClip/diagnostics.log`.
