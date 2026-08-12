package com.javaclaw.chat;

import com.javaclaw.browser.PlaywrightBrowserManager;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.config.DataManager;
import com.javaclaw.config.EmailConfig;
import com.javaclaw.config.NotificationConfig;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.diagnostics.TraceRecorder;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.spring.ApplicationContexts;
import com.javaclaw.platform.spring.WorkspaceSpringContextFactory;
import com.javaclaw.plugin.PluginManager;
import com.javaclaw.runtime.ApplicationKernel;
import com.javaclaw.system.CommandWhitelistManager;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class ChatViewFxmlLoadTest {

    private static final long TIMEOUT_SECONDS = 20;

    @TempDir
    Path tempDirectory;

    private AnnotationConfigApplicationContext rootContext;
    private ApplicationKernel kernel;
    private ViewHandle<BorderPane> chatView;

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
    void mainChatViewLoadsFromSpringAndReleasesEveryController() throws Exception {
        rootContext = ApplicationContexts.createRoot(
                new DataRoot(tempDirectory.resolve("data")));
        ApplicationContexts.registerDesktopInfrastructure(rootContext);
        kernel = createKernel(rootContext);
        assertNotNull(kernel.initialize());
        ApplicationContexts.registerApplicationKernel(rootContext, kernel);

        chatView = callFx(() -> rootContext.getBean(SpringFxmlLoader.class).load(
                java.util.Objects.requireNonNull(
                        getClass().getResource("/fxml/chat/chat-view.fxml"))));
        Scene scene = callFx(() -> new Scene(chatView.root(), 1200, 700));

        assertSame(chatView.root(), scene.getRoot());
        assertEquals(1200, scene.getWidth());
        assertEquals(700, scene.getHeight());
        assertNotNull(chatView.controller(ChatViewController.class));
        assertControllerCreated(ChatSessionController.class);
        assertControllerCreated(ChatComposerController.class);
        assertControllerCreated(ChatModeController.class);
        assertControllerCreated(ChatHeaderController.class);
        assertControllerCreated(SidebarController.class);
        assertControllerCreated(ThinkingPanelController.class);
        assertControllerCreated(WorkspaceSwitchOverlayController.class);

        runFx(chatView::close);
        chatView = null;
        assertNotNull(kernel.current());
    }

    private void assertControllerCreated(Class<?> type) {
        assertTrue(chatView.controllers().stream().anyMatch(type::isInstance),
                () -> "主聊天 FXML 未创建 " + type.getSimpleName());
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
}
