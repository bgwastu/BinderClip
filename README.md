# BinderClip

Clipboard sharing between Android and Mac.

BinderClip syncs text and images over a direct encrypted TCP connection on your LAN or mesh VPN (e.g. Tailscale).

## Features

- End-to-end encrypted (AES-256-GCM) over direct TCP on port 39421
- Text and still images — PNG, JPEG, WebP, HEIC up to 30 MiB
- Android share integration and optional automatic clipboard sync (with root access, or with accessibility permission for non-root)
