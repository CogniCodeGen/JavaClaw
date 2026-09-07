package com.javaclaw.client.extension;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.VersionedExtensionDocument;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.JsonRpcMessage;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.RpcConnection;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 自动化 facade 测试使用的严格 Extension RPC 脚本连接。 */
final class ScriptedExtensionConnection implements RpcConnection {
    private static final Object CLOSED = new Object();
    private static final CanonicalJson JSON = new CanonicalJson();
    private static final CommandOptions CREATE = new CommandOptions("create", 0);
    private static final CommandOptions REVISION_ONE = new CommandOptions("revision-one", 1);
    private static final CommandOptions REVISION_TWO = new CommandOptions("revision-two", 2);

    private final WorkspaceId workspaceId;
    private final BlockingQueue<Object> inbound = new ArrayBlockingQueue<>(32);
    private final ArrayDeque<ExpectedCall> expected = new ArrayDeque<>();

    ScriptedExtensionConnection(WorkspaceId workspaceId) {
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }

    void expectCrud(
            String extensionId,
            Object saveRequest,
            VersionedExtensionDocument first,
            VersionedExtensionDocument second) {
        expectCrud(extensionId, saveRequest, first, second, "definition/create", "definition/update");
    }

    void expectCrud(
            String extensionId,
            Object saveRequest,
            VersionedExtensionDocument first,
            VersionedExtensionDocument second,
            String createOperation,
            String updateOperation) {
        expectQuery(extensionId, "read", new DocumentContracts.Key(first.id()), first, first.revision());
        DocumentContracts.Page page = new DocumentContracts.Page(List.of(JSON.encode(first)), "next");
        expectQuery(extensionId, "list", new DocumentContracts.PageRequest("", 10), page, 0);
        expectCommand(extensionId, createOperation, saveRequest, CREATE, first, first.revision());
        expectCommand(extensionId, updateOperation, saveRequest, REVISION_ONE, second, second.revision());
        expectCommand(
                extensionId,
                "delete",
                new DocumentContracts.Key(first.id()),
                REVISION_TWO,
                new DocumentContracts.Deleted(first.id()),
                3);
    }

    void expectQuery(String extensionId, String operation, Object request, Object response, long responseRevision) {
        expected.add(new ExpectedCall(
                "extension/query",
                extensionId,
                operation,
                request,
                Optional.empty(),
                response,
                responseRevision,
                Optional.empty()));
    }

    void expectCommand(
            String extensionId,
            String operation,
            Object request,
            CommandOptions options,
            Object response,
            long responseRevision) {
        expectCommand(extensionId, operation, request, options, response, responseRevision, null);
    }

    void expectCommand(
            String extensionId,
            String operation,
            Object request,
            CommandOptions options,
            Object response,
            long responseRevision,
            ThreadId threadId) {
        expected.add(new ExpectedCall(
                "extension/command",
                extensionId,
                operation,
                request,
                Optional.of(options),
                response,
                responseRevision,
                Optional.ofNullable(threadId)));
    }

    void assertExhausted() {
        assertTrue(expected.isEmpty(), () -> "unconsumed call: " + expected.peekFirst());
    }

    @Override
    public void send(JsonRpcMessage message) {
        JsonRpcRequest request = (JsonRpcRequest) message;
        ExpectedCall call = expected.removeFirst();
        assertEquals(call.rpcMethod(), request.method());
        ExtensionRpcContracts.CallPayload payload = call.options().isPresent()
                ? commandPayload(request, call.options().orElseThrow())
                : JSON.decode(request.params(), ExtensionRpcContracts.CallPayload.class);
        assertEquals(call.extensionId(), payload.extensionId());
        assertEquals(workspaceId, payload.workspaceId());
        assertEquals(call.threadId(), payload.threadId());
        assertTrue(payload.turnId().isEmpty(), "管理请求不得通过客户端 Turn ID 声明授权");
        assertEquals(call.operation(), payload.operation());
        assertEquals(JSON.encode(call.request()), payload.payload());
        ExtensionRpcContracts.CallResult result =
                new ExtensionRpcContracts.CallResult(JSON.encode(call.response()), call.responseRevision());
        inbound.add(JsonRpcResponse.success(request.id(), JSON.encode(result)));
    }

    private ExtensionRpcContracts.CallPayload commandPayload(JsonRpcRequest request, CommandOptions options) {
        WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
        assertEquals(options.idempotencyKey(), command.idempotencyKey());
        assertEquals(options.expectedRevision(), command.expectedRevision());
        return JSON.decode(command.payload(), ExtensionRpcContracts.CallPayload.class);
    }

    @Override
    public JsonRpcMessage receive() throws IOException {
        try {
            Object message = inbound.take();
            if (message == CLOSED) {
                throw new IOException("scripted connection closed");
            }
            return (JsonRpcMessage) message;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("scripted connection interrupted", interrupted);
        }
    }

    @Override
    public void close() {
        inbound.clear();
        inbound.offer(CLOSED);
    }

    private record ExpectedCall(
            String rpcMethod,
            String extensionId,
            String operation,
            Object request,
            Optional<CommandOptions> options,
            Object response,
            long responseRevision,
            Optional<ThreadId> threadId) {}
}
