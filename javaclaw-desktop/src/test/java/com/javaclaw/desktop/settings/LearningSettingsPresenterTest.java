package com.javaclaw.desktop.settings;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopTestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningSettingsPresenterTest {
    @Test
    void 保存使用权威revision并在成功后清除脏状态() {
        ImmediateGateway gateway = new ImmediateGateway();
        LearningSettingsPresenter presenter = new LearningSettingsPresenter(gateway);
        AtomicReference<LearningSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);

        presenter.reload();
        presenter.edit(MemoryContracts.LearningPolicy.AUTO_LOW_RISK);
        assertTrue(latest.get().dirty());

        presenter.save();

        assertEquals(4, gateway.expectedRevision);
        assertEquals(
                MemoryContracts.LearningPolicy.AUTO_LOW_RISK,
                latest.get().saved().orElseThrow().policy());
        assertFalse(latest.get().dirty());
        assertEquals(SettingsLoadState.READY, latest.get().phase());
    }

    @Test
    void 切换Workspace后丢弃先前请求的迟到响应() {
        Workspace first = DesktopTestFixtures.workspace();
        Workspace second = workspace("4f8d5c7a-50b4-4d4a-8096-7789a0ea5385", "第二工作区");
        DeferredGateway gateway = new DeferredGateway(List.of(first, second));
        LearningSettingsPresenter presenter = new LearningSettingsPresenter(gateway);

        presenter.reload();
        presenter.select(second);
        gateway.complete(second.id(), 7, MemoryContracts.LearningPolicy.OFF);
        gateway.complete(first.id(), 3, MemoryContracts.LearningPolicy.AUTO_LOW_RISK);

        assertEquals(second.id(), presenter.state().workspace().orElseThrow().id());
        assertEquals(MemoryContracts.LearningPolicy.OFF, presenter.state().draft());
        assertEquals(7, presenter.state().saved().orElseThrow().revision());
    }

    private static Workspace workspace(String id, String name) {
        Instant now = DesktopTestFixtures.NOW;
        return new Workspace(WorkspaceId.parse(id), name, Path.of("/tmp", id), WorkspaceLifecycle.ACTIVE, 1, now, now);
    }

    private static final class ImmediateGateway implements LearningSettingsGateway {
        private final Workspace workspace = DesktopTestFixtures.workspace();
        private long expectedRevision = -1;

        @Override
        public CompletionStage<List<Workspace>> workspaces() {
            return CompletableFuture.completedFuture(List.of(workspace));
        }

        @Override
        public CompletionStage<MemoryContracts.LearningSettings> read(WorkspaceId workspaceId) {
            return CompletableFuture.completedFuture(settings(4, MemoryContracts.LearningPolicy.SUGGEST));
        }

        @Override
        public CompletionStage<MemoryContracts.LearningSettings> update(
                WorkspaceId workspaceId, MemoryContracts.LearningPolicy policy, CommandOptions options) {
            expectedRevision = options.expectedRevision();
            return CompletableFuture.completedFuture(settings(5, policy));
        }
    }

    private static final class DeferredGateway implements LearningSettingsGateway {
        private final List<Workspace> workspaces;
        private final Map<WorkspaceId, CompletableFuture<MemoryContracts.LearningSettings>> reads =
                new ConcurrentHashMap<>();

        private DeferredGateway(List<Workspace> workspaces) {
            this.workspaces = List.copyOf(workspaces);
        }

        @Override
        public CompletionStage<List<Workspace>> workspaces() {
            return CompletableFuture.completedFuture(workspaces);
        }

        @Override
        public CompletionStage<MemoryContracts.LearningSettings> read(WorkspaceId workspaceId) {
            return reads.computeIfAbsent(workspaceId, ignored -> new CompletableFuture<>());
        }

        @Override
        public CompletionStage<MemoryContracts.LearningSettings> update(
                WorkspaceId workspaceId, MemoryContracts.LearningPolicy policy, CommandOptions options) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("本场景不保存"));
        }

        private void complete(WorkspaceId workspaceId, long revision, MemoryContracts.LearningPolicy policy) {
            reads.get(workspaceId).complete(settings(revision, policy));
        }
    }

    private static MemoryContracts.LearningSettings settings(long revision, MemoryContracts.LearningPolicy policy) {
        return new MemoryContracts.LearningSettings(revision, policy, DesktopTestFixtures.NOW);
    }
}
