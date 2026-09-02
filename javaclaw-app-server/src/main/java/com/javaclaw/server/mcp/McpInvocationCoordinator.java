package com.javaclaw.server.mcp;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpCatalogEntry;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpFrozenTool;
import com.javaclaw.api.McpInvocationRequest;
import com.javaclaw.api.McpInvocationResult;
import com.javaclaw.api.McpTransport;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.McpCredentialStatusPort;
import com.javaclaw.extension.spi.McpNetworkAuthorizationPort;
import com.javaclaw.extension.spi.McpRemotePort;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CommandLocks;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2Transactions;
import com.javaclaw.server.persistence.PersistenceException;

/** 对冻结 MCP Tool 执行实时端点、目录、Schema、凭据、授权与副作用恢复校验。 */
final class McpInvocationCoordinator {
    private final H2Transactions transactions;
    private final McpEndpointRepository endpoints;
    private final McpCatalogRepository catalogs;
    private final McpRemotePort remote;
    private final McpClientInteractionFactory interactions;
    private final McpNetworkAuthorizationPort network;
    private final McpCredentialStatusPort credentials;
    private final McpInvocationLedger ledger;
    private final CanonicalJson json;
    private final Clock clock;

    McpInvocationCoordinator(
            H2Database database, McpServiceDependencies dependencies, CanonicalJson json, Clock clock) {
        Objects.requireNonNull(dependencies, "dependencies");
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        endpoints = new McpEndpointRepository(json);
        catalogs = new McpCatalogRepository(json);
        remote = dependencies.remote();
        interactions = dependencies.interactions();
        network = dependencies.network();
        credentials = dependencies.credentials();
        ledger = new McpInvocationLedger(database, json, clock);
    }

    McpInvocationResult invoke(
            TurnId turnId,
            WorkspaceId workspaceId,
            ToolDescriptor descriptor,
            CanonicalPayload arguments,
            String idempotencyKey,
            CancellationToken cancellation)
            throws Exception {
        McpEndpoint endpoint = requireEndpoint(McpToolMapping.endpointId(descriptor.identity()));
        requireWorkspace(endpoint, workspaceId);
        requireEnabled(endpoint);
        McpCatalogEntry entry = requireTool(endpoint, descriptor.identity().name());
        McpFrozenTool frozen = McpToolMapping.frozen(endpoint, entry);
        McpToolMapping.requireFrozenDescriptor(descriptor, entry, frozen);
        requireCredential(endpoint);
        authorize(endpoint);
        cancellation.throwIfCancelled();
        McpInvocationRequest invocation = new McpInvocationRequest(frozen, arguments, idempotencyKey);
        synchronized (CommandLocks.forKey(invocation.idempotencyKey())) {
            return invokeRecorded(
                    Objects.requireNonNull(turnId, "turnId"), workspaceId, endpoint, invocation, cancellation);
        }
    }

    private McpInvocationResult invokeRecorded(
            TurnId turnId,
            WorkspaceId workspaceId,
            McpEndpoint endpoint,
            McpInvocationRequest invocation,
            CancellationToken cancellation)
            throws Exception {
        Optional<McpInvocationResult> recovered = ledger.begin(endpoint, invocation, invocation.arguments());
        if (recovered.isPresent()) {
            return recovered.orElseThrow();
        }
        try {
            McpInvocationResult result = remote.invoke(
                    endpoint,
                    invocation,
                    new McpInteractionGuard(
                            endpoint.id(), interactions.bind(turnId, workspaceId, endpoint), json, clock),
                    cancellation);
            ledger.complete(invocation.idempotencyKey(), result);
            return result;
        } catch (Exception failure) {
            ledger.unknown(invocation.idempotencyKey());
            throw failure;
        }
    }

    private McpEndpoint requireEndpoint(String id) {
        return execute(connection -> endpoints
                .latest(connection, id, false)
                .orElseThrow(() -> PersistenceException.invalidRequest("MCP Endpoint 不存在")));
    }

    private McpCatalogEntry requireTool(McpEndpoint endpoint, String name) {
        return execute(connection -> catalogs.findTool(connection, endpoint.id(), endpoint.catalogRevision(), name)
                .orElseThrow(() -> PersistenceException.invalidRequest("冻结的 MCP Tool 已不存在")));
    }

    private void requireCredential(McpEndpoint endpoint) {
        if (endpoint.spec().authType() != McpAuthType.NONE
                && endpoint.spec().credential().filter(credentials::available).isEmpty()) {
            throw PersistenceException.invalidRequest("MCP CredentialRef 已清除或 Vault 已锁定");
        }
    }

    private void authorize(McpEndpoint endpoint) {
        if (endpoint.spec().transport() != McpTransport.STREAMABLE_HTTPS) {
            return;
        }
        try {
            network.authorize(endpoint);
        } catch (Exception failure) {
            throw new PersistenceException("MCP Network Broker 授权失败", failure);
        }
    }

    private static void requireWorkspace(McpEndpoint endpoint, WorkspaceId workspaceId) {
        if (!endpoint.spec().workspaceId().equals(Objects.requireNonNull(workspaceId, "workspaceId"))) {
            throw PersistenceException.invalidRequest("MCP Endpoint 不属于当前 Workspace");
        }
    }

    private static void requireEnabled(McpEndpoint endpoint) {
        if (endpoint.state() != McpEndpointState.ENABLED) {
            throw PersistenceException.invalidRequest("MCP Endpoint 已停用");
        }
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (PersistenceException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("MCP 调用校验读取失败", failure);
        }
    }
}
