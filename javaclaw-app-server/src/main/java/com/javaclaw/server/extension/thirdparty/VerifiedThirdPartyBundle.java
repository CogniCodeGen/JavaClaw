package com.javaclaw.server.extension.thirdparty;

import java.nio.file.Path;
import java.util.Objects;

/** 已通过签名、清单和逐文件摘要校验的 Bundle 目录。 */
record VerifiedThirdPartyBundle(
        ThirdPartyBundleManifest manifest, String manifestDigest, String signingKeyFingerprint, Path root) {
    VerifiedThirdPartyBundle {
        Objects.requireNonNull(manifest, "manifest");
        if (manifestDigest == null || !manifestDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("manifestDigest must be SHA-256");
        }
        if (signingKeyFingerprint == null || !signingKeyFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("signingKeyFingerprint must be SHA-256");
        }
        root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }
}
