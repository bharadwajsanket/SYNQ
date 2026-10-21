#!/bin/sh
set -eu

SDK_PATH="${SDKROOT:-$(xcrun --sdk macosx --show-sdk-path)}"
CACHE_ROOT="${TMPDIR:-/private/tmp}/synq-swift-build"
CONFIGURATION="${CONFIGURATION:-debug}"
APP_PATH=".build/$CONFIGURATION/SYNQ.app"

env \
  SDKROOT="$SDK_PATH" \
  CLANG_MODULE_CACHE_PATH="$CACHE_ROOT/clang" \
  SWIFTPM_MODULECACHE_OVERRIDE="$CACHE_ROOT/swift" \
  swift build -c "$CONFIGURATION"

BUILD_BIN_DIR="$(swift build -c "$CONFIGURATION" --show-bin-path)"
mkdir -p "$APP_PATH/Contents/MacOS"
mkdir -p "$APP_PATH/Contents/Resources"
if [ -f "$BUILD_BIN_DIR/SYNQMac" ]; then
  cp "$BUILD_BIN_DIR/SYNQMac" "$APP_PATH/Contents/MacOS/SYNQMac"
else
  cp ".build/$CONFIGURATION/SYNQMac" "$APP_PATH/Contents/MacOS/SYNQMac"
fi
cp "Resources/Info.plist" "$APP_PATH/Contents/Info.plist"
for localization in Resources/*.lproj; do
  [ -d "$localization" ] || continue
  cp -R "$localization" "$APP_PATH/Contents/Resources/"
done
python3 ./create-icns.py \
  "Resources/AppIcon.iconset" \
  "$APP_PATH/Contents/Resources/SYNQ.icns"
cp "$APP_PATH/Contents/Resources/SYNQ.icns" "$APP_PATH/Contents/Resources/AppIcon.icns"
cp "$APP_PATH/Contents/Resources/SYNQ.icns" "Resources/SYNQ.icns"

# macOS prefers an app icon compiled into Assets.car, while older releases
# still use SYNQ.icns. Build both from the same reviewed source images.
if xcrun --find actool >/dev/null 2>&1; then
  ASSET_CATALOG="$CACHE_ROOT/SYNQAssets.xcassets"
  APP_ICON_SET="$ASSET_CATALOG/AppIcon.appiconset"
  rm -rf "$ASSET_CATALOG"
  mkdir -p "$APP_ICON_SET"
  cp Resources/AppIcon.iconset/*.png "$APP_ICON_SET/"
  cp Resources/AppIconContents.json "$APP_ICON_SET/Contents.json"
  xcrun actool "$ASSET_CATALOG" \
    --compile "$APP_PATH/Contents/Resources" \
    --platform macosx \
    --minimum-deployment-target 13.0 \
    --app-icon AppIcon \
    --output-partial-info-plist "$CACHE_ROOT/SYNQAppIcon.plist"
  test -f "$APP_PATH/Contents/Resources/Assets.car"
  # Overwrite AppIcon.icns produced by actool with the comprehensive multi-resolution version
  cp "$APP_PATH/Contents/Resources/SYNQ.icns" "$APP_PATH/Contents/Resources/AppIcon.icns"
else
  echo "warning: actool is unavailable; keeping the compatible SYNQ.icns icon only" >&2
fi
if [ -n "${MACOS_SIGNING_IDENTITY:-}" ]; then
  codesign --force --deep --options runtime --timestamp --entitlements Resources/SYNQ.entitlements --sign "$MACOS_SIGNING_IDENTITY" "$APP_PATH"
else
  # Keep an explicit, stable designated requirement for ad-hoc builds. System
  # services such as Notification Center and Local Network privacy otherwise
  # see only a changing code hash after each update and can lose bundle metadata.
  codesign --force --deep --sign - \
    --entitlements Resources/SYNQ.entitlements \
    --requirements '=designated => identifier "dev.synq.mac"' \
    "$APP_PATH"
fi

LSREGISTER="/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister"
if [ -x "$LSREGISTER" ]; then
  "$LSREGISTER" -u "$APP_PATH" >/dev/null 2>&1 || true
  "$LSREGISTER" -f "$APP_PATH" >/dev/null 2>&1 || true
fi

# Enable the Finder service in pbs domain so macOS shows it immediately
defaults write pbs NSServices_Status -dict-add '"dev.synq.mac - SYNQ - sendFilesWithSYNQ"' '{ "enabled_context_menu" = 1; "enabled_services_menu" = 1; }' 2>/dev/null || true

if [ -x "/System/Library/CoreServices/pbs" ]; then
  /System/Library/CoreServices/pbs -update >/dev/null 2>&1 || true
fi

echo "Built $APP_PATH successfully."
