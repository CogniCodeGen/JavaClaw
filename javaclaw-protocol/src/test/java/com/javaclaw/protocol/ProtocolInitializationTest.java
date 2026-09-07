package com.javaclaw.protocol;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolInitializationTest {
    @Test
    void initialize只协商双方声明的能力并冻结结果() {
        HashSet<String> clientStable = new HashSet<>(Set.of("items", "unknown"));
        CapabilityAdvertisement advertisement =
                new CapabilityAdvertisement(clientStable, Set.of("native-compaction", "unknown-experimental"));
        InitializeParams params =
                new InitializeParams(ProtocolVersion.CURRENT, new ClientInfo(" Desktop ", " 5.0 "), advertisement);
        ProtocolNegotiator negotiator = new ProtocolNegotiator(Set.of("items", "views"), Set.of("native-compaction"));

        NegotiatedCapabilities negotiated = negotiator.negotiate(params);
        clientStable.clear();

        assertEquals(Set.of("items"), negotiated.stableCapabilities());
        assertEquals(Set.of("native-compaction"), negotiated.experimentalCapabilities());
        assertTrue(negotiated.allows("items"));
        assertTrue(negotiated.allows("native-compaction"));
        assertFalse(negotiated.allows("unknown"));
        negotiated.require("items");
        ProtocolException missing = assertThrows(ProtocolException.class, () -> negotiated.require("unknown"));
        assertEquals(ProtocolErrorCode.CAPABILITY_NOT_NEGOTIATED, missing.code());
    }

    @Test
    void initialize值对象拒绝空信息错误版本和空集合() {
        assertEquals("client", new ClientInfo(" client ", " 1 ").name());
        assertThrows(NullPointerException.class, () -> new ClientInfo(null, "1"));
        assertThrows(IllegalArgumentException.class, () -> new ClientInfo(" ", "1"));
        assertThrows(IllegalArgumentException.class, () -> new ClientInfo("client", " "));
        assertThrows(NullPointerException.class, () -> new CapabilityAdvertisement(null, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new CapabilityAdvertisement(Set.of(" "), Set.of()));
        assertThrows(NullPointerException.class, () -> new InitializeParams(2, null, validAdvertisement()));
        assertThrows(NullPointerException.class, () -> new InitializeParams(2, validClient(), null));

        ProtocolNegotiator negotiator = new ProtocolNegotiator(Set.of(), Set.of());
        ProtocolException version = assertThrows(
                ProtocolException.class,
                () -> negotiator.negotiate(new InitializeParams(2, validClient(), validAdvertisement())));
        assertEquals(ProtocolErrorCode.UNSUPPORTED_PROTOCOL_VERSION, version.code());
        assertTrue(version.getMessage().contains("UNSUPPORTED_PROTOCOL_VERSION"));
        assertThrows(NullPointerException.class, () -> negotiator.negotiate(null));
    }

    @Test
    void initializeResult只接受当前协议和完整服务信息() {
        NegotiatedCapabilities capabilities = new NegotiatedCapabilities(Set.of("items"), Set.of());
        InitializeResult result = new InitializeResult(3, " JavaClaw ", " 5.0.0 ", capabilities, testSessionKey());

        assertEquals("JavaClaw", result.serverName());
        assertEquals("5.0.0", result.serverVersion());
        assertThrows(
                IllegalArgumentException.class,
                () -> new InitializeResult(2, "server", "1", capabilities, testSessionKey()));
        assertThrows(
                NullPointerException.class, () -> new InitializeResult(3, null, "1", capabilities, testSessionKey()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new InitializeResult(3, " ", "1", capabilities, testSessionKey()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new InitializeResult(3, "server", " ", capabilities, testSessionKey()));
        assertThrows(NullPointerException.class, () -> new InitializeResult(3, "server", "1", null, testSessionKey()));
        assertThrows(NullPointerException.class, () -> new InitializeResult(3, "server", "1", capabilities, null));
        assertThrows(NullPointerException.class, () -> new NegotiatedCapabilities(null, Set.of()));
    }

    private static SessionKeyInfo testSessionKey() {
        return new SessionKeyInfo(SessionKeyInfo.ALGORITHM, "test-session", "AA");
    }

    @Test
    void 方法目录稳定且未知或未协商能力被拒绝() {
        NegotiatedCapabilities none = new NegotiatedCapabilities(Set.of(), Set.of());
        assertEquals(159, MethodCatalog.methods().size());
        assertEquals(
                RpcMethodKind.QUERY, MethodCatalog.require("thread/read", none).kind());
        assertEquals(
                RpcMethodKind.COMMAND,
                MethodCatalog.require("extension/bundle/stage", none).kind());
        assertEquals(
                RpcMethodKind.COMMAND,
                MethodCatalog.require("extension/bundle/uninstall", none).kind());
        assertEquals(
                ProtocolErrorCode.METHOD_NOT_FOUND,
                assertThrows(ProtocolException.class, () -> MethodCatalog.require("thread/missing", none))
                        .code());

        RpcMethod stable = new RpcMethod("feature/read", RpcMethodKind.QUERY, Optional.of(" feature "), false);
        RpcMethod experimental = new RpcMethod("feature/run", RpcMethodKind.COMMAND, Optional.of("experiment"), true);
        assertEquals(Optional.of("feature"), stable.capability());
        assertTrue(experimental.experimental());
        assertThrows(
                IllegalArgumentException.class,
                () -> new RpcMethod("feature/run", RpcMethodKind.COMMAND, Optional.empty(), true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RpcMethod("invalid", RpcMethodKind.QUERY, Optional.empty(), false));
        assertThrows(NullPointerException.class, () -> new RpcMethod("feature/read", null, Optional.empty(), false));
        assertThrows(NullPointerException.class, () -> new RpcMethod("feature/read", RpcMethodKind.QUERY, null, false));
    }

    @Test
    void 写信封拒绝空幂等键负版本和空payload() {
        CanonicalPayload payload = new CanonicalPayload("{}");
        WriteCommand command = new WriteCommand(" key ", 0, payload);
        assertEquals("key", command.idempotencyKey());
        assertThrows(NullPointerException.class, () -> new WriteCommand(null, 0, payload));
        assertThrows(IllegalArgumentException.class, () -> new WriteCommand(" ", 0, payload));
        assertThrows(IllegalArgumentException.class, () -> new WriteCommand("x".repeat(201), 0, payload));
        assertThrows(IllegalArgumentException.class, () -> new WriteCommand("key", -1, payload));
        assertThrows(NullPointerException.class, () -> new WriteCommand("key", 0, null));
    }

    @Test
    void 协议常量和枚举完整可访问() {
        assertEquals(3, ProtocolVersion.CURRENT);
        assertEquals(3, RpcMethodKind.values().length);
        assertEquals(3, TransportKind.values().length);
        assertEquals(-32700, ProtocolErrorCode.PARSE_ERROR);
        assertEquals(-32600, ProtocolErrorCode.INVALID_REQUEST);
        assertEquals(-32601, ProtocolErrorCode.METHOD_NOT_FOUND);
        assertEquals(-32602, ProtocolErrorCode.INVALID_PARAMS);
        assertEquals(-32603, ProtocolErrorCode.INTERNAL_ERROR);
        assertEquals(-32020, ProtocolErrorCode.UNSUPPORTED_PROTOCOL_VERSION);
        assertEquals(-32022, ProtocolErrorCode.REVISION_CONFLICT);
        assertEquals(-32023, ProtocolErrorCode.IDEMPOTENCY_CONFLICT);
        assertEquals(-32024, ProtocolErrorCode.PERMISSION_DENIED);
    }

    @Test
    void 能力集合拒绝Null元素而不是静默丢弃() {
        HashSet<String> withNull = new HashSet<>();
        withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> new CapabilityAdvertisement(withNull, Set.of()));
    }

    private static ClientInfo validClient() {
        return new ClientInfo("client", "1");
    }

    private static CapabilityAdvertisement validAdvertisement() {
        return new CapabilityAdvertisement(Set.of(), Set.of());
    }
}
