package com.javaclaw.chat;

import com.javaclaw.api.conversation.PlanProfile;
import com.javaclaw.application.chat.ChatModeApplicationService;
import com.javaclaw.config.ToolReviewMode;
import com.javaclaw.platform.execution.ExecutionLimits;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.layout.HBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.Field;
import java.net.URL;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class ChatModeFxmlLoadTest {

    private static final long TIMEOUT_SECONDS = 5;
    private AnnotationConfigApplicationContext context;
    private ViewHandle<HBox> handle;

    @BeforeAll
    static void startToolkit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (handle != null) runFx(handle::close);
        if (context != null) context.close();
    }

    @Test
    void mapsModesWorkflowsReviewAndParentCallbacks() throws Exception {
        FakeChatModeApplicationService fake = new FakeChatModeApplicationService();
        ChatModeController controller = load(fake);
        ComboBox<?> modes = field(controller, "conversationModeSelector", ComboBox.class);
        @SuppressWarnings("rawtypes")
        ComboBox profiles = field(controller, "planProfileSelector", ComboBox.class);
        ComboBox<?> workflows = field(controller, "workflowSelector", ComboBox.class);
        MenuButton loops = field(controller, "loopTemplateMenu", MenuButton.class);
        MenuButton reviews = field(controller, "reviewModeMenu", MenuButton.class);

        awaitFx(() -> workflows.getItems().size() == 1);
        assertEquals("chat", callFx(controller::selectedModeId));
        assertEquals("对话", callFx(() -> modes.getValue().toString()));
        assertEquals("build", fake.selectedWorkflow.get());

        runFx(() -> controller.selectMode("plan"));
        assertTrue(callFx(profiles::isVisible));
        runFx(() -> profiles.setValue(PlanProfile.DEEP));
        assertEquals(PlanProfile.DEEP, callFx(controller::planProfile));

        AtomicReference<String> template = new AtomicReference<>();
        runFx(() -> {
            controller.setOnLoopTemplateSelected(template::set);
            controller.selectMode("loop");
            loops.getItems().getFirst().fire();
        });
        assertTrue(template.get().startsWith("@loop"));
        assertTrue(callFx(loops::isVisible));

        runFx(() -> reviews.getItems().getLast().fire());
        assertEquals(ToolReviewMode.AUTO, fake.reviewMode);
        assertEquals("全自动", callFx(reviews::getText));

        AtomicBoolean workflowOpened = new AtomicBoolean();
        AtomicBoolean tasksOpened = new AtomicBoolean();
        runFx(() -> {
            controller.setOnOpenWorkflowCenter(() -> workflowOpened.set(true));
            controller.setOnOpenTaskManager(() -> tasksOpened.set(true));
            buttons(handle.root()).stream()
                    .filter(button -> "前往工作流中心".equals(button.getText())
                            || "托管任务".equals(button.getText()))
                    .forEach(Button::fire);
            controller.updateTokenSummary("摘要", "详情");
        });
        assertTrue(workflowOpened.get());
        assertTrue(tasksOpened.get());
        Label token = field(controller, "tokenLabel", Label.class);
        assertEquals("摘要", callFx(token::getText));

        runFx(handle::close);
        assertTrue(controller.isClosed());
        handle = null;
    }

    @Test
    void dropsWorkflowSnapshotAfterWorkspaceChanges() throws Exception {
        FakeChatModeApplicationService fake = new FakeChatModeApplicationService();
        fake.blockWorkflows = new CountDownLatch(1);
        ChatModeController controller = load(fake);
        ComboBox<?> workflows = field(controller, "workflowSelector", ComboBox.class);
        assertTrue(fake.queryStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        fake.workspaceId = "second";
        fake.blockWorkflows.countDown();
        assertTrue(fake.queryReturned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertTrue(callFx(workflows.getItems()::isEmpty));
        runFx(() -> controller.selectMode("workflow"));
        assertFalse(callFx(workflows::isDisable));
    }

    private ChatModeController load(FakeChatModeApplicationService fake) throws Exception {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(ChatModeApplicationService.class, () -> fake);
        context.registerBean(ManagedTaskExecutor.class,
                () -> new ManagedTaskExecutor(new ExecutionLimits(2, 1, 8, 1, 1)),
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean(FxDispatcher.class, FxDispatcher::new);
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.refresh();
        URL resource = getClass().getResource("/fxml/chat/chat-mode-bar.fxml");
        assertNotNull(resource);
        handle = callFx(() -> context.getBean(SpringFxmlLoader.class).load(resource));
        return handle.controller(ChatModeController.class);
    }

    private static List<Button> buttons(Node root) {
        java.util.ArrayList<Button> found = new java.util.ArrayList<>();
        collectButtons(root, found);
        return found;
    }

    private static void collectButtons(Node node, List<Button> found) {
        if (node instanceof Button button) found.add(button);
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) collectButtons(child, found);
        }
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        assertTrue(field.trySetAccessible());
        return type.cast(field.get(target));
    }

    private static void awaitFx(CheckedBoolean condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "等待 JavaFX 状态超时");
    }

    private static void runFx(ThrowingRunnable action) throws Exception {
        callFx(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T callFx(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) return action.call();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                result.set(action.call());
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (failure.get() instanceof Exception exception) throw exception;
        if (failure.get() instanceof Error error) throw error;
        return result.get();
    }

    private static final class FakeChatModeApplicationService
            implements ChatModeApplicationService {
        private volatile String workspaceId = "first";
        private volatile CountDownLatch blockWorkflows;
        private volatile ToolReviewMode reviewMode = ToolReviewMode.SMART;
        private final AtomicReference<String> selectedWorkflow = new AtomicReference<>();
        private final CountDownLatch queryStarted = new CountDownLatch(1);
        private final CountDownLatch queryReturned = new CountDownLatch(1);

        @Override
        public List<ModeOption> availableConversationModes() {
            return List.of(
                    new ModeOption("chat", "Chat", "普通对话"),
                    new ModeOption("plan", "Plan", "多智能体研讨"),
                    new ModeOption("loop", "Loop", "循环执行"),
                    new ModeOption("workflow", "Workflow", "工作流"));
        }

        @Override
        public WorkflowSnapshot publishedWorkflows() {
            String queriedWorkspace = workspaceId;
            queryStarted.countDown();
            CountDownLatch blocker = blockWorkflows;
            try {
                if (blocker != null) blocker.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return new WorkflowSnapshot(queriedWorkspace,
                        List.of(new WorkflowOption("build", "构建流程")));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("测试查询被中断", interrupted);
            } finally {
                queryReturned.countDown();
            }
        }

        @Override
        public boolean isCurrent(WorkflowSnapshot snapshot) {
            return workspaceId.equals(snapshot.workspaceId());
        }

        @Override
        public boolean isTransitioning() {
            return false;
        }

        @Override
        public void selectWorkflow(String workflowId) {
            selectedWorkflow.set(workflowId);
        }

        @Override
        public ToolReviewMode currentReviewMode() {
            return reviewMode;
        }

        @Override
        public void changeReviewMode(ToolReviewMode mode) {
            reviewMode = mode;
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean getAsBoolean() throws Exception;
    }
}
