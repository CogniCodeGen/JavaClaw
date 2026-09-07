package com.javaclaw.server.mcp;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpCatalogEntry;
import com.javaclaw.api.McpCatalogKind;
import com.javaclaw.api.McpCatalogPage;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointSpec;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpInvocationRequest;
import com.javaclaw.api.McpInvocationResult;
import com.javaclaw.api.McpProtocol;
import com.javaclaw.api.McpRemoteSession;
import com.javaclaw.api.McpTransport;
import com.javaclaw.api.Workspace;
import com.javaclaw.extension.spi.McpClientInteractionPort;
import com.javaclaw.extension.spi.McpRemotePort;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2Transactions;
import com.javaclaw.server.persistence.PersistenceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpCatalogCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private CanonicalJson json;
    private Workspace workspace;
    private FakeRemote remote;
    private AtomicInteger networkAuthorizations;
    private McpCatalogCoordinator coordinator;

    @BeforeEach
    void 初始化空白Catalog存储() {
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        json = new CanonicalJson();
        workspace = new CoreCommandService(database, json, CLOCK)
                .createWorkspace(
                        identity("workspace/create", "workspace", 0, "workspace"),
                        "MCP",
                        temporaryDirectory.resolve("workspace"));
        remote = new FakeRemote(json);
        networkAuthorizations = new AtomicInteger();
        coordinator = coordinator(reference -> true, endpoint -> networkAuthorizations.incrementAndGet());
    }

    @Test
    void 刷新原子提交分页目录并按相同命令直接恢复() throws Exception {
        McpEndpoint endpoint = seed(endpoint("docs", McpEndpointState.ENABLED, McpTransport.STREAMABLE_HTTPS));
        remote.entries = entries(201, "tool-");
        CommandIdentity refresh = identity("mcp/catalog/refresh", "refresh-docs", 1, "docs");

        McpEndpoint committed = coordinator.refresh(refresh, endpoint);
        McpEndpoint recovered = coordinator.refresh(refresh, endpoint);

        assertEquals(committed, recovered);
        assertEquals(1, committed.catalogRevision());
        assertEquals(1, networkAuthorizations.get());
        assertEquals(
                200,
                coordinator.page(committed, Optional.empty(), 0, 200).entries().size());
        assertEquals(
                Optional.of("200"),
                coordinator.page(committed, Optional.empty(), 0, 200).nextCursor());
        assertEquals(
                1,
                coordinator.page(committed, Optional.empty(), 200, 10).entries().size());
        assertEquals(
                201,
                coordinator
                                .page(committed, Optional.of(McpCatalogKind.TOOL), 0, 200)
                                .entries()
                                .size()
                        + coordinator
                                .page(committed, Optional.of(McpCatalogKind.TOOL), 200, 200)
                                .entries()
                                .size());
        assertEquals(201, coordinator.toolDescriptors(List.of(committed)).size());
    }

    @Test
    void 空目录版本和禁用Endpoint不会暴露工具且分页参数严格受限() throws Exception {
        McpEndpoint empty = seed(endpoint("empty", McpEndpointState.ENABLED, McpTransport.STREAMABLE_HTTPS));
        McpEndpoint disabled = seed(endpoint("disabled", McpEndpointState.DISABLED, McpTransport.STREAMABLE_HTTPS));

        assertTrue(coordinator.page(empty, Optional.empty(), -1, 0).entries().isEmpty());
        assertTrue(coordinator.toolDescriptors(List.of(empty, disabled)).isEmpty());

        remote.entries = List.of(tool("hidden"));
        McpEndpoint refreshedDisabled =
                coordinator.refresh(identity("mcp/catalog/refresh", "refresh-disabled", 1, "disabled"), disabled);
        assertTrue(coordinator.toolDescriptors(List.of(refreshedDisabled)).isEmpty());
        assertThrows(
                IllegalArgumentException.class, () -> coordinator.page(refreshedDisabled, Optional.empty(), -1, 10));
        assertThrows(IllegalArgumentException.class, () -> coordinator.page(refreshedDisabled, Optional.empty(), 0, 0));
        assertThrows(
                IllegalArgumentException.class, () -> coordinator.page(refreshedDisabled, Optional.empty(), 0, 201));
    }

    @Test
    void Credential缺失与Network授权失败均在发现前FailClosed() throws Exception {
        CredentialRef credential = new CredentialRef("mcp", "credential-1");
        McpEndpoint authenticated = seed(endpoint(
                "authenticated",
                McpEndpointState.ENABLED,
                McpTransport.STREAMABLE_HTTPS,
                McpAuthType.BEARER,
                Optional.of(credential)));
        McpCatalogCoordinator unavailable = coordinator(reference -> false, endpoint -> {});
        assertThrows(
                PersistenceException.class,
                () -> unavailable.refresh(
                        identity("mcp/catalog/refresh", "missing-secret", 1, "authenticated"), authenticated));
        assertEquals(0, remote.initializations);

        McpEndpoint networked = seed(endpoint("networked", McpEndpointState.ENABLED, McpTransport.STREAMABLE_HTTPS));
        McpCatalogCoordinator denied = coordinator(reference -> true, endpoint -> {
            throw new SecurityException("denied");
        });
        PersistenceException deniedFailure = assertThrows(
                PersistenceException.class,
                () -> denied.refresh(identity("mcp/catalog/refresh", "network-denied", 1, "networked"), networked));
        assertTrue(deniedFailure.getMessage().contains("Network Broker"));
        assertEquals(0, remote.initializations);
    }

    @Test
    void 签名Stdio跳过Network授权而重复Tool名与命令漂移被拒绝() throws Exception {
        McpEndpoint stdio = seed(endpoint("stdio", McpEndpointState.ENABLED, McpTransport.SIGNED_BUNDLE_STDIO));
        remote.entries = List.of(tool("shared"));
        McpEndpoint first = coordinator.refresh(identity("mcp/catalog/refresh", "stdio-refresh", 1, "stdio"), stdio);
        assertEquals(0, networkAuthorizations.get());

        McpEndpoint secondEndpoint = seed(endpoint("second", McpEndpointState.ENABLED, McpTransport.STREAMABLE_HTTPS));
        McpEndpoint second =
                coordinator.refresh(identity("mcp/catalog/refresh", "second-refresh", 1, "second"), secondEndpoint);
        assertThrows(PersistenceException.class, () -> coordinator.toolDescriptors(List.of(first, second)));

        assertThrows(
                PersistenceException.class,
                () -> coordinator.refresh(
                        identity("mcp/catalog/refresh", "stdio-refresh", 1, "changed-digest"), stdio));
        assertThrows(
                PersistenceException.class,
                () -> coordinator.refresh(identity("mcp/catalog/refresh", "stale-revision", 1, "stdio"), first));
    }

    private McpCatalogCoordinator coordinator(
            com.javaclaw.extension.spi.McpCredentialStatusPort credentials,
            com.javaclaw.extension.spi.McpNetworkAuthorizationPort network) {
        return new McpCatalogCoordinator(database, remote, network, credentials, json, CLOCK);
    }

    private McpEndpoint seed(McpEndpoint endpoint) throws Exception {
        new H2Transactions(database).execute(connection -> {
            new McpEndpointRepository(json).insert(connection, endpoint);
            return null;
        });
        return endpoint;
    }

    private McpEndpoint endpoint(String id, McpEndpointState state, McpTransport transport) {
        return endpoint(id, state, transport, McpAuthType.NONE, Optional.empty());
    }

    private McpEndpoint endpoint(
            String id,
            McpEndpointState state,
            McpTransport transport,
            McpAuthType authType,
            Optional<CredentialRef> credential) {
        boolean https = transport == McpTransport.STREAMABLE_HTTPS;
        McpEndpointSpec spec = new McpEndpointSpec(
                workspace.id(),
                id,
                transport,
                https ? Optional.of(URI.create("https://" + id + ".example/rpc")) : Optional.empty(),
                https ? Optional.empty() : Optional.of("signed.bundle"),
                authType,
                credential,
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(10));
        return new McpEndpoint(id, 1, state, 0, spec, NOW, NOW);
    }

    private List<McpCatalogEntry> entries(int count, String prefix) {
        List<McpCatalogEntry> entries = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            entries.add(tool(prefix + index));
        }
        return List.copyOf(entries);
    }

    private McpCatalogEntry tool(String name) {
        return McpCatalogEntry.tool(
                name,
                Optional.empty(),
                "Tool " + name,
                json.parse("{\"type\":\"object\"}"),
                json.parse("{\"type\":\"object\"}"));
    }

    private CommandIdentity identity(String method, String key, long revision, Object digestInput) {
        return new CommandIdentity(
                method,
                key,
                revision,
                json.encode(java.util.Map.of("value", digestInput)).sha256());
    }

    private static final class FakeRemote implements McpRemotePort {
        private final CanonicalJson json;
        private List<McpCatalogEntry> entries = List.of();
        private int initializations;

        private FakeRemote(CanonicalJson json) {
            this.json = json;
        }

        @Override
        public McpRemoteSession initialize(
                McpEndpoint endpoint, String requiredProtocol, CancellationToken cancellation) {
            initializations++;
            return new McpRemoteSession(McpProtocol.VERSION, Set.of("tools"));
        }

        @Override
        public McpCatalogPage catalog(McpEndpoint endpoint, Optional<String> cursor, CancellationToken cancellation) {
            if (entries.size() <= 200) {
                return new McpCatalogPage(entries, Optional.empty());
            }
            return cursor.isEmpty()
                    ? new McpCatalogPage(entries.subList(0, 200), Optional.of("page-2"))
                    : new McpCatalogPage(entries.subList(200, entries.size()), Optional.empty());
        }

        @Override
        public McpInvocationResult invoke(
                McpEndpoint endpoint,
                McpInvocationRequest request,
                McpClientInteractionPort interactions,
                CancellationToken cancellation) {
            return new McpInvocationResult(true, json.parse("{}"), Optional.empty(), List.of());
        }
    }
}
