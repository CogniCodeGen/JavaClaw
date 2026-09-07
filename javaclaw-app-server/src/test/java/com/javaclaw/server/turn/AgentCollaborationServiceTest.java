package com.javaclaw.server.turn;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.PermissionConstraint;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.server.ProviderEndpointTestFixtures;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.PersistenceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCollaborationServiceTest extends AgentCollaborationTestFixture {
    @Test
    void explorer在真实工具Checkpoint预留预算并冻结只读约束() throws Exception {
        createParent(true);
        var request = request("explorer");
        var spawned = service.spawn(identity("agent/spawn", "explorer", parent.revision(), request), request);
        assertTrue(childStarted.await(3, TimeUnit.SECONDS));

        assertEquals(ThreadExecutionIntent.READ_ONLY, spawned.thread().executionIntent());
        assertEquals(
                PermissionConstraint.READ_ONLY,
                core.resolvedConfig(spawned.turn().id()).permissionConstraint());
        assertEquals(7000, account.remainingInputTokens());
        assertEquals(
                parent.id(),
                core.parentTurn(spawned.thread().id()).orElseThrow().id());
        assertEquals(spawned.configuration(), spawned.turn().resolvedConfig());
        assertFalse(spawned.configuration().effectiveCapabilities().contains(CollaborationTools.SPAWN));
        assertEquals(TurnStatus.RUNNING, service.read(spawned.turn().id()).status());
        AgentTurn running = service.read(spawned.turn().id());
        service.interrupt(identity("agent/interrupt", "interrupt", running.revision(), Map.of()), running.id(), "测试取消");
        awaitCancelled(running.id());
        assertThrows(PersistenceException.class, () -> service.read(parent.id()));
    }

    @Test
    void 持久化的Workspace子智能体默认模型进入实际Spawn解析() {
        providers.create(
                identity("provider/create", "child-provider", 0, Map.of()),
                "child-provider",
                ProviderEndpointTestFixtures.chat("Child", ProviderAdapter.OPENAI_COMPATIBLE, "child-model"),
                ProviderLifecycle.ACTIVE);
        ProviderRef selected = new ProviderRef("child-provider", 1, "child-model");
        ExecutionOverrides childDefaults = new ExecutionOverrides(
                Optional.empty(),
                Optional.of(selected),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(com.javaclaw.api.ReasoningPreference.HIGH));
        defaults.updateSubagentDefaults(
                identity("execution/subagent/update", "child-defaults", 0, childDefaults),
                Optional.of(workspace),
                Optional.empty(),
                childDefaults);
        createParent(true);
        var request = request("explorer");
        var spawned = service.spawn(identity("agent/spawn", "configured-child", parent.revision(), request), request);

        assertEquals(selected, spawned.configuration().provider());
        assertEquals(
                Optional.of(com.javaclaw.api.ReasoningPreference.HIGH),
                spawned.configuration().reasoning());
        assertEquals(new ProviderRef("provider", 1, "model"), parent.provider());
    }

    @Test
    void Role归档后的同键重试复用冻结版本且不重复扣父预算() throws Exception {
        AgentRole custom = roles.clone(
                identity("agent/role/clone", "role-clone", 0, Map.of()),
                roles.requireLatest("explorer").ref(),
                "reviewer",
                "Reviewer");
        createParent(true);
        var request = request(custom.id());
        CommandIdentity command = identity("agent/spawn", "retry-child", parent.revision(), request);
        var first = service.spawn(command, request);
        assertTrue(childStarted.await(3, TimeUnit.SECONDS));
        roles.archive(identity("agent/role/archive", "archive", custom.revision(), Map.of()), custom.id());
        var retried = service.spawn(command, request);

        assertEquals(first.thread(), retried.thread());
        assertEquals(first.turn().id(), retried.turn().id());
        assertEquals(custom.ref(), retried.configuration().role());
        assertEquals(RoleLifecycle.ARCHIVED, roles.requireLatest(custom.id()).lifecycle());
        assertEquals(7000, account.remainingInputTokens());
        assertEquals(2, core.listThreads(workspace).size());
    }

    @Test
    void 父级持久取消传播到正在执行的子Harness() throws Exception {
        createParent(true);
        var request = request("explorer");
        var spawned = service.spawn(identity("agent/spawn", "parent-cancel", parent.revision(), request), request);
        assertTrue(childStarted.await(3, TimeUnit.SECONDS));
        core.requestTurnCancellation(
                identity("turn/cancel", "cancel-parent", parent.revision(), Map.of()), parent.id(), "父任务取消");
        awaitCancelled(spawned.turn().id());
        assertTrue(core.cancellationRequested(parent.id()));
    }

    @Test
    void 缺少冻结Spawn目录时拒绝且不扣预算() {
        spawnCatalog = false;
        createParent(true);
        var request = request("explorer");
        assertThrows(
                PersistenceException.class,
                () -> service.spawn(identity("agent/spawn", "no-tool", parent.revision(), request), request));
        assertEquals(8000, account.remainingInputTokens());
        assertEquals(1, core.listThreads(workspace).size());
    }

    @Test
    void 模型调用进行中不能越过工具Checkpoint创建子任务() {
        createParent(false);
        var request = request("explorer");
        assertThrows(
                PersistenceException.class,
                () -> service.spawn(identity("agent/spawn", "model-phase", parent.revision(), request), request));
        assertEquals(8000, account.remainingInputTokens());
        assertEquals(1, core.listThreads(workspace).size());
    }

    @Test
    void 协作Wait工具只返回自己子任务的持久消息且零等待仍检查取消() throws Exception {
        createParent(true);
        var request = request("explorer");
        var spawned = service.spawn(identity("agent/spawn", "wait-child", parent.revision(), request), request);
        assertTrue(childStarted.await(3, TimeUnit.SECONDS));
        CollaborationToolExecutor executor = new CollaborationToolExecutor(service, core, json, CLOCK);
        var wait = waitRequest(parent.id(), spawned.turn().id(), 0);
        var first = executor.execute(wait, new com.javaclaw.api.CancellationSource())
                .result();
        assertTrue(first.receipt().isEmpty());
        assertTrue(json.textField(first.output(), "assistantText").isEmpty());
        journal.append(
                spawned.turn().id(),
                "assistant",
                com.javaclaw.api.CoreSchemas.MESSAGE,
                new CorePayloads.Message(MessageRole.ASSISTANT, "已检查两个文件", List.of(), Optional.empty()),
                com.javaclaw.api.ItemStatus.COMPLETED);
        var read = executor.execute(wait, new com.javaclaw.api.CancellationSource())
                .result();
        assertEquals(Optional.of("已检查两个文件"), json.textField(read.output(), "assistantText"));
        assertEquals(
                spawned.turn().id(),
                json.decode(json.objectField(read.output(), "turn").orElseThrow(), AgentTurn.class)
                        .id());
        assertThrows(
                com.javaclaw.runtime.TurnFailureException.class,
                () -> executor.execute(
                        waitRequest(TurnId.random(), spawned.turn().id(), 0),
                        new com.javaclaw.api.CancellationSource()));
        com.javaclaw.api.CancellationSource cancelled = new com.javaclaw.api.CancellationSource();
        cancelled.cancel("用户取消等待");
        assertThrows(com.javaclaw.api.TurnCancelledException.class, () -> executor.execute(wait, cancelled));
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(
                        waitRequest(parent.id(), spawned.turn().id(), 60_001),
                        new com.javaclaw.api.CancellationSource()));
    }

    @Test
    void 已结束子Thread不能通过新Turn幂等键或篡改配置获得额外额度() throws Exception {
        createParent(true);
        var request = request("explorer");
        var spawned = service.spawn(identity("agent/spawn", "one-child-turn", parent.revision(), request), request);
        assertTrue(childStarted.await(3, TimeUnit.SECONDS));
        finishChild.countDown();
        awaitStatus(spawned.turn().id(), TurnStatus.COMPLETED);
        var configuration = core.resolvedConfig(spawned.turn().id());
        var same = childRequest(spawned.turn(), configuration);
        assertThrows(
                PersistenceException.class,
                () -> core.startTurn(identity("turn/start", "second-child-turn", 0, same), same));
        var changed = new com.javaclaw.api.ResolvedTurnConfig(
                configuration.role(),
                configuration.provider(),
                configuration.permissionProfile(),
                configuration.approvalPolicy(),
                new TurnBudget(1001, 100, 0, 0, Duration.ofMinutes(1)),
                configuration.effectiveCapabilities(),
                configuration.reasoning(),
                configuration.permissionConstraint(),
                configuration.effectiveSkills(),
                configuration.promptManifestDigest(),
                configuration.toolCatalogDigest(),
                configuration.provenance());
        var forged = childRequest(spawned.turn(), changed);
        assertThrows(
                PersistenceException.class,
                () -> core.startTurn(identity("turn/start", "changed-child-budget", 0, forged), forged));
        assertEquals(7000, account.remainingInputTokens());
        assertEquals(
                spawned.turn().id(),
                service.spawn(identity("agent/spawn", "one-child-turn", parent.revision(), request), request)
                        .turn()
                        .id());
    }
}
