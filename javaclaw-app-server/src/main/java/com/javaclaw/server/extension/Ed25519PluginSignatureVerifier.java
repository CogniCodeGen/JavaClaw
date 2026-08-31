package com.javaclaw.server.extension;

import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;

/** JCA Ed25519 verifier backed by the explicit publisher trust store. */
public final class Ed25519PluginSignatureVerifier implements PluginSignatureVerifier {
    private final PluginTrustStore trust;

    /** 绑定公钥信任仓库；仅验证 Ed25519 来源，不批准插件声明权限。 */
    public Ed25519PluginSignatureVerifier(PluginTrustStore trust) {
        this.trust = Objects.requireNonNull(trust, "trust");
    }

    @Override
    public boolean verify(byte[] payload, Path bundleRoot, PluginSignature signature) throws Exception {
        if (!"Ed25519".equals(signature.algorithm())) {
            return false;
        }
        var trusted = trust.find(signature.keyId()).orElse(null);
        if (trusted == null) {
            return false;
        }
        byte[] encodedSignature;
        try {
            encodedSignature = Base64.getDecoder().decode(signature.value());
        } catch (IllegalArgumentException standardFailure) {
            try {
                encodedSignature = Base64.getUrlDecoder().decode(signature.value());
            } catch (IllegalArgumentException urlFailure) {
                return false;
            }
        }
        var publicKey =
                KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(trusted.x509PublicKey()));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(payload);
        return verifier.verify(encodedSignature);
    }
}
