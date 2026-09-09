package com.javaclaw.desktop.settings;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ConversationThread;
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
    void 首次绑定与连接建立只读一次而真实失效仍补读() {
        Gateway gateway = new Gateway();
        CompletableFuture<ExecutionPreview> pending = new CompletableFuture<>();
        gateway.previews.add(pending);
        try (ChatConfigurationPresenter presenter = new ChatConfigurationPresenter(gateway)) {
            var workspace = Optional.of(DesktopTestFixtures.workspace());
            var thread = Optional.of(DesktopTestFixtures.thread());
            var connection = Optional.of(DesktopTestFixtures.NOW);
            presenter.bind(workspace, thread, connection);
            presenter.bind(workspace, thread, connection);
            assertEquals(1, gateway.previewReads);
            gateway.changed();
            pending.complete(preview(selection(1, ReasoningPreference.LOW)));
            assertEquals(2, gateway.previewReads);
            presenter.bind(workspace, thread, Optional.empty());
            presenter.bind(workspace, thread, Optional.of(DesktopTestFixtures.NOW.plusSeconds(1)));
            assertEquals(3, gateway.previewReads);
            assertTrue(presenter.state().ready());
        }
    }

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

    @Test
    void 重复激活与返回最近对话复用完整配置和原写入版本() {
        Gateway gateway = new Gateway();
        try (ChatConfigurationPresenter presenter = bound(gateway)) {
            for (int index = 0; index < 10; index++) {
                presenter.activate();
                presenter.bind(Optional.of(DesktopTestFixtures.workspace()), Optional.of(DesktopTestFixtures.thread()));
            }
            assertEquals(1, gateway.previewReads);
            gateway.saved = configuration(selection(2, ReasoningPreference.HIGH), 8);
            presenter.bind(Optional.of(DesktopTestFixtures.workspace()), Optional.of(thread("thread-second")));
            assertEquals(2, gateway.previewReads);
            presenter.bind(Optional.of(DesktopTestFixtures.workspace()), Optional.of(DesktopTestFixtures.thread()));
            assertEquals(
                    selection(1, ReasoningPreference.LOW), presenter.state().selection());
            assertEquals(2, gateway.previewReads);
            assertTrue(presenter.state().ready());
            presenter.edit(selection(1, ReasoningPreference.HIGH), false);
            assertEquals(List.of(3L), gateway.revisions);
        }
    }

    @Test
    void 激活不延长有效期且过期补读期间禁止发送() {
        Gateway gateway = new Gateway();
        AtomicLong clock = new AtomicLong();
        try (ChatConfigurationPresenter presenter = new ChatConfigurationPresenter(gateway, clock::get)) {
            presenter.bind(Optional.of(DesktopTestFixtures.workspace()), Optional.of(DesktopTestFixtures.thread()));
            clock.set(Duration.ofMinutes(4).toNanos());
            presenter.activate();
            assertEquals(1, gateway.previewReads);
            CompletableFuture<ExecutionPreview> pending = new CompletableFuture<>();
            gateway.previews.add(pending);
            clock.set(Duration.ofMinutes(5).toNanos());
            presenter.activate();
            assertEquals(2, gateway.previewReads);
            assertFalse(presenter.state().ready());
            presenter.activate();
            assertEquals(2, gateway.previewReads);
            pending.complete(preview(selection(1, ReasoningPreference.LOW)));
            assertTrue(presenter.state().ready());
            presenter.refresh();
            assertEquals(3, gateway.previewReads, "手动刷新始终读取服务端");
        }
    }

    @Test
    void 其他对话失效只清理其缓存而全局目录失效影响所有对话() {
        Gateway gateway = new Gateway();
        try (ChatConfigurationPresenter presenter = bound(gateway)) {
            presenter.bind(Optional.of(DesktopTestFixtures.workspace()), Optional.of(thread("thread-second")));
            gateway.changed();
            assertEquals(2, gateway.previewReads, "非当前对话更新不重读当前配置");
            presenter.bind(Optional.of(DesktopTestFixtures.workspace()), Optional.of(DesktopTestFixtures.thread()));
            assertEquals(3, gateway.previewReads);
            presenter.bind(Optional.of(DesktopTestFixtures.workspace()), Optional.of(thread("thread-second")));
            assertEquals(3, gateway.previewReads);
            gateway.events.publish(new DesktopConfigurationChange(
                    DesktopConfigurationChange.Kind.PROVIDERS, Optional.empty(), Optional.empty()));
            assertEquals(4, gateway.previewReads);
            presenter.bind(Optional.of(DesktopTestFixtures.workspace()), Optional.of(DesktopTestFixtures.thread()));
            assertEquals(5, gateway.previewReads);
        }
    }

    @Test
    void 同次重连与切换对话先清缓存再开始一次读取() {
        Gateway gateway = new Gateway();
        try (ChatConfigurationPresenter presenter = new ChatConfigurationPresenter(gateway)) {
            var workspace = Optional.of(DesktopTestFixtures.workspace());
            var connection = Optional.of(DesktopTestFixtures.NOW);
            presenter.bind(workspace, Optional.of(DesktopTestFixtures.thread()), connection);
            presenter.bind(workspace, Optional.of(thread("thread-second")), connection);
            CompletableFuture<ExecutionPreview> pending = new CompletableFuture<>();
            gateway.previews.add(pending);
            presenter.bind(
                    workspace,
                    Optional.of(DesktopTestFixtures.thread()),
                    Optional.of(DesktopTestFixtures.NOW.plusSeconds(1)));
            assertEquals(3, gateway.previewReads);
            assertFalse(presenter.state().ready());
            pending.complete(preview(selection(1, ReasoningPreference.LOW)));
            presenter.bind(
                    workspace,
                    Optional.of(thread("thread-second")),
                    Optional.of(DesktopTestFixtures.NOW.plusSeconds(1)));
            assertEquals(4, gateway.previewReads, "重连前其他作用域的结果也必须失效");
        }
    }

    @Test
    void 失效后的迟到预览不会短暂发布就绪或重新写入缓存() {
        Gateway gateway = new Gateway();
        CompletableFuture<ExecutionPreview> first = new CompletableFuture<>();
        CompletableFuture<ExecutionPreview> second = new CompletableFuture<>();
        gateway.previews.add(first);
        gateway.previews.add(second);
        try (ChatConfigurationPresenter presenter = bound(gateway)) {
            List<Boolean> ready = new ArrayList<>();
            presenter.subscribe(state -> ready.add(state.ready()));
            gateway.changed();
            first.complete(preview(selection(1, ReasoningPreference.LOW)));
            assertFalse(ready.contains(true));
            presenter.activate();
            assertEquals(2, gateway.previewReads);
            second.complete(preview(selection(1, ReasoningPreference.LOW)));
            assertTrue(presenter.state().ready());
        }
    }

    @Test
    void 保存后的缓存包含新选择来源与基线且保存失败不能复用旧值() {
        Gateway gateway = new Gateway();
        gateway.publishWrites = false;
        try (ChatConfigurationPresenter presenter = bound(gateway)) {
            presenter.edit(selection(1, ReasoningPreference.HIGH), false);
            presenter.bind(Optional.of(DesktopTestFixtures.workspace()), Optional.of(thread("thread-second")));
            presenter.bind(Optional.of(DesktopTestFixtures.workspace()), Optional.of(DesktopTestFixtures.thread()));
            assertEquals(3, gateway.previewReads);
            assertEquals(
                    selection(1, ReasoningPreference.HIGH), presenter.state().selection());
            assertEquals(
                    4,
                    presenter
                            .state()
                            .snapshot()
                            .orElseThrow()
                            .sources()
                            .thread()
                            .orElseThrow()
                            .revision());
            gateway.nextWrite = CompletableFuture.failedFuture(new IllegalStateException("保存失败"));
            presenter.edit(selection(1, ReasoningPreference.NONE), false);
            presenter.activate();
            assertTrue(presenter.state().dirty());
            assertFalse(presenter.state().ready());
            assertEquals(3, gateway.previewReads, "聚焦不重复刷新失败草稿");
            presenter.bind(Optional.of(DesktopTestFixtures.workspace()), Optional.of(thread("thread-second")));
            presenter.bind(Optional.of(DesktopTestFixtures.workspace()), Optional.of(DesktopTestFixtures.thread()));
            assertEquals(4, gateway.previewReads, "写入开始后旧缓存不得再次启用发送");
        }
    }

    @Test
    void 切走后返回期间的迟到保存仍使旧缓存失效() {
        Gateway gateway = new Gateway();
        gateway.publishWrites = false;
        try (ChatConfigurationPresenter presenter = bound(gateway)) {
            CompletableFuture<ExecutionConfiguration> saving = new CompletableFuture<>();
            gateway.nextWrite = saving;
            presenter.edit(selection(1, ReasoningPreference.HIGH), false);
            presenter.bind(Optional.of(DesktopTestFixtures.workspace()), Optional.of(thread("thread-second")));
            presenter.bind(Optional.of(DesktopTestFixtures.workspace()), Optional.of(DesktopTestFixtures.thread()));
            assertEquals(
                    selection(1, ReasoningPreference.LOW), presenter.state().selection());
            gateway.saved = configuration(selection(1, ReasoningPreference.HIGH), 4);
            saving.complete(gateway.saved);
            assertEquals(
                    selection(1, ReasoningPreference.HIGH), presenter.state().selection());
            assertEquals(4, gateway.previewReads);
            assertTrue(presenter.state().ready());
        }
    }

    @Test
    void 最近对话缓存最多保存三十二项() {
        Gateway gateway = new Gateway();
        try (ChatConfigurationPresenter presenter = bound(gateway)) {
            for (int index = 0; index < 32; index++) {
                presenter.bind(Optional.of(DesktopTestFixtures.workspace()), Optional.of(thread("thread-" + index)));
            }
            assertEquals(33, gateway.previewReads);
            presenter.bind(Optional.of(DesktopTestFixtures.workspace()), Optional.of(DesktopTestFixtures.thread()));
            assertEquals(34, gateway.previewReads);
        }
    }

    private static ConversationThread thread(String id) {
        ConversationThread source = DesktopTestFixtures.thread();
        return new ConversationThread(
                new ThreadId(UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8))),
                source.workspaceId(),
                source.parentThreadId(),
                source.executionIntent(),
                source.title(),
                source.status(),
                source.revision(),
                source.createdAt(),
                source.updatedAt());
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
        private boolean publishWrites = true;

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
            if (publishWrites) {
                changed();
            }
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
