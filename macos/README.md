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

The debug executable is signed ad hoc for local use. For live debugging, run
the script with `--no-launch`, then start `~/Applications/BinderClip Debug.app`
from Xcode/LLDB or attach to its process. Diagnostic logs are available from
`~/Library/Application Support/BinderClip/diagnostics.log`.
