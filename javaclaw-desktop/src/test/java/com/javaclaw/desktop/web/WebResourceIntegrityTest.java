package com.javaclaw.desktop.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebResourceIntegrityTest {
    @Test
    void 锁定第三方脚本与许可证摘要并提供发行SBOM() throws Exception {
        String manifest = new String(resource("SHA256SUMS"), StandardCharsets.UTF_8);
        String bom = new String(resource("web-bom.json"), StandardCharsets.UTF_8);
        for (String line : manifest.lines().toList()) {
            String[] entry = line.split("\\s+", 2);
            String digest = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(resource(entry[1])));
            assertEquals(entry[0], digest, entry[1]);
            if (entry[1].endsWith(".js")) {
                assertTrue(bom.contains(digest));
            }
        }
        assertTrue(bom.contains("CycloneDX"));
        assertTrue(bom.contains("BSD-3-Clause"));
        assertTrue(bom.contains("MIT"));
    }

    private static byte[] resource(String name) throws Exception {
        try (var input = WebSurfaceResources.class.getResourceAsStream("/web/vendor/" + name)) {
            return java.util.Objects.requireNonNull(input).readAllBytes();
        }
    }
}
