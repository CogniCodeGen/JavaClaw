package com.javaclaw.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptPreviewRpcContractsTest {
    @Test
    void 预览参数经过规范Json往返保持精确引用() {
        CanonicalJson json = new CanonicalJson();
        ProviderProfileRpcContracts.PromptPreviewPayload payload = new ProviderProfileRpcContracts.PromptPreviewPayload(
                WorkspaceId.parse("61e9d496-0798-49d8-a58e-f2337058e382"), new AgentProfileRef("default", 3));

        assertEquals(
                payload, json.decode(json.encode(payload), ProviderProfileRpcContracts.PromptPreviewPayload.class));
        assertEquals(
                RpcMethodKind.QUERY,
                MethodCatalog.require("profile/prompt/preview", new NegotiatedCapabilities(Set.of(), Set.of()))
                        .kind());
    }

    @Test
    void 发布逐方法Schema且不声明项目约定正文() throws IOException {
        String schema = read("/schema/prompt-preview-v2.schema.json");
        String methods = read("/schema/methods-v2.json");

        assertTrue(methods.contains("prompt-preview-v2.schema.json#/$defs/previewParams"));
        assertTrue(methods.contains("prompt-preview-v2.schema.json#/$defs/preview"));
        assertTrue(schema.contains("\"manifestDigest\""));
        assertTrue(schema.contains("\"estimatedInputTokens\""));
        assertFalse(schema.contains("projectInstructionContent"));
        assertFalse(schema.contains("skillContent"));
    }

    private static String read(String resource) throws IOException {
        try (var stream = PromptPreviewRpcContractsTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
