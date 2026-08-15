# BinderClip design system

BinderClip is an operating tool, not a dashboard. Each platform uses its native UI language: Material 3 on Android and a compact macOS menu-bar panel. The interface answers three things immediately: whether the group is connected, which trusted devices exist, and how to pair or manually send the current clipboard.

- Color is restrained: native dynamic/system surfaces with one BinderClip teal action role; connection state uses standard success/warning/error roles.
- Typography uses platform system type scales. Labels are plain language, never protocol vocabulary.
- Lists are the primary structure; controls use native buttons, switches, dialogs, and notifications.
- Pairing is a protected task: a short-lived QR, clear expiry, and an explicit “trusted device” explanation.
- Android shows unresolved permissions inline and hides them once granted. Automatic Sync Clipboard explains and selects the available method: approved root for text/images, otherwise user-enabled Accessibility for copied text. macOS pairing is a focused QR panel with a live countdown and automatic QR replacement; unresolved Launch at Login appears as an action only until enabled.
