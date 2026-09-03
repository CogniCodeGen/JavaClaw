package com.javaclaw.desktop.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.ProfileBinding;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopTestFixtures;

/** Workspace 设置测试共享的目录和写入回执。 */
final class TestWorkspaceSettings {
    final List<Workspace> catalog = new ArrayList<>(List.of(DesktopTestFixtures.workspace()));
    Optional<ProfileBinding> binding = Optional.empty();
    RuntimeException nextFailure;
    CompletableFuture<List<Workspace>> nextResponse;
    String lastName = "";
    AgentProfileRef lastProfile;
    boolean archived;
    int reads;

    CompletionStage<List<Workspace>> list() {
        reads++;
        CompletableFuture<List<Workspace>> response = nextResponse;
        nextResponse = null;
        if (response != null) {
            return response;
        }
        RuntimeException failure = nextFailure;
        nextFailure = null;
        if (failure != null) {
            return CompletableFuture.failedFuture(failure);
        }
        return CompletableFuture.completedFuture(List.copyOf(catalog));
    }

    Workspace rename(Workspace workspace, String name) {
        lastName = name;
        return new Workspace(
                workspace.id(),
                name,
                workspace.root(),
                workspace.lifecycle(),
                workspace.revision() + 1,
                workspace.createdAt(),
                DesktopTestFixtures.NOW);
    }

    Workspace archive(Workspace workspace) {
        archived = true;
        return new Workspace(
                workspace.id(),
                workspace.name(),
                workspace.root(),
                WorkspaceLifecycle.ARCHIVED,
                workspace.revision() + 1,
                workspace.createdAt(),
                DesktopTestFixtures.NOW);
    }

    ProfileBinding bind(WorkspaceId workspaceId, AgentProfileRef profile, CommandOptions options) {
        lastProfile = profile;
        ProfileBinding updated = new ProfileBinding(
                workspaceId, Optional.empty(), profile, options.expectedRevision() + 1, DesktopTestFixtures.NOW);
        binding = Optional.of(updated);
        return updated;
    }
}
