#!/usr/bin/env bash
set -euo pipefail

# Run with OpenJDK jextract 25. Checked-in sources keep ordinary Maven builds
# independent of jextract and make changes to the C ABI reviewable.
native_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd "$native_dir/../../.." && pwd)"
mode="${1:-check}"
jextract_bin="${JEXTRACT_BIN:-jextract}"

if [[ "$mode" != generate && "$mode" != check ]]; then
  echo "usage: JEXTRACT_BIN=/path/to/jextract $0 [generate|check]" >&2
  exit 2
fi
if [[ "$("$jextract_bin" --version 2>&1 | head -n 1)" != "jextract 25" ]]; then
  echo "This bridge requires OpenJDK jextract 25 and JDK 25." >&2
  exit 2
fi

mkdir -p "$project_dir/target"
scratch="$(mktemp -d "$project_dir/target/jextract.XXXXXX")"
trap 'rm -rf "$scratch"' EXIT

"$jextract_bin" "@$native_dir/desktop_bridge.jextract.args" \
  --output "$scratch" \
  --target-package com.javaclaw.desktop.nativebridge.generated \
  --header-class-name desktop_bridge_h \
  "$native_dir/desktop_bridge.h"

generated="$scratch/com/javaclaw/desktop/nativebridge/generated"
checked_in="$project_dir/src/main/java/com/javaclaw/desktop/nativebridge/generated"
shared="$generated/desktop_bridge_h\$shared.java"
header_java="$generated/desktop_bridge_h.java"

# jextract emits an unused C_LONG constant with the host's C long width.
# The ABI itself uses only fixed-width integers. Make that unused constant
# platform-neutral so the same checked-in classes work on Windows (LLP64)
# and macOS (LP64); fail if a future generator changes the expected line.
awk '
  /public static final ValueLayout\.Of(Long|Int) C_LONG =/ {
    print "    public static final MemoryLayout C_LONG = Linker.nativeLinker().canonicalLayouts().get(\"long\");"
    matches++
    next
  }
  { print }
  END { if (matches != 1) exit 1 }
' "$shared" > "$shared.portable"
mv "$shared.portable" "$shared"

# Do not let generated wrappers fall back to loader/default lookup. The bridge
# first resolves the exact, managed library path and provides its lookup here.
awk '
  /static final SymbolLookup SYMBOL_LOOKUP = SymbolLookup.loaderLookup\(\)/ {
    if ((getline fallback) != 1 || fallback !~ /\.or\(Linker.nativeLinker\(\).defaultLookup\(\)\);/) exit 1
    print "    static final SymbolLookup SYMBOL_LOOKUP ="
    print "            com.javaclaw.desktop.nativebridge.DesktopBridge.nativeLookup();"
    matches++
    next
  }
  { print }
  END { if (matches != 1) exit 1 }
' "$header_java" > "$header_java.trusted"
mv "$header_java.trusted" "$header_java"

files=(desktop_bridge_h.java 'desktop_bridge_h$shared.java' \
       jc_desktop_action.java jc_desktop_element.java jc_desktop_frame.java jc_desktop_window.java)
if [[ "$mode" == generate ]]; then
  mkdir -p "$checked_in"
  for file in "${files[@]}"; do
    cp "$generated/$file" "$checked_in/$file"
  done
  echo "Updated jextract bindings in $checked_in"
else
  for file in "${files[@]}"; do
    diff -u "$checked_in/$file" "$generated/$file"
  done
  echo "jextract bindings match desktop_bridge.h"
fi
