package com.javaclaw.protocol;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ApprovalState;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.DecodedItemPayload;
import com.javaclaw.api.EffectReceipt;
import com.javaclaw.api.EncodedItemPayload;
import com.javaclaw.api.ItemPayload;
import com.javaclaw.api.ItemSchemaRegistry;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolRisk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CoreItemCodecsTest {
    private static final String DIGEST = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void 全部CorePayload由同一Registry规范往返() {
        CanonicalJson json = new CanonicalJson();
        ItemSchemaRegistry registry = CoreItemCodecs.createRegistry(json);
        EffectReceipt receipt = new EffectReceipt("key", "write", DIGEST, DIGEST, NOW);
        List<ItemPayload> payloads = List.of(
                new CorePayloads.Message(MessageRole.USER, "hello", List.of(), Optional.empty()),
                new CorePayloads.ToolCall("call", "core", "read", 1, new CanonicalPayload("{\"path\":\"a\"}")),
                new CorePayloads.ToolResult("call", true, new CanonicalPayload("{\"ok\":true}"), Optional.of(receipt)),
                new CorePayloads.Command("command", List.of("git", "status"), Path.of("workspace"), Optional.of(0)),
                new CorePayloads.FileChange(
                        Path.of("src/Main.java"), "update", Optional.of(DIGEST), Optional.of(DIGEST)),
                new CorePayloads.Approval(
                        "approval", "write", ToolRisk.WORKSPACE_WRITE, ApprovalState.APPROVED, "allowed"),
                new CorePayloads.Input("input", "continue?", false, true),
                new CorePayloads.Subagent(new ThreadId(new UUID(2, 1)), "completed", 100, 50),
                new CorePayloads.Compaction("summary", 500, "summary", Optional.of(DIGEST)),
                receipt,
                new CorePayloads.Error("MODEL", "failed", true, NOW, Map.of("provider", "test")));

        assertEquals(11, registry.snapshot().size());
        for (ItemPayload payload : payloads) {
            EncodedItemPayload encoded = registry.encode(payload);
            DecodedItemPayload.Known decoded = assertInstanceOf(
                    DecodedItemPayload.Known.class, registry.decode(encoded.schemaId(), encoded.payload()));
            assertEquals(payload, decoded.value());
        }
    }
}
