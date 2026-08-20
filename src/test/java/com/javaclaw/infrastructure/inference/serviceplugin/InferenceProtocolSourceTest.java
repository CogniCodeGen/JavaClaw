package com.javaclaw.infrastructure.inference.serviceplugin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferenceProtocolSourceTest {

    @Test
    void hostDtosDeclareTheExactCanonicalProtocolRevision() throws Exception {
        Path source = Path.of("src/main/resources/protocol/inference-runtime.yaml");
        byte[] bytes = Files.readAllBytes(source);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

        assertEquals(InferencePluginProtocol.SOURCE_SHA256, hash,
                "协议变更后必须同时重新生成 Host 与服务插件 DTO，并更新双方 source hash");
        String yaml = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        for (String schema : new String[]{"StartupConfig", "ChatRequest",
                "ChatResponse", "EmbeddingRequest", "EmbeddingResponse", "StreamEvent",
                "ErrorEnvelope"}) {
            assertTrue(yaml.contains("    " + schema + ":"), schema);
        }
    }
}
