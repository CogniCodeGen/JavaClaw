package com.javaclaw.client;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.PromptManifestPreview;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ResolvedTurnConfigSummary;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ThreadStatus;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.facade.AgentClient;
import com.javaclaw.client.facade.PromptManifestClient;
import com.javaclaw.client.facade.TurnClient;
import com.javaclaw.client.testkit.ScriptedRpcConnection;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CollaborationRpcContracts;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentClientTest {
    private static final CanonicalJson JSON = new CanonicalJson();
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");
    private static final ThreadId THREAD_ID = ThreadId.parse("00000000-0000-0000-0000-000000000002");
    private static final WorkspaceId WORKSPACE_ID = WorkspaceId.parse("00000000-0000-0000-0000-000000000001");
    private static final ResolvedTurnConfigSummary CONFIGURATION = configuration();
    private static final AgentTurn TURN = turn();

    @Test
    void 协作SDK发送父Revision与agentType并保留返回冻结摘要() {
        ConversationThread thread = new ConversationThread(
                THREAD_ID,
                WORKSPACE_ID,
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                "任务",
                ThreadStatus.ACTIVE,
                1,
                NOW,
                NOW);
        CollaborationRpcContracts.SpawnResult result =
                new CollaborationRpcContracts.SpawnResult(thread, TURN, CONFIGURATION);
        ScriptedRpcConnection rpc = new ScriptedRpcConnection(request -> {
            if (request.method().equals("agent/wait")) {
                assertEquals(
                        TURN.id(),
                        JSON.decode(request.params(), CollaborationRpcContracts.WaitPayload.class)
                                .turnId());
            } else {
                WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
                assertEquals(3, command.expectedRevision());
                if (request.method().equals("agent/spawn")) {
                    CollaborationRpcContracts.SpawnPayload payload =
                            JSON.decode(command.payload(), CollaborationRpcContracts.SpawnPayload.class);
                    assertEquals("worker", payload.agentType());
                    assertFalse(command.payload().json().contains("profile"));
                    return JsonRpcResponse.success(request.id(), JSON.encode(result));
                }
                assertEquals(
                        TURN.id(),
                        JSON.decode(command.payload(), CollaborationRpcContracts.InterruptPayload.class)
                                .turnId());
            }
            return JsonRpcResponse.success(request.id(), JSON.encode(TURN));
        });
        AgentClient client = new AgentClient(new RpcClientConnection(rpc, JSON, ignored -> {}));
        CommandOptions options = new CommandOptions("child", 3);
        CollaborationRpcContracts.SpawnPayload payload = new CollaborationRpcContracts.SpawnPayload(
                TURN.id(), "worker", "完成有限子任务", ExecutionOverrides.empty(), "子任务");

        assertEquals(result, client.spawn(payload, options));
        assertEquals(TURN, client.waitFor(TURN.id()));
        assertEquals(TURN, client.interrupt(TURN.id(), "用户取消", options));
    }

    @Test
    void Turn启动返回独立安全摘要且Prompt预览使用统一选择() {
        PromptManifestPreview preview = new PromptManifestPreview(
                CONFIGURATION.role(),
                CONFIGURATION.provider(),
                CONFIGURATION.permissionProfile(),
                List.of(),
                "a".repeat(64),
                10,
                "test",
                "平台约定",
                "角色指令");
        ScriptedRpcConnection rpc = new ScriptedRpcConnection(request -> JsonRpcResponse.success(
                request.id(),
                JSON.encode(
                        request.method().equals("turn/start")
                                ? new CoreRpcContracts.TurnStartResult(TURN, CONFIGURATION)
                                : preview)));
        RpcClientConnection connection = new RpcClientConnection(rpc, JSON, ignored -> {});
        CoreRpcContracts.TurnStartResult result = new TurnClient(connection)
                .start(
                        new CoreRpcContracts.TurnStartPayload(THREAD_ID, ExecutionOverrides.empty(), "任务"),
                        new CommandOptions("turn", 0));
        assertEquals(CONFIGURATION, result.configuration());
        assertFalse(JSON.encode(result.configuration()).json().contains("developerInstructions"));
        assertEquals(
                preview,
                new PromptManifestClient(connection)
                        .preview(WORKSPACE_ID, Optional.of(THREAD_ID), ExecutionOverrides.empty()));
    }

    private static ResolvedTurnConfigSummary configuration() {
        return new ResolvedTurnConfigSummary(
                new AgentRoleRef("worker", 1),
                new ProviderRef("local", 2, "model"),
                new PermissionProfileRef("read-only", 3),
                ApprovalPolicy.RISKY,
                new TurnBudget(1000, 100, 10, 0, Duration.ofMinutes(1)),
                Set.of("read_file"),
                Optional.empty(),
                "a".repeat(64),
                "b".repeat(64),
                false,
                List.of());
    }

    private static AgentTurn turn() {
        return new AgentTurn(
                TurnId.parse("00000000-0000-0000-0000-000000000003"),
                THREAD_ID,
                TurnStatus.RUNNING,
                3,
                CONFIGURATION.budget(),
                CONFIGURATION.role(),
                CONFIGURATION.provider(),
                CONFIGURATION.permissionProfile(),
                Path.of("/workspace"),
                CONFIGURATION.promptManifestDigest(),
                CONFIGURATION.toolCatalogDigest(),
                Optional.empty(),
                NOW,
                NOW,
                CONFIGURATION);
    }
}
