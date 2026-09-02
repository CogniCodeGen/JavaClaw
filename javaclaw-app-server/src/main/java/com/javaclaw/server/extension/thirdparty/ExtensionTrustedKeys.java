package com.javaclaw.server.extension.thirdparty;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.javaclaw.protocol.BundleRpcContracts;
import com.javaclaw.server.persistence.ExtensionTrustKeyRecord;

/** 仅从 H2 v5 Trust Key 仓储加载的 Ed25519 实时验证目录。 */
final class ExtensionTrustedKeys {
    private static final int MAX_KEY_BYTES = 4096;
    private final ConcurrentHashMap<String, KeyEntry> keys = new ConcurrentHashMap<>();

    private ExtensionTrustedKeys() {}

    static ExtensionTrustedKeys from(List<ExtensionTrustKeyRecord> records) {
        ExtensionTrustedKeys trusted = new ExtensionTrustedKeys();
        records.stream()
                .filter(record -> record.metadata().state() == BundleRpcContracts.TrustState.ACTIVE)
                .forEach(trusted::trust);
        return trusted;
    }

    void trust(ExtensionTrustKeyRecord record) {
        Objects.requireNonNull(record, "record");
        if (record.metadata().state() != BundleRpcContracts.TrustState.ACTIVE) {
            throw new IllegalArgumentException("only ACTIVE Trust Key can enter verifier catalog");
        }
        PublicKey key = decodeDer(record.encodedKey());
        String actual = fingerprint(key.getEncoded());
        if (!actual.equals(record.metadata().fingerprint())) {
            throw new SecurityException("Trust Key fingerprint differs from persisted public key");
        }
        keys.put(record.metadata().id(), new KeyEntry(key, actual));
    }

    void revoke(String keyId) {
        keys.remove(requireKeyId(keyId));
    }

    String fingerprint(String keyId) {
        KeyEntry key = keys.get(requireKeyId(keyId));
        if (key == null) {
            throw new SecurityException("extension signing key is not trusted: " + keyId);
        }
        return key.fingerprint();
    }

    void verify(String keyId, byte[] message, byte[] signature) {
        KeyEntry entry = keys.get(requireKeyId(keyId));
        if (entry == null) {
            throw new SecurityException("extension signing key is not trusted: " + keyId);
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(entry.key());
            verifier.update(Objects.requireNonNull(message, "message"));
            if (!verifier.verify(Objects.requireNonNull(signature, "signature"))) {
                throw new SecurityException("extension manifest signature is invalid");
            }
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Ed25519 verification is unavailable", failure);
        }
    }

    static DecodedKey decodeAttachment(byte[] content) {
        byte[] source = Objects.requireNonNull(content, "content").clone();
        if (source.length < 1 || source.length > MAX_KEY_BYTES) {
            throw new IllegalArgumentException("Trust Key attachment size is invalid");
        }
        PublicKey key;
        try {
            key = decodeDer(source);
        } catch (IllegalArgumentException directFailure) {
            try {
                byte[] decoded = Base64.getDecoder().decode(new String(source, StandardCharsets.US_ASCII).strip());
                key = decodeDer(decoded);
            } catch (IllegalArgumentException encodedFailure) {
                encodedFailure.addSuppressed(directFailure);
                throw new IllegalArgumentException(
                        "Trust Key attachment is not Ed25519 DER or Base64 DER", encodedFailure);
            }
        }
        byte[] encoded = key.getEncoded();
        return new DecodedKey(encoded, fingerprint(encoded));
    }

    private static PublicKey decodeDer(byte[] encoded) {
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
            if (!"EdDSA".equalsIgnoreCase(key.getAlgorithm()) && !"Ed25519".equalsIgnoreCase(key.getAlgorithm())) {
                throw new IllegalArgumentException("Trust Key algorithm is not Ed25519");
            }
            return key;
        } catch (GeneralSecurityException failure) {
            throw new IllegalArgumentException("Trust Key DER is invalid", failure);
        }
    }

    private static String fingerprint(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String requireKeyId(String keyId) {
        if (keyId == null || !keyId.matches("[A-Za-z0-9._-]{1,80}")) {
            throw new IllegalArgumentException("invalid extension signing key id");
        }
        return keyId;
    }

    record DecodedKey(byte[] encoded, String fingerprint) {
        DecodedKey {
            encoded = encoded.clone();
            Objects.requireNonNull(fingerprint, "fingerprint");
        }

        @Override
        public byte[] encoded() {
            return encoded.clone();
        }
    }

    private record KeyEntry(PublicKey key, String fingerprint) {
        private KeyEntry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(fingerprint, "fingerprint");
        }
    }
}
