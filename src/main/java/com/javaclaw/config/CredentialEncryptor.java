package com.javaclaw.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * 敏感凭据加密工具 — 基于 AES-256-GCM 的对称加密
 *
 * <p>使用设备绑定的派生密钥对 API Key、密码等敏感信息进行加密存储。
 * 加密后的字符串以 {@code ENC()} 包裹，便于识别已加密的配置项。</p>
 *
 * <p>密钥派生：用户名 + 主机名 + 固定盐 → PBKDF2 → AES-256 密钥。
 * 每次加密使用随机 IV，保证相同明文产生不同密文。</p>
 *
 * @author JavaClaw
 */
public final class CredentialEncryptor {

    private static final Logger log = LoggerFactory.getLogger(CredentialEncryptor.class);

    /** 加密标识前缀和后缀 */
    private static final String ENC_PREFIX = "ENC(";
    private static final String ENC_SUFFIX = ")";

    /** AES-GCM 参数 */
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;
    private static final int SALT_LENGTH = 16;
    private static final int KEY_LENGTH = 256;
    private static final int PBKDF2_ITERATIONS = 65536;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private CredentialEncryptor() {
    }

    /**
     * 判断值是否已加密
     */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX) && value.endsWith(ENC_SUFFIX);
    }

    /**
     * 加密明文。如果已经是加密格式则原样返回。
     *
     * @param plainText 明文
     * @return ENC(base64密文) 格式字符串，明文为空时原样返回
     */
    public static String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank() || isEncrypted(plainText)) {
            return plainText;
        }
        try {
            byte[] salt = new byte[SALT_LENGTH];
            SECURE_RANDOM.nextBytes(salt);

            byte[] iv = new byte[IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);

            SecretKey key = deriveKey(salt);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // 格式：salt(16) + iv(12) + cipherText
            ByteBuffer buffer = ByteBuffer.allocate(SALT_LENGTH + IV_LENGTH + cipherText.length);
            buffer.put(salt);
            buffer.put(iv);
            buffer.put(cipherText);

            return ENC_PREFIX + Base64.getEncoder().encodeToString(buffer.array()) + ENC_SUFFIX;
        } catch (Exception e) {
            log.error("加密失败，将使用明文存储", e);
            return plainText;
        }
    }

    /**
     * 解密密文。如果不是加密格式则原样返回（兼容旧的明文配置）。
     *
     * @param encryptedText ENC(base64密文) 格式字符串
     * @return 明文
     */
    public static String decrypt(String encryptedText) {
        if (encryptedText == null || !isEncrypted(encryptedText)) {
            return encryptedText;
        }
        try {
            String base64 = encryptedText.substring(ENC_PREFIX.length(),
                    encryptedText.length() - ENC_SUFFIX.length());
            byte[] decoded = Base64.getDecoder().decode(base64);

            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            byte[] salt = new byte[SALT_LENGTH];
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(salt);
            buffer.get(iv);
            byte[] cipherText = new byte[buffer.remaining()];
            buffer.get(cipherText);

            SecretKey key = deriveKey(salt);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] plainText = cipher.doFinal(cipherText);

            return new String(plainText, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("解密失败，返回原文（可能是旧版明文配置）", e);
            // 解密失败时返回原文，兼容未加密的旧配置
            return encryptedText;
        }
    }

    /**
     * 从盐值派生 AES-256 密钥（基于设备信息绑定）
     */
    private static SecretKey deriveKey(byte[] salt) throws Exception {
        String passphrase = System.getProperty("user.name", "javaclaw")
                + "@" + getHostName()
                + "#JavaClaw";
        KeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * 获取主机名（用于密钥派生）
     */
    private static String getHostName() {
        try {
            Path hostnamePath = Path.of("/etc/hostname");
            if (Files.exists(hostnamePath)) {
                return Files.readString(hostnamePath).trim();
            }
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }
}
