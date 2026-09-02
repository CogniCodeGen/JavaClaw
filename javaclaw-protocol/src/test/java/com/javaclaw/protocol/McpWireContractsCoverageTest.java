package com.javaclaw.protocol;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpCacheScope;
import com.javaclaw.api.McpCatalogKind;
import com.javaclaw.api.McpCatalogPage;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointSpec;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpPromptDescriptor;
import com.javaclaw.api.McpPromptPage;
import com.javaclaw.api.McpResourceDescriptor;
import com.javaclaw.api.McpResourcePage;
import com.javaclaw.api.McpTransport;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpWireContractsCoverageTest {
    private static final WorkspaceId WORKSPACE_ID = WorkspaceId.parse("00000000-0000-0000-0000-000000000301");

    private final CanonicalJson json = new CanonicalJson();

    @Test
    void Endpoint写入查询列表与OAuth结果保持强类型Wire契约() {
        McpEndpointSpec spec = endpointSpec();
        McpEndpoint endpoint = new McpEndpoint(
                "docs",
                2,
                McpEndpointState.ENABLED,
                4,
                spec,
                Instant.parse("2026-09-02T00:00:00Z"),
                Instant.parse("2026-09-02T00:01:00Z"));

        assertRoundTrip(new McpRpcContracts.EndpointQuery(endpoint.id()), McpRpcContracts.EndpointQuery.class);
        assertRoundTrip(
                new McpRpcContracts.EndpointWritePayload(endpoint.id(), spec),
                McpRpcContracts.EndpointWritePayload.class);
        assertEquals(
                List.of(endpoint),
                assertRoundTrip(
                                new McpRpcContracts.EndpointListResult(List.of(endpoint)),
                                McpRpcContracts.EndpointListResult.class)
                        .endpoints());
        assertRoundTrip(new McpRpcContracts.OAuthStartPayload(endpoint.id()), McpRpcContracts.OAuthStartPayload.class);
        assertEquals(
                Optional.empty(),
                assertRoundTrip(new McpRpcContracts.OAuthResult(Optional.empty()), McpRpcContracts.OAuthResult.class)
                        .authorization());
    }

    @Test
    void Catalog游标分页和空刷新结果保持权威Wire语义() {
        McpRpcContracts.CatalogQuery first =
                new McpRpcContracts.CatalogQuery("docs", Optional.empty(), Optional.empty(), 200);
        McpRpcContracts.CatalogQuery later =
                new McpRpcContracts.CatalogQuery("docs", Optional.of(McpCatalogKind.TOOL), Optional.of("20"), 50);

        assertEquals(
                0, assertRoundTrip(first, McpRpcContracts.CatalogQuery.class).offset());
        assertEquals(
                20, assertRoundTrip(later, McpRpcContracts.CatalogQuery.class).offset());
        assertEquals(
                new McpCatalogPage(List.of(), Optional.empty()),
                assertRoundTrip(
                                new McpRpcContracts.CatalogResult(new McpCatalogPage(List.of(), Optional.empty())),
                                McpRpcContracts.CatalogResult.class)
                        .page());
        assertEquals(
                Optional.empty(),
                assertRoundTrip(
                                new McpRpcContracts.CatalogRefreshResult(Optional.empty()),
                                McpRpcContracts.CatalogRefreshResult.class)
                        .refresh());
    }

    @Test
    void SignedBundle注册超时和Catalog边界拒绝含糊输入() {
        McpRpcContracts.SignedBundleRegisterPayload registration = new McpRpcContracts.SignedBundleRegisterPayload(
                "bundle-stdio", WORKSPACE_ID, "signed.bundle", "Signed MCP", Duration.ofMinutes(2));

        assertEquals(registration, assertRoundTrip(registration, McpRpcContracts.SignedBundleRegisterPayload.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpRpcContracts.SignedBundleRegisterPayload(
                        "bundle-stdio", WORKSPACE_ID, "signed.bundle", "Signed MCP", Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpRpcContracts.SignedBundleRegisterPayload(
                        "bundle-stdio", WORKSPACE_ID, "signed.bundle", "Signed MCP", Duration.ofSeconds(-1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpRpcContracts.SignedBundleRegisterPayload(
                        "bundle-stdio", WORKSPACE_ID, "signed.bundle", "Signed MCP", Duration.ofMinutes(3)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpRpcContracts.SignedBundleRegisterPayload(
                        "bundle-stdio", WORKSPACE_ID, "signed.bundle", " ", Duration.ofSeconds(1)));

        assertThrows(
                IllegalArgumentException.class,
                () -> new McpRpcContracts.CatalogQuery("docs", Optional.empty(), Optional.empty(), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpRpcContracts.CatalogQuery("docs", Optional.empty(), Optional.empty(), 201));
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpRpcContracts.CatalogQuery("docs", Optional.empty(), Optional.of("01"), 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpRpcContracts.CatalogQuery("docs", Optional.empty(), Optional.of("1000000000"), 10));
        assertThrows(IllegalArgumentException.class, () -> new McpRpcContracts.EndpointQuery("bad/id"));
    }

    @Test
    void Resource与Prompt方法保持强类型外部数据Wire契约() {
        McpRpcContracts.ExternalPageQuery page = new McpRpcContracts.ExternalPageQuery("docs", Optional.of("next"));
        McpRpcContracts.ResourceReadQuery read = new McpRpcContracts.ResourceReadQuery("docs", "docs://guide");
        McpRpcContracts.PromptGetQuery prompt =
                new McpRpcContracts.PromptGetQuery("docs", "review", Map.of("topic", "v5"));
        McpResourcePage resources = new McpResourcePage(
                List.of(new McpResourceDescriptor(
                        "guide",
                        "docs://guide",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of("text/plain"),
                        Optional.of(10L))),
                Optional.empty(),
                McpCacheScope.PRIVATE,
                0,
                List.of());
        McpPromptPage prompts = new McpPromptPage(
                List.of(new McpPromptDescriptor("review", Optional.empty(), Optional.empty(), List.of())),
                Optional.empty(),
                McpCacheScope.PRIVATE,
                0,
                List.of());

        assertEquals(page, assertRoundTrip(page, McpRpcContracts.ExternalPageQuery.class));
        assertEquals(read, assertRoundTrip(read, McpRpcContracts.ResourceReadQuery.class));
        assertEquals(prompt, assertRoundTrip(prompt, McpRpcContracts.PromptGetQuery.class));
        assertEquals(
                resources,
                assertRoundTrip(
                                new McpRpcContracts.ResourcePageResult(resources),
                                McpRpcContracts.ResourcePageResult.class)
                        .page());
        assertEquals(
                prompts,
                assertRoundTrip(new McpRpcContracts.PromptPageResult(prompts), McpRpcContracts.PromptPageResult.class)
                        .page());
        assertThrows(IllegalArgumentException.class, () -> new McpRpcContracts.ResourceReadQuery("docs", "relative"));
    }

    private static McpEndpointSpec endpointSpec() {
        return new McpEndpointSpec(
                WORKSPACE_ID,
                "Docs",
                McpTransport.STREAMABLE_HTTPS,
                Optional.of(URI.create("https://mcp.example.test/rpc")),
                Optional.empty(),
                McpAuthType.NONE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(30));
    }

    private <T> T assertRoundTrip(T value, Class<T> type) {
        T decoded = json.decode(json.encode(value), type);
        assertEquals(value, decoded);
        return decoded;
    }
}
