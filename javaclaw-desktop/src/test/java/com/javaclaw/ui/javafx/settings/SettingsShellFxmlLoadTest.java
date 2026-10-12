package com.javaclaw.ui.javafx.settings;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class SettingsShellFxmlLoadTest {
    private static final long TIMEOUT_SECONDS = 5;

    private final List<ViewHandle<?>> handles = new ArrayList<>();
    private AnnotationConfigApplicationContext context;

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
        runFx(() -> {
            for (int index = handles.size() - 1; index >= 0; index--) {
                handles.get(index).close();
            }
            handles.clear();
        });
        if (context != null) context.close();
    }

    @Test
    void navigationLoadsFiltersSelectsAndRendersDirtyState() throws Exception {
        prepareContext();
        ViewHandle<Region> handle = add(callFx(() -> load("settings-navigation.fxml")));
        SettingsNavigationController controller =
                handle.controller(SettingsNavigationController.class);
        AtomicReference<SettingsCategory> selected = new AtomicReference<>();
        runFx(() -> {
            new Scene(handle.root(), 240, 700);
            controller.configure(selected::set);
            controller.showDirty(Set.of(SettingsCategory.EMAIL));
            ((TextField) handle.root().lookup("#searchField")).setText("smtp");
        });

        ToggleButton email = callFx(() -> button(handle, SettingsCategory.EMAIL));
        ToggleButton model = callFx(() -> button(handle, SettingsCategory.MODEL));
        assertTrue(callFx(email::isVisible));
        assertFalse(callFx(model::isVisible));
        runFx(email::fire);
        assertEquals(SettingsCategory.EMAIL, selected.get());
        assertTrue(callFx(() -> dirtyDot(email).isVisible()));
    }

    @Test
    void footerLoadsAndReflectsCapabilitiesWithoutCreatingLayoutInController() throws Exception {
        prepareContext();
        ViewHandle<Region> handle = add(callFx(() -> load("settings-footer.fxml")));
        SettingsFooterController controller = handle.controller(SettingsFooterController.class);
        runFx(() -> {
            new Scene(handle.root(), 800, 80);
            controller.capabilities(true, true, true, false, "测试嵌入");
            controller.showUnsaved();
        });

        assertEquals("测试嵌入", callFx(() -> ((javafx.scene.control.Button)
                handle.root().lookup("#testButton")).getText()));
        assertFalse(callFx(() -> ((javafx.scene.control.Button)
                handle.root().lookup("#saveButton")).isDisabled()));
        assertEquals("有未保存的更改", callFx(() -> ((javafx.scene.control.Label)
                handle.root().lookup("#statusLabel")).getText()));
    }

    private void prepareContext() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.refresh();
    }

    @SuppressWarnings("unchecked")
    private ViewHandle<Region> load(String file) throws Exception {
        URL resource = getClass().getResource("/fxml/settings/" + file);
        return (ViewHandle<Region>) (ViewHandle<?>) context.getBean(SpringFxmlLoader.class)
                .load(resource);
    }

    private <T extends ViewHandle<?>> T add(T handle) {
        handles.add(handle);
        return handle;
    }

    private static ToggleButton button(ViewHandle<Region> handle, SettingsCategory category) {
        return handle.root().lookupAll(".modal-nav-btn").stream()
                .map(ToggleButton.class::cast)
                .filter(button -> category.name().equals(String.valueOf(button.getUserData())))
                .findFirst().orElseThrow();
    }

    private static Node dirtyDot(ToggleButton button) {
        return ((javafx.scene.layout.HBox) button.getGraphic()).getChildren().get(4);
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
