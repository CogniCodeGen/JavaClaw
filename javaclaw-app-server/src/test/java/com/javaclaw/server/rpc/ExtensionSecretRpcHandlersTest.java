package com.javaclaw.server.rpc;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.ExtensionSecretRpcContracts;
import com.javaclaw.protocol.SessionSecretChannel;
import com.javaclaw.protocol.SessionSecretSealer;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExtensionSecretRpcHandlersTest {
    private static final CanonicalJson JSON = new CanonicalJson();
    private static final WorkspaceId WORKSPACE = WorkspaceId.parse("8c817593-886a-4961-8a42-ce1ea487b1f4");

    @Test
    void 只允许注册操作消费绑定版本的秘密且重试先恢复回执() throws Exception {
        try (SessionSecretChannel secrets = SessionSecretChannel.open()) {
            AtomicReference<byte[]> consumed = new AtomicReference<>();
            AtomicInteger executions = new AtomicInteger();
            AtomicReference<ExtensionRpcContracts.CallResult> replay = new AtomicReference<>();
            ExtensionSecretRpcHandlers.Handler handler = handler(consumed, executions, replay);
            RpcRouter.Builder routes = RpcRouter.builder();
            new ExtensionSecretRpcHandlers(Map.of("site/account/set", handler), JSON).register(routes);
            RpcRouter router = routes.build();
            var call = call("account/set");
            var sealed = SessionSecretSealer.seal(
                    secrets.publicKey(), ExtensionSecretRpcContracts.purpose(call, 2), "user\0password".toCharArray());
            var command =
                    new WriteCommand("one", 2, JSON.encode(new ExtensionSecretRpcContracts.Payload(call, sealed)));
            var result = router.route(ExtensionSecretRpcContracts.METHOD, JSON.encode(command), secrets);
            assertEquals(1, executions.get());
            assertArrayEquals(new byte[13], consumed.get());
            assertEquals(result, router.route(ExtensionSecretRpcContracts.METHOD, JSON.encode(command), secrets));
            assertEquals(1, executions.get());

            var unknown = new WriteCommand(
                    "unknown", 2, JSON.encode(new ExtensionSecretRpcContracts.Payload(call("unregistered"), sealed)));
            assertThrows(
                    SecurityException.class,
                    () -> router.route(ExtensionSecretRpcContracts.METHOD, JSON.encode(unknown), secrets));
        }
    }

    @Test
    void 密文不能替换资源版本或扩展地址() throws Exception {
        try (SessionSecretChannel secrets = SessionSecretChannel.open()) {
            RpcRouter.Builder routes = RpcRouter.builder();
            new ExtensionSecretRpcHandlers(
                            Map.of(
                                    "site/account/set",
                                    handler(new AtomicReference<>(), new AtomicInteger(), new AtomicReference<>())),
                            JSON)
                    .register(routes);
            var call = call("account/set");
            var sealed = SessionSecretSealer.seal(
                    secrets.publicKey(), ExtensionSecretRpcContracts.purpose(call, 2), "user\0password".toCharArray());
            var command =
                    new WriteCommand("changed", 3, JSON.encode(new ExtensionSecretRpcContracts.Payload(call, sealed)));
            assertThrows(
                    RuntimeException.class,
                    () -> routes.build().route(ExtensionSecretRpcContracts.METHOD, JSON.encode(command), secrets));
        }
    }

    private static ExtensionSecretRpcHandlers.Handler handler(
            AtomicReference<byte[]> consumed,
            AtomicInteger executions,
            AtomicReference<ExtensionRpcContracts.CallResult> replay) {
        return new ExtensionSecretRpcHandlers.Handler() {
            @Override
            public void validate(ExtensionRpcContracts.CallPayload call) {
                assertEquals(WORKSPACE, call.workspaceId());
            }

            @Override
            public Optional<ExtensionRpcContracts.CallResult> recover(CommandIdentity identity) {
                return Optional.ofNullable(replay.get());
            }

            @Override
            public ExtensionRpcContracts.CallResult execute(
                    ExtensionRpcContracts.CallPayload call, CommandIdentity identity, byte[] plaintext) {
                assertEquals("user\0password", new String(plaintext, StandardCharsets.UTF_8));
                consumed.set(plaintext);
                executions.incrementAndGet();
                var result = new ExtensionRpcContracts.CallResult(JSON.encode(Map.of("saved", true)), 3);
                replay.set(result);
                return result;
            }
        };
    }

    private static ExtensionRpcContracts.CallPayload call(String operation) {
        return new ExtensionRpcContracts.CallPayload(
                "site",
                WORKSPACE,
                Optional.empty(),
                Optional.empty(),
                operation,
                JSON.encode(Map.of("accountId", "account")));
    }
}
