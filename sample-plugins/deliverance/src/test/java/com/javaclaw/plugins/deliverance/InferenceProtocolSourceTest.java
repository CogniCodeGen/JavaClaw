package com.javaclaw.plugins.deliverance;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InferenceProtocolSourceTest {

    @Test
    void servicePluginDtosDeclareTheExactCanonicalProtocolRevision() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of(
                "../../src/main/resources/protocol/inference-runtime.yaml"));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(Protocol.SOURCE_SHA256, hash,
                "协议变更后必须重新生成服务插件 DTO 并更新 source hash");
    }
}
