package com.javaclaw.protocol;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** X25519、HKDF 与 AES-GCM 的共享无状态实现。 */
final class SessionSecretCryptography {
    static final int NONCE_BYTES = 12;
    static final int MAX_PLAINTEXT_BYTES = 4 * 1024 * 1024;
    private static final int GCM_TAG_BITS = 128;
    private static final byte[] HKDF_LABEL = "javaclaw/session-secret/v1".getBytes(StandardCharsets.US_ASCII);

    private SessionSecretCryptography() {}

    static KeyPair keyPair(SecureRandom random) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("X25519");
            generator.initialize(NamedParameterSpec.X25519, random);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException failure) {
            throw new SecretSealingException("无法创建会话 Secret 密钥", failure);
        }
    }

    static PublicKey publicKey(String encoded) {
        byte[] bytes = decode(encoded, 256, "public key");
        try {
            return KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(bytes));
        } catch (GeneralSecurityException failure) {
            throw new SecretSealingException("会话 Secret 公钥无效", failure);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static byte[] decode(String value, int maxBytes, String name) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (decoded.length == 0 || decoded.length > maxBytes) {
                Arrays.fill(decoded, (byte) 0);
                throw new SecretSealingException(name + " size is invalid");
            }
            return decoded;
        } catch (IllegalArgumentException failure) {
            if (failure instanceof SecretSealingException sealing) {
                throw sealing;
            }
            throw new SecretSealingException(name + " encoding is invalid", failure);
        }
    }

    static byte[] derive(Key localKey, PublicKey peerKey, String keyId, String purpose) {
        byte[] shared = agreement(localKey, peerKey);
        byte[] salt = digest((SessionKeyInfo.ALGORITHM + '\u0000' + keyId).getBytes(StandardCharsets.UTF_8));
        byte[] info = info(purpose);
        byte[] prk = hmac(salt, shared);
        byte[] expanded = Arrays.copyOf(info, info.length + 1);
        expanded[expanded.length - 1] = 1;
        try {
            return hmac(prk, expanded);
        } finally {
            Arrays.fill(shared, (byte) 0);
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(info, (byte) 0);
            Arrays.fill(prk, (byte) 0);
            Arrays.fill(expanded, (byte) 0);
        }
    }

    static byte[] encrypt(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext) {
        return crypt(Cipher.ENCRYPT_MODE, key, nonce, aad, plaintext);
    }

    static byte[] decrypt(byte[] key, byte[] nonce, byte[] aad, byte[] ciphertext) {
        try {
            return crypt(Cipher.DECRYPT_MODE, key, nonce, aad, ciphertext);
        } catch (SecretSealingException failure) {
            throw new SecretSealingException("SealedSecret 完整性校验失败", failure);
        }
    }

    static byte[] aad(String keyId, String purpose, String ephemeralPublicKey) {
        return ("javaclaw/session-secret/v1\u0000" + keyId + '\u0000' + purpose + '\u0000' + ephemeralPublicKey)
                .getBytes(StandardCharsets.UTF_8);
    }

    static byte[] utf8(char[] secret) {
        char[] characters = secret.clone();
        if (characters.length == 0 || characters.length > MAX_PLAINTEXT_BYTES) {
            Arrays.fill(characters, '\u0000');
            throw new IllegalArgumentException("Secret size is outside the supported range");
        }
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(characters));
        byte[] result = new byte[encoded.remaining()];
        encoded.get(result);
        Arrays.fill(characters, '\u0000');
        if (encoded.hasArray()) {
            Arrays.fill(encoded.array(), (byte) 0);
        }
        if (result.length == 0 || result.length > MAX_PLAINTEXT_BYTES) {
            Arrays.fill(result, (byte) 0);
            throw new IllegalArgumentException("UTF-8 Secret size is outside the supported range");
        }
        return result;
    }

    static String fingerprint(SealedSecret sealed) {
        byte[] material = (sealed.keyId()
                        + '\u0000'
                        + sealed.purpose()
                        + '\u0000'
                        + sealed.ephemeralPublicKey()
                        + '\u0000'
                        + sealed.nonce()
                        + '\u0000'
                        + sealed.ciphertext())
                .getBytes(StandardCharsets.US_ASCII);
        try {
            return java.util.HexFormat.of().formatHex(digest(material));
        } finally {
            Arrays.fill(material, (byte) 0);
        }
    }

    private static byte[] crypt(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException failure) {
            throw new SecretSealingException("无法处理 SealedSecret", failure);
        }
    }

    private static byte[] agreement(Key localKey, PublicKey peerKey) {
        try {
            KeyAgreement agreement = KeyAgreement.getInstance("X25519");
            agreement.init(localKey);
            agreement.doPhase(peerKey, true);
            return agreement.generateSecret();
        } catch (GeneralSecurityException failure) {
            throw new SecretSealingException("无法协商会话 Secret 密钥", failure);
        }
    }

    private static byte[] hmac(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (GeneralSecurityException failure) {
            throw new SecretSealingException("无法派生会话 Secret 密钥", failure);
        }
    }

    private static byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static byte[] info(String purpose) {
        byte[] purposeBytes = purpose.getBytes(StandardCharsets.UTF_8);
        byte[] info = Arrays.copyOf(HKDF_LABEL, HKDF_LABEL.length + 1 + purposeBytes.length);
        System.arraycopy(purposeBytes, 0, info, HKDF_LABEL.length + 1, purposeBytes.length);
        Arrays.fill(purposeBytes, (byte) 0);
        return info;
    }
}
