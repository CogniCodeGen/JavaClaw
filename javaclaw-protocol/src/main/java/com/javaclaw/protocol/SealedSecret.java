package com.javaclaw.protocol;

/**
 * 只允许当前 App Server 会话解封的 Secret wire envelope。
 *
 * <p>所有字段都可以安全序列化；Secret 明文绝不能作为相邻字段、日志或错误消息传输。
 *
 * @param keyId initialize 返回的会话 key id
 * @param purpose 服务端预期的精确写入用途
 * @param ephemeralPublicKey Base64URL 编码的临时 X.509 X25519 公钥
 * @param nonce Base64URL 编码的 96-bit GCM nonce
 * @param ciphertext Base64URL 编码且带认证标签的密文
 */
public record SealedSecret(String keyId, String purpose, String ephemeralPublicKey, String nonce, String ciphertext) {
    /** 校验有界 wire 形状；认证与用途绑定由解封器完成。 */
    public SealedSecret {
        keyId = SessionKeyInfo.identifier(keyId, "keyId", 120);
        purpose = SessionKeyInfo.identifier(purpose, "purpose", 240);
        ephemeralPublicKey = SessionKeyInfo.base64Url(ephemeralPublicKey, "ephemeralPublicKey", 256);
        nonce = SessionKeyInfo.base64Url(nonce, "nonce", 32);
        ciphertext = SessionKeyInfo.base64Url(ciphertext, "ciphertext", 6_000_000);
    }
}
