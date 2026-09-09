package com.javaclaw.desktop.settings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ExecutionPreview;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopConfigurationChange;
import com.javaclaw.desktop.DesktopConfigurationEvents;
import com.javaclaw.desktop.DesktopNotificationSubscription;
import com.javaclaw.desktop.DesktopTestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatConfigurationPresenterTest {
    @Test
    void 配置保存通知在相同对话立即刷新且不偷偷升级模型版本() {
        Gateway gateway = new Gateway();
        try (ChatConfigurationPresenter presenter = bound(gateway)) {
            assertTrue(presenter.state().ready());
            gateway.saved = configuration(selection(4, ReasoningPreference.HIGH), 7);
            gateway.changed();
            assertEquals(gateway.saved.overrides(), presenter.state().selection());
            assertEquals(
                    4,
                    presenter
                            .state()
                            .preview()
                            .orElseThrow()
                            .provider()
                            .orElseThrow()
                            .endpointRevision());
            assertTrue(presenter.state().ready());
        }
    }

    @Test
    void 保存失败后目录刷新保留草稿和原写入版本() {
        Gateway gateway = new Gateway();
        try (ChatConfigurationPresenter presenter = bound(gateway)) {
            ExecutionOverrides draft = selection(1, ReasoningPreference.HIGH);
            gateway.nextWrite = CompletableFuture.failedFuture(new IllegalStateException("连接中断"));
            presenter.edit(draft, true);
            assertTrue(presenter.state().dirty());
            assertFalse(presenter.state().ready());
            gateway.saved = configuration(selection(2, ReasoningPreference.LOW), 9);
            gateway.changed();
            assertEquals(draft, presenter.state().selection());
            presenter.retry();
            assertEquals(List.of(3L, 3L), gateway.revisions);
            assertFalse(presenter.state().dirty());
        }
    }

    @Test
    void 在途读取期间的多次通知合并补读而不是丢失() {
        Gateway gateway = new Gateway();
        CompletableFuture<ExecutionPreview> first = new CompletableFuture<>();
        gateway.previews.add(first);
        try (ChatConfigurationPresenter presenter = bound(gateway)) {
            assertTrue(presenter.state().pending());
            gateway.changed();
            gateway.changed();
            first.complete(preview(selection(1, ReasoningPreference.LOW)));
            assertEquals(2, gateway.previewReads);
            assertTrue(presenter.state().ready());
        }
    }

    @Test
    void 自动刷新保留实际配置展示但完成前禁止发送() {
        Gateway gateway = new Gateway();
        try (ChatConfigurationPresenter presenter = bound(gateway)) {
            Optional<ExecutionPreview> previous = presenter.state().preview();
            CompletableFuture<ExecutionPreview> pending = new CompletableFuture<>();
            gateway.previews.add(pending);
            gateway.changed();
            assertEquals(previous, presenter.state().preview());
            assertTrue(presenter.state().pending());
            assertFalse(presenter.state().ready());
            pending.complete(preview(selection(1, ReasoningPreference.LOW)));
            assertTrue(presenter.state().ready());
        }
    }

    @Test
    void 切换作用域后迟到预览不能恢复原对话的就绪状态() {
        Gateway gateway = new Gateway();
        CompletableFuture<ExecutionPreview> first = new CompletableFuture<>();
        gateway.previews.add(first);
        try (ChatConfigurationPresenter presenter = bound(gateway)) {
            presenter.bind(Optional.empty(), Optional.empty());
            first.complete(preview(selection(1, ReasoningPreference.LOW)));
            assertFalse(presenter.state().ready());
            assertTrue(presenter.state().preview().isEmpty());
        }
    }

    @Test
    void 关闭后配置通知不再读取服务且恢复项目不更新日常偏好() {
        Gateway gateway = new Gateway();
        ChatConfigurationPresenter presenter = bound(gateway);
        presenter.edit(ExecutionOverrides.empty(), false);
        assertFalse(gateway.remembered);
        int reads = gateway.previewReads;
        presenter.close();
        gateway.changed();
        assertEquals(reads, gateway.previewReads);
    }

    private static ChatConfigurationPresenter bound(Gateway gateway) {
        ChatConfigurationPresenter presenter = new ChatConfigurationPresenter(gateway);
        presenter.bind(Optional.of(DesktopTestFixtures.workspace()), Optional.of(DesktopTestFixtures.thread()));
        return presenter;
    }

    private static ExecutionOverrides selection(long revision, ReasoningPreference reasoning) {
        return new ExecutionOverrides(
                Optional.empty(),
                Optional.of(new ProviderRef("provider-main", revision, "fake-model")),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(reasoning));
    }

    private static ExecutionConfiguration configuration(ExecutionOverrides selected, long revision) {
        return new ExecutionConfiguration(
                Optional.of(DesktopTestFixtures.workspace().id()),
                Optional.of(DesktopTestFixtures.thread().id()),
                selected,
                revision,
                DesktopTestFixtures.NOW);
    }

    private static ExecutionPreview preview(ExecutionOverrides selected) {
        return new ExecutionPreview(
                Optional.of(new AgentRoleRef("default", 1)),
                selected.provider().or(() -> Optional.of(new ProviderRef("provider-main", 1, "fake-model"))),
                selected.reasoning(),
                false,
                false,
                List.of(),
                List.of());
    }

    private static final class Gateway extends TestCoreSettingsGateway {
        private final DesktopConfigurationEvents events = new DesktopConfigurationEvents();
        private final List<Long> revisions = new ArrayList<>();
        private final Queue<CompletableFuture<ExecutionPreview>> previews = new ArrayDeque<>();
        private ExecutionConfiguration saved = configuration(selection(1, ReasoningPreference.LOW), 3);
        private CompletableFuture<ExecutionConfiguration> nextWrite;
        private int previewReads;
        private boolean remembered;

        @Override
        public DesktopNotificationSubscription onConfigurationChanged(Consumer<DesktopConfigurationChange> listener) {
            return events.subscribe(listener);
        }

        @Override
        public CompletionStage<Optional<ExecutionConfiguration>> executionDefaults(Optional<WorkspaceId> workspace) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletionStage<Optional<ExecutionConfiguration>> threadExecution(
                WorkspaceId workspace, ThreadId thread) {
            return CompletableFuture.completedFuture(Optional.of(saved));
        }

        @Override
        public CompletionStage<ExecutionPreview> previewChatExecution(
                WorkspaceId workspace, Optional<ThreadId> thread, ExecutionOverrides selected) {
            previewReads++;
            return previews.isEmpty() ? CompletableFuture.completedFuture(preview(selected)) : previews.remove();
        }

        @Override
        public CompletionStage<ExecutionConfiguration> rememberChatSelection(
                WorkspaceId workspace,
                ThreadId thread,
                ExecutionOverrides selected,
                CommandOptions options,
                boolean remember) {
            revisions.add(options.expectedRevision());
            remembered = remember;
            if (nextWrite != null) {
                var result = nextWrite;
                nextWrite = null;
                return result;
            }
            saved = configuration(selected, options.expectedRevision() + 1);
            changed();
            return CompletableFuture.completedFuture(saved);
        }

        private void changed() {
            events.publish(new DesktopConfigurationChange(
                    DesktopConfigurationChange.Kind.EXECUTION,
                    Optional.of(DesktopTestFixtures.workspace().id()),
                    Optional.of(DesktopTestFixtures.thread().id())));
        }
    }
}
