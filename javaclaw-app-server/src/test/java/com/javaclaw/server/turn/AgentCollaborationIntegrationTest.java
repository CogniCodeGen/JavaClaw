package com.javaclaw.server.turn;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.ManagedWorktreeState;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.api.TurnId;
import com.javaclaw.protocol.CollaborationRpcContracts;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.runtime.BudgetAccount;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.runtime.TurnFailureException;
import com.javaclaw.server.persistence.PersistenceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCollaborationIntegrationTest extends AgentCollaborationIntegrationFixture {
    @Test
    void 写型子任务创建真实Worktree且同键重试沿用执行和父预算() throws Exception {
        PermissionProfile current = permissions.require("collaboration", 2);
        FilePermission files = new FilePermission(
                List.of(workspaceRoot().resolve("allowed")),
                List.of(workspaceRoot().resolve("allowed")),
                false,
                false);
        updateParentPermission(permission(current, files, current.resources()));
        createParent(true);
        var request = request("worker");
        var command = identity("agent/spawn", "worker", parent.revision(), request);

        var spawned = service.spawn(command, request);
        assertTrue(childStarted.await(3, TimeUnit.SECONDS));
        var worktree = worktrees.findByChild(spawned.thread().id()).orElseThrow();
        var retried = service.spawn(command, request);

        assertEquals(ThreadExecutionIntent.ISOLATED_WRITE, spawned.thread().executionIntent());
        assertEquals(ManagedWorktreeState.RUNNING, worktree.state());
        assertEquals(worktree.executionRoot(), spawned.turn().executionRoot());
        assertTrue(Files.isRegularFile(worktree.executionRoot().resolve("allowed/tracked.txt")));
        assertEquals(
                List.of(worktree.executionRoot().resolve("allowed")),
                childCommand.get().effectivePermissions().files().writeRoots());
        assertFalse(
                childCommand.get().effectivePermissions().files().writeRoots().contains(workspaceRoot()));
        assertEquals(spawned.turn().id(), retried.turn().id());
        assertEquals(1, gitSandbox.creations.get());
        assertEquals(1, childExecutions.get());
        assertEquals(7000, account.remainingInputTokens());
        assertEquals(1900, account.remainingOutputTokens());
        assertEquals(2, core.listThreads(workspace).size());
    }

    @Test
    void Git已创建但H2未提交时重试复用目录和恢复后的预算() throws Exception {
        retryProvisionAfterFailure(false);
    }

    @Test
    void 启动补偿已持久化Worktree后同键重试复用绑定和恢复后的预算() throws Exception {
        retryProvisionAfterFailure(true);
    }

    private void retryProvisionAfterFailure(boolean reconcileBeforeRetry) throws Exception {
        createParent(true);
        var request = request("worker");
        var command = identity("agent/spawn", "provision-retry", parent.revision(), request);
        gitSandbox.failAfterCreate.set(true);

        assertThrows(PersistenceException.class, () -> service.spawn(command, request));
        ConversationThread reserved = onlyChild();
        assertTrue(children.started(reserved.id()).isEmpty());
        assertTrue(worktrees.findByChild(reserved.id()).isEmpty());
        assertEquals(7000, account.remainingInputTokens());
        // 模拟恢复时从持久 reservation 重建内存账户，不能再次分配额度。
        journal.deactivateBudget(parent.id(), account);
        account = new BudgetAccount(BUDGET, CLOCK, parent.createdAt(), ModelUsage.zero(), 0);
        journal.activateBudget(parent.id(), account);
        if (reconcileBeforeRetry) {
            worktrees.reconcileProvisioning();
        }

        var resumed = service.spawn(command, request);
        assertTrue(childStarted.await(3, TimeUnit.SECONDS));
        assertEquals(reserved.id(), resumed.thread().id());
        assertEquals(
                resumed.turn().id(),
                children.started(reserved.id()).orElseThrow().id());
        assertEquals(1, worktrees.list(workspace, false).size());
        assertEquals(1, gitSandbox.creations.get());
        assertEquals(1, childExecutions.get());
        assertEquals(7000, account.remainingInputTokens());
        assertEquals(1900, account.remainingOutputTokens());
        assertEquals(2, core.listThreads(workspace).size());
    }

    @Test
    void 子配置资源更宽时真实目录绑定使用父交集并成功启动() throws Exception {
        PermissionProfile current = permissions.require("collaboration", 2);
        ResourceLimits narrower = new ResourceLimits(32L * 1024 * 1024, 1024 * 1024, 1, 16);
        updateParentPermission(permission(current, current.files(), narrower));
        PermissionProfile wider = permissions.cloneProfile(
                identity("permissionProfile/clone", "wide-child", 0, Map.of()),
                new PermissionProfileRef("collaboration", 2),
                "wide-child");
        createParent(true);
        var original = request("explorer");
        ExecutionOverrides selection = new ExecutionOverrides(
                Optional.empty(),
                Optional.empty(),
                Optional.of(new PermissionProfileRef(wider.id(), wider.version())),
                Optional.empty(),
                original.execution().budget(),
                Optional.empty(),
                Optional.empty());
        var request = new CollaborationRpcContracts.SpawnPayload(
                parent.id(), "explorer", original.message(), selection, original.title());

        var spawned = service.spawn(identity("agent/spawn", "bounded-permission", parent.revision(), request), request);

        assertTrue(childStarted.await(3, TimeUnit.SECONDS));
        assertEquals(ThreadExecutionIntent.READ_ONLY, spawned.thread().executionIntent());
        assertEquals(narrower, childCommand.get().effectivePermissions().resources());
        assertEquals(
                narrower, childCommand.get().toolCatalog().permissionCeiling().resources());
        assertEquals(0, gitSandbox.creations.get());
        assertEquals(7000, account.remainingInputTokens());
    }

    @Test
    void 预留后父权限撤销会在创建子Turn之前阻止绑定且不再次扣预算() {
        createParent(true);
        var request = request("explorer");
        ConversationThread reserved = reserveOnly("revoked-parent", request);
        PermissionProfile current = permissions.require("collaboration", 2);
        updateParentPermission(
                permission(current, current.files(), new ResourceLimits(32L * 1024 * 1024, 1024 * 1024, 1, 16)));

        TurnFailureException failure = assertThrows(
                TurnFailureException.class,
                () -> service.spawn(identity("agent/spawn", "revoked-parent", parent.revision(), request), request));

        assertEquals("TOOL_CATALOG_CHANGED", failure.code());
        assertTrue(children.started(reserved.id()).isEmpty());
        assertEquals(0, childExecutions.get());
        assertEquals(7000, account.remainingInputTokens());
        assertEquals(2, core.listThreads(workspace).size());
    }

    @Test
    void 预留后父取消会在创建子Turn之前停止且不再次扣预算() {
        createParent(true);
        var request = request("explorer");
        ConversationThread reserved = reserveOnly("cancelled-parent", request);
        core.requestTurnCancellation(
                identity("turn/cancel", "cancel-parent", parent.revision(), Map.of()), parent.id(), "停止子任务");

        assertThrows(
                TurnCancelledException.class,
                () -> service.spawn(identity("agent/spawn", "cancelled-parent", parent.revision(), request), request));

        assertTrue(children.started(reserved.id()).isEmpty());
        assertEquals(0, childExecutions.get());
        assertEquals(7000, account.remainingInputTokens());
        assertEquals(2, core.listThreads(workspace).size());
    }

    @Test
    void 多级写型子任务继承祖父资源和父级限制并逐级映射到各自Worktree() throws Exception {
        var middle = createMiddleWorker();
        var running = core.findTurn(middle.turn().id()).orElseThrow();
        BudgetAccount middleAccount = activateToolBoundary(running);
        try {
            var leafRequest = nestedRequest(
                    running.id(),
                    new PermissionProfileRef("collaboration", 2),
                    new TurnBudget(1000, 100, 0, 0, Duration.ofMinutes(1)));
            var leaf = service.spawn(identity("agent/spawn", "leaf", running.revision(), leafRequest), leafRequest);
            var payload = new CoreRpcContracts.TurnStartPayload(
                    leaf.thread().id(),
                    AgentConfigurationResolver.overrides(
                            core.resolvedConfig(leaf.turn().id())),
                    leafRequest.message());
            var command = factory.create(core.findTurn(leaf.turn().id()).orElseThrow(), payload);

            assertEquals(
                    new ResourceLimits(32L * 1024 * 1024, 1024 * 1024, 1, 8),
                    command.effectivePermissions().resources());
            assertEquals(
                    List.of(leaf.turn().executionRoot().resolve("allowed")),
                    command.effectivePermissions().files().writeRoots());
            assertEquals(
                    List.of(leaf.turn().executionRoot().resolve("allowed")),
                    command.toolCatalog().permissionCeiling().files().readRoots());
            assertFalse(leaf.turn().executionRoot().equals(middle.turn().executionRoot()));
            assertEquals(2, gitSandbox.creations.get());
            assertEquals(6000, account.remainingInputTokens());
            assertEquals(1000, middleAccount.remainingInputTokens());
        } finally {
            journal.deactivateBudget(running.id(), middleAccount);
        }
    }

    private CollaborationRpcContracts.SpawnResult createMiddleWorker() throws Exception {
        PermissionProfile rootProfile = permissions.require("collaboration", 2);
        FilePermission files = new FilePermission(
                List.of(workspaceRoot().resolve("allowed")),
                List.of(workspaceRoot().resolve("allowed")),
                false,
                false);
        ResourceLimits rootLimits = new ResourceLimits(32L * 1024 * 1024, 1024 * 1024, 1, 16);
        updateParentPermission(permission(rootProfile, files, rootLimits));
        PermissionProfile middleProfile = permissions.cloneProfile(
                identity("permissionProfile/clone", "middle-profile", 0, Map.of()),
                new PermissionProfileRef("collaboration", 2),
                "middle-profile");
        PermissionProfile middleLimits = permission(
                middleProfile, middleProfile.files(), new ResourceLimits(64L * 1024 * 1024, 2 * 1024 * 1024, 1, 8));
        permissions.update(identity("permissionProfile/update", "middle-limits", 1, middleLimits), middleLimits);
        createParent(true);
        var middleRequest = nestedRequest(
                parent.id(),
                new PermissionProfileRef("middle-profile", 2),
                new TurnBudget(2000, 500, 2, 1, Duration.ofMinutes(1)));
        var middle = service.spawn(identity("agent/spawn", "middle", parent.revision(), middleRequest), middleRequest);
        assertTrue(childStarted.await(3, TimeUnit.SECONDS));
        return middle;
    }

    private static CollaborationRpcContracts.SpawnPayload nestedRequest(
            TurnId parentId, PermissionProfileRef permissions, TurnBudget budget) {
        ExecutionOverrides selected = new ExecutionOverrides(
                Optional.empty(),
                Optional.empty(),
                Optional.of(permissions),
                Optional.empty(),
                Optional.of(budget),
                Optional.empty(),
                Optional.empty());
        return new CollaborationRpcContracts.SpawnPayload(parentId, "worker", "检查后代执行范围", selected, "Nested");
    }

    private ConversationThread onlyChild() {
        return core.listThreads(workspace).stream()
                .filter(thread -> thread.parentThreadId()
                        .filter(parent.threadId()::equals)
                        .isPresent())
                .findFirst()
                .orElseThrow();
    }

    private static PermissionProfile permission(
            PermissionProfile current, FilePermission files, ResourceLimits resources) {
        return new PermissionProfile(
                current.id(),
                current.version() + 1,
                files,
                current.network(),
                current.processes(),
                current.tools(),
                resources);
    }
}
