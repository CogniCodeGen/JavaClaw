package com.javaclaw.ui.javafx.settings;

import com.javaclaw.browser.PlaywrightBrowserManager;
import com.javaclaw.config.DataManager;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import com.javaclaw.platform.spring.WorkspaceContextHandle;
import com.javaclaw.platform.spring.WorkspaceRuntimeOptions;
import com.javaclaw.platform.spring.WorkspaceSpringContextFactory;
import com.javaclaw.runtime.WorkspaceContext;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class SettingsWindowFxmlLoadTest {
    private static final long TIMEOUT_SECONDS = 15;

    @TempDir
    java.nio.file.Path tempDirectory;

    private AnnotationConfigApplicationContext root;
    private WorkspaceContextHandle workspace;
    private PlaywrightBrowserManager browser;
    private SettingsView view;
    private String previousDataDirectory;

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
        if (view != null) runFx(view::close);
        if (workspace != null) workspace.close();
        if (browser != null) browser.shutdown();
        if (root != null) root.close();
        if (previousDataDirectory == null) {
            System.clearProperty(DataRoot.DATA_DIR_PROPERTY);
        } else {
            System.setProperty(DataRoot.DATA_DIR_PROPERTY, previousDataDirectory);
        }
    }

    @Test
    void completeWindowLoadsAllSectionsFromWorkspaceContextAndClosesCleanly() throws Exception {
        previousDataDirectory = System.getProperty(DataRoot.DATA_DIR_PROPERTY);
        System.setProperty(DataRoot.DATA_DIR_PROPERTY, tempDirectory.resolve("data-v3").toString());
        root = ApplicationContexts.createRoot(DataRoot.resolve());
        WorkspaceManager.getInstance().init();
        DataManager.getInstance().reload();
        WorkspaceContext current = WorkspaceContext.captureCurrent();
        browser = new PlaywrightBrowserManager(true, current.browserDir(), current.screenshotsDir());
        workspace = root.getBean(WorkspaceSpringContextFactory.class).create(current,
                new WorkspaceRuntimeOptions(browser, () -> { }, () -> { }, () -> { }, Set.of()));

        view = callFx(() -> workspace.bean(SettingsViewFactory.class).create(null));

        assertNotNull(view);
        assertNotNull(workspace.bean(SettingsPanelCatalogFactory.class));
        runFx(view::close);
        view = null;
    }

    private static void runFx(Runnable action) throws Exception {
        callFx(() -> { action.run(); return null; });
    }

    private static <T> T callFx(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { result.set(action.call()); }
            catch (Throwable thrown) { failure.set(thrown); }
            finally { completed.countDown(); }
        });
        assertTrue(completed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }
}
