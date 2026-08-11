package com.javaclaw.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * 基于 AES-256-GCM 的进程级凭据加密服务。
 *
 * <p>主密钥保存在当前 3.0 数据库的 {@code app_state} 表，并由数据库主键保证首次创建互斥。
 * 每个根 Spring Context 拥有独立实例与缓存，不读取静态数据库指针。每个值使用随机盐和 IV；
 * 旧版设备派生口令仅用于解密兼容，任何加密都必须取得持久主密钥。</p>
 *
 * <p>实例线程安全。解密失败时原样返回 {@code ENC(...)}，防止调用方保存空值后不可逆地
 * 覆盖原密文；加密失败则抛异常，绝不降级保存明文。</p>
 */
public final class CredentialEncryptor implements CredentialCipher {

    private static final Logger log = LoggerFactory.getLogger(CredentialEncryptor.class);
    private static final String ENC_PREFIX = "ENC(";
    private static final String ENC_SUFFIX = ")";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;
    private static final int SALT_LENGTH = 16;
    private static final int KEY_LENGTH = 256;
    private static final int PBKDF2_ITERATIONS = 65_536;
    private static final String KEY_STATE_KEY = "credential.master.key";

    private final JdbcTemplate jdbc;
    private final SecureRandom random;
    private final Object masterKeyLock = new Object();
    private volatile String cachedMasterSecret;

    public CredentialEncryptor(JdbcTemplate jdbc) {
        this(jdbc, new SecureRandom());
    }

    CredentialEncryptor(JdbcTemplate jdbc, SecureRandom random) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public void warmUp() {
        try {
            masterPassphrase();
            log.info("凭据主密钥已预热（持久密钥已缓存）");
        } catch (RuntimeException failure) {
            log.warn("凭据主密钥预热失败，将在首次使用时重试：{}", failure.getMessage());
        }
    }

    @Override
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX) && value.endsWith(ENC_SUFFIX);
    }

    @Override
    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank() || isEncrypted(plainText)) {
            return plainText;
        }
        try {
            byte[] salt = randomBytes(SALT_LENGTH);
            byte[] iv = randomBytes(IV_LENGTH);
            SecretKey key = deriveKey(salt, masterPassphrase());
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            ByteBuffer payload = ByteBuffer.allocate(salt.length + iv.length + encrypted.length);
            payload.put(salt).put(iv).put(encrypted);
            return ENC_PREFIX + Base64.getEncoder().encodeToString(payload.array()) + ENC_SUFFIX;
        } catch (Exception failure) {
            log.error("凭据加密失败，已拒绝返回明文", failure);
            throw new IllegalStateException("凭据加密失败，未保存明文", failure);
        }
    }

    @Override
    public String decrypt(String encryptedText) {
        if (encryptedText == null || !isEncrypted(encryptedText)) {
            return encryptedText;
        }
        EncryptedPayload payload;
        try {
            payload = decode(encryptedText);
        } catch (RuntimeException malformed) {
            log.error("密文格式损坏，已原样保留（请重新填写该凭据）", malformed);
            return encryptedText;
        }

        try {
            return decrypt(payload, masterPassphrase());
        } catch (Exception primaryFailure) {
            try {
                String plain = decrypt(payload, legacyPassphrase());
                log.info("凭据经旧版设备密钥解密成功；重新保存后将迁移到数据库主密钥");
                return plain;
            } catch (Exception legacyFailure) {
                log.error("凭据解密失败，已原样保留密文；跨机迁移时请确认数据库主密钥完整",
                        primaryFailure);
                return encryptedText;
            }
        }
    }

    private String masterPassphrase() {
        String cached = cachedMasterSecret;
        if (cached != null) {
            return cached;
        }
        synchronized (masterKeyLock) {
            if (cachedMasterSecret != null) {
                return cachedMasterSecret;
            }
            String existing = readMasterKey();
            if (existing != null && !existing.isBlank()) {
                cachedMasterSecret = existing;
                return existing;
            }
            String candidate = Base64.getEncoder().encodeToString(randomBytes(32));
            String selected = insertOrAdoptMasterKey(candidate);
            cachedMasterSecret = selected;
            log.info("已在 H2 创建凭据主密钥");
            return selected;
        }
    }

    private String readMasterKey() {
        List<String> values = jdbc.query(
                "SELECT state_value FROM app_state WHERE state_key = ?",
                (row, index) -> row.getString("state_value"), KEY_STATE_KEY);
        if (values.isEmpty()) {
            return null;
        }
        String value = values.getFirst();
        return value == null ? null : value.strip();
    }

    /** 插入失败时读取并发胜者；若没有胜者则保留原始数据库异常。 */
    private String insertOrAdoptMasterKey(String candidate) {
        try {
            jdbc.update("""
                            INSERT INTO app_state(state_key, state_value, updated_at)
                            VALUES (?, ?, CURRENT_TIMESTAMP)
                            """,
                    KEY_STATE_KEY, candidate);
            return candidate;
        } catch (DataAccessException insertFailure) {
            try {
                String winner = readMasterKey();
                if (winner != null && !winner.isBlank()) {
                    log.info("凭据主密钥已由并发方创建，采用数据库中的胜者密钥");
                    return winner;
                }
            } catch (DataAccessException readFailure) {
                insertFailure.addSuppressed(readFailure);
            }
            throw insertFailure;
        }
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    private static EncryptedPayload decode(String encryptedText) {
        String encoded = encryptedText.substring(
                ENC_PREFIX.length(), encryptedText.length() - ENC_SUFFIX.length());
        ByteBuffer payload = ByteBuffer.wrap(Base64.getDecoder().decode(encoded));
        if (payload.remaining() <= SALT_LENGTH + IV_LENGTH) {
            throw new IllegalArgumentException("密文载荷长度不足");
        }
        byte[] salt = new byte[SALT_LENGTH];
        byte[] iv = new byte[IV_LENGTH];
        payload.get(salt).get(iv);
        byte[] cipherText = new byte[payload.remaining()];
        payload.get(cipherText);
        return new EncryptedPayload(salt, iv, cipherText);
    }

    private static String decrypt(EncryptedPayload payload, String passphrase) throws Exception {
        SecretKey key = deriveKey(payload.salt(), passphrase);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(GCM_TAG_LENGTH, payload.iv()));
        return new String(cipher.doFinal(payload.cipherText()), StandardCharsets.UTF_8);
    }

    private static SecretKey deriveKey(byte[] salt, String passphrase) throws Exception {
        PBEKeySpec specification = new PBEKeySpec(
                passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return new SecretKeySpec(factory.generateSecret(specification).getEncoded(), "AES");
        } finally {
            specification.clearPassword();
        }
    }

    private static String legacyPassphrase() {
        return System.getProperty("user.name", "javaclaw")
                + "@" + hostName() + "#JavaClaw";
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return "localhost";
        }
    }

    private record EncryptedPayload(byte[] salt, byte[] iv, byte[] cipherText) {
    }
}
