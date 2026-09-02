package com.javaclaw.server.mcp;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointSpec;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpFrozenTool;
import com.javaclaw.api.McpInvocationRequest;
import com.javaclaw.api.McpInvocationResult;
import com.javaclaw.api.McpTransport;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.PersistenceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpInvocationLedgerTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final String SCHEMA_HASH = "b".repeat(64);

    @TempDir
    Path temporaryDirectory;

    private CanonicalJson json;
    private H2Database database;
    private McpInvocationLedger ledger;

    @BeforeEach
    void 初始化空白调用账本() {
        json = new CanonicalJson();
        database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        ledger = new McpInvocationLedger(database, json, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void 明确完成结果可按相同幂等身份恢复且不会重放() {
        McpEndpoint endpoint = endpoint("docs", 2, 3);
        McpInvocationRequest request = request("effect-1", "docs", 2, 3, "search", "{\"q\":\"v5\"}");
        assertTrue(ledger.begin(endpoint, request, request.arguments()).isEmpty());

        McpInvocationResult result =
                new McpInvocationResult(true, json.parse("{\"content\":[]}"), Optional.of("remote-1"), List.of());
        ledger.complete(request.idempotencyKey(), result);

        assertEquals(
                result, ledger.begin(endpoint, request, request.arguments()).orElseThrow());
        assertThrows(PersistenceException.class, () -> ledger.complete(request.idempotencyKey(), result));
        ledger.unknown(request.idempotencyKey());
        assertEquals(
                result, ledger.begin(endpoint, request, request.arguments()).orElseThrow());
    }

    @Test
    void 未完成调用重启后收口未知并永久禁止自动重试() {
        McpEndpoint endpoint = endpoint("docs", 1, 1);
        McpInvocationRequest request = request("effect-restart", "docs", 1, 1, "lookup", "{}");
        ledger.begin(endpoint, request, request.arguments());

        McpInvocationLedger restarted =
                new McpInvocationLedger(database, json, Clock.fixed(NOW.plusSeconds(5), ZoneOffset.UTC));

        PersistenceException failure =
                assertThrows(PersistenceException.class, () -> restarted.begin(endpoint, request, request.arguments()));
        assertTrue(failure.getMessage().contains("UNKNOWN_OUTCOME"));
    }

    @Test
    void 显式未知结果只转换进行中调用且不存在键保持幂等() {
        McpEndpoint endpoint = endpoint("docs", 1, 1);
        McpInvocationRequest request = request("effect-unknown", "docs", 1, 1, "lookup", "{}");
        ledger.unknown("missing-effect");
        ledger.begin(endpoint, request, request.arguments());

        ledger.unknown(request.idempotencyKey());
        ledger.unknown(request.idempotencyKey());

        assertThrows(PersistenceException.class, () -> ledger.begin(endpoint, request, request.arguments()));
    }

    @Test
    void 同一幂等键拒绝Endpoint目录Tool与参数的任意漂移() {
        McpEndpoint endpoint = endpoint("docs", 2, 3);
        McpInvocationRequest original = request("effect-bound", "docs", 2, 3, "search", "{\"q\":\"v5\"}");
        ledger.begin(endpoint, original, original.arguments());

        assertConflict(endpoint("other", 2, 3), request("effect-bound", "other", 2, 3, "search", "{\"q\":\"v5\"}"));
        assertConflict(endpoint("docs", 4, 3), request("effect-bound", "docs", 4, 3, "search", "{\"q\":\"v5\"}"));
        assertConflict(endpoint("docs", 2, 5), request("effect-bound", "docs", 2, 5, "search", "{\"q\":\"v5\"}"));
        assertConflict(endpoint, request("effect-bound", "docs", 2, 3, "other-tool", "{\"q\":\"v5\"}"));
        assertConflict(endpoint, request("effect-bound", "docs", 2, 3, "search", "{\"q\":\"changed\"}"));
    }

    private void assertConflict(McpEndpoint endpoint, McpInvocationRequest request) {
        PersistenceException failure =
                assertThrows(PersistenceException.class, () -> ledger.begin(endpoint, request, request.arguments()));
        assertTrue(failure.getMessage().contains("幂等键"));
    }

    private McpInvocationRequest request(
            String key, String endpointId, long endpointRevision, long catalogRevision, String name, String arguments) {
        McpFrozenTool tool = new McpFrozenTool(endpointId, endpointRevision, catalogRevision, name, SCHEMA_HASH);
        return new McpInvocationRequest(tool, json.parse(arguments), key);
    }

    private static McpEndpoint endpoint(String id, long revision, long catalogRevision) {
        McpEndpointSpec spec = new McpEndpointSpec(
                WorkspaceId.random(),
                "Docs",
                McpTransport.STREAMABLE_HTTPS,
                Optional.of(URI.create("https://mcp.example/rpc")),
                Optional.empty(),
                McpAuthType.NONE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(10));
        return new McpEndpoint(id, revision, McpEndpointState.ENABLED, catalogRevision, spec, NOW, NOW);
    }
}
