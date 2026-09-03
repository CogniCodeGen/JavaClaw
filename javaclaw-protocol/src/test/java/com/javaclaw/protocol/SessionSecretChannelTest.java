package com.javaclaw.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionSecretChannelTest {
    @Test
    void X25519封装往返且wire中没有明文() {
        try (SessionSecretChannel channel = SessionSecretChannel.open()) {
            char[] source = "密钥-provider-2026".toCharArray();
            SealedSecret sealed = SessionSecretSealer.seal(channel.publicKey(), "credential/provider/create", source);

            byte[] opened = channel.unseal(sealed, "credential/provider/create");
            try {
                assertArrayEquals(new String(source).getBytes(StandardCharsets.UTF_8), opened);
                String wire = new CanonicalJson().encode(sealed).json();
                assertFalse(wire.contains(new String(source)));
            } finally {
                Arrays.fill(opened, (byte) 0);
                Arrays.fill(source, '\u0000');
            }
        }
    }

    @Test
    void 每次使用临时密钥和随机nonce() {
        try (SessionSecretChannel channel = SessionSecretChannel.open()) {
            SealedSecret first =
                    SessionSecretSealer.seal(channel.publicKey(), "credential/mcp/create", "same-secret".toCharArray());
            SealedSecret second =
                    SessionSecretSealer.seal(channel.publicKey(), "credential/mcp/create", "same-secret".toCharArray());

            assertNotEquals(first.ephemeralPublicKey(), second.ephemeralPublicKey());
            assertNotEquals(first.nonce(), second.nonce());
            assertNotEquals(first.ciphertext(), second.ciphertext());
        }
    }

    @Test
    void purpose会话篡改和成功后的replay都被拒绝() {
        try (SessionSecretChannel first = SessionSecretChannel.open();
                SessionSecretChannel second = SessionSecretChannel.open()) {
            SealedSecret sealed =
                    SessionSecretSealer.seal(first.publicKey(), "credential/site/rotate", "storage".toCharArray());

            assertThrows(SecretSealingException.class, () -> first.unseal(sealed, "credential/site/create"));
            assertThrows(SecretSealingException.class, () -> second.unseal(sealed, "credential/site/rotate"));
            SealedSecret tampered = new SealedSecret(
                    sealed.keyId(),
                    sealed.purpose(),
                    sealed.ephemeralPublicKey(),
                    sealed.nonce(),
                    flip(sealed.ciphertext()));
            assertThrows(SecretSealingException.class, () -> first.unseal(tampered, "credential/site/rotate"));

            byte[] opened = first.unseal(sealed, "credential/site/rotate");
            Arrays.fill(opened, (byte) 0);
            assertThrows(SecretSealingException.class, () -> first.unseal(sealed, "credential/site/rotate"));
        }
    }

    @Test
    void 关闭和非法wire形状不能继续解封() {
        SessionSecretChannel channel = SessionSecretChannel.open();
        SessionKeyInfo info = channel.publicKey();
        SealedSecret sealed = SessionSecretSealer.seal(info, "credential/oauth/create", "token".toCharArray());
        channel.close();

        assertThrows(SecretSealingException.class, channel::publicKey);
        assertThrows(SecretSealingException.class, () -> channel.unseal(sealed, "credential/oauth/create"));
        assertThrows(IllegalArgumentException.class, () -> new SessionKeyInfo("RSA", "key", info.encodedPublicKey()));
        assertThrows(IllegalArgumentException.class, () -> new SealedSecret("../key", "purpose", "a", "a", "a"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SessionSecretSealer.seal(info, "credential/oauth/create", new char[0]));
    }

    @Test
    void 连接关闭回调只执行一次且关闭后注册会立即执行() {
        SessionSecretChannel channel = SessionSecretChannel.open();
        AtomicInteger callbacks = new AtomicInteger();
        assertFalse(channel.isClosed());
        channel.onClose(callbacks::incrementAndGet);

        channel.close();
        channel.close();
        channel.onClose(callbacks::incrementAndGet);

        assertTrue(channel.isClosed());
        assertEquals(2, callbacks.get());
    }

    private static String flip(String encoded) {
        char first = encoded.charAt(0);
        char replacement = first == 'A' ? 'B' : 'A';
        return replacement + encoded.substring(1);
    }
}
