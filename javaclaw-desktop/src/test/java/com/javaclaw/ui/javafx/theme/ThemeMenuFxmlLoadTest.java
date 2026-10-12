package com.javaclaw.ui.javafx.theme;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.net.URL;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class ThemeMenuFxmlLoadTest {

    private static final long TIMEOUT_SECONDS = 5;
    private AnnotationConfigApplicationContext context;
    private ViewHandle<MenuButton> handle;
    private FakeThemeSelectionService themes;

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
    void supportsFxmlMenuItemRootsAndReleasesDynamicEntries() throws Exception {
        prepareContext();
        URL resource = ThemeMenuFxmlLoadTest.class.getResource("/fxml/chat/theme-menu.fxml");
        handle = callFx(() -> context.getBean(SpringFxmlLoader.class).load(resource));
        ThemeMenuController controller = handle.controller(ThemeMenuController.class);

        runFx(controller::rebuildEntries);

        assertEquals(2, callFx(() -> handle.root().getItems().size()));
        MenuItem first = callFx(() -> handle.root().getItems().getFirst());
        MenuItem second = callFx(() -> handle.root().getItems().get(1));
        assertEquals("翡翠 Emerald", callFx(() -> label(first, "nameLabel").getText()));
        assertEquals("✓", callFx(() -> label(first, "selectedMark").getText()));
        assertEquals(" ", callFx(() -> label(second, "selectedMark").getText()));

        runFx(second::fire);
        assertEquals("ocean", themes.currentThemeId());

        runFx(controller::reloadFromWorkspace);
        assertEquals(1, themes.reloads.get());

        runFx(handle::close);
        assertTrue(controller.isClosed());
        assertTrue(callFx(() -> handle.root().getItems().isEmpty()));
        handle = null;
    }

    @Test
    void entryFactoryLoadsMenuItemAsNonNodeFxmlRoot() throws Exception {
        prepareContext();
        ThemeOption option = themes.availableThemes().getFirst();
        AtomicInteger selections = new AtomicInteger();
        ThemeMenuEntryView entry = callFx(() ->
                context.getBean(ThemeMenuEntryFactory.class).create(
                        option, true, selections::incrementAndGet));

        assertEquals("翡翠 Emerald", callFx(() -> label(entry.root(), "nameLabel").getText()));
        runFx(entry.root()::fire);
        assertEquals(1, selections.get());

        runFx(entry::close);
        assertTrue(entry.controller().isClosed());
    }

    private void prepareContext() {
        context = new AnnotationConfigApplicationContext();
        themes = new FakeThemeSelectionService();
        context.registerBean(ThemeSelectionService.class, () -> themes);
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.registerBean(ThemeMenuEntryFactory.class,
                () -> new ThemeMenuEntryFactory(context.getBean(SpringFxmlLoader.class)));
        context.refresh();
    }

    private static Label label(MenuItem item, String id) {
        Node match = findById(item.getGraphic(), id);
        assertTrue(match instanceof Label, "未找到 #" + id);
        return (Label) match;
    }

    private static Node findById(Node node, String id) {
        if (node == null) return null;
        if (id.equals(node.getId())) return node;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node match = findById(child, id);
                if (match != null) return match;
            }
        }
        return null;
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

    private static final class FakeThemeSelectionService implements ThemeSelectionService {
        private final List<ThemeOption> options = List.of(
                new ThemeOption("emerald", "翡翠 Emerald", "默认",
                        "#2E9A6A", "#FBFAF6", "#FFFFFF"),
                new ThemeOption("ocean", "海洋 Ocean", "青绿强调",
                        "#0E8C8C", "#F6FAFA", "#FFFFFF"));
        private final SimpleStringProperty current = new SimpleStringProperty("emerald");
        private final AtomicInteger reloads = new AtomicInteger();

        @Override
        public List<ThemeOption> availableThemes() { return options; }

        @Override
        public ThemeOption currentTheme() {
            return options.stream()
                    .filter(option -> option.id().equals(current.get()))
                    .findFirst()
                    .orElse(options.getFirst());
        }

        @Override
        public String currentThemeId() { return current.get(); }

        @Override
        public ReadOnlyStringProperty currentThemeProperty() { return current; }

        @Override
        public void select(String themeId) { current.set(themeId); }

        @Override
        public void reloadFromWorkspace() { reloads.incrementAndGet(); }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
