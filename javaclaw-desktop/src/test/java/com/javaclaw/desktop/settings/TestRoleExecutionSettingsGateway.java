package com.javaclaw.desktop.settings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleFileExport;
import com.javaclaw.api.AgentRoleFileFormat;
import com.javaclaw.api.AgentRoleFilePreview;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.PromptManifestPreview;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;

/** 角色与执行配置的内存 SDK 夹具；与 Provider、权限夹具分开保持职责清晰。 */
abstract class TestRoleExecutionSettingsGateway implements CoreSettingsGateway {
    private static final Instant NOW = Instant.parse("2026-09-01T01:00:00Z");
    final List<AgentRole> profiles = new ArrayList<>();
    final TestWorkspaceSettings workspaceSettings = new TestWorkspaceSettings();

    @Override
    public CompletionStage<List<AgentRole>> roles() {
        return completed(List.copyOf(profiles));
    }

    @Override
    public CompletionStage<AgentRole> role(AgentRoleRef reference) {
        return completed(profiles.stream()
                .filter(candidate ->
                        candidate.id().equals(reference.id()) && candidate.revision() == reference.revision())
                .findFirst()
                .orElseThrow());
    }

    @Override
    public CompletionStage<AgentRole> createRole(String id, AgentRoleSpec spec, CommandOptions options) {
        AgentRole created = new AgentRole(id, 1, RoleLifecycle.ACTIVE, spec, false, NOW, NOW);
        profiles.add(created);
        return completed(created);
    }

    @Override
    public CompletionStage<AgentRole> updateRole(
            String id, AgentRoleSpec spec, RoleLifecycle lifecycle, CommandOptions options) {
        AgentRole current = profiles.stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow();
        AgentRole updated = new AgentRole(id, current.revision() + 1, lifecycle, spec, false, current.createdAt(), NOW);
        profiles.remove(current);
        profiles.add(updated);
        return completed(updated);
    }

    @Override
    public CompletionStage<AgentRole> archiveRole(String id, CommandOptions options) {
        AgentRole current = profiles.stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow();
        return updateRole(id, current.spec(), RoleLifecycle.ARCHIVED, options);
    }

    @Override
    public CompletionStage<Optional<ExecutionConfiguration>> executionDefaults(Optional<WorkspaceId> workspaceId) {
        return completed(workspaceSettings.binding);
    }

    @Override
    public CompletionStage<ExecutionConfiguration> updateExecutionDefaults(
            Optional<WorkspaceId> workspaceId, ExecutionOverrides execution, CommandOptions options) {
        return completed(workspaceSettings.bind(workspaceId, execution, options));
    }

    @Override
    public CompletionStage<Optional<ExecutionConfiguration>> threadExecution(
            WorkspaceId workspaceId, ThreadId threadId) {
        return completed(Optional.empty());
    }

    @Override
    public CompletionStage<AgentRole> cloneRole(AgentRoleRef source, String id, String name, CommandOptions options) {
        return role(source).thenCompose(original -> {
            AgentRoleSpec spec = original.spec();
            return createRole(
                    id,
                    new AgentRoleSpec(
                            name,
                            spec.description(),
                            spec.developerInstructions(),
                            spec.model(),
                            spec.reasoning(),
                            spec.narrowing(),
                            spec.permissionConstraint(),
                            spec.extensions()),
                    options);
        });
    }

    @Override
    public CompletionStage<PromptManifestPreview> previewExecution(
            WorkspaceId workspaceId, ExecutionOverrides execution) {
        AgentRole selected = execution
                .role()
                .map(ref -> profiles.stream()
                        .filter(role -> role.id().equals(ref.id()))
                        .findFirst()
                        .orElseThrow())
                .orElseGet(() -> profiles.getFirst());
        return completed(new PromptManifestPreview(
                new AgentRoleRef(selected.id(), selected.revision()),
                execution.provider().orElse(new ProviderRef("provider-main", 1, "fake-model")),
                execution.permissionProfile().orElse(new PermissionProfileRef("standard", 1)),
                List.of(),
                "a".repeat(64),
                100,
                "test",
                "测试模板",
                selected.spec().developerInstructions()));
    }

    @Override
    public CompletionStage<AgentRoleFilePreview> previewRoleImport(
            String id, String content, AgentRoleFileFormat format) {
        return completed(new AgentRoleFilePreview(
                "preview-test",
                id,
                TestCoreSettingsFixtures.profileSpec(),
                "a".repeat(64),
                Optional.empty(),
                List.of("developerInstructions"),
                format));
    }

    @Override
    public CompletionStage<AgentRole> commitRoleImport(
            String previewId, Optional<ProviderRef> mapping, CommandOptions options) {
        return createRole("imported", TestCoreSettingsFixtures.profileSpec(), options);
    }

    @Override
    public CompletionStage<AgentRoleFileExport> exportRole(AgentRoleRef reference, AgentRoleFileFormat format) {
        return completed(
                new AgentRoleFileExport(reference.id() + ".agent.toml", "name = \"test\"", "a".repeat(64), format));
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
