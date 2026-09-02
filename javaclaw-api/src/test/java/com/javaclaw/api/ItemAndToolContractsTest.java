package com.javaclaw.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemAndToolContractsTest {
    private static final ItemPayloadCodec<CorePayloads.Input> INPUT_CODEC = new ItemPayloadCodec<>() {
        @Override
        public String schemaId() {
            return CoreSchemas.INPUT;
        }

        @Override
        public Class<CorePayloads.Input> payloadType() {
            return CorePayloads.Input.class;
        }

        @Override
        public CanonicalPayload encode(CorePayloads.Input payload) {
            return new CanonicalPayload("{\"requestId\":\"" + payload.requestId() + "\"}");
        }

        @Override
        public CorePayloads.Input decode(CanonicalPayload payload) {
            return new CorePayloads.Input("decoded", payload.json(), false, true);
        }
    };

    @Test
    void canonicalPayloadAndEnvelopeEnforcePersistentShape() {
        CanonicalPayload payload = ApiFixtures.payload();
        Instant completedAt = ApiFixtures.NOW.plusSeconds(1);
        ItemEnvelope active = new ItemEnvelope(
                ItemId.random(),
                TurnId.random(),
                1,
                "message",
                CoreSchemas.MESSAGE,
                "core",
                ItemStatus.IN_PROGRESS,
                payload,
                ApiFixtures.NOW,
                Optional.empty());
        ItemEnvelope completed = new ItemEnvelope(
                ItemId.random(),
                TurnId.random(),
                2,
                "message",
                CoreSchemas.MESSAGE,
                "core",
                ItemStatus.COMPLETED,
                payload,
                ApiFixtures.NOW,
                Optional.of(completedAt));

        assertEquals(64, payload.sha256().length());
        assertTrue(active.completedAt().isEmpty());
        assertEquals(completedAt, completed.completedAt().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new CanonicalPayload("[]"));
        assertThrows(IllegalArgumentException.class, () -> new CanonicalPayload("{broken"));
    }

    @Test
    void envelopeRejectsCompletionStateMismatches() {
        CanonicalPayload payload = ApiFixtures.payload();
        Instant completedAt = ApiFixtures.NOW.plusSeconds(1);

        assertThrows(
                IllegalArgumentException.class,
                () -> new ItemEnvelope(
                        ItemId.random(),
                        TurnId.random(),
                        1,
                        "message",
                        CoreSchemas.MESSAGE,
                        "core",
                        ItemStatus.COMPLETED,
                        payload,
                        ApiFixtures.NOW,
                        Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ItemEnvelope(
                        ItemId.random(),
                        TurnId.random(),
                        1,
                        "message",
                        CoreSchemas.MESSAGE,
                        "core",
                        ItemStatus.IN_PROGRESS,
                        payload,
                        ApiFixtures.NOW,
                        Optional.of(completedAt)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ItemEnvelope(
                        ItemId.random(),
                        TurnId.random(),
                        1,
                        "message",
                        CoreSchemas.MESSAGE,
                        "core",
                        ItemStatus.COMPLETED,
                        payload,
                        ApiFixtures.NOW,
                        Optional.of(ApiFixtures.NOW.minusSeconds(1))));
    }

    @Test
    void schemaRegistryPreservesUnknownPayloadAndRejectsAmbiguousCodecs() {
        ItemSchemaRegistry registry = new ItemSchemaRegistry();
        CanonicalPayload raw = ApiFixtures.payload();
        DecodedItemPayload.Unknown unknown =
                assertInstanceOf(DecodedItemPayload.Unknown.class, registry.decode("extension/unknown@1", raw));
        CorePayloads.Input input = new CorePayloads.Input("request", "question", false, false);

        assertSame(raw, unknown.payload());
        registry.register(INPUT_CODEC);
        EncodedItemPayload encoded = registry.encode(input);
        DecodedItemPayload.Known known =
                assertInstanceOf(DecodedItemPayload.Known.class, registry.decode(CoreSchemas.INPUT, encoded.payload()));
        assertEquals(CoreSchemas.INPUT, encoded.schemaId());
        assertInstanceOf(CorePayloads.Input.class, known.value());
        assertThrows(
                UnsupportedOperationException.class, () -> registry.snapshot().clear());
        assertThrows(IllegalArgumentException.class, () -> registry.register(INPUT_CODEC));
        assertThrows(
                IllegalArgumentException.class, () -> registry.register(codec("different", CorePayloads.Input.class)));
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.encode(new CorePayloads.Error("code", "message", false, ApiFixtures.NOW, Map.of())));
        registry.unregister(CoreSchemas.INPUT);
        assertTrue(registry.snapshot().isEmpty());
        registry.unregister(CoreSchemas.INPUT);
        assertTrue(registry.snapshot().isEmpty());
        assertThrows(NullPointerException.class, () -> registry.register(null));
    }

    @Test
    void corePayloadsValidateDomainIndependentItemData() {
        CanonicalPayload payload = ApiFixtures.payload();
        ThreadId child = ThreadId.random();
        EffectReceipt receipt =
                new EffectReceipt("key", "tool", ApiFixtures.DIGEST, ApiFixtures.DIGEST, ApiFixtures.NOW);
        CorePayloads.Message message = new CorePayloads.Message(MessageRole.USER, "", List.of(), Optional.empty());
        CorePayloads.Message toolMessage =
                new CorePayloads.Message(MessageRole.TOOL, "result", List.of(), Optional.of(" call "));
        CorePayloads.ToolCall call = new CorePayloads.ToolCall("call", "core", "read", 1, payload);
        CorePayloads.ToolResult result = new CorePayloads.ToolResult("call", true, payload, Optional.of(receipt));
        CorePayloads.Command command =
                new CorePayloads.Command("command", List.of("git", "status"), Path.of("a/../work"), Optional.empty());
        CorePayloads.FileChange change = new CorePayloads.FileChange(
                Path.of("src/Main.java"), "update", Optional.of(" before "), Optional.empty());
        CorePayloads.Approval approval = new CorePayloads.Approval(
                "approval", "write", ToolRisk.WORKSPACE_WRITE, ApprovalState.PENDING, "needs review");
        CorePayloads.Input input = new CorePayloads.Input("input", "continue?", false, true);
        CorePayloads.Subagent subagent = new CorePayloads.Subagent(child, "running", 10, 20);
        CorePayloads.Compaction compaction =
                new CorePayloads.Compaction("summary", 30, "summary", Optional.of(" digest "));
        CorePayloads.Error error =
                new CorePayloads.Error("failed", "safe message", true, ApiFixtures.NOW, Map.of("part", "tool"));

        assertEquals("", message.text());
        assertEquals("call", toolMessage.toolCallId().orElseThrow());
        assertEquals(1, call.toolRevision());
        assertSame(receipt, result.receipt().orElseThrow());
        assertEquals(Path.of("work"), command.workingDirectory());
        assertEquals("before", change.beforeDigest().orElseThrow());
        assertEquals(ApprovalState.PENDING, approval.state());
        assertTrue(input.answered());
        assertEquals(child, subagent.childThreadId());
        assertEquals("digest", compaction.providerStateDigest().orElseThrow());
        assertEquals("tool", error.details().get("part"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CorePayloads.Message(MessageRole.USER, "x", List.of(), Optional.of("call")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CorePayloads.Command("x", List.of(), Path.of("."), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CorePayloads.Command("x", List.of(" "), Path.of("."), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CorePayloads.FileChange(Path.of("/tmp/x"), "update", Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CorePayloads.FileChange(Path.of("../x"), "update", Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CorePayloads.FileChange(Path.of("x"), "update", Optional.of(" "), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new CorePayloads.Subagent(child, "running", -1, 0));
    }

    @Test
    void frozenToolCatalogSearchesOnlyCapturedDefinitions() {
        ToolDescriptor alpha = ApiFixtures.tool("alpha", "read files", Set.of("filesystem"));
        ToolDescriptor beta = ApiFixtures.tool("beta", "query records", Set.of("database"));
        ToolDescriptor gamma = ApiFixtures.tool("gamma", "browser helper", Set.of("Web"));
        ToolCatalogSnapshot catalog = new ToolCatalogSnapshot(
                TurnId.random(), 7, List.of(gamma, beta, alpha), ApiFixtures.profile("default", 1), ApiFixtures.NOW);

        assertEquals(
                List.of("alpha", "beta", "gamma"),
                catalog.tools().stream().map(tool -> tool.identity().name()).toList());
        assertEquals(alpha, catalog.search("ALP", 10).getFirst());
        assertEquals(beta, catalog.search("records", 10).getFirst());
        assertEquals(gamma, catalog.search("web", 10).getFirst());
        assertTrue(catalog.search("missing", 10).isEmpty());
        assertEquals(1, catalog.search("a", 1).size());
        assertSame(alpha, catalog.require(alpha.identity()));
        assertThrows(IllegalArgumentException.class, () -> catalog.search("x", 0));
        assertThrows(IllegalArgumentException.class, () -> catalog.search("x", 101));
        assertThrows(IllegalArgumentException.class, () -> catalog.require(new ToolIdentity("core", "alpha", 2)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolCatalogSnapshot(
                        TurnId.random(),
                        1,
                        List.of(alpha, ApiFixtures.tool("alpha", "duplicate", Set.of())),
                        ApiFixtures.profile("default", 1),
                        ApiFixtures.NOW));
    }

    @Test
    void toolCallContractsKeepIdentityRevisionAndNormalizedTags() {
        ToolIdentity identity = new ToolIdentity(" core ", "read_file", 2);
        ToolDescriptor descriptor = new ToolDescriptor(
                identity,
                " read a file ",
                ApiFixtures.payload(),
                ApiFixtures.payload(),
                ToolRisk.READ_ONLY,
                Set.of(" FILE "));
        ToolCallRequest request =
                new ToolCallRequest(TurnId.random(), " call ", identity, ApiFixtures.payload(), " idempotent ", 3);
        ToolCallResult result = new ToolCallResult(" call ", true, ApiFixtures.payload(), Optional.empty());
        EncodedItemPayload encoded = new EncodedItemPayload(CoreSchemas.INPUT, ApiFixtures.payload());
        DecodedItemPayload.Known known = new DecodedItemPayload.Known(
                CoreSchemas.INPUT, new CorePayloads.Input("request", "question", false, false));

        assertEquals("core", identity.producerId());
        assertEquals(Set.of("file"), descriptor.tags());
        assertEquals("call", request.callId());
        assertEquals("idempotent", request.idempotencyKey());
        assertEquals("call", result.callId());
        assertEquals(CoreSchemas.INPUT, encoded.schemaId());
        assertEquals(CoreSchemas.INPUT, known.schemaId());
        assertEquals(CoreTools.SEARCH_NAME, CoreTools.search().identity().name());
        assertEquals(
                CoreTools.WORKTREE_APPLY_NAME,
                CoreTools.worktreeApply().identity().name());
        assertEquals(ToolRisk.WORKSPACE_WRITE, CoreTools.worktreeApply().risk());
        assertThrows(IllegalArgumentException.class, () -> new ToolIdentity("core", "bad name", 1));
    }

    private static <T extends ItemPayload> ItemPayloadCodec<T> codec(String schemaId, Class<T> type) {
        return new ItemPayloadCodec<>() {
            @Override
            public String schemaId() {
                return schemaId;
            }

            @Override
            public Class<T> payloadType() {
                return type;
            }

            @Override
            public CanonicalPayload encode(T payload) {
                return ApiFixtures.payload();
            }

            @Override
            public T decode(CanonicalPayload payload) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
