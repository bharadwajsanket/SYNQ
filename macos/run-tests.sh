#!/bin/sh
set -eu

XCODE_DIR="/Applications/Xcode.app/Contents/Developer"
XCODE_PLUGINS="$XCODE_DIR/Platforms/MacOSX.platform/Developer/usr/lib/swift/host/plugins"
XCODE_FRAMEWORKS="$XCODE_DIR/Platforms/MacOSX.platform/Developer/Library/Frameworks"
XCODE_USRLIB="$XCODE_DIR/Platforms/MacOSX.platform/Developer/usr/lib"

echo "==> Compiling SYNQMac unit tests..."
swift build --build-tests \
  -Xswiftc -plugin-path -Xswiftc "$XCODE_PLUGINS" \
  -Xswiftc -F -Xswiftc "$XCODE_FRAMEWORKS" \
  -Xswiftc -I -Xswiftc "$XCODE_USRLIB" \
  -Xlinker -F -Xlinker "$XCODE_FRAMEWORKS" \
  -Xlinker -L -Xlinker "$XCODE_USRLIB" \
  -Xlinker -rpath -Xlinker "$XCODE_FRAMEWORKS" \
  -Xlinker -rpath -Xlinker "$XCODE_USRLIB" \
  -Xlinker -framework -Xlinker XCTest

echo "==> Running SYNQMac test bundle..."
"$XCODE_DIR/usr/bin/xctest" .build/out/Products/Debug/SYNQMacTests.xctest
