#!/bin/zsh

# Build and install the macOS debug app in a stable per-user location.
# This deliberately uses a separate bundle identifier and app name so it
# cannot replace a production install in /Applications.

set -euo pipefail

SCRIPT_DIR="${0:A:h}"
PACKAGE_DIR="$SCRIPT_DIR/BinderClipMac"
APP_NAME="BinderClip Debug.app"
BUNDLE_ID="net.wastu.clipboard.debug"
INSTALL_DIR="$HOME/Applications"
INSTALL_PATH="$INSTALL_DIR/$APP_NAME"
STAGING_PATH="$INSTALL_DIR/.BinderClip Debug.app.new"
BACKUP_PATH="$INSTALL_DIR/.BinderClip Debug.app.previous"

usage() {
  print "Usage: $0 [--no-launch]"
  print "Build and replace ~/Applications/$APP_NAME, then launch it."
}

launch=true
if [[ "${1:-}" == "--no-launch" ]]; then
  launch=false
elif [[ $# -gt 0 ]]; then
  usage >&2
  exit 2
fi

print "Building BinderClip debug executable..."
swift build --package-path "$PACKAGE_DIR" --configuration debug

EXECUTABLE="$PACKAGE_DIR/.build/debug/BinderClip"
[[ -x "$EXECUTABLE" ]] || { print -u2 "Build did not produce $EXECUTABLE"; exit 1; }

mkdir -p "$INSTALL_DIR"
rm -rf "$STAGING_PATH"
mkdir -p "$STAGING_PATH/Contents/MacOS" "$STAGING_PATH/Contents/Resources"

cp "$EXECUTABLE" "$STAGING_PATH/Contents/MacOS/BinderClip"
chmod 755 "$STAGING_PATH/Contents/MacOS/BinderClip"
cp "$PACKAGE_DIR/Resources/AppIcon.icns" "$STAGING_PATH/Contents/Resources/AppIcon.icns"
cp "$PACKAGE_DIR/Resources/StatusBarBinderFilled.png" "$STAGING_PATH/Contents/Resources/StatusBarBinderFilled.png"
cp "$PACKAGE_DIR/Resources/StatusBarBinderOutline.png" "$STAGING_PATH/Contents/Resources/StatusBarBinderOutline.png"
cp "$PACKAGE_DIR/Resources/StatusBarIcon.png" "$STAGING_PATH/Contents/Resources/StatusBarIcon.png"
cp "$PACKAGE_DIR/Resources/StatusBarIcon@2x.png" "$STAGING_PATH/Contents/Resources/StatusBarIcon@2x.png"

cat > "$STAGING_PATH/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleDisplayName</key>
  <string>BinderClip Debug</string>
  <key>CFBundleExecutable</key>
  <string>BinderClip</string>
  <key>CFBundleIconFile</key>
  <string>AppIcon</string>
  <key>CFBundleIdentifier</key>
  <string>$BUNDLE_ID</string>
  <key>CFBundleName</key>
  <string>BinderClip Debug</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>CFBundleShortVersionString</key>
  <string>0.0.0-debug</string>
  <key>CFBundleVersion</key>
  <string>$(date +%s)</string>
  <key>LSMinimumSystemVersion</key>
  <string>13.0</string>
  <key>LSUIElement</key>
  <true/>
  <key>NSBluetoothAlwaysUsageDescription</key>
  <string>BinderClip uses Bluetooth to sync clipboard data with your devices.</string>
  <key>NSLocalNetworkUsageDescription</key>
  <string>BinderClip uses your local network to transfer clipboard images.</string>
</dict>
</plist>
PLIST

codesign --force --deep --sign - "$STAGING_PATH" >/dev/null

if pgrep -f "$INSTALL_PATH/Contents/MacOS/BinderClip" >/dev/null 2>&1; then
  print "Stopping the previous debug app..."
  osascript -e "tell application id \"$BUNDLE_ID\" to quit" >/dev/null 2>&1 || true
  sleep 1
fi

rm -rf "$BACKUP_PATH"
if [[ -d "$INSTALL_PATH" ]]; then
  mv "$INSTALL_PATH" "$BACKUP_PATH"
fi
if ! mv "$STAGING_PATH" "$INSTALL_PATH"; then
  [[ -d "$BACKUP_PATH" ]] && mv "$BACKUP_PATH" "$INSTALL_PATH"
  exit 1
fi
rm -rf "$BACKUP_PATH"

print "Installed: $INSTALL_PATH"
if $launch; then
  open "$INSTALL_PATH"
fi
