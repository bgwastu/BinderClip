#!/bin/zsh

# Build a production BinderClip.app and its distributable DMG.
# This intentionally does not notarize: the resulting app is ad-hoc signed.

set -euo pipefail

SCRIPT_DIR="${0:A:h}"
PACKAGE_DIR="$SCRIPT_DIR/BinderClipMac"
RELEASE_REF="${GITHUB_REF_NAME:-}"
VERSION="${VERSION:-${RELEASE_REF#v}}"
VERSION="${VERSION:-0.0.4}"
BUILD_NUMBER="${BUILD_NUMBER:-${GITHUB_RUN_NUMBER:-1}}"
APP_NAME="BinderClip.app"
APP_PATH="$PWD/$APP_NAME"
OUTPUT_DIR="${OUTPUT_DIR:-$PWD/dist}"
DMG_PATH="$OUTPUT_DIR/BinderClip-$VERSION.dmg"
BUILD_ROOT="$PACKAGE_DIR/.build/release-package"

if [[ ! "$VERSION" =~ '^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$' ]]; then
  print -u2 "VERSION must be a release version such as 1.0.0"
  exit 2
fi
if [[ ! "$BUILD_NUMBER" =~ '^[1-9][0-9]*$' ]]; then
  print -u2 "BUILD_NUMBER must be a positive integer"
  exit 2
fi

SPARKLE_PUBLIC_ED_KEY="${SPARKLE_PUBLIC_ED_KEY:-}"
[[ -n "$SPARKLE_PUBLIC_ED_KEY" ]] || {
  print -u2 "SPARKLE_PUBLIC_ED_KEY is required for a production build"
  exit 2
}
print "Building BinderClip $VERSION..."
rm -rf "$BUILD_ROOT"
swift build --package-path "$PACKAGE_DIR" --configuration release \
  --triple arm64-apple-macosx13.0 --scratch-path "$BUILD_ROOT/arm64"
swift build --package-path "$PACKAGE_DIR" --configuration release \
  --triple x86_64-apple-macosx13.0 --scratch-path "$BUILD_ROOT/x86_64"

ARM_EXECUTABLE="$BUILD_ROOT/arm64/release/BinderClip"
INTEL_EXECUTABLE="$BUILD_ROOT/x86_64/release/BinderClip"
[[ -x "$ARM_EXECUTABLE" && -x "$INTEL_EXECUTABLE" ]] || {
  print -u2 "Universal build did not produce both BinderClip architectures"
  exit 1
}

rm -rf "$APP_PATH" "$OUTPUT_DIR"
mkdir -p "$APP_PATH/Contents/MacOS" "$APP_PATH/Contents/Resources" "$OUTPUT_DIR"
mkdir -p "$APP_PATH/Contents/Frameworks"
lipo -create "$ARM_EXECUTABLE" "$INTEL_EXECUTABLE" \
  -output "$APP_PATH/Contents/MacOS/BinderClip"
SPARKLE_FRAMEWORK="$BUILD_ROOT/arm64/artifacts/sparkle/Sparkle/Sparkle.xcframework/macos-arm64_x86_64/Sparkle.framework"
[[ -d "$SPARKLE_FRAMEWORK" ]] || {
  print -u2 "Sparkle.framework was not produced at $SPARKLE_FRAMEWORK"
  exit 1
}
ditto "$SPARKLE_FRAMEWORK" "$APP_PATH/Contents/Frameworks/Sparkle.framework"
cp "$PACKAGE_DIR/Resources/AppIcon.icns" "$APP_PATH/Contents/Resources/AppIcon.icns"
cp "$PACKAGE_DIR/Resources/BinderClipMenuIcon.svg" "$APP_PATH/Contents/Resources/BinderClipMenuIcon.svg"
chmod 755 "$APP_PATH/Contents/MacOS/BinderClip"

cat > "$APP_PATH/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleDisplayName</key><string>BinderClip</string>
  <key>CFBundleExecutable</key><string>BinderClip</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundleIdentifier</key><string>net.wastu.binderclip</string>
  <key>CFBundleName</key><string>BinderClip</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>$VERSION</string>
  <key>CFBundleVersion</key><string>$BUILD_NUMBER</string>
  <key>LSMinimumSystemVersion</key><string>13.0</string>
  <key>LSUIElement</key><true/>
  <key>NSBluetoothAlwaysUsageDescription</key><string>BinderClip uses Bluetooth to sync clipboard data with your devices.</string>
  <key>NSLocalNetworkUsageDescription</key><string>BinderClip uses your local network to transfer clipboard images.</string>
  <key>SUPublicEDKey</key><string>$SPARKLE_PUBLIC_ED_KEY</string>
</dict></plist>
PLIST

install_name_tool -add_rpath '@loader_path/../Frameworks' "$APP_PATH/Contents/MacOS/BinderClip"
# The update archive is authenticated separately with the Ed25519 key above.
codesign --force --deep --sign - "$APP_PATH"
codesign --verify --deep --strict "$APP_PATH"
[[ "$(lipo -archs "$APP_PATH/Contents/MacOS/BinderClip")" == *"arm64"* ]] || exit 1
[[ "$(lipo -archs "$APP_PATH/Contents/MacOS/BinderClip")" == *"x86_64"* ]] || exit 1
ditto -c -k --sequesterRsrc --keepParent "$APP_PATH" "$OUTPUT_DIR/BinderClip-$VERSION.zip"

DMG_STAGING="$OUTPUT_DIR/dmg-root"
mkdir -p "$DMG_STAGING"
cp -R "$APP_PATH" "$DMG_STAGING/BinderClip.app"
ln -s /Applications "$DMG_STAGING/Applications"
hdiutil create -volname "BinderClip $VERSION" -srcfolder "$DMG_STAGING" -ov -format UDZO "$DMG_PATH" >/dev/null
rm -rf "$DMG_STAGING" "$APP_PATH"
print "Created $DMG_PATH"
