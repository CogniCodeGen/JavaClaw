package com.javaclaw.server.turn;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.AutomationExecutionSnapshot;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.AutomationStepPort;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.OrchestratedTurnCommand;
import com.javaclaw.extension.spi.OrchestratedTurnResult;
import com.javaclaw.extension.spi.ScheduledCommand;
import com.javaclaw.extension.spi.TurnOrchestrationPort;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeferredPortsTest {
    private final CanonicalJson json = new CanonicalJson();
    private final CancellationSource cancellation = new CancellationSource();

    @Test
    void AutomationStep端口绑定前拒绝且绑定后完整转发() throws Exception {
        DeferredAutomationStepPort deferred = new DeferredAutomationStepPort();
        AutomationStepPort.ToolCommand tool = toolCommand();
        AutomationStepPort.InputCommand input = inputCommand();
        AutomationStepPort.ToolResult toolResult = new AutomationStepPort.ToolResult(
                ThreadId.random(), TurnId.random(), true, json.encode(Map.of("ok", true)), Optional.of("receipt"));
        AutomationStepPort.InputResult inputResult =
                new AutomationStepPort.InputResult(ThreadId.random(), TurnId.random(), "request");

        assertThrows(IllegalStateException.class, () -> deferred.executeTool(tool, cancellation));
        assertThrows(IllegalStateException.class, () -> deferred.openInput(input, cancellation));
        assertThrows(NullPointerException.class, () -> deferred.bind(null));
        deferred.bind(new AutomationStepPort() {
            @Override
            public ToolResult executeTool(ToolCommand command, com.javaclaw.api.CancellationToken token) {
                assertSame(tool, command);
                assertSame(cancellation, token);
                return toolResult;
            }

            @Override
            public InputResult openInput(InputCommand command, com.javaclaw.api.CancellationToken token) {
                assertSame(input, command);
                assertSame(cancellation, token);
                return inputResult;
            }
        });

        assertSame(toolResult, deferred.executeTool(tool, cancellation));
        assertSame(inputResult, deferred.openInput(input, cancellation));
        assertThrows(IllegalStateException.class, () -> deferred.bind(AutomationStepPort.unavailable()));
    }

    @Test
    void Turn编排端口仅允许单次绑定() throws Exception {
        DeferredTurnOrchestrationPort deferred = new DeferredTurnOrchestrationPort();
        OrchestratedTurnCommand command = turnCommand();
        OrchestratedTurnResult expected = new OrchestratedTurnResult(
                ThreadId.random(), TurnId.random(), TurnStatus.COMPLETED, json.encode(Map.of("done", true)));

        assertThrows(IllegalStateException.class, () -> deferred.execute(command, cancellation));
        assertThrows(NullPointerException.class, () -> deferred.bind(null));
        TurnOrchestrationPort delegate = (received, token) -> {
            assertSame(command, received);
            assertSame(cancellation, token);
            return expected;
        };
        deferred.bind(delegate);

        assertSame(expected, deferred.execute(command, cancellation));
        assertThrows(IllegalStateException.class, () -> deferred.bind(delegate));
    }

    @Test
    void 派生Turn通过延迟绑定保留来源排除语义且不降级为普通调用() throws Exception {
        var deferred = new DeferredTurnOrchestrationPort();
        var command = turnCommand();
        var expected = new OrchestratedTurnResult(
                ThreadId.random(), TurnId.random(), TurnStatus.COMPLETED, json.encode(Map.of("derived", true)));
        assertThrows(IllegalStateException.class, () -> deferred.executeDerived(command, cancellation));
        deferred.bind(new TurnOrchestrationPort() {
            @Override
            public OrchestratedTurnResult execute(
                    OrchestratedTurnCommand received, com.javaclaw.api.CancellationToken token) {
                throw new AssertionError("派生 Turn 不得调用普通执行入口");
            }

            @Override
            public OrchestratedTurnResult executeDerived(
                    OrchestratedTurnCommand received, com.javaclaw.api.CancellationToken token) {
                assertSame(command, received);
                assertSame(cancellation, token);
                return expected;
            }
        });
        assertSame(expected, deferred.executeDerived(command, cancellation));
    }

    @Test
    void Schedule端口绑定后保留所有命令身份() throws Exception {
        DeferredScheduledCommandPort deferred = new DeferredScheduledCommandPort();
        WorkspaceId workspaceId = WorkspaceId.random();
        var payload = json.encode(Map.of("fixed", "value"));
        ScheduledCommand command = new ScheduledCommand(
                workspaceId,
                "site",
                "refresh",
                payload,
                "occurrence",
                6,
                Optional.of("a".repeat(64)),
                Optional.empty());
        ExtensionResponse expected = new ExtensionResponse(json.encode(Map.of("accepted", true)), 7);

        assertThrows(IllegalStateException.class, () -> deferred.execute(command, cancellation));
        assertThrows(NullPointerException.class, () -> deferred.bind(null));
        deferred.bind((actual, token) -> {
            assertSame(command, actual);
            assertSame(cancellation, token);
            return expected;
        });

        assertSame(expected, deferred.execute(command, cancellation));
        assertThrows(IllegalStateException.class, () -> deferred.bind((actual, token) -> expected));
    }

    private AutomationStepPort.ToolCommand toolCommand() {
        return new AutomationStepPort.ToolCommand(stepContext(), "builtin.echo", json.encode(Map.of("text", "ok")));
    }

    private AutomationStepPort.InputCommand inputCommand() {
        return new AutomationStepPort.InputCommand(
                stepContext(), "请确认", json.encode(Map.of("type", "object")), Instant.parse("2026-09-02T10:10:00Z"));
    }

    private AutomationStepPort.StepContext stepContext() {
        return new AutomationStepPort.StepContext(
                WorkspaceId.random(), Optional.empty(), "Workflow step", snapshot(), "step-key");
    }

    private OrchestratedTurnCommand turnCommand() {
        return new OrchestratedTurnCommand(
                WorkspaceId.random(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                "Automation turn",
                snapshot(),
                "execute one unit",
                json.encode(Map.of("unit", 1)),
                "turn-key");
    }

    private AutomationExecutionSnapshot snapshot() {
        PermissionProfile permission = permission();
        return TurnV6Fixtures.snapshot(
                new AgentRoleRef("profile", 1),
                new ProviderRef("provider", 1, "model"),
                new PermissionProfileRef(permission.id(), permission.version()),
                new TurnBudget(1_000, 1_000, 4, 1, Duration.ofMinutes(1)),
                new ToolCatalogSnapshot(
                        TurnId.random(), 1, List.of(), permission, Instant.parse("2026-09-02T10:00:00Z")),
                Optional.empty());
    }

    private static PermissionProfile permission() {
        return new PermissionProfile(
                "automation",
                1,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), false),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(30)),
                new ToolPermission(Set.of(), ToolRisk.READ_ONLY, ApprovalRequirement.NONE),
                new ResourceLimits(512L * 1024 * 1024, 64L * 1024, 4, 128));
    }
}
