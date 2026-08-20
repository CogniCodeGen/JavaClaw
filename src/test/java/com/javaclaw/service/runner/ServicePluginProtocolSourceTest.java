package com.javaclaw.service.runner;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServicePluginProtocolSourceTest {
    @Test
    void runnerDtosMatchCanonicalProtocol() throws Exception {
        byte[] bytes = Files.readAllBytes(
                Path.of("src/main/resources/protocol/service-plugin.yaml"));
        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(ServicePluginWire.SOURCE_SHA256, hash,
                "协议变更后必须重新生成 Runner DTO 并更新 source hash");
    }
}
