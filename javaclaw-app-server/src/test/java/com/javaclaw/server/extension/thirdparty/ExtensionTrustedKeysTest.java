package com.javaclaw.server.extension.thirdparty;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExtensionTrustedKeysTest {
    @Test
    void 持久记录目录验证Ed25519并支持实时撤销() throws Exception {
        KeyPair keys = ThirdPartyBundleTestFixtures.keyPair();
        byte[] message = "manifest".getBytes(StandardCharsets.UTF_8);
        byte[] signature = sign(keys, message);
        ExtensionTrustedKeys trusted = ThirdPartyBundleTestFixtures.trustedKeys(keys);

        trusted.verify(ThirdPartyBundleTestFixtures.KEY_ID, message, signature);
        trusted.revoke(ThirdPartyBundleTestFixtures.KEY_ID);

        assertThrows(
                SecurityException.class, () -> trusted.verify(ThirdPartyBundleTestFixtures.KEY_ID, message, signature));
        assertThrows(SecurityException.class, () -> trusted.verify("unknown", message, signature));
    }

    @Test
    void attachment解码Der或Base64并拒绝非法和无界内容() {
        byte[] encoded = ThirdPartyBundleTestFixtures.keyPair().getPublic().getEncoded();

        ExtensionTrustedKeys.DecodedKey direct = ExtensionTrustedKeys.decodeAttachment(encoded);
        ExtensionTrustedKeys.DecodedKey base64 =
                ExtensionTrustedKeys.decodeAttachment(Base64.getEncoder().encode(encoded));

        assertArrayEquals(encoded, direct.encoded());
        assertArrayEquals(encoded, base64.encoded());
        assertEquals(ThirdPartyBundleTestFixtures.digest(encoded), direct.fingerprint());
        assertThrows(IllegalArgumentException.class, () -> ExtensionTrustedKeys.decodeAttachment(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> ExtensionTrustedKeys.decodeAttachment(new byte[4097]));
        assertThrows(
                IllegalArgumentException.class,
                () -> ExtensionTrustedKeys.decodeAttachment("not-base64".getBytes(StandardCharsets.US_ASCII)));
    }

    private static byte[] sign(KeyPair keys, byte[] message) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keys.getPrivate());
        signature.update(message);
        return signature.sign();
    }
}
