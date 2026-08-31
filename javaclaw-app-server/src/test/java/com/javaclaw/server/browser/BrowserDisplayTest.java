package com.javaclaw.server.browser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserDisplayTest {
    @TempDir
    Path temporary;

    @Test
    void refusesRemoteDisplayAndDoesNotReadArbitraryAuthorityWhenDisplayIsUnavailable() throws Exception {
        assertTrue(BrowserDisplay.discover(
                        "Linux", Map.of("DISPLAY", "evil.test:0", "XAUTHORITY", "/must-not-read"), temporary)
                .environment()
                .isEmpty());
        assertTrue(BrowserDisplay.discover("Linux", Map.of("DISPLAY", ":99"), temporary)
                .environment()
                .isEmpty());
        assertTrue(
                BrowserDisplay.discover("Windows", Map.of("DISPLAY", ":99", "XAUTHORITY", "/must-not-read"), temporary)
                        .environment()
                        .isEmpty());
    }

    @Test
    void copiesOnlyTheCurrentLocalCookieAndRejectsTruncatedOrOversizedAuthority() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var writer = new DataOutputStream(bytes)) {
            cookie(writer, 256, "98", (byte) 1);
            cookie(writer, 0, "99", (byte) 2);
            cookie(writer, 256, "99", (byte) 3);
        }
        byte[] source = bytes.toByteArray();
        byte[] selected = BrowserDisplay.selectAuthority(source, "99");
        assertEquals(0, java.util.Arrays.compare(source, new byte[source.length]), "临时认证缓冲必须清空");
        try (var input = new DataInputStream(new ByteArrayInputStream(selected))) {
            assertEquals(65535, input.readUnsignedShort());
            input.skipNBytes(input.readUnsignedShort());
            assertEquals("99", new String(input.readNBytes(input.readUnsignedShort()), StandardCharsets.US_ASCII));
            input.skipNBytes(input.readUnsignedShort());
            assertEquals(16, input.readUnsignedShort());
            assertEquals(3, input.readByte());
            input.skipNBytes(15);
            assertEquals(-1, input.read());
        }
        assertThrows(java.io.IOException.class, () -> BrowserDisplay.selectAuthority(new byte[] {0}, "99"));
        assertThrows(java.io.IOException.class, () -> BrowserDisplay.selectAuthority(new byte[65_537], "99"));
    }

    private static void cookie(DataOutputStream writer, int family, String number, byte value) throws Exception {
        writer.writeShort(family);
        byte[] token = new byte[16];
        java.util.Arrays.fill(token, value);
        for (byte[] field : new byte[][] {
            "localhost".getBytes(StandardCharsets.US_ASCII),
            number.getBytes(StandardCharsets.US_ASCII),
            "MIT-MAGIC-COOKIE-1".getBytes(StandardCharsets.US_ASCII),
            token
        }) {
            writer.writeShort(field.length);
            writer.write(field);
        }
    }
}
