#!/bin/sh
set -eu
ARTIFACT_DIR=$1
SIGNING_KEY=$2
PASSPHRASE=$3
[ -d "$ARTIFACT_DIR" ] || { echo "native artifact directory is missing" >&2; exit 2; }
FOUND=0
for ARTIFACT in "$ARTIFACT_DIR"/*.deb "$ARTIFACT_DIR"/*.rpm; do
    [ -f "$ARTIFACT" ] || continue
    FOUND=1
    gpg --batch --yes --pinentry-mode loopback --passphrase "$PASSPHRASE" \
        --armor --detach-sign --local-user "$SIGNING_KEY" \
        --output "$ARTIFACT.asc" "$ARTIFACT"
    gpg --batch --verify "$ARTIFACT.asc" "$ARTIFACT"
done
[ "$FOUND" -eq 1 ] || { echo "no Linux package artifacts found" >&2; exit 2; }
