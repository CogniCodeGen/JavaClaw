#!/bin/sh
set -eu
ARTIFACT_DIR=$1
APPLICATION_IDENTITY=$2
INSTALLER_IDENTITY=$3
NOTARY_PROFILE=$4
[ -d "$ARTIFACT_DIR" ] || { echo "native artifact directory is missing" >&2; exit 2; }
FOUND=0
for ARTIFACT in "$ARTIFACT_DIR"/*.dmg "$ARTIFACT_DIR"/*.pkg; do
    [ -f "$ARTIFACT" ] || continue
    FOUND=1
    case "$ARTIFACT" in
        *.pkg)
            SIGNED="$ARTIFACT.signed"
            /usr/bin/productsign --sign "$INSTALLER_IDENTITY" "$ARTIFACT" "$SIGNED"
            /bin/mv -f "$SIGNED" "$ARTIFACT"
            ;;
        *) /usr/bin/codesign --force --timestamp --sign "$APPLICATION_IDENTITY" "$ARTIFACT" ;;
    esac
    /usr/bin/xcrun notarytool submit "$ARTIFACT" \
        --keychain-profile "$NOTARY_PROFILE" --wait
    /usr/bin/xcrun stapler staple "$ARTIFACT"
done
[ "$FOUND" -eq 1 ] || { echo "no macOS installer artifacts found" >&2; exit 2; }
