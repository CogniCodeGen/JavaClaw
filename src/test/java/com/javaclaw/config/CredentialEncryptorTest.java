package com.javaclaw.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link CredentialEncryptor} 加解密往返与明文兼容回归测试（站点密码加密迁移的基础） */
class CredentialEncryptorTest {

    @Test
    void 加密解密往返一致() {
        String plain = "s3cret-密码!@#";
        String enc = CredentialEncryptor.encrypt(plain);
        assertTrue(CredentialEncryptor.isEncrypted(enc), "加密结果应为 ENC(...) 格式");
        assertNotEquals(plain, enc);
        assertEquals(plain, CredentialEncryptor.decrypt(enc));
    }

    @Test
    void 明文解密透传_兼容旧配置() {
        assertEquals("plain-password", CredentialEncryptor.decrypt("plain-password"));
        assertNull(CredentialEncryptor.decrypt(null));
        assertEquals("", CredentialEncryptor.decrypt(""));
    }

    @Test
    void 重复加密幂等_不双重包裹() {
        String enc = CredentialEncryptor.encrypt("abc");
        assertEquals(enc, CredentialEncryptor.encrypt(enc), "已是 ENC 格式的值不应再次加密");
    }

    @Test
    void 空值加密原样返回() {
        assertNull(CredentialEncryptor.encrypt(null));
        assertEquals("", CredentialEncryptor.encrypt(""));
    }
}
