package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.PromptManifestPreview;
import com.javaclaw.api.PromptSourceKind;
import com.javaclaw.api.PromptSourceMetadata;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.DesktopTestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptPreviewSettingsPresenterTest {
    @Test
    void 相同工作区重新绑定保留已生成预览与请求代次() {
        ImmediateGateway gateway = new ImmediateGateway();
        PromptPreviewSettingsPresenter presenter = new PromptPreviewSettingsPresenter(gateway);
        presenter.reloadWorkspaces();
        presenter.selectRole(Optional.of(DesktopTestFixtures.profile()));
        presenter.preview();
        PromptPreviewSettingsState before = presenter.state();
        presenter.selectWorkspace(DesktopTestFixtures.workspace());
        assertEquals(before, presenter.state());
    }

    @Test
    void 预览使用当前Workspace和精确ProfileRevision() {
        ImmediateGateway gateway = new ImmediateGateway();
        PromptPreviewSettingsPresenter presenter = new PromptPreviewSettingsPresenter(gateway);

        presenter.reloadWorkspaces();
        presenter.selectRole(Optional.of(DesktopTestFixtures.profile()));
        presenter.preview();

        assertEquals(DesktopTestFixtures.workspace().id(), gateway.workspaceId);
        assertEquals(new AgentRoleRef("default", 1), gateway.role);
        assertEquals("a".repeat(64), presenter.state().preview().orElseThrow().manifestDigest());
        assertEquals(SettingsLoadState.READY, presenter.state().phase());
    }

    @Test
    void Profile切换后丢弃先前请求的迟到响应() {
        DeferredGateway gateway = new DeferredGateway();
        PromptPreviewSettingsPresenter presenter = new PromptPreviewSettingsPresenter(gateway);
        AgentRole first = DesktopTestFixtures.profile();
        AgentRole second = new AgentRole(
                first.id(),
                2,
                first.lifecycle(),
                first.spec(),
                false,
                first.createdAt(),
                first.updatedAt().plusSeconds(1));

        presenter.reloadWorkspaces();
        presenter.selectRole(Optional.of(first));
        presenter.preview();
        presenter.selectRole(Optional.of(second));
        gateway.result.complete(preview(first));

        assertEquals(Optional.of(second), presenter.state().role());
        assertTrue(presenter.state().preview().isEmpty());
    }

    private static PromptManifestPreview preview(AgentRole role) {
        PromptSourceMetadata source = new PromptSourceMetadata(
                PromptSourceKind.MODEL_BASE,
                "core/turn-system@5",
                Optional.of("5"),
                Optional.of("b".repeat(64)),
                32,
                List.of());
        return new PromptManifestPreview(
                new AgentRoleRef(role.id(), role.revision()),
                role.spec().model().orElseThrow().provider(),
                DesktopTestFixtures.resolved().permissionProfile(),
                List.of(source),
                "a".repeat(64),
                18,
                "utf8-bytes-div-4-v1",
                "你是 JavaClaw。",
                role.spec().developerInstructions());
    }

    private static final class ImmediateGateway implements PromptPreviewSettingsGateway {
        private WorkspaceId workspaceId;
        private AgentRoleRef role;

        @Override
        public CompletionStage<List<Workspace>> workspaces() {
            return CompletableFuture.completedFuture(List.of(DesktopTestFixtures.workspace()));
        }

        @Override
        public CompletionStage<PromptManifestPreview> preview(WorkspaceId workspaceId, AgentRoleRef role) {
            this.workspaceId = workspaceId;
            this.role = role;
            return CompletableFuture.completedFuture(
                    PromptPreviewSettingsPresenterTest.preview(DesktopTestFixtures.profile()));
        }
    }

    private static final class DeferredGateway implements PromptPreviewSettingsGateway {
        private final CompletableFuture<PromptManifestPreview> result = new CompletableFuture<>();

        @Override
        public CompletionStage<List<Workspace>> workspaces() {
            return CompletableFuture.completedFuture(List.of(DesktopTestFixtures.workspace()));
        }

        @Override
        public CompletionStage<PromptManifestPreview> preview(WorkspaceId workspaceId, AgentRoleRef role) {
            return result;
        }
    }
}
