package com.javaclaw.server.mcp;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.BrokerResponse;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.McpCatalogKind;
import com.javaclaw.api.McpOAuthState;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.PersistenceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpProtocolCodecTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final String DIGEST = "a".repeat(64);

    private final CanonicalJson json = new CanonicalJson();

    @Test
    void Json响应拆为唯一Frame且拒绝不支持的内容类型() {
        McpWireResponseDecoder decoder = new McpWireResponseDecoder(json);
        List<CanonicalPayload> decoded = decoder.frames(jsonResponse("""
                {"jsonrpc":"2.0","id":"request-1","result":{"ok":true}}
                """));
        assertEquals(1, decoded.size());
        assertEquals("request-1", json.textField(decoded.getFirst(), "id").orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> decoder.frames(response("text/plain", "{}")));
    }

    @Test
    void Sse按顺序拆分多个Event与DataFrame() {
        McpWireResponseDecoder decoder = new McpWireResponseDecoder(json);
        BrokerResponse valid = response(
                "text/event-stream; charset=utf-8",
                ": keep-alive\r\nevent: message\r\ndata: {\"jsonrpc\":\"2.0\",\r\ndata: \"method\":\"notifications/progress\",\"params\":{}}\r\n\r\n"
                        + "data: {\"jsonrpc\":\"2.0\",\"id\":\"sse-1\",\"result\":{}}\n\n");
        List<CanonicalPayload> frames = decoder.frames(valid);
        assertEquals(2, frames.size());
        assertEquals(
                "notifications/progress",
                json.textField(frames.getFirst(), "method").orElseThrow());
        assertEquals("sse-1", json.textField(frames.getLast(), "id").orElseThrow());

        assertThrows(
                IllegalArgumentException.class,
                () -> decoder.frames(response("text/event-stream", "event: custom\ndata: {}\n\n")));
        assertThrows(
                IllegalArgumentException.class, () -> decoder.frames(response("text/event-stream", ": no-data\n\n")));
    }

    @Test
    void Catalog游标跨三类目录并保留远端分页位置() {
        McpCatalogWireCodec codec = new McpCatalogWireCodec(json);
        McpCatalogWireCodec.Request first = codec.request(Optional.empty());
        assertEquals("tools/list", first.method());
        var tools = codec.decode(
                first,
                json.parse(fixedPage("\"tools\":[{\"name\":\"search\",\"title\":\"Search\",\"description\":\"Docs\","
                        + "\"inputSchema\":{\"type\":\"object\"}}],\"nextCursor\":\"remote-page\"")));
        assertEquals(McpCatalogKind.TOOL, tools.entries().getFirst().kind());
        assertEquals(
                "{\"type\":\"object\"}",
                tools.entries().getFirst().outputSchema().orElseThrow().json());

        McpCatalogWireCodec.Request nextTool = codec.request(tools.nextCursor());
        assertEquals("tools/list", nextTool.method());
        assertEquals("remote-page", json.textField(nextTool.params(), "cursor").orElseThrow());
        var finishedTools = codec.decode(nextTool, json.parse(fixedPage("\"tools\":[]")));

        McpCatalogWireCodec.Request prompts = codec.request(finishedTools.nextCursor());
        assertEquals("prompts/list", prompts.method());
        var promptPage = codec.decode(prompts, json.parse(fixedPage("\"prompts\":[{\"name\":\"draft\"}]")));
        assertEquals("MCP prompt draft", promptPage.entries().getFirst().description());
        assertTrue(promptPage.entries().getFirst().inputSchema().isEmpty());

        McpCatalogWireCodec.Request resources = codec.request(promptPage.nextCursor());
        assertEquals("resources/list", resources.method());
        var resourcePage = codec.decode(
                resources, json.parse(fixedPage("\"resources\":[{\"name\":\"manual\",\"description\":\"Guide\"}]")));
        assertEquals(McpCatalogKind.RESOURCE, resourcePage.entries().getFirst().kind());
        assertTrue(resourcePage.nextCursor().isEmpty());
    }

    @Test
    void Catalog拒绝缺失Schema畸形游标与不安全远端游标() {
        McpCatalogWireCodec codec = new McpCatalogWireCodec(json);
        McpCatalogWireCodec.Request first = codec.request(Optional.empty());
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(first, json.parse("{\"tools\":[{\"name\":\"search\"}]}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(first, json.parse("{\"tools\":[{\"name\":\"\",\"inputSchema\":{}}]}")));
        assertThrows(IllegalArgumentException.class, () -> codec.request(Optional.of("3.")));
        assertThrows(IllegalArgumentException.class, () -> codec.request(Optional.of("0.*")));
        assertThrows(IllegalArgumentException.class, () -> codec.request(Optional.of("0.A")));
        assertThrows(IllegalArgumentException.class, () -> codec.request(Optional.of("0." + "a".repeat(4_097))));
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(first, json.encode(Map.of("tools", List.of(), "nextCursor", " "))));
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(first, json.parse("{\"tools\":[],\"nextCursor\":\"bad\\nvalue\"}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(first, json.encode(Map.of("tools", List.of(), "nextCursor", "a".repeat(4_097)))));
        assertThrows(IllegalArgumentException.class, () -> new McpCatalogWireCodec.CursorState(-1, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new McpCatalogWireCodec.CursorState(3, Optional.empty()));
    }

    @Test
    void OAuth安全材料校验回调边界并派生隔离命令身份() {
        McpOAuthSecurity security = new McpOAuthSecurity();
        McpOAuthSecurity.PkceMaterial material = security.generate();
        assertTrue(material.verifier().length() >= 43);
        assertTrue(material.state().length() >= 43);
        assertEquals(43, material.challenge().length());
        assertEquals(material, security.decode(material.encoded()));
        assertThrows(
                PersistenceException.class, () -> security.decode("only-one-part".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(PersistenceException.class, () -> security.decode("\nstate".getBytes(StandardCharsets.US_ASCII)));

        URI redirect = URI.create("http://127.0.0.1:17845/oauth/callback");
        assertEquals(redirect, security.requireLoopback(redirect));
        for (URI invalid : List.of(
                URI.create("https://127.0.0.1:17845/oauth/callback"),
                URI.create("http://[::1]:17845/oauth/callback"),
                URI.create("http://localhost:17845/oauth/callback"),
                URI.create("http://user@127.0.0.1:17845/oauth/callback"),
                URI.create("http://127.0.0.1/oauth/callback"),
                URI.create("http://127.0.0.1:17845/oauth/callback?q=1"),
                URI.create("http://127.0.0.1:17845/oauth/callback#fragment"))) {
            assertThrows(IllegalArgumentException.class, () -> security.requireLoopback(invalid));
        }

        URI callback = URI.create(redirect + "?code=one&state=two");
        assertEquals(callback, security.requireCallback(callback, redirect));
        for (URI invalid : List.of(
                URI.create("http://127.0.0.1:17846/oauth/callback?code=one"),
                URI.create("http://127.0.0.1:17845/other?code=one"),
                URI.create(redirect.toString()),
                URI.create(redirect + "?code=one#fragment"))) {
            assertThrows(IllegalArgumentException.class, () -> security.requireCallback(invalid, redirect));
        }
        assertEquals(
                URI.create("https://Login.Example:8443"),
                security.origin(URI.create("HTTPS://Login.Example:8443/a?q=1")));
        assertEquals("valid-id", security.identifier(" valid-id "));
        assertThrows(IllegalArgumentException.class, () -> security.identifier("bad/id"));

        CommandIdentity source = new CommandIdentity("mcp/oauth/start", "command-1", 7, DIGEST);
        assertEquals("credential/create", security.vaultCreateIdentity(source).method());
        assertEquals(0, security.vaultCreateIdentity(source).expectedRevision());
        McpOAuthSession pending = session(McpOAuthState.PENDING);
        assertEquals(1, security.vaultRotateIdentity(source, pending).expectedRevision());
        assertEquals(
                2,
                security.vaultRotateIdentity(
                                source, pending.transition(McpOAuthState.AUTHORIZED, Optional.empty(), NOW))
                        .expectedRevision());
        assertEquals(9, security.oauthAttachIdentity(source, 9).expectedRevision());
        assertNotEquals(
                DIGEST,
                security.browserCompleteIdentity("authorization-1", callback).requestDigest());
        assertEquals(4, security.cleanupIdentity(pending, 4).expectedRevision());
    }

    @Test
    void OAuth会话只接受Https授权Uri与OAuth命名空间() {
        McpOAuthSession pending = session(McpOAuthState.PENDING);
        assertEquals("login.example", pending.projection().authorizationHost());
        McpOAuthSession failed = pending.transition(McpOAuthState.FAILED, Optional.of("DENIED"), NOW.plusSeconds(1));
        assertEquals(McpOAuthState.FAILED, failed.projection().state());
        assertEquals(Optional.of("DENIED"), failed.detail());

        assertThrows(
                IllegalArgumentException.class,
                () -> session(0, URI.create("https://login.example/authorize"), oauthRef()));
        for (URI invalid : List.of(
                URI.create("http://login.example/authorize"),
                URI.create("/relative"),
                URI.create("https://user@login.example/authorize"),
                URI.create("https://login.example/authorize#fragment"))) {
            assertThrows(IllegalArgumentException.class, () -> session(1, invalid, oauthRef()));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> session(1, URI.create("https://login.example/authorize"), new CredentialRef("mcp", "secret-1")));
    }

    private McpOAuthSession session(McpOAuthState state) {
        return new McpOAuthSession(
                "authorization-1",
                "endpoint-1",
                1,
                URI.create("https://Login.Example/authorize?request=one"),
                oauthRef(),
                state,
                NOW.plusSeconds(600),
                Optional.empty(),
                NOW);
    }

    private McpOAuthSession session(long revision, URI authorizationUri, CredentialRef credential) {
        return new McpOAuthSession(
                "authorization-1",
                "endpoint-1",
                revision,
                authorizationUri,
                credential,
                McpOAuthState.PENDING,
                NOW.plusSeconds(600),
                Optional.empty(),
                NOW);
    }

    private static CredentialRef oauthRef() {
        return new CredentialRef("oauth", "temporary-1");
    }

    private static BrokerResponse jsonResponse(String body) {
        return response("application/json; charset=utf-8", body);
    }

    private static String fixedPage(String fields) {
        return "{\"cacheScope\":\"private\",\"resultType\":\"complete\",\"ttlMs\":0," + fields + '}';
    }

    private static BrokerResponse response(String contentType, String body) {
        return new BrokerResponse(
                200, Map.of("content-type", List.of(contentType)), body.getBytes(StandardCharsets.UTF_8), false);
    }
}
