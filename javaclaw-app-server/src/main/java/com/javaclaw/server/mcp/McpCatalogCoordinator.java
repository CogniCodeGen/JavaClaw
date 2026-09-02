package com.javaclaw.server.mcp;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpCatalogEntry;
import com.javaclaw.api.McpCatalogKind;
import com.javaclaw.api.McpCatalogPage;
import com.javaclaw.api.McpCatalogRefresh;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpTransport;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.extension.spi.McpCredentialStatusPort;
import com.javaclaw.extension.spi.McpNetworkAuthorizationPort;
import com.javaclaw.extension.spi.McpRemotePort;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CommandLocks;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2Transactions;
import com.javaclaw.server.persistence.IdempotencyRepository;
import com.javaclaw.server.persistence.PersistenceException;

/** 负责 MCP Catalog 的远端分页发现、原子提交、查询和冻结描述生成。 */
final class McpCatalogCoordinator {
    private static final int MAXIMUM_CATALOG_ENTRIES = 10_000;

    private final H2Transactions transactions;
    private final McpEndpointRepository endpoints;
    private final McpCatalogRepository catalogs;
    private final McpCatalogRefreshTracker refreshes;
    private final IdempotencyRepository idempotency = new IdempotencyRepository();
    private final McpCatalogDiscovery discovery;
    private final McpNetworkAuthorizationPort network;
    private final McpCredentialStatusPort credentials;
    private final CanonicalJson json;
    private final Clock clock;

    McpCatalogCoordinator(
            H2Database database,
            McpRemotePort remote,
            McpNetworkAuthorizationPort network,
            McpCredentialStatusPort credentials,
            CanonicalJson json,
            Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        endpoints = new McpEndpointRepository(json);
        catalogs = new McpCatalogRepository(json);
        refreshes = new McpCatalogRefreshTracker(database, clock);
        discovery = new McpCatalogDiscovery(Objects.requireNonNull(remote, "remote"));
        this.network = Objects.requireNonNull(network, "network");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    McpEndpoint refresh(CommandIdentity identity, McpEndpoint current) {
        Optional<McpEndpoint> recovered = recoverEndpoint(identity);
        if (recovered.isPresent()) {
            return recovered.orElseThrow();
        }
        requireCredential(current);
        authorize(current);
        refreshes.start(current);
        try {
            List<McpCatalogEntry> discovered =
                    discovery.discover(current, (pages, entries) -> refreshes.progress(current, pages, entries));
            return commit(identity, current, discovered);
        } catch (RuntimeException failure) {
            refreshes.fail(current, failure);
            throw failure;
        }
    }

    Optional<McpCatalogRefresh> refreshStatus(String endpointId) {
        return refreshes.find(endpointId);
    }

    McpCatalogPage page(McpEndpoint endpoint, Optional<McpCatalogKind> kind, int offset, int limit) {
        if (endpoint.catalogRevision() == 0) {
            return new McpCatalogPage(List.of(), Optional.empty());
        }
        requirePage(offset, limit);
        List<McpCatalogEntry> entries = execute(connection ->
                catalogs.list(connection, endpoint.id(), endpoint.catalogRevision(), kind, offset, limit + 1));
        boolean more = entries.size() > limit;
        List<McpCatalogEntry> values = more ? entries.subList(0, limit) : entries;
        return new McpCatalogPage(values, more ? Optional.of(Integer.toString(offset + limit)) : Optional.empty());
    }

    List<ToolDescriptor> toolDescriptors(List<McpEndpoint> workspaceEndpoints) {
        List<ToolDescriptor> descriptors = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (McpEndpoint endpoint : workspaceEndpoints) {
            if (endpoint.state() != McpEndpointState.ENABLED || endpoint.catalogRevision() == 0) {
                continue;
            }
            for (McpCatalogEntry entry : allTools(endpoint)) {
                if (!names.add(entry.name())) {
                    throw PersistenceException.invalidRequest("MCP Tool 同名冲突：" + entry.name());
                }
                descriptors.add(McpToolMapping.descriptor(endpoint, entry));
            }
        }
        return descriptors.stream()
                .sorted(java.util.Comparator.comparing(value -> value.identity().name()))
                .toList();
    }

    private McpEndpoint commit(CommandIdentity identity, McpEndpoint current, List<McpCatalogEntry> discovered) {
        Objects.requireNonNull(identity, "identity");
        synchronized (CommandLocks.forKey(identity.idempotencyKey())) {
            return execute(connection -> {
                Optional<IdempotencyRepository.StoredCommand> stored =
                        idempotency.find(connection, identity.idempotencyKey());
                if (stored.isPresent()) {
                    return recover(identity, stored.orElseThrow());
                }
                McpEndpoint locked = endpoints
                        .latest(connection, current.id(), true)
                        .orElseThrow(() -> PersistenceException.invalidRequest("MCP Endpoint 不存在"));
                requireRevision(locked, identity.expectedRevision());
                long catalogRevision = Math.addExact(locked.catalogRevision(), 1);
                Instant now = clock.instant();
                McpEndpoint next = new McpEndpoint(
                        locked.id(),
                        locked.revision() + 1,
                        locked.state(),
                        catalogRevision,
                        locked.spec(),
                        locked.createdAt(),
                        now);
                catalogs.insert(connection, next.id(), catalogRevision, discovered);
                endpoints.insert(connection, next);
                refreshes.complete(connection, next, catalogRevision, now);
                idempotency.insert(connection, identity, json.encode(next), now);
                return next;
            });
        }
    }

    private List<McpCatalogEntry> allTools(McpEndpoint endpoint) {
        return execute(connection -> catalogs.list(
                connection,
                endpoint.id(),
                endpoint.catalogRevision(),
                Optional.of(McpCatalogKind.TOOL),
                0,
                MAXIMUM_CATALOG_ENTRIES));
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

    private Optional<McpEndpoint> recoverEndpoint(CommandIdentity identity) {
        CommandIdentity checked = Objects.requireNonNull(identity, "identity");
        return execute(connection ->
                idempotency.find(connection, checked.idempotencyKey()).map(stored -> recover(checked, stored)));
    }

    private McpEndpoint recover(CommandIdentity identity, IdempotencyRepository.StoredCommand stored) {
        if (!stored.method().equals(identity.method())
                || !stored.requestDigest().equals(identity.requestDigest())) {
            throw PersistenceException.idempotencyConflict("幂等键已被不同 MCP 命令使用");
        }
        return json.decode(stored.response(), McpEndpoint.class);
    }

    private static void requireRevision(McpEndpoint current, long expectedRevision) {
        if (current.revision() != expectedRevision) {
            throw PersistenceException.revisionConflict("MCP Endpoint revision 已变化");
        }
    }

    private static void requirePage(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 200) {
            throw new IllegalArgumentException("offset/limit is outside MCP paging bounds");
        }
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (PersistenceException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("MCP Catalog 持久化失败", failure);
        }
    }
}
