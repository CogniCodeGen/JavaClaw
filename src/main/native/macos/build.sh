#!/usr/bin/env bash
set -euo pipefail

# The caller may pass ApplicationHome/runtime/native/macos/libjavaclaw_desktop.dylib
# when assembling a portable distribution. The default stays in this repo's target/.
source_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd "$source_dir/../../../.." && pwd)"
output="${1:-$project_dir/target/native/macos/libjavaclaw_desktop.dylib}"
mkdir -p "$(dirname "$output")"

xcrun clang++ \
  -std=c++17 -fobjc-arc -fblocks -mmacosx-version-min=14.0 \
  -arch arm64 -arch x86_64 \
  -Wall -Wextra -Werror -dynamiclib -fvisibility=hidden \
  -Wl,-install_name,@rpath/libjavaclaw_desktop.dylib \
  -I "$project_dir/src/main/native" \
  -framework AppKit -framework ApplicationServices -framework CoreGraphics \
  -framework CoreMedia -framework CoreVideo -framework ScreenCaptureKit \
  "$source_dir/desktop_bridge_mac.mm" -o "$output"

echo "$output"
