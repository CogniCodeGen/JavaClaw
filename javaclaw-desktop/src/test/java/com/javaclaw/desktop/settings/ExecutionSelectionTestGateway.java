package com.javaclaw.desktop.settings;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RemoteRpcException;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.protocol.JsonRpcError;
import com.javaclaw.protocol.ProtocolErrorCode;

/** 独立区分安装、Workspace 和 Thread 的 SDK 夹具，避免测试把所有继承层投影成同一配置。 */
final class ExecutionSelectionTestGateway extends TestCoreSettingsGateway {
    static final Instant NOW = Instant.parse("2026-09-07T01:00:00Z");
    final Map<Optional<WorkspaceId>, ExecutionConfiguration> defaults = new HashMap<>();
    final Map<ThreadId, ExecutionConfiguration> threads = new HashMap<>();
    final Queue<CompletableFuture<Optional<ExecutionConfiguration>>> workspaceReads = new ArrayDeque<>();
    final List<CommandOptions> writes = new ArrayList<>();
    CompletableFuture<AgentRole> roleResponse;
    CompletableFuture<ExecutionConfiguration> writeResponse;
    RuntimeException readFailure;
    int catalogReads;
    ExecutionOverrides submitted;
    Workspace workspace = DesktopTestFixtures.workspace();

    ExecutionSelectionTestGateway() {
        profiles.add(new AgentRole(
                "default", 1, RoleLifecycle.ACTIVE, TestCoreSettingsFixtures.profileSpec(), false, NOW, NOW));
    }

    @Override
    public CompletionStage<List<AgentRole>> roles() {
        catalogReads++;
        if (readFailure != null) {
            RuntimeException failure = readFailure;
            readFailure = null;
            return CompletableFuture.failedFuture(failure);
        }
        return super.roles();
    }

    @Override
    public CompletionStage<AgentRole> role(AgentRoleRef reference) {
        if (roleResponse != null) {
            CompletableFuture<AgentRole> response = roleResponse;
            roleResponse = null;
            return response;
        }
        return super.role(reference);
    }

    @Override
    public CompletionStage<List<ProviderEndpoint>> providers() {
        return CompletableFuture.completedFuture(List.of(TestCoreSettingsFixtures.provider(
                1, TestCoreSettingsFixtures.providerSpec(Optional.empty()), ProviderLifecycle.ACTIVE)));
    }

    @Override
    public CompletionStage<List<PermissionProfile>> permissionProfiles() {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public CompletionStage<List<Workspace>> workspaces() {
        return CompletableFuture.completedFuture(List.of(workspace));
    }

    @Override
    public CompletionStage<Workspace> renameWorkspace(Workspace current, String name, CommandOptions options) {
        workspace = new Workspace(
                current.id(),
                name,
                current.root(),
                current.lifecycle(),
                current.revision() + 1,
                current.createdAt(),
                NOW);
        return CompletableFuture.completedFuture(workspace);
    }

    @Override
    public CompletionStage<Optional<ExecutionConfiguration>> executionDefaults(Optional<WorkspaceId> workspaceId) {
        if (workspaceId.isPresent() && !workspaceReads.isEmpty()) {
            return workspaceReads.remove();
        }
        return CompletableFuture.completedFuture(Optional.ofNullable(defaults.get(workspaceId)));
    }

    @Override
    public CompletionStage<Optional<ExecutionConfiguration>> threadExecution(
            WorkspaceId workspaceId, ThreadId threadId) {
        return CompletableFuture.completedFuture(Optional.ofNullable(threads.get(threadId)));
    }

    @Override
    public CompletionStage<ExecutionConfiguration> updateExecutionDefaults(
            Optional<WorkspaceId> workspaceId, ExecutionOverrides execution, CommandOptions options) {
        writes.add(options);
        submitted = execution;
        if (writeResponse != null) {
            CompletableFuture<ExecutionConfiguration> response = writeResponse;
            writeResponse = null;
            return response;
        }
        long current = Optional.ofNullable(defaults.get(workspaceId))
                .map(ExecutionConfiguration::revision)
                .orElse(0L);
        if (options.expectedRevision() != current) {
            return CompletableFuture.failedFuture(new RemoteRpcException(
                    new JsonRpcError(ProtocolErrorCode.REVISION_CONFLICT, "版本冲突", Optional.empty())));
        }
        ExecutionConfiguration saved = configuration(workspaceId, execution, current + 1);
        defaults.put(workspaceId, saved);
        return CompletableFuture.completedFuture(saved);
    }

    static ExecutionConfiguration configuration(
            Optional<WorkspaceId> workspace, ExecutionOverrides execution, long revision) {
        return new ExecutionConfiguration(workspace, Optional.empty(), execution, revision, NOW);
    }
}
