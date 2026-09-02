package com.javaclaw.server.rpc;

import java.io.EOFException;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionSchema;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.JsonRpcMessage;
import com.javaclaw.protocol.JsonRpcNotification;
import com.javaclaw.protocol.RpcConnection;
import com.javaclaw.protocol.SessionSecretChannel;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.extension.contract.ExtensionHost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionEventHubTest {
    private static final CanonicalJson JSON = new CanonicalJson();

    @Test
    void extension命令只广播资源标识而不复制业务Payload() throws Exception {
        WorkspaceId workspaceId = WorkspaceId.random();
        RecordingConnection connection = new RecordingConnection();
        try (ExtensionEventHub hub = new ExtensionEventHub(JSON);
                ExtensionEventHub.Subscription ignored = hub.subscribe(connection);
                SessionSecretChannel secrets = SessionSecretChannel.open()) {
            RpcRouter.Builder routes = RpcRouter.builder();
            new ExtensionRpcHandlers(new SuccessfulHost(), JSON, hub).register(routes);
            ExtensionRpcContracts.CallPayload call = new ExtensionRpcContracts.CallPayload(
                    "memory",
                    workspaceId,
                    Optional.empty(),
                    Optional.empty(),
                    "proposal/accept",
                    JSON.parse("{\"secretValue\":\"must-not-leak\"}"));
            WriteCommand command = new WriteCommand("event-key", 3, JSON.encode(call));

            CanonicalPayload result = routes.build().route("extension/command", JSON.encode(command), secrets);
            JsonRpcNotification notification = connection.awaitNotification();
            ExtensionRpcContracts.ExtensionEvent event =
                    JSON.decode(notification.params(), ExtensionRpcContracts.ExtensionEvent.class);

            assertEquals(
                    4,
                    JSON.decode(result, ExtensionRpcContracts.CallResult.class).revision());
            assertEquals("extension/event", notification.method());
            assertEquals(workspaceId, event.workspaceId());
            assertEquals("workspace", event.scope());
            assertEquals(workspaceId.toString(), event.resourceId());
            assertEquals("proposal/accept", event.operation());
            assertEquals(4, event.revision());
            assertFalse(notification.params().json().contains("must-not-leak"));
            assertFalse(notification.params().json().contains("secretValue"));
        }
    }

    @Test
    void 慢客户端达到有界队列上限后会被断开() throws Exception {
        BlockingConnection connection = new BlockingConnection();
        try (ExtensionEventHub hub = new ExtensionEventHub(JSON);
                ExtensionEventHub.Subscription ignored = hub.subscribe(connection)) {
            hub.publish(event(1));
            assertTrue(connection.sendStarted.await(2, TimeUnit.SECONDS));
            for (int revision = 2; revision <= 66; revision++) {
                hub.publish(event(revision));
            }
            assertTrue(connection.closed.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void 正常取消订阅不会夺取会话持有的连接所有权() {
        RecordingConnection connection = new RecordingConnection();
        try (ExtensionEventHub hub = new ExtensionEventHub(JSON)) {
            ExtensionEventHub.Subscription subscription = hub.subscribe(connection);
            subscription.close();
            assertFalse(connection.closed);
        }
    }

    private static ExtensionRpcContracts.ExtensionEvent event(long revision) {
        WorkspaceId workspaceId = WorkspaceId.random();
        return new ExtensionRpcContracts.ExtensionEvent(
                workspaceId, "plan", "workspace", workspaceId.toString(), "put", revision);
    }

    private static final class SuccessfulHost implements ExtensionHost {
        @Override
        public List<ExtensionRpcContracts.Summary> list() {
            return List.of();
        }

        @Override
        public List<ToolDescriptor> tools() {
            return List.of();
        }

        @Override
        public ExtensionResponse executeTool(
                ToolCallRequest request,
                ToolDescriptor frozenDescriptor,
                PermissionProfile callerPermissions,
                CancellationToken cancellation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExtensionResponse query(ExtensionRpcContracts.CallPayload call) {
            return new ExtensionResponse(new CanonicalPayload("{}"), 0);
        }

        @Override
        public ExtensionResponse command(
                ExtensionRpcContracts.CallPayload call, String idempotencyKey, long expectedRevision) {
            return new ExtensionResponse(JSON.parse("{\"accepted\":true}"), 4);
        }

        @Override
        public ExtensionSchema schema(String extensionId, String schemaId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ExtensionRpcContracts.ViewDocument> views(Optional<String> extensionId) {
            return List.of();
        }

        @Override
        public void close() {}
    }

    private static class RecordingConnection implements RpcConnection {
        private final BlockingQueue<JsonRpcNotification> notifications = new LinkedBlockingQueue<>();
        private volatile boolean closed;

        @Override
        public void send(JsonRpcMessage message) {
            if (message instanceof JsonRpcNotification notification) {
                notifications.add(notification);
            }
        }

        @Override
        public JsonRpcMessage receive() throws IOException {
            throw new EOFException();
        }

        @Override
        public void close() {
            closed = true;
        }

        private JsonRpcNotification awaitNotification() throws InterruptedException {
            JsonRpcNotification notification = notifications.poll(2, TimeUnit.SECONDS);
            assertNotNull(notification);
            return notification;
        }
    }

    private static final class BlockingConnection implements RpcConnection {
        private final CountDownLatch sendStarted = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void send(JsonRpcMessage message) throws IOException {
            sendStarted.countDown();
            try {
                if (!closed.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("test send was not released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("test send interrupted", interrupted);
            }
            throw new IOException("connection closed");
        }

        @Override
        public JsonRpcMessage receive() throws IOException {
            throw new EOFException();
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }
}
