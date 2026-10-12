package com.javaclaw.config;

/**
 * 敏感配置的加密边界。
 *
 * <p>实现必须线程安全；{@link #encrypt(String)} 对已加密值幂等，不能在失败时返回明文；
 * {@link #decrypt(String)} 无法恢复密文时必须原样返回，避免上层重存时破坏数据。</p>
 */
public interface CredentialCipher {

    String encrypt(String plainText);

    String decrypt(String encryptedText);

    boolean isEncrypted(String value);

    /** 启动期预热持久主密钥；失败后允许后续调用重试。 */
    void warmUp();
}
