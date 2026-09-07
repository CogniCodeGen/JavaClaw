package com.javaclaw.server.turn;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.protocol.CollaborationRpcContracts;
import com.javaclaw.runtime.TurnFailureException;
import com.javaclaw.server.persistence.PersistenceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCollaborationBoundaryTest extends AgentCollaborationTestFixture {
    @Test
    void 协作工具创建与中断返回可追踪凭据且未知工具被拒绝() throws Exception {
        createParent(true);
        CollaborationToolExecutor executor = new CollaborationToolExecutor(service, core, json, CLOCK);
        ToolCallRequest spawn = tool(
                parent.id(),
                CollaborationTools.SPAWN,
                Map.of("agentType", "explorer", "message", "检查范围", "title", "Review"),
                "tool-spawn");
        var outcome = executor.execute(spawn, new CancellationSource()).result();
        var child = json.decode(outcome.output(), CollaborationRpcContracts.SpawnResult.class);
        assertTrue(childStarted.await(3, TimeUnit.SECONDS));
        assertTrue(outcome.receipt().isPresent());
        assertEquals(spawn.arguments().sha256(), outcome.receipt().orElseThrow().requestDigest());
        assertEquals(2000, child.configuration().budget().inputTokens());
        assertEquals(Duration.ofMinutes(5), child.configuration().budget().wallTime());
        var interrupt = executor.execute(
                        tool(
                                parent.id(),
                                CollaborationTools.INTERRUPT,
                                Map.of("turnId", child.turn().id(), "reason", "范围已确认"),
                                "tool-interrupt"),
                        new CancellationSource())
                .result();
        assertTrue(interrupt.receipt().isPresent());
        awaitCancelled(child.turn().id());
        TurnFailureException unknown = assertThrows(
                TurnFailureException.class,
                () -> executor.execute(
                        tool(parent.id(), "unregistered_agent_tool", Map.of(), "unknown"), new CancellationSource()));
        assertEquals("TOOL_NOT_FOUND", unknown.code());
    }

    @Test
    void 显式角色冲突与未知角色拒绝且合法同角色预算被父截止时间截断() {
        createParent(true);
        ExecutionOverrides conflict =
                TurnV6Fixtures.selection(roles.requireLatest("default").ref());
        var wrongRole = new CollaborationRpcContracts.SpawnPayload(parent.id(), "explorer", "检查", conflict, "Review");
        assertThrows(
                PersistenceException.class,
                () -> service.spawn(identity("agent/spawn", "role-conflict", parent.revision(), wrongRole), wrongRole));
        var missingRole = request("missing-role");
        assertThrows(
                PersistenceException.class,
                () -> service.spawn(
                        identity("agent/spawn", "missing-role", parent.revision(), missingRole), missingRole));
        ExecutionOverrides bounded = new ExecutionOverrides(
                Optional.of(roles.requireLatest("explorer").ref()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new TurnBudget(1000, 100, 0, 0, Duration.ofHours(1))),
                Optional.empty(),
                Optional.empty());
        var accepted = new CollaborationRpcContracts.SpawnPayload(parent.id(), "explorer", "检查", bounded, "Review");
        var child = service.spawn(identity("agent/spawn", "matching-role", parent.revision(), accepted), accepted);
        assertEquals(Duration.ofSeconds(599), child.configuration().budget().wallTime());
        assertEquals(7000, account.remainingInputTokens());
    }

    @Test
    void 父Turn缺失或没有剩余时间时不能预留预算() {
        createParent(true);
        var missing = new CollaborationRpcContracts.SpawnPayload(
                TurnId.random(), "explorer", "检查", ExecutionOverrides.empty(), "Review");
        assertThrows(
                PersistenceException.class,
                () -> service.spawn(identity("agent/spawn", "missing-parent", 1, missing), missing));
        var request = request("explorer");
        for (long seconds : new long[] {599, 600}) {
            AgentCollaborationService expired = at(Duration.ofSeconds(seconds));
            assertThrows(
                    PersistenceException.class,
                    () -> expired.spawn(
                            identity("agent/spawn", "expired-" + seconds, parent.revision(), request), request));
        }
        assertEquals(8000, account.remainingInputTokens());
        assertEquals(1, core.listThreads(workspace).size());
        assertThrows(PersistenceException.class, () -> service.read(TurnId.random()));
    }

    @Test
    void 默认子预算受父剩余时间约束且同键不同内容不能复用创建结果() {
        createParent(true);
        AgentCollaborationService nearingDeadline = at(Duration.ofMinutes(8));
        var request = new CollaborationRpcContracts.SpawnPayload(
                parent.id(), "explorer", "检查", ExecutionOverrides.empty(), "Review");
        var identity = identity("agent/spawn", "fixed-request", parent.revision(), request);
        var child = nearingDeadline.spawn(identity, request);
        assertEquals(Duration.ofSeconds(119), child.configuration().budget().wallTime());
        assertEquals(6000, account.remainingInputTokens());
        var changed = new CollaborationRpcContracts.SpawnPayload(
                parent.id(), "explorer", "另一项工作", ExecutionOverrides.empty(), "Review");
        assertThrows(
                PersistenceException.class,
                () -> nearingDeadline.spawn(
                        identity("agent/spawn", "fixed-request", parent.revision(), changed), changed));
        assertEquals(6000, account.remainingInputTokens());
        assertEquals(2, core.listThreads(workspace).size());
    }

    @Test
    void 父权限实时撤销工具或降低风险后冻结目录不能继续授权Spawn() {
        createParent(true);
        var request = request("explorer");
        updateTools(2, Set.of(CollaborationTools.WAIT), ToolRisk.EXTERNAL_EFFECT);
        assertThrows(
                PersistenceException.class,
                () -> service.spawn(identity("agent/spawn", "tool-revoked", parent.revision(), request), request));
        updateTools(3, TOOLS, ToolRisk.READ_ONLY);
        assertThrows(
                PersistenceException.class,
                () -> service.spawn(identity("agent/spawn", "risk-revoked", parent.revision(), request), request));
        assertEquals(8000, account.remainingInputTokens());
        assertEquals(1, core.listThreads(workspace).size());
    }

    @Test
    void 等待可有界轮询并对提前取消负数超时和其他父中断失败关闭() throws Exception {
        createParent(true);
        var request = request("explorer");
        var child = service.spawn(identity("agent/spawn", "wait-boundary", parent.revision(), request), request);
        assertTrue(childStarted.await(3, TimeUnit.SECONDS));
        CollaborationToolExecutor executor = new CollaborationToolExecutor(service, core, json, CLOCK);
        var result = executor.execute(waitRequest(parent.id(), child.turn().id(), 200), new CancellationSource())
                .result();
        assertEquals(
                TurnStatus.RUNNING,
                json.decode(json.objectField(result.output(), "turn").orElseThrow(), com.javaclaw.api.AgentTurn.class)
                        .status());
        CancellationSource cancelled = new CancellationSource();
        cancelled.cancel("取消等待");
        assertThrows(
                TurnCancelledException.class,
                () -> executor.execute(waitRequest(parent.id(), child.turn().id(), 200), cancelled));
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(waitRequest(parent.id(), child.turn().id(), -1), new CancellationSource()));
        TurnFailureException foreign = assertThrows(
                TurnFailureException.class,
                () -> executor.execute(
                        tool(
                                TurnId.random(),
                                CollaborationTools.INTERRUPT,
                                Map.of("turnId", child.turn().id(), "reason", "错误父任务"),
                                "foreign"),
                        new CancellationSource()));
        assertEquals("CHILD_NOT_OWNED", foreign.code());
        finishChild.countDown();
        awaitStatus(child.turn().id(), TurnStatus.COMPLETED);
        var terminal = executor.execute(
                        tool(
                                parent.id(),
                                CollaborationTools.WAIT,
                                new DefaultWait(child.turn().id(), Optional.empty()),
                                "terminal-wait"),
                        new CancellationSource())
                .result();
        assertEquals(
                TurnStatus.COMPLETED,
                json.decode(json.objectField(terminal.output(), "turn").orElseThrow(), com.javaclaw.api.AgentTurn.class)
                        .status());
    }

    private AgentCollaborationService at(Duration elapsed) {
        return new AgentCollaborationService(services, children, dispatcher, json, Clock.offset(CLOCK, elapsed));
    }

    private void updateTools(long revision, Set<String> allowed, ToolRisk risk) {
        PermissionProfile current = permissions.require("collaboration", revision);
        PermissionProfile updated = new PermissionProfile(
                current.id(),
                revision + 1,
                current.files(),
                current.network(),
                current.processes(),
                new ToolPermission(allowed, risk, current.tools().approvalRequirement()),
                current.resources());
        permissions.update(identity("permissionProfile/update", "tools-" + revision, revision, updated), updated);
    }

    private ToolCallRequest tool(TurnId owner, String operation, Object arguments, String key) {
        return new ToolCallRequest(owner, key, new ToolIdentity("core", operation, 1), json.encode(arguments), key, 1);
    }

    private record DefaultWait(TurnId turnId, Optional<Long> timeoutMillis) {}
}
