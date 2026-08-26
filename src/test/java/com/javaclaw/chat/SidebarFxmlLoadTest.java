package com.javaclaw.chat;

import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.spring.ApplicationContexts;
import javafx.application.Platform;
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
            callFx(() -> {
                controller.startNewStream();
                controller.appendThinking("分析中");
                controller.recordPipelineProgress(
                        "route", "路由", "done", "已选择普通对话");
                controller.updateMetrics(12, 4, "缓存 3 · 写入 1 · 推理 2");
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
