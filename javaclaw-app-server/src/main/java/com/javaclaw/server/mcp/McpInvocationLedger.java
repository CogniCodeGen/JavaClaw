package com.javaclaw.server.mcp;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpFrozenTool;
import com.javaclaw.api.McpInvocationRequest;
import com.javaclaw.api.McpInvocationResult;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.mcp.McpInvocationRepository.InvocationState;
import com.javaclaw.server.mcp.McpInvocationRepository.StoredInvocation;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2Transactions;
import com.javaclaw.server.persistence.PersistenceException;

/**
 * MCP Tool 外部副作用的 intent-first 恢复账本。
 *
 * <p><strong>不变量：</strong>调用前先提交 IN_FLIGHT；只有明确响应才提交 COMPLETED。进程启动会把残留 IN_FLIGHT 收口为
 * UNKNOWN_OUTCOME，相同幂等键永不自动重试，避免重复外部副作用。
 */
final class McpInvocationLedger {
    private final H2Transactions transactions;
    private final McpInvocationRepository invocations = new McpInvocationRepository();
    private final CanonicalJson json;
    private final Clock clock;

    McpInvocationLedger(H2Database database, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        execute(connection -> {
            invocations.recoverUnknown(connection, clock.instant());
            return null;
        });
    }

    Optional<McpInvocationResult> begin(
            McpEndpoint endpoint, McpInvocationRequest request, CanonicalPayload arguments) {
        IntentFingerprint fingerprint = fingerprint(endpoint, request.tool(), arguments);
        return execute(connection -> {
            Optional<StoredInvocation> existing = invocations.find(connection, request.idempotencyKey(), true);
            if (existing.isPresent()) {
                return recover(existing.orElseThrow(), fingerprint);
            }
            var now = clock.instant();
            invocations.insert(
                    connection,
                    new StoredInvocation(
                            request.idempotencyKey(),
                            endpoint.id(),
                            endpoint.revision(),
                            endpoint.catalogRevision(),
                            request.tool().name(),
                            json.encode(fingerprint).sha256(),
                            InvocationState.IN_FLIGHT,
                            Optional.empty(),
                            now,
                            now));
            return Optional.empty();
        });
    }

    void complete(String idempotencyKey, McpInvocationResult result) {
        execute(connection -> {
            invocations.finish(
                    connection,
                    idempotencyKey,
                    InvocationState.IN_FLIGHT,
                    InvocationState.COMPLETED,
                    Optional.of(json.encode(result)),
                    clock.instant());
            return null;
        });
    }

    void unknown(String idempotencyKey) {
        execute(connection -> {
            Optional<StoredInvocation> current = invocations.find(connection, idempotencyKey, true);
            if (current.isPresent() && current.orElseThrow().state() == InvocationState.IN_FLIGHT) {
                invocations.finish(
                        connection,
                        idempotencyKey,
                        InvocationState.IN_FLIGHT,
                        InvocationState.UNKNOWN_OUTCOME,
                        Optional.empty(),
                        clock.instant());
            }
            return null;
        });
    }

    private Optional<McpInvocationResult> recover(StoredInvocation stored, IntentFingerprint expected) {
        String digest = json.encode(expected).sha256();
        if (!stored.endpointId().equals(expected.endpointId())
                || stored.endpointRevision() != expected.endpointRevision()
                || stored.catalogRevision() != expected.catalogRevision()
                || !stored.toolName().equals(expected.tool().name())
                || !stored.requestDigest().equals(digest)) {
            throw PersistenceException.idempotencyConflict("MCP 幂等键已绑定其他 Tool 调用");
        }
        if (stored.state() == InvocationState.COMPLETED) {
            return Optional.of(json.decode(stored.result().orElseThrow(), McpInvocationResult.class));
        }
        throw new PersistenceException("MCP Tool 调用结果未知（UNKNOWN_OUTCOME），禁止自动重试");
    }

    private IntentFingerprint fingerprint(McpEndpoint endpoint, McpFrozenTool tool, CanonicalPayload arguments) {
        return new IntentFingerprint(
                endpoint.id(), endpoint.revision(), endpoint.catalogRevision(), tool, arguments.sha256());
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (PersistenceException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("MCP invocation 账本失败", failure);
        }
    }

    private record IntentFingerprint(
            String endpointId,
            long endpointRevision,
            long catalogRevision,
            McpFrozenTool tool,
            String argumentsDigest) {}
}
