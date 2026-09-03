package com.javaclaw.desktop.settings;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.InstructionResolution;
import com.javaclaw.api.InstructionScope;
import com.javaclaw.api.InstructionSourceResolution;
import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceInstructionSettings;
import com.javaclaw.api.WorktreeId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopTestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstructionSettingsPresenterTest {
    @Test
    void 页面只接收相对路径摘要字节与告警() {
        InstructionSettingsPresenter presenter = new InstructionSettingsPresenter(new FakeGateway());
        AtomicReference<InstructionSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);

        presenter.reload();

        InstructionResolution resolution = latest.get().resolution().orElseThrow();
        assertEquals(SettingsLoadState.READY, latest.get().phase());
        assertEquals("module/AGENTS.md", resolution.sources().getFirst().relativePath());
        assertEquals(12, resolution.projectIncludedBytes());
        assertEquals("PROJECT.md", latest.get().fallbackDraft());
        assertTrue(latest.get().feedback().message().contains("变更只影响下一任务"));
    }

    @Test
    void 保存备用文件名后重新读取权威revision() {
        FakeGateway gateway = new FakeGateway();
        InstructionSettingsPresenter presenter = new InstructionSettingsPresenter(gateway);
        presenter.reload();

        presenter.editFallback("TEAM.md");
        assertTrue(presenter.state().dirty());
        presenter.saveFallback();

        assertEquals("TEAM.md", presenter.state().fallbackDraft());
        assertEquals(2, presenter.state().settings().orElseThrow().revision());
    }

    private static final class FakeGateway implements InstructionSettingsGateway {
        private final Workspace workspace = DesktopTestFixtures.workspace();
        private WorkspaceInstructionSettings settings = new WorkspaceInstructionSettings(
                workspace.id(), Optional.of("PROJECT.md"), 1, Instant.parse("2026-09-01T00:00:00Z"));

        @Override
        public CompletionStage<List<Workspace>> workspaces() {
            return completed(List.of(workspace));
        }

        @Override
        public CompletionStage<List<ManagedWorktree>> managedWorktrees(
                WorkspaceId workspaceId, boolean includeCleaned) {
            return completed(List.of());
        }

        @Override
        public CompletionStage<InstructionResolution> instructionResolution(
                WorkspaceId workspaceId, Optional<WorktreeId> worktreeId) {
            InstructionSourceResolution source = new InstructionSourceResolution(
                    InstructionScope.PROJECT,
                    "module/AGENTS.md",
                    Optional.of("b".repeat(64)),
                    12,
                    12,
                    false,
                    Optional.empty());
            return completed(new InstructionResolution(
                    List.of(source), "a".repeat(64), 0, 12, List.of(), Instant.parse("2026-09-01T00:00:00Z")));
        }

        @Override
        public CompletionStage<WorkspaceInstructionSettings> instructionSettings(WorkspaceId workspaceId) {
            return completed(settings);
        }

        @Override
        public CompletionStage<WorkspaceInstructionSettings> updateInstructionSettings(
                WorkspaceId workspaceId, Optional<String> fallbackBasename, CommandOptions options) {
            settings = new WorkspaceInstructionSettings(
                    workspaceId,
                    fallbackBasename,
                    options.expectedRevision() + 1,
                    settings.updatedAt().plusSeconds(1));
            return completed(settings);
        }

        private static <T> CompletionStage<T> completed(T value) {
            return CompletableFuture.completedFuture(value);
        }
    }
}
