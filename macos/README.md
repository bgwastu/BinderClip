# macOS BinderClip

BinderClip is a direct menu-bar clipboard utility. It listens on TCP port
`39421`, advertises reachable LAN/mesh-VPN addresses in a short-lived QR code,
and encrypts pairing plus clipboard payloads end to end. It supports text and
one still image at a time (PNG, JPEG, WebP, or HEIC, up to 10 MiB and 16 MP).
Images use the same authenticated connection as text, are chunked with ACKs,
and are never persisted for retry. It has no relay, account, or Bluetooth fallback,
and integrates Sparkle 2 for signed auto-updates.

## Debug install

From the repository root:

```sh
./macos/debug-install.sh
```

This atomically replaces `~/Applications/BinderClip Debug.app`. Pairing secrets
live in an owner-only file under `~/Library/Application Support/net.wastu.binderclip`;
the app does not use Keychain, so local debug builds do not prompt for Keychain access.

## Tests and package

```sh
swift test --package-path macos/BinderClipMac
./macos/package-release.sh
```

The release script creates an ad-hoc-signed universal app, ZIP, and DMG in
`dist/`. Supply `VERSION` and `BUILD_NUMBER` to override their defaults.
