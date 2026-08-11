package com.javaclaw.chat;

import com.javaclaw.agent.evaluation.EvaluationResult;
import com.javaclaw.api.conversation.CancellationReason;
import com.javaclaw.api.conversation.Capabilities;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationHandle;
import com.javaclaw.api.conversation.ConversationMode;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.api.conversation.Mode;
import com.javaclaw.api.conversation.ModeRegistry;
import com.javaclaw.api.conversation.Placement;
import com.javaclaw.browser.PlaywrightBrowserManager;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.config.DataManager;
import com.javaclaw.config.EmailConfig;
import com.javaclaw.config.NotificationConfig;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.diagnostics.TraceRecorder;
import com.javaclaw.loop.model.Decision;
import com.javaclaw.loop.model.LoopStatus;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.spring.ApplicationContexts;
import com.javaclaw.platform.spring.WorkspaceSpringContextFactory;
import com.javaclaw.plugin.PluginManager;
import com.javaclaw.runtime.ApplicationKernel;
import com.javaclaw.system.CommandWhitelistManager;
import javafx.application.Platform;
import javafx.scene.layout.BorderPane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class ChatTurnControllerBehaviorTest {

    private static final long TIMEOUT_SECONDS = 20;

    @TempDir
    Path tempDirectory;

    private AnnotationConfigApplicationContext rootContext;
    private ApplicationKernel kernel;
    private ViewHandle<BorderPane> chatView;
    private ChatTurnController turns;
    private ChatComposerController composer;
    private ChatModeController modeBar;
    private ChatSessionCoordinator sessions;
    private ModeRegistry registry;

    @BeforeAll
    static void startToolkit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(() -> {
                Platform.setImplicitExit(false);
                started.countDown();
            });
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(started::countDown);
        }
        assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @BeforeEach
    void loadChat() throws Exception {
        rootContext = ApplicationContexts.createRoot(
                new DataRoot(tempDirectory.resolve("data-v3")));
        ApplicationContexts.registerDesktopInfrastructure(rootContext);
        kernel = createKernel(rootContext);
        assertNotNull(kernel.initialize());
        ApplicationContexts.registerApplicationKernel(rootContext, kernel);
        chatView = callFx(() -> rootContext.getBean(SpringFxmlLoader.class).load(
                java.util.Objects.requireNonNull(
                        getClass().getResource("/fxml/chat/chat-view.fxml"))));
        ChatViewController chat = chatView.controller(ChatViewController.class);
        turns = field(chat, "turns", ChatTurnController.class);
        composer = field(chat, "composerController", ChatComposerController.class);
        modeBar = field(chat, "modeBarController", ChatModeController.class);
        sessions = field(chat, "sessionCoordinator", ChatSessionCoordinator.class);
        registry = kernel.current().modeRegistry();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (chatView != null) {
            runFx(chatView::close);
            chatView = null;
        }
        if (kernel != null) {
            kernel.close();
            kernel = null;
        }
        if (rootContext != null) {
            rootContext.close();
            rootContext = null;
        }
    }

    @Test
    void 完成回合路由全部事件并保存计量结果() throws Exception {
        FakeConversationMode chat = install(new FakeConversationMode("chat", true));

        runFx(() -> {
            composer.replaceInput("   ");
            turns.sendFromComposer();
        });
        assertEquals(0, chat.starts);

        runFx(() -> {
            composer.replaceInput("解释虚拟线程");
            turns.sendFromComposer();
        });
        drainFxQueue();

        assertTrue(turns.isStreaming());
        assertFalse(turns.isPlanStream());
        assertSame(sessions.currentSession(), turns.streamingSession());
        assertEquals("解释虚拟线程", chat.request.userInput());
        assertTrue(chat.request.attachments().isEmpty());
        assertNotNull(chat.request.sessionId());

        runFx(() -> {
            chat.event(new ConversationEvent.Thinking("正在分析"));
            chat.event(new ConversationEvent.Reply("虚拟"));
            chat.event(new ConversationEvent.Reply("线程"));
            chat.event(new ConversationEvent.ToolResult("coding_expert", "工具结果"));
            chat.event(new ConversationEvent.SubAgentThinking("知识专家", "检索中"));
            chat.event(new ConversationEvent.SubAgentReply("知识专家", "检索结果"));
            chat.event(new ConversationEvent.Hint("整理答案"));
            chat.event(new ConversationEvent.Evaluation(
                    new EvaluationResult(4.5, "结果可靠", true, List.of("补充示例"))));
            chat.event(new ConversationEvent.LoopDetected("检测到重复调用"));
            chat.event(new ConversationEvent.Progress("route", "路由", null, null));
            chat.event(new ConversationEvent.Progress(
                    "route", "路由", ConversationEvent.Progress.Status.DONE, "完成"));
            chat.event(new ConversationEvent.Usage(-3, 5));
            chat.event(new ConversationEvent.Usage(11, -7));
            chat.event(new ConversationEvent.Custom("unknown", "ignored"));
            chat.complete();
        });

        assertFalse(turns.isStreaming());
        assertNull(turns.streamingSession());
        ChatMessage assistant = lastMessage(ChatMessage.Role.ASSISTANT);
        assertTrue(assistant.getContent().contains("虚拟线程"));
        assertTrue(assistant.getContent().contains("循环中断"));
        assertEquals(DeliveryState.COMPLETE, assistant.getDeliveryState());
        assertEquals(11, assistant.getMetrics().inputTokens());
        assertEquals(5, assistant.getMetrics().outputTokens());

        int messageCount = sessions.currentSession().getMessages().size();
        runFx(() -> {
            chat.event(new ConversationEvent.Usage(100, 100));
            chat.complete();
        });
        assertEquals(messageCount, sessions.currentSession().getMessages().size());
    }

    @Test
    void 规划回合采用最终草稿并隔离澄清后的迟到回调() throws Exception {
        FakeConversationMode plan = install(new FakeConversationMode("plan", true));

        runFx(() -> {
            composer.replaceInput("/plan 设计迁移方案");
            turns.sendFromComposer();
        });
        drainFxQueue();

        assertTrue(turns.isPlanStream());
        assertEquals("设计迁移方案", plan.request.userInput());
        assertEquals(modeBar.planProfile(), plan.request.options().planProfile());

        runFx(() -> {
            plan.event(new ConversationEvent.AgentStart("架构师"));
            plan.event(new ConversationEvent.AgentReply("架构师", "初稿[PLAN_COMPLETE]"));
            plan.event(new ConversationEvent.AgentStart("审查者"));
            plan.event(new ConversationEvent.AgentReply("审查者", "复核意见"));
            plan.event(new ConversationEvent.Custom("plan_final", "最终迁移方案"));
            plan.complete();
        });

        ChatMessage completed = lastMessage(ChatMessage.Role.ASSISTANT);
        assertEquals("最终迁移方案", completed.getContent());
        assertEquals(DeliveryState.COMPLETE, completed.getDeliveryState());

        runFx(() -> {
            composer.replaceInput("/plan 需要澄清的任务");
            turns.sendFromComposer();
        });
        drainFxQueue();
        int messagesBeforeClarification = sessions.currentSession().getMessages().size();

        runFx(() -> plan.event(new ConversationEvent.Custom(
                "clarify_request",
                new com.javaclaw.agent.clarify.ClarifyPayload("缺少边界", "目标版本是什么？"))));
        assertFalse(turns.isStreaming());
        assertEquals(messagesBeforeClarification + 1,
                sessions.currentSession().getMessages().size());

        runFx(() -> {
            plan.event(new ConversationEvent.Reply("不应出现"));
            plan.complete();
        });
        assertFalse(lastMessage(ChatMessage.Role.ASSISTANT).getContent().contains("不应出现"));
    }

    @Test
    void 取消策略覆盖接受拒绝终态异常与提前失效() throws Exception {
        FakeConversationMode chat = install(new FakeConversationMode("chat", true));

        startChat(chat, "保留部分回复");
        runFx(() -> chat.event(new ConversationEvent.Reply("已生成部分")));
        chat.handle.cancelBehavior = CancelBehavior.ACCEPT_AND_TERMINATE;
        runFx(turns::stop);
        assertFalse(turns.isStreaming());
        assertEquals(DeliveryState.CANCELLED,
                lastMessage(ChatMessage.Role.ASSISTANT).getDeliveryState());
        assertTrue(lastMessage(ChatMessage.Role.ASSISTANT).getContent().contains("已停止"));

        startChat(chat, "句柄已有终态");
        chat.handle.cancelBehavior = CancelBehavior.REJECT_TERMINAL;
        runFx(turns::stop);
        assertTrue(turns.isStreaming());
        runFx(chat::complete);
        assertFalse(turns.isStreaming());

        startChat(chat, "拒绝取消则强制丢弃");
        int beforeDiscard = sessions.currentSession().getMessages().size();
        chat.handle.cancelBehavior = CancelBehavior.REJECT_ACTIVE;
        runFx(turns::stop);
        assertFalse(turns.isStreaming());
        runFx(() -> {
            chat.event(new ConversationEvent.Reply("迟到内容"));
            chat.complete();
        });
        assertEquals(beforeDiscard, sessions.currentSession().getMessages().size());

        startChat(chat, "取消抛异常也必须隔离旧回调");
        chat.handle.cancelBehavior = CancelBehavior.THROW;
        runFx(() -> turns.stop(
                CancellationReason.SESSION_SWITCH,
                false,
                ChatTurnController.StopPolicy.DISCARD_AND_INVALIDATE));
        assertFalse(turns.isStreaming());

        int starts = chat.starts;
        runFx(() -> {
            composer.replaceInput("排队后立即作废");
            turns.sendFromComposer();
            turns.stop(CancellationReason.SESSION_SWITCH, false,
                    ChatTurnController.StopPolicy.DISCARD_AND_INVALIDATE);
        });
        drainFxQueue();
        assertEquals(starts, chat.starts);

        runFx(() -> {
            turns.setInputEnabled(false);
            turns.stop(CancellationReason.USER_REQUEST, false,
                    ChatTurnController.StopPolicy.PRESERVE_PARTIAL);
        });
        assertFalse(turns.isStreaming());
    }

    @Test
    void 模式能力与启动失败都恢复输入并给出可见结果() throws Exception {
        FakeConversationMode chat = install(new FakeConversationMode("chat", false));
        ChatComposerViewModel composerState = field(
                composer, "viewModel", ChatComposerViewModel.class);
        int baseline = sessions.currentSession().getMessages().size();

        runFx(() -> {
            composer.replaceInput("携带附件");
            composerState.addAttachments(List.of(new File("pom.xml")));
            turns.sendFromComposer();
        });
        assertEquals(0, chat.starts);
        assertFalse(turns.isStreaming());
        assertEquals(1, composer.attachmentCount());
        assertEquals(baseline + 1, sessions.currentSession().getMessages().size());

        chat.supportsAttachments = true;
        chat.terminalOnStart = ConversationOutcome.completed();
        startChat(chat, "支持附件的模式");
        assertEquals(Path.of("pom.xml").toAbsolutePath().normalize().toFile(),
                chat.request.attachments().getFirst());
        assertFalse(turns.isStreaming());

        chat.startFailure = new IllegalStateException("同步启动失败");
        startChat(chat, "触发启动异常");
        assertFalse(turns.isStreaming());
        assertTrue(lastMessage(ChatMessage.Role.SYSTEM).getContent().contains("调用失败"));

        chat.startFailure = null;
        chat.terminalOnStart = ConversationOutcome.completed();
        startChat(chat, "同步完成");
        assertFalse(turns.isStreaming());

        registry.unregister("plan");
        registry.register(new NonConversationMode("plan"));
        runFx(() -> {
            composer.replaceInput("/plan 未注册的对话模式");
            turns.sendFromComposer();
        });
        drainFxQueue();
        assertFalse(turns.isStreaming());
        assertTrue(lastMessage(ChatMessage.Role.SYSTEM).getContent().contains("模式未注册"));
    }

    @Test
    void 失败回合区分部分结果与空结果且重建期间拒绝新动作() throws Exception {
        FakeConversationMode chat = install(new FakeConversationMode("chat", true));

        startChat(chat, "保留失败前的部分结果");
        runFx(() -> {
            chat.event(new ConversationEvent.Reply("部分回答"));
            chat.fail(new IllegalArgumentException("上游拒绝"));
        });
        ChatMessage failed = lastMessage(ChatMessage.Role.ASSISTANT);
        assertEquals(DeliveryState.FAILED, failed.getDeliveryState());
        assertTrue(failed.getContent().contains("部分回答"));
        assertTrue(failed.getContent().contains("失败"));

        startChat(chat, "空失败结果");
        runFx(() -> chat.fail(new IllegalStateException("没有正文")));
        assertEquals(ChatMessage.Role.SYSTEM,
                sessions.currentSession().getMessages().getLast().getRole());

        ChatTurnController rebuilding = copyWithRebuildingFlag(true);
        int starts = chat.starts;
        runFx(() -> {
            composer.replaceInput("重建期间不发送");
            rebuilding.sendFromComposer();
            rebuilding.setInputEnabled(false);
            rebuilding.stop();
            rebuilding.setTransitionBlocked(true);
            rebuilding.setTransitionBlocked(false);
        });
        assertEquals(starts, chat.starts);
        assertTrue(rebuilding.isStreaming());
        runFx(() -> rebuilding.stop(
                CancellationReason.RUNTIME_REBUILD,
                false,
                ChatTurnController.StopPolicy.DISCARD_AND_INVALIDATE));
        assertFalse(rebuilding.isStreaming());
    }

    private void startChat(FakeConversationMode mode, String text) throws Exception {
        mode.resetRun();
        runFx(() -> {
            composer.replaceInput(text);
            turns.sendFromComposer();
        });
        drainFxQueue();
        assertNotNull(mode.request);
    }

    private FakeConversationMode install(FakeConversationMode mode) {
        registry.unregister(mode.id());
        registry.register(mode);
        return mode;
    }

    private ChatMessage lastMessage(ChatMessage.Role role) {
        return sessions.currentSession().getMessages().reversed().stream()
                .filter(message -> message.getRole() == role)
                .findFirst()
                .orElseThrow();
    }

    private ChatTurnController copyWithRebuildingFlag(boolean rebuilding) {
        return new ChatTurnController(
                field(turns, "fx", com.javaclaw.platform.fx.FxDispatcher.class),
                field(turns, "backgroundTasks", com.javaclaw.platform.execution.TaskScope.class),
                composer,
                modeBar,
                field(turns, "thinking", ThinkingPanelController.class),
                field(turns, "status", ChatStatusController.class),
                field(turns, "renderer", ChatStreamRenderer.class),
                field(turns, "navigation", ChatNavigationController.class),
                field(turns, "runtime", java.util.function.Supplier.class),
                field(turns, "modes", java.util.function.Supplier.class),
                () -> rebuilding,
                sessions);
    }

    private void drainFxQueue() throws Exception {
        callFx(() -> null);
    }

    private static ApplicationKernel createKernel(AnnotationConfigApplicationContext context) {
        return new ApplicationKernel(
                context.getBean(PlaywrightBrowserManager.class),
                () -> { }, () -> { }, () -> { },
                context.getBean(WorkspaceSpringContextFactory.class),
                context.getBean(PluginManager.class),
                context.getBean(WorkspaceManager.class),
                context.getBean(DataManager.class),
                context.getBean(TraceRecorder.class),
                context.getBean(AgentConfig.class),
                context.getBean(EmailConfig.class),
                context.getBean(NotificationConfig.class),
                context.getBean(CommandWhitelistManager.class));
    }

    private static void runFx(Runnable action) throws Exception {
        callFx(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T callFx(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(action.call());
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                completed.countDown();
            }
        });
        assertTrue(completed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private enum CancelBehavior {
        ACCEPT_AND_TERMINATE,
        REJECT_TERMINAL,
        REJECT_ACTIVE,
        THROW
    }

    private static final class FakeConversationMode implements ConversationMode {
        private final String id;
        private boolean supportsAttachments;
        private int starts;
        private ConversationRequest request;
        private ConversationCallbacks callbacks;
        private FakeHandle handle;
        private RuntimeException startFailure;
        private ConversationOutcome terminalOnStart;

        private FakeConversationMode(String id, boolean supportsAttachments) {
            this.id = id;
            this.supportsAttachments = supportsAttachments;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return "测试模式";
        }

        @Override
        public Placement placement() {
            return Placement.TOP_SEGMENT;
        }

        @Override
        public Capabilities capabilities() {
            return new Capabilities(supportsAttachments, true, true, false, false);
        }

        @Override
        public ConversationHandle start(
                ConversationRequest request, ConversationCallbacks callbacks) {
            starts++;
            this.request = request;
            this.callbacks = callbacks;
            if (startFailure != null) throw startFailure;
            handle = new FakeHandle(callbacks);
            if (terminalOnStart != null) {
                handle.terminal = true;
                callbacks.onTerminal(terminalOnStart);
                terminalOnStart = null;
            }
            return handle;
        }

        private void event(ConversationEvent event) {
            assertNotNull(callbacks);
            callbacks.onEvent(event);
        }

        private void complete() {
            terminal(ConversationOutcome.completed());
        }

        private void fail(Throwable failure) {
            terminal(ConversationOutcome.failed(failure));
        }

        private void terminal(ConversationOutcome outcome) {
            assertNotNull(callbacks);
            if (handle != null) handle.terminal = true;
            callbacks.onTerminal(outcome);
        }

        private void resetRun() {
            request = null;
            callbacks = null;
            handle = null;
        }
    }

    private static final class FakeHandle implements ConversationHandle {
        private final ConversationCallbacks callbacks;
        private final List<CancellationReason> cancellations = new ArrayList<>();
        private CancelBehavior cancelBehavior = CancelBehavior.ACCEPT_AND_TERMINATE;
        private boolean terminal;

        private FakeHandle(ConversationCallbacks callbacks) {
            this.callbacks = callbacks;
        }

        @Override
        public boolean cancel(CancellationReason reason) {
            cancellations.add(reason);
            return switch (cancelBehavior) {
                case ACCEPT_AND_TERMINATE -> {
                    terminal = true;
                    callbacks.onTerminal(ConversationOutcome.cancelled(reason));
                    yield true;
                }
                case REJECT_TERMINAL -> {
                    terminal = true;
                    yield false;
                }
                case REJECT_ACTIVE -> false;
                case THROW -> throw new IllegalStateException("取消失败");
            };
        }

        @Override
        public boolean isTerminal() {
            return terminal;
        }
    }

    private record NonConversationMode(String id) implements Mode {
        @Override
        public String displayName() {
            return "非对话模式";
        }

        @Override
        public Placement placement() {
            return Placement.TOP_SEGMENT;
        }

        @Override
        public Capabilities capabilities() {
            return Capabilities.minimal();
        }
    }
}
