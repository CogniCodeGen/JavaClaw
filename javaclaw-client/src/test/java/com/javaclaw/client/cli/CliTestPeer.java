package com.javaclaw.client.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;
import java.util.function.Supplier;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.ApprovalRecord;
import com.javaclaw.api.ApprovalRequest;
import com.javaclaw.api.ApprovalState;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.InputRequest;
import com.javaclaw.api.InputRequestRecord;
import com.javaclaw.api.InputRequestState;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ResolvedTurnConfigSummary;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.InitializeResult;
import com.javaclaw.protocol.InputJobRpcContracts;
import com.javaclaw.protocol.JsonRpcError;
import com.javaclaw.protocol.JsonRpcMessage;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.LocalTransport;
import com.javaclaw.protocol.NegotiatedCapabilities;
import com.javaclaw.protocol.ProtocolException;
import com.javaclaw.protocol.RpcConnection;
import com.javaclaw.protocol.SessionKeyInfo;
import com.javaclaw.protocol.TransportKind;
import com.javaclaw.protocol.WriteCommand;

/** 仅以共享协议模拟对端，无服务端、Desktop 或模型依赖。 */
final class CliTestPeer {
    static final CanonicalJson JSON = new CanonicalJson();
    static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");
    static final ThreadId THREAD = ThreadId.parse("00000000-0000-0000-0000-000000000001");
    static final TurnId TURN = TurnId.parse("00000000-0000-0000-0000-000000000002");
    static final com.javaclaw.api.WorkspaceId WORKSPACE = com.javaclaw.api.WorkspaceId.random();
    volatile AgentTurn current = turn(TurnStatus.RUNNING, 2);
    volatile List<ApprovalRecord> approvals = List.of();
    volatile List<InputRequestRecord> inputs = List.of();
    volatile boolean closed;
    volatile int reads;
    volatile int resolutions;
    volatile int cancellations;
    Supplier<AgentTurn> onRead = () -> current;
    Function<CoreRpcContracts.ItemList, CoreRpcContracts.ItemListResult> onItems;
    Function<com.javaclaw.protocol.ExtensionRpcContracts.CallPayload, Object> onCoding = call -> {
        if (!call.operation().equals("execution/list")) {
            throw new AssertionError("未配置 Coding 输出页：" + call.operation());
        }
        return new com.javaclaw.builtin.contracts.CodingResults.ExecutionList(List.of());
    };
    Function<WriteCommand, ApprovalRecord> onApproval = command -> {
        throw new AssertionError("未配置审批响应");
    };
    Function<WriteCommand, InputRequestRecord> onInput = command -> {
        throw new AssertionError("未配置输入响应");
    };
    Function<WriteCommand, AgentTurn> onCancel = command -> {
        current = turn(TurnStatus.CANCELLED, current.revision() + 1);
        return current;
    };

    JsonRpcResponse respond(JsonRpcRequest request) {
        try {
            Object result =
                    switch (request.method()) {
                        case "initialize/session" -> initialize(request);
                        case "turn/start" -> new CoreRpcContracts.TurnStartResult(current, current.resolvedConfig());
                        case "turn/read" -> {
                            reads++;
                            yield onRead.get();
                        }
                        case "item/list" -> messages(request);
                        case "approval/list" -> new CoreRpcContracts.ApprovalListResult(approvals);
                        case "turn/input/list" -> new InputJobRpcContracts.InputListResult(inputs);
                        case "approval/resolve" -> {
                            resolutions++;
                            yield onApproval.apply(JSON.decode(request.params(), WriteCommand.class));
                        }
                        case "turn/input/resolve" -> {
                            resolutions++;
                            yield onInput.apply(JSON.decode(request.params(), WriteCommand.class));
                        }
                        case "turn/cancel" -> {
                            cancellations++;
                            yield onCancel.apply(JSON.decode(request.params(), WriteCommand.class));
                        }
                        default -> codingResponse(request);
                    };
            return JsonRpcResponse.success(request.id(), JSON.encode(result));
        } catch (ProtocolException failure) {
            return JsonRpcResponse.failure(
                    request.id(), new JsonRpcError(failure.code(), failure.getMessage(), Optional.empty()));
        }
    }

    private Object codingResponse(JsonRpcRequest request) {
        return switch (request.method()) {
            case "thread/read" ->
                new com.javaclaw.api.ConversationThread(
                        THREAD,
                        WORKSPACE,
                        Optional.empty(),
                        com.javaclaw.api.ThreadExecutionIntent.WORKSPACE,
                        "Coding CLI",
                        com.javaclaw.api.ThreadStatus.ACTIVE,
                        1,
                        NOW,
                        NOW);
            case "extension/query" ->
                new com.javaclaw.protocol.ExtensionRpcContracts.CallResult(
                        JSON.encode(onCoding.apply(JSON.decode(
                                request.params(), com.javaclaw.protocol.ExtensionRpcContracts.CallPayload.class))),
                        0);
            default -> throw new AssertionError("意外 RPC: " + request.method());
        };
    }

    JavaClawClient connect() throws IOException {
        LinkedBlockingQueue<JsonRpcMessage> responses = new LinkedBlockingQueue<>();
        RpcConnection connection = new RpcConnection() {
            @Override
            public void send(JsonRpcMessage message) {
                responses.add(respond((JsonRpcRequest) message));
            }

            @Override
            public JsonRpcMessage receive() throws IOException {
                try {
                    return responses.take();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }

            @Override
            public void close() {
                closed = true;
            }
        };
        LocalTransport transport = new LocalTransport() {
            @Override
            public TransportKind kind() {
                return TransportKind.STDIO;
            }

            @Override
            public RpcConnection connect() {
                return connection;
            }
        };
        return JavaClawClient.connect(transport, new ClientInfo("cli-test", "6"), Set.of(), ignored -> {});
    }

    static AgentTurn turn(TurnStatus status, long revision) {
        var role = new AgentRoleRef("default", 1);
        var provider = new ProviderRef("test", 1, "model");
        var permission = new PermissionProfileRef("standard", 1);
        var budget = new TurnBudget(1000, 1000, 10, 1, Duration.ofMinutes(1));
        var config = new ResolvedTurnConfigSummary(
                role,
                provider,
                permission,
                ApprovalPolicy.RISKY,
                budget,
                Set.of(),
                Optional.empty(),
                "a".repeat(64),
                "b".repeat(64),
                false,
                List.of());
        return new AgentTurn(
                TURN,
                THREAD,
                status,
                revision,
                budget,
                role,
                provider,
                permission,
                Path.of(".").toAbsolutePath(),
                config.promptManifestDigest(),
                config.toolCatalogDigest(),
                status == TurnStatus.FAILED ? Optional.of("TEST_FAILED") : Optional.empty(),
                NOW,
                NOW,
                config);
    }

    static ApprovalRecord approval(long revision, ApprovalState state) {
        var request = new ApprovalRequest(
                "approval-test",
                TURN,
                "call-test",
                new ToolIdentity("core", "write", 1),
                ToolRisk.EXTERNAL_EFFECT,
                "写入测试文件",
                "c".repeat(64),
                NOW,
                NOW.plusSeconds(60));
        return new ApprovalRecord(
                request, state, revision, state == ApprovalState.PENDING ? Optional.empty() : Optional.of("测试结果"), NOW);
    }

    static InputRequestRecord input(long revision) {
        var schema = JSON.parse("""
            {"type":"object","additionalProperties":false,"properties":{"count":{"type":"integer"},
            "ready":{"type":"boolean"}},"required":["count","ready"]}
            """);
        return new InputRequestRecord(
                new InputRequest("input-test", TURN, "core", "填写数量与确认", schema, NOW, NOW.plusSeconds(60)),
                InputRequestState.PENDING,
                revision,
                Optional.empty(),
                Optional.empty(),
                NOW);
    }

    private Object messages(JsonRpcRequest request) {
        var query = JSON.decode(request.params(), CoreRpcContracts.ItemList.class);
        if (onItems != null) {
            return onItems.apply(query);
        }
        List<ItemEnvelope> items = current.status() == TurnStatus.COMPLETED && query.afterSequence() == 0
                ? List.of(new ItemEnvelope(
                        ItemId.parse("00000000-0000-0000-0000-000000000003"),
                        TURN,
                        1,
                        "message",
                        CoreSchemas.MESSAGE,
                        "core",
                        ItemStatus.COMPLETED,
                        JSON.encode(
                                new CorePayloads.Message(MessageRole.ASSISTANT, "任务完成", List.of(), Optional.empty())),
                        NOW,
                        Optional.of(NOW)))
                : List.of();
        return new CoreRpcContracts.ItemListResult(items, items.isEmpty() ? query.afterSequence() : 1);
    }

    private Object initialize(JsonRpcRequest request) {
        var params = JSON.decode(request.params(), InitializeParams.class);
        return new InitializeResult(
                3,
                "cli-test",
                "6",
                new NegotiatedCapabilities(params.capabilities().stableCapabilities(), Set.of()),
                new SessionKeyInfo(SessionKeyInfo.ALGORITHM, "test", "AA"));
    }
}
