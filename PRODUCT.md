# Product

<!-- impeccable:product-schema 1 -->

## Platform

adaptive

## Users

People who work between one or more Android phones and Macs and need text or still images to follow them without handing contents to a cloud service. This record is inferred from the confirmed rewrite brief.

## Product Purpose

BinderClip keeps text and still images moving between a user’s trusted devices over direct, encrypted connections. Success means pairing is quick, connection state is obvious, and a supported clipboard value reaches a reachable peer predictably.

## Positioning

It is a serverless, direct-only clipboard sync group: the product does not operate a relay, account system, cloud clipboard store, or VPN.

## Operating Context

Devices communicate over a shared LAN or an existing routable mesh/VPN such as Tailscale or Cloudflare Mesh. Initial enrollment uses a short-lived QR invitation shown by an already trusted device.

## Capabilities and Constraints

- Text and still images; no general files, folders, Bluetooth, or product-operated relay. Android receives text and supported images from the system share sheet.
- macOS observes and forwards supported local clipboard changes. Android follows platform clipboard privacy rules: it can send the current clipboard from its visible screen, and shows received text or image for an explicit copy when the screen is not visible.
- Still images are single-item PNG, JPEG, WebP, or HEIC payloads up to 30 MiB. They stream over the established encrypted connection in acknowledged chunks. Clipboard URI content is fail-closed: an unsupported file name or Finder thumbnail is never treated as clipboard text or an image.
- A group contains at most eight trusted devices.
- Android automatic clipboard behavior is opt-in. A user-enabled, no-window-inspection Accessibility service syncs copied text on non-root devices; a sideload-only KernelSU/Magisk bridge syncs text and images only after `su` approval. Incoming content always has a notification/manual-copy path.
- No backward compatibility: existing users re-pair.

## Brand Commitments

The existing BinderClip name and icon remain. The operating interface is deliberately small, calm, and transparent about connection and privacy state.

## Evidence on Hand

The repository includes Android and macOS applications, a BinderClip icon, and a rooted Android test device reachable through ADB. No customer, pricing, benchmark, or external-service claims may be fabricated.

## Product Principles

1. Direct routes, never product-operated infrastructure.
2. A device is trusted only through explicit pairing and is visible to every member of the shared device chain.
3. Clipboard values are bounded, authenticated, and fail closed; text and images use the same authenticated direct transport.
4. Remove capabilities that weaken reliability or make state hard to understand.

## Accessibility & Inclusion

Use native Material 3 and macOS controls, system font scaling, and clear semantic connection states. Accessibility is optional and only requested for Android automatic clipboard sync.
