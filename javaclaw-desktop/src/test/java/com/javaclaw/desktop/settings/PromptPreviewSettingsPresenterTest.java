package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
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
    void 预览使用当前Workspace和精确ProfileRevision() {
        ImmediateGateway gateway = new ImmediateGateway();
        PromptPreviewSettingsPresenter presenter = new PromptPreviewSettingsPresenter(gateway);

        presenter.reloadWorkspaces();
        presenter.selectProfile(Optional.of(DesktopTestFixtures.profile()));
        presenter.preview();

        assertEquals(DesktopTestFixtures.workspace().id(), gateway.workspaceId);
        assertEquals(new AgentProfileRef("default", 1), gateway.profile);
        assertEquals("a".repeat(64), presenter.state().preview().orElseThrow().manifestDigest());
        assertEquals(SettingsLoadState.READY, presenter.state().phase());
    }

    @Test
    void Profile切换后丢弃先前请求的迟到响应() {
        DeferredGateway gateway = new DeferredGateway();
        PromptPreviewSettingsPresenter presenter = new PromptPreviewSettingsPresenter(gateway);
        AgentProfile first = DesktopTestFixtures.profile();
        AgentProfile second = new AgentProfile(
                first.id(),
                2,
                first.lifecycle(),
                first.spec(),
                first.createdAt(),
                first.updatedAt().plusSeconds(1));

        presenter.reloadWorkspaces();
        presenter.selectProfile(Optional.of(first));
        presenter.preview();
        presenter.selectProfile(Optional.of(second));
        gateway.result.complete(preview(first));

        assertEquals(Optional.of(second), presenter.state().profile());
        assertTrue(presenter.state().preview().isEmpty());
    }

    private static PromptManifestPreview preview(AgentProfile profile) {
        PromptSourceMetadata source = new PromptSourceMetadata(
                PromptSourceKind.CORE_TEMPLATE,
                "core/turn-system@5",
                Optional.of("5"),
                Optional.of("b".repeat(64)),
                32,
                List.of());
        return new PromptManifestPreview(
                new AgentProfileRef(profile.id(), profile.revision()),
                profile.spec().provider(),
                profile.spec().permissionProfile(),
                List.of(source),
                "a".repeat(64),
                18,
                "utf8-bytes-div-4-v1",
                "你是 JavaClaw。",
                profile.spec().systemInstruction());
    }

    private static final class ImmediateGateway implements PromptPreviewSettingsGateway {
        private WorkspaceId workspaceId;
        private AgentProfileRef profile;

        @Override
        public CompletionStage<List<Workspace>> workspaces() {
            return CompletableFuture.completedFuture(List.of(DesktopTestFixtures.workspace()));
        }

        @Override
        public CompletionStage<PromptManifestPreview> preview(WorkspaceId workspaceId, AgentProfileRef profile) {
            this.workspaceId = workspaceId;
            this.profile = profile;
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
        public CompletionStage<PromptManifestPreview> preview(WorkspaceId workspaceId, AgentProfileRef profile) {
            return result;
        }
    }
}
