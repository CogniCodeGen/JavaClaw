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

    @Test
    void 无法解密的密文原样返回_非破坏防止重存抹空() {
        // 随机字节拼出的合法格式 ENC 值：任何密钥都解不开（模拟密钥丢失/主机名漂移后的旧密文）。
        // 契约：原样返回 ENC(...) 密文而非空串——返回空串会在上层「重新加密全部凭据回写」时把
        // encrypt("")=="" 覆盖掉好端端的密文，一次瞬时失败即永久毁凭据；返回原密文则密文完好、可自愈
        byte[] junk = new byte[60];
        new java.util.Random(42).nextBytes(junk);
        String bogus = "ENC(" + java.util.Base64.getEncoder().encodeToString(junk) + ")";
        assertEquals(bogus, CredentialEncryptor.decrypt(bogus), "解密失败应原样返回密文（非破坏），而非空串");
        // 且解密失败值经 encrypt() 往返保持不变（isEncrypted 守卫跳过），故重存不会覆盖存量密文
        assertEquals(bogus, CredentialEncryptor.encrypt(CredentialEncryptor.decrypt(bogus)),
                "解密失败的密文重新加密应原样保留");
    }
}
