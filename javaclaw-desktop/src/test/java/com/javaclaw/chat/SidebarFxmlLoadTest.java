package com.javaclaw.chat;

import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.spring.ApplicationContexts;
import com.javaclaw.loop.model.Decision;
import com.javaclaw.loop.model.LoopStatus;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class SidebarFxmlLoadTest {

    @BeforeAll
    static void startToolkit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(5, TimeUnit.SECONDS));
    }

    @Test
    void springCreatesAndDestroysSidebarController(@TempDir Path directory) throws Exception {
        try (var context = ApplicationContexts.createRoot(
                new DataRoot(directory.resolve("data")))) {
            SpringFxmlLoader loader = context.getBean(SpringFxmlLoader.class);
            ViewHandle<VBox> handle = callFx(() -> loader.load(
                    getClass().getResource("/fxml/chat/sidebar-view.fxml")));
            try {
                SidebarController controller = handle.controller(SidebarController.class);
                SidebarSessionListController sessions =
                        handle.controller(SidebarSessionListController.class);
                SidebarProfileController profile =
                        handle.controller(SidebarProfileController.class);
                assertSame(handle.root(), controller.getRoot());
                assertNotNull(injectedField(controller, "workspaceCombo"));
                assertNotNull(injectedField(controller, "sessionListController"));
                assertNotNull(injectedField(controller, "profileController"));
                assertNotNull(sessions.getRoot());
                assertNotNull(profile.getRoot());
            } finally {
                callFx(() -> {
                    handle.close();
                    return null;
                });
            }
            assertTrue(handle.controller(SidebarController.class).isClosed());
            assertTrue(handle.controller(SidebarSessionListController.class).isClosed());
            assertTrue(handle.controller(SidebarProfileController.class).isClosed());

            ViewHandle<StackPane> cellHandle = callFx(() -> loader.load(
                    getClass().getResource("/fxml/chat/sidebar-session-cell.fxml")));
            SidebarSessionCellController cell =
                    cellHandle.controller(SidebarSessionCellController.class);
            assertNotNull(injectedField(cell, "conversationRow"));
            java.util.List<String> lifecycle = new java.util.ArrayList<>();
            SidebarSessionCellActions actions = new SidebarSessionCellActions() {
                @Override public void activate(String id) { }
                @Override public void delete(String id) { }
                @Override public void checked(String id, boolean selected) { }
                @Override public void archive(String id) { lifecycle.add("archive:" + id); }
                @Override public void resume(String id) { lifecycle.add("resume:" + id); }
                @Override public void fork(String id) { lifecycle.add("fork:" + id); }
                @Override public void inspect(String id) { lifecycle.add("inspect:" + id); }
            };
            callFx(() -> {
                cell.show(new SidebarSessionItem.Conversation("child", "Worker", "today", false,
                        false, false, false, "parent"), actions);
                for (String name : java.util.List.of("archiveItem", "resumeItem", "forkItem", "inspectItem"))
                    ((javafx.scene.control.MenuItem) injectedField(cell, name)).fire();
                cell.show(new SidebarSessionItem.Conversation("child", "Worker", "today", false,
                        true, false, true, "parent"), actions);
                assertTrue(((javafx.scene.control.Label) injectedField(cell, "titleLabel")).getText().contains("[已归档] ↳"));
                for (String name : java.util.List.of("archiveItem", "resumeItem", "forkItem", "inspectItem")) {
                    var item = (javafx.scene.control.MenuItem) injectedField(cell, name);
                    assertTrue(item.isDisable());
                    item.fire();
                }
                return null;
            });
            assertEquals(java.util.List.of("archive:child", "resume:child", "fork:child", "inspect:child"), lifecycle);
            callFx(() -> {
                cellHandle.close();
                return null;
            });
            assertTrue(cell.isClosed());
        }
    }

    @Test
    void thinkingPanelRendersDynamicContentAndStopsOnClose(@TempDir Path directory)
            throws Exception {
        try (var context = ApplicationContexts.createRoot(
                new DataRoot(directory.resolve("data")))) {
            SpringFxmlLoader loader = context.getBean(SpringFxmlLoader.class);
            ViewHandle<VBox> handle = callFx(() -> loader.load(
                    getClass().getResource("/fxml/chat/thinking-panel.fxml")));
            ThinkingPanelController controller =
                    handle.controller(ThinkingPanelController.class);
            VBox sections = (VBox) injectedField(controller, "dynamicSectionsHost");
            callFx(() -> {
                controller.startNewStream();
                controller.appendThinking("思考明细：筛选亲子地点");
                controller.updatePlan("规划明细：先确认交通再安排景点");
                controller.recordPipelineProgress(
                        "route", "路由", ThinkingContentRenderer.StageState.DONE,
                        "阶段明细：已选择普通对话");
                controller.appendSubAgentThinking("知识专家", "智能体过程：核对开放时间；失败、已停止只是内容");
                assertEquals(1, countNodesWithClass(sections, "agent-status-thinking"));
                controller.markSubAgentReplying("知识专家");
                assertTrue(renderedText(sections).contains("返回结果中"));
                controller.markSubAgentResult("知识专家", "智能体结果：找到两个候选景点");
                assertEquals(1, countNodesWithClass(sections, "agent-status-done"));
                controller.appendSubAgentThinking("验证专家", "校验失败只是思考内容");
                assertEquals(1, countNodesWithClass(sections, "agent-status-thinking"));
                controller.completeSubAgentIfPresent("验证专家",
                        com.javaclaw.framework.api.ToolExecutionStatus.FAILED);
                assertEquals(1, countNodesWithClass(sections, "agent-status-failed"));
                controller.appendSubAgentThinking("复核专家", "核验尚无结论");
                controller.completeSubAgentIfPresent("复核专家",
                        com.javaclaw.framework.api.ToolExecutionStatus.UNCERTAIN);
                assertEquals(1, countNodesWithClass(sections, "agent-status-stopped"));
                controller.appendToolCall("搜索", "工具输入：亲子景点 error failed done",
                        ThinkingContentRenderer.ToolState.RUNNING);
                assertEquals(1, countNodesWithClass(sections, "tp-tool-status-running"));
                controller.appendToolCall("搜索", "工具输入：亲子景点",
                        ThinkingContentRenderer.ToolState.SUCCEEDED);
                controller.appendToolCall("搜索", "工具输入：中秋交通",
                        ThinkingContentRenderer.ToolState.RUNNING);
                controller.appendToolCall("搜索", "工具输入：中秋交通",
                        ThinkingContentRenderer.ToolState.SUCCEEDED);
                controller.recordLoopStatus(new LoopStatus(
                        2, Decision.CONTINUE, "PRIVATE_LOOP_REASON", 1, 3, 42, 5));
                String visibleDetails = visibleRenderedText(sections);
                assertTrue(visibleDetails.contains("路由"));
                assertTrue(visibleDetails.contains("知识专家"));
                for (String detail : java.util.List.of(
                        "思考明细：筛选亲子地点", "规划明细：先确认交通再安排景点",
                        "阶段明细：已选择普通对话", "智能体过程：核对开放时间",
                        "智能体结果：找到两个候选景点", "工具输入：亲子景点",
                        "工具输入：中秋交通")) {
                    assertTrue(visibleDetails.contains(detail), () -> "右栏缺少可见明细: " + detail);
                }
                assertEquals(2, countNodesWithClass(sections, "tp-tool-row"),
                        "同名工具的两次调用应分别保留明细");
                assertTrue(visibleDetails.contains("第 2 轮 · 已满足 1/3 项 · 5 秒后继续"));
                assertFalse(renderedText(sections).contains("PRIVATE_LOOP_REASON"),
                        "循环自由文本理由不属于进度明细");
                controller.recordPipelineProgress("planning", "选择上下文",
                        ThinkingContentRenderer.StageState.RUNNING,
                        "正在确定本轮需要的资料和工具");
                assertTrue(visibleRenderedText(sections).contains("正在确定本轮需要的资料和工具"));
                controller.recordPipelineProgress("planning", "选择上下文",
                        ThinkingContentRenderer.StageState.DONE, null);
                assertFalse(visibleRenderedText(sections).contains("正在确定本轮需要的资料和工具"));
                controller.recordPipelineProgress("failed", "读取资料",
                        ThinkingContentRenderer.StageState.RUNNING, "正在读取资料");
                controller.recordPipelineProgress("failed", "读取资料",
                        ThinkingContentRenderer.StageState.ERROR, "资料读取失败");
                assertTrue(visibleRenderedText(sections).contains("资料读取失败"));
                assertFalse(visibleRenderedText(sections).contains("正在读取资料"));
                controller.appendToolResult("认证检查",
                        "{\"token\":\"synthetic-sensitive-value\"}");
                assertTrue(visibleRenderedText(sections).contains("<敏感内容已隐藏>"));
                assertFalse(renderedText(sections).contains("synthetic-sensitive-value"));
                controller.updateMetrics(12, 4, "¥0.01");
                controller.endStream();
                return null;
            });
            assertEquals("处理完成", controller.viewModel().statusTextProperty().get());
            assertEquals(12, controller.viewModel().tokensInProperty().get());
            callFx(() -> {
                handle.close();
                return null;
            });
            assertTrue(controller.isClosed());
        }
    }

    private static Object injectedField(Object controller, String name) throws Exception {
        Field field = controller.getClass().getDeclaredField(name);
        assertTrue(field.trySetAccessible());
        return field.get(controller);
    }

    private static String renderedText(Node node) {
        StringBuilder text = new StringBuilder();
        if (node instanceof Label label) text.append(label.getText()).append('\n');
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                text.append(renderedText(child));
            }
        }
        return text.toString();
    }

    private static String visibleRenderedText(Node node) {
        if (!node.isVisible() || !node.isManaged()) return "";
        StringBuilder text = new StringBuilder();
        if (node instanceof Label label) text.append(label.getText()).append('\n');
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                text.append(visibleRenderedText(child));
            }
        }
        return text.toString();
    }

    private static long countNodesWithClass(Node node, String styleClass) {
        long count = node.getStyleClass().contains(styleClass) ? 1 : 0;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                count += countNodesWithClass(child, styleClass);
            }
        }
        return count;
    }

    private static <T> T callFx(Callable<T> action) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                value.set(action.call());
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                finished.countDown();
            }
        });
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        if (failure.get() instanceof Exception exception) throw exception;
        if (failure.get() != null) throw new AssertionError(failure.get());
        return value.get();
    }
}
