# BinderClip

Clipboard sharing between Android and Mac.

BinderClip syncs text and images over a persistent WebSocket on your LAN or mesh VPN (for example Tailscale). The Mac always hosts; Android always connects.

## Features

- Pair by scanning a QR from the Mac menu bar (shared 256-bit PSK as the access gate)
- Text and still images — PNG, JPEG, WebP, HEIC up to 30 MiB
- Android share integration and optional automatic clipboard sync (with root access, or with accessibility permission for non-root)
