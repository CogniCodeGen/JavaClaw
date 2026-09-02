package com.javaclaw.api;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpContractsTest {
    private static final WorkspaceId WORKSPACE_ID = WorkspaceId.random();
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final URI ENDPOINT = URI.create("https://mcp.example.test/rpc");

    @Test
    void https端点支持四种认证并保留精确私网授权() {
        McpEndpointSpec none = https(McpAuthType.NONE, Optional.empty(), Optional.empty());
        McpEndpointSpec bearer =
                https(McpAuthType.BEARER, Optional.of(new CredentialRef("mcp", "bearer_token")), Optional.empty());
        McpEndpointSpec apiKey =
                https(McpAuthType.API_KEY, Optional.of(new CredentialRef("mcp", "api_key")), Optional.of("X-Api-Key"));
        McpEndpointSpec oauth = https(
                McpAuthType.OAUTH_2_1_PKCE, Optional.of(new CredentialRef("oauth", "oauth_token")), Optional.empty());

        assertEquals(McpAuthType.NONE, none.authType());
        assertEquals("X-Api-Key", apiKey.apiKeyHeader().orElseThrow());
        assertEquals("mcp", bearer.credential().orElseThrow().namespace());
        assertEquals("oauth", oauth.credential().orElseThrow().namespace());
        assertEquals("network_grant", none.privateNetworkGrant().orElseThrow().id());
    }

    @Test
    void signedBundleStdio拒绝地址和宿主认证() {
        McpEndpointSpec stdio = new McpEndpointSpec(
                WORKSPACE_ID,
                "Signed tool",
                McpTransport.SIGNED_BUNDLE_STDIO,
                Optional.empty(),
                Optional.of("bundle_vendor"),
                McpAuthType.NONE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(5));

        assertEquals("bundle_vendor", stdio.signedBundleId().orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> copy(stdio, Optional.of(ENDPOINT), stdio.signedBundleId(), McpAuthType.NONE, Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> copy(stdio, Optional.empty(), Optional.empty(), McpAuthType.NONE, Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> copy(
                        stdio,
                        Optional.empty(),
                        stdio.signedBundleId(),
                        McpAuthType.BEARER,
                        Optional.of(new CredentialRef("mcp", "token"))));
    }

    @Test
    void https端点拒绝不安全地址认证组合和超时() {
        assertInvalidUri("http://mcp.example.test/rpc");
        assertInvalidUri("https://user@mcp.example.test/rpc");
        assertInvalidUri("https://mcp.example.test/rpc?secret=value");
        assertInvalidUri("https://mcp.example.test/rpc#fragment");
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpEndpointSpec(
                        WORKSPACE_ID,
                        "MCP",
                        McpTransport.STREAMABLE_HTTPS,
                        Optional.empty(),
                        Optional.empty(),
                        McpAuthType.NONE,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Duration.ofSeconds(5)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpEndpointSpec(
                        WORKSPACE_ID,
                        "MCP",
                        McpTransport.STREAMABLE_HTTPS,
                        Optional.of(ENDPOINT),
                        Optional.of("bundle_vendor"),
                        McpAuthType.NONE,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Duration.ofSeconds(5)));
        assertInvalidAuth(McpAuthType.NONE, Optional.of(new CredentialRef("mcp", "token")), Optional.empty());
        assertInvalidAuth(McpAuthType.BEARER, Optional.empty(), Optional.empty());
        assertInvalidAuth(McpAuthType.BEARER, Optional.of(new CredentialRef("mcp", "token")), Optional.of("X-Key"));
        assertInvalidAuth(McpAuthType.API_KEY, Optional.of(new CredentialRef("mcp", "token")), Optional.empty());
        assertInvalidAuth(
                McpAuthType.API_KEY, Optional.of(new CredentialRef("provider", "token")), Optional.of("X-Key"));
        assertInvalidHeader("Authorization");
        assertInvalidHeader("Cookie");
        assertInvalidHeader("Host");
        assertInvalidHeader("1-Invalid");
        assertInvalidTimeout(Duration.ZERO);
        assertInvalidTimeout(Duration.ofSeconds(-1));
        assertInvalidTimeout(Duration.ofMinutes(2).plusNanos(1));
    }

    @Test
    void catalog只允许工具携带规范Schema() {
        CanonicalPayload input = new CanonicalPayload("{\"type\":\"object\"}");
        CanonicalPayload output = new CanonicalPayload("{\"type\":\"object\",\"required\":[]}");
        McpCatalogEntry tool = McpCatalogEntry.tool("read_file", Optional.of("Read"), "读取文件", input, output);
        McpCatalogEntry prompt = external(McpCatalogKind.PROMPT, "review_prompt");
        McpCatalogEntry resource = external(McpCatalogKind.RESOURCE, "guide_resource");

        assertEquals(McpCatalogKind.TOOL, tool.kind());
        assertEquals(
                McpHashes.sha256(input.sha256() + ":" + output.sha256()),
                tool.schemaHash().orElseThrow());
        assertTrue(prompt.inputSchema().isEmpty());
        assertEquals(McpCatalogKind.RESOURCE, resource.kind());
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpCatalogEntry(
                        McpCatalogKind.TOOL,
                        "bad name",
                        Optional.empty(),
                        "描述",
                        Optional.of(input),
                        Optional.of(output),
                        Optional.of(ApiFixtures.DIGEST)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpCatalogEntry(
                        McpCatalogKind.TOOL,
                        "tool",
                        Optional.empty(),
                        "描述",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpCatalogEntry(
                        McpCatalogKind.PROMPT,
                        "prompt",
                        Optional.empty(),
                        "描述",
                        Optional.of(input),
                        Optional.of(output),
                        Optional.of(ApiFixtures.DIGEST)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpCatalogEntry(
                        McpCatalogKind.TOOL,
                        "tool",
                        Optional.empty(),
                        "描述",
                        Optional.of(input),
                        Optional.of(output),
                        Optional.of("not-a-digest")));
    }

    @Test
    void catalog刷新进度约束终态字段和时间() {
        McpCatalogRefresh running =
                refresh(McpCatalogRefreshState.RUNNING, 1, 2, Optional.empty(), Optional.empty(), NOW.plusSeconds(1));
        McpCatalogRefresh completed =
                refresh(McpCatalogRefreshState.COMPLETED, 2, 3, Optional.of(7L), Optional.empty(), NOW.plusSeconds(2));
        McpCatalogRefresh failed = refresh(
                McpCatalogRefreshState.FAILED,
                2,
                3,
                Optional.empty(),
                Optional.of("CATALOG_INVALID"),
                NOW.plusSeconds(2));

        assertEquals(7, completed.committedCatalogRevision().orElseThrow());
        assertEquals("CATALOG_INVALID", failed.detail().orElseThrow());
        assertTrue(running.committedCatalogRevision().isEmpty());
        assertInvalidRefresh(-1, 0, McpCatalogRefreshState.RUNNING, Optional.empty(), Optional.empty(), NOW);
        assertInvalidRefresh(51, 0, McpCatalogRefreshState.RUNNING, Optional.empty(), Optional.empty(), NOW);
        assertInvalidRefresh(0, -1, McpCatalogRefreshState.RUNNING, Optional.empty(), Optional.empty(), NOW);
        assertInvalidRefresh(0, 10_001, McpCatalogRefreshState.RUNNING, Optional.empty(), Optional.empty(), NOW);
        assertInvalidRefresh(0, 0, McpCatalogRefreshState.COMPLETED, Optional.empty(), Optional.empty(), NOW);
        assertInvalidRefresh(0, 0, McpCatalogRefreshState.RUNNING, Optional.of(1L), Optional.empty(), NOW);
        assertInvalidRefresh(0, 0, McpCatalogRefreshState.FAILED, Optional.empty(), Optional.empty(), NOW);
        assertInvalidRefresh(0, 0, McpCatalogRefreshState.RUNNING, Optional.empty(), Optional.of("FAIL"), NOW);
        assertInvalidRefresh(
                0, 0, McpCatalogRefreshState.RUNNING, Optional.empty(), Optional.empty(), NOW.minusSeconds(1));
    }

    @Test
    void oauth端点和健康快照不泄露授权URL() {
        McpOAuthAuthorization authorization = new McpOAuthAuthorization(
                "oauth_flow",
                "mcp_endpoint",
                2,
                "AUTH.Example.Test",
                McpOAuthState.PENDING,
                NOW.plusSeconds(60),
                Optional.of("WAITING_CALLBACK"),
                NOW);
        McpHealth health = new McpHealth(
                "mcp_endpoint", 2, McpHealthState.HEALTHY, Optional.of("2026-07-28"), Optional.empty(), NOW);

        assertEquals("auth.example.test", authorization.authorizationHost());
        assertEquals("2026-07-28", health.negotiatedProtocol().orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpOAuthAuthorization(
                        "oauth_flow",
                        "mcp_endpoint",
                        2,
                        "https://auth.example.test/path",
                        McpOAuthState.PENDING,
                        NOW.plusSeconds(60),
                        Optional.empty(),
                        NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpOAuthAuthorization(
                        "oauth_flow",
                        "mcp_endpoint",
                        2,
                        "auth.example.test",
                        McpOAuthState.FAILED,
                        NOW.plusSeconds(60),
                        Optional.of("secret detail"),
                        NOW));
    }

    @Test
    void endpoint与冻结工具携带精确版本() {
        McpEndpoint endpoint = new McpEndpoint(
                "mcp_endpoint",
                2,
                McpEndpointState.ENABLED,
                3,
                https(McpAuthType.NONE, Optional.empty(), Optional.empty()),
                NOW,
                NOW);
        McpFrozenTool tool = new McpFrozenTool("mcp_endpoint", 2, 3, "read_file", ApiFixtures.DIGEST);

        assertEquals(3, endpoint.catalogRevision());
        assertTrue(tool.toolRevision() > 0);
        assertEquals(tool.toolRevision(), tool.toolRevision());
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpEndpoint("mcp_endpoint", 2, McpEndpointState.ENABLED, -1, endpoint.spec(), NOW, NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpEndpoint(
                        "mcp_endpoint", 2, McpEndpointState.DISABLED, 0, endpoint.spec(), NOW, NOW.minusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new McpFrozenTool("mcp_endpoint", 2, 3, "read_file", "bad"));
    }

    @Test
    void samplingElicitation与调用对象实施硬上限() {
        McpSamplingMessage message = new McpSamplingMessage(McpSamplingRole.USER, ApiFixtures.payload());
        McpSamplingRequest sampling = new McpSamplingRequest("sample_request", "mcp_endpoint", List.of(message), 256);
        McpElicitationRequest elicitation = new McpElicitationRequest(
                "input_request", "mcp_endpoint", "请选择范围", ApiFixtures.payload(), NOW.plusSeconds(60));
        McpRemoteSession session = new McpRemoteSession("2026-07-28", Set.of("tools", "resources"));
        McpFrozenTool tool = new McpFrozenTool("mcp_endpoint", 2, 3, "read_file", ApiFixtures.DIGEST);
        McpInvocationRequest invocation = new McpInvocationRequest(tool, ApiFixtures.payload(), "idempotency-key");
        McpInvocationResult result =
                new McpInvocationResult(true, ApiFixtures.payload(), Optional.of("remote-1"), List.of());

        assertEquals(256, sampling.maximumOutputTokens());
        assertEquals("请选择范围", elicitation.prompt());
        assertTrue(session.capabilities().contains("tools"));
        assertEquals(tool, invocation.tool());
        assertEquals("remote-1", result.remoteRequestId().orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpSamplingRequest("sample_request", "mcp_endpoint", List.of(), 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpSamplingRequest("sample_request", "mcp_endpoint", repeatedMessages(33), 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpSamplingRequest("sample_request", "mcp_endpoint", List.of(message), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpSamplingRequest("sample_request", "mcp_endpoint", List.of(message), 2049));
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpElicitationRequest(
                        "input_request", "mcp_endpoint", "x".repeat(1001), ApiFixtures.payload(), NOW));
        assertThrows(
                IllegalArgumentException.class, () -> new McpRemoteSession("2026-07-28", Set.of("bad capability")));
    }

    @Test
    void catalog分页复制集合并限制每页数量() {
        List<McpCatalogEntry> source = new ArrayList<>(List.of(external(McpCatalogKind.PROMPT, "prompt")));
        McpCatalogPage page = new McpCatalogPage(source, Optional.of("next-page"));
        source.clear();

        assertEquals(1, page.entries().size());
        assertEquals("next-page", page.nextCursor().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new McpCatalogPage(repeatedEntries(201), Optional.empty()));
        assertFalse(new McpInvocationResult(false, ApiFixtures.payload(), Optional.empty(), List.of()).successful());
    }

    @Test
    void resource与Prompt契约保持外部数据并校验进度() {
        McpProgress progress =
                new McpProgress("request-progress", BigDecimal.ONE, Optional.of(BigDecimal.TEN), Optional.of("读取中"));
        McpResourceDescriptor descriptor = new McpResourceDescriptor(
                "guide",
                "docs://guide",
                Optional.of("Guide"),
                Optional.empty(),
                Optional.of("text/plain"),
                Optional.of(5L));
        McpResourcePage page =
                new McpResourcePage(List.of(descriptor), Optional.empty(), McpCacheScope.PRIVATE, 0, List.of(progress));
        McpResourceContent content = new McpResourceContent(
                descriptor.uri(), Optional.of("text/plain"), Optional.of("hello"), Optional.empty());
        McpPromptResult prompt = new McpPromptResult(
                Optional.of("外部模板"),
                List.of(new McpPromptMessage(McpSamplingRole.USER, ApiFixtures.payload())),
                List.of(progress));

        assertEquals("docs://guide", page.resources().getFirst().uri());
        assertEquals("hello", content.text().orElseThrow());
        assertEquals(McpSamplingRole.USER, prompt.messages().getFirst().role());
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpProgress(
                        "request-progress", BigDecimal.TEN, Optional.of(BigDecimal.ONE), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpResourceContent(
                        "docs://guide", Optional.empty(), Optional.of("text"), Optional.of("YmxvYg==")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpResourceDescriptor(
                        "guide", "relative", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
    }

    private static McpEndpointSpec https(
            McpAuthType authType, Optional<CredentialRef> credential, Optional<String> header) {
        return new McpEndpointSpec(
                WORKSPACE_ID,
                "Example MCP",
                McpTransport.STREAMABLE_HTTPS,
                Optional.of(ENDPOINT),
                Optional.empty(),
                authType,
                credential,
                header,
                Optional.of(new PrivateNetworkGrantRef("network_grant", 4)),
                Duration.ofSeconds(30));
    }

    private static McpEndpointSpec copy(
            McpEndpointSpec source,
            Optional<URI> endpoint,
            Optional<String> bundle,
            McpAuthType authType,
            Optional<CredentialRef> credential) {
        return new McpEndpointSpec(
                source.workspaceId(),
                source.displayName(),
                source.transport(),
                endpoint,
                bundle,
                authType,
                credential,
                Optional.empty(),
                Optional.empty(),
                source.requestTimeout());
    }

    private static McpCatalogEntry external(McpCatalogKind kind, String name) {
        return new McpCatalogEntry(
                kind, name, Optional.empty(), "外部数据", Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static McpCatalogRefresh refresh(
            McpCatalogRefreshState state,
            int pages,
            int entries,
            Optional<Long> revision,
            Optional<String> detail,
            Instant updatedAt) {
        return new McpCatalogRefresh("mcp_endpoint", 2, state, pages, entries, revision, detail, NOW, updatedAt);
    }

    private static void assertInvalidUri(String value) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpEndpointSpec(
                        WORKSPACE_ID,
                        "MCP",
                        McpTransport.STREAMABLE_HTTPS,
                        Optional.of(URI.create(value)),
                        Optional.empty(),
                        McpAuthType.NONE,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Duration.ofSeconds(5)));
    }

    private static void assertInvalidAuth(
            McpAuthType authType, Optional<CredentialRef> credential, Optional<String> header) {
        assertThrows(IllegalArgumentException.class, () -> https(authType, credential, header));
    }

    private static void assertInvalidHeader(String header) {
        assertInvalidAuth(McpAuthType.API_KEY, Optional.of(new CredentialRef("mcp", "api_key")), Optional.of(header));
    }

    private static void assertInvalidTimeout(Duration timeout) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpEndpointSpec(
                        WORKSPACE_ID,
                        "MCP",
                        McpTransport.STREAMABLE_HTTPS,
                        Optional.of(ENDPOINT),
                        Optional.empty(),
                        McpAuthType.NONE,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        timeout));
    }

    private static void assertInvalidRefresh(
            int pages,
            int entries,
            McpCatalogRefreshState state,
            Optional<Long> revision,
            Optional<String> detail,
            Instant updatedAt) {
        assertThrows(IllegalArgumentException.class, () -> refresh(state, pages, entries, revision, detail, updatedAt));
    }

    private static List<McpSamplingMessage> repeatedMessages(int count) {
        McpSamplingMessage message = new McpSamplingMessage(McpSamplingRole.ASSISTANT, ApiFixtures.payload());
        return java.util.Collections.nCopies(count, message);
    }

    private static List<McpCatalogEntry> repeatedEntries(int count) {
        return java.util.Collections.nCopies(count, external(McpCatalogKind.RESOURCE, "resource"));
    }
}
