package com.javaclaw.ui.javafx.knowledge;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.application.Platform;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.net.URL;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class KnowledgeMenuFxmlLoadTest {

    private static final long TIMEOUT_SECONDS = 5;
    private AnnotationConfigApplicationContext context;
    private ViewHandle<MenuButton> handle;

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
    void rebuildsGroupedEntriesAndSubmitsDocumentIds() throws Exception {
        prepareContext();
        URL resource = getClass().getResource("/fxml/chat/knowledge-menu.fxml");
        handle = callFx(() -> context.getBean(SpringFxmlLoader.class).load(resource));
        KnowledgeMenuController controller = handle.controller(KnowledgeMenuController.class);
        AtomicReference<Set<String>> selected = new AtomicReference<>();
        AtomicInteger managerOpens = new AtomicInteger();
        AtomicReference<KnowledgeMenuSnapshot> snapshot = new AtomicReference<>(snapshot());
        runFx(() -> controller.configure(
                snapshot::get, selected::set, managerOpens::incrementAndGet));

        runFx(controller::refresh);

        assertEquals(9, callFx(() -> handle.root().getItems().size()));
        CheckMenuItem workspaceDocument = callFx(() -> checkItem("workspace.md"));
        assertFalse(callFx(workspaceDocument::isSelected));
        runFx(() -> {
            workspaceDocument.setSelected(true);
            workspaceDocument.fire();
        });
        assertEquals(Set.of("global.md", "workspace.md"), selected.get());
        assertEquals("知识库(2)", callFx(handle.root()::getText));
        assertTrue(callFx(() -> handle.root().getStyleClass().contains("knowledge-active")));

        CheckMenuItem selectAll = callFx(() ->
                (CheckMenuItem) handle.root().getItems().getFirst());
        runFx(() -> {
            selectAll.setSelected(false);
            selectAll.fire();
        });
        assertEquals(Set.of(), selected.get());
        assertEquals("知识库", callFx(handle.root()::getText));

        MenuItem manage = callFx(() -> handle.root().getItems().getLast());
        runFx(manage::fire);
        assertEquals(1, managerOpens.get());

        snapshot.set(KnowledgeMenuSnapshot.ragDisabled());
        runFx(controller::refresh);
        assertEquals(1, callFx(() -> handle.root().getItems().size()));
        assertTrue(callFx(() -> handle.root().getItems().getFirst().isDisable()));

        runFx(handle::close);
        assertTrue(controller.isClosed());
        assertTrue(callFx(() -> handle.root().getItems().isEmpty()));
        handle = null;
    }

    @Test
    void entryFactoryLoadsAndDestroysEveryFxmlVariant() throws Exception {
        prepareContext();
        KnowledgeMenuEntryFactory factory = context.getBean(KnowledgeMenuEntryFactory.class);
        AtomicReference<Boolean> checked = new AtomicReference<>();
        AtomicInteger actions = new AtomicInteger();

        KnowledgeMenuEntryView check = callFx(() ->
                factory.check("文档", false, "knowledge-doc-item", checked::set));
        KnowledgeMenuEntryView action = callFx(() ->
                factory.action("管理", false, "knowledge-manage-entry", actions::incrementAndGet));
        KnowledgeMenuEntryView header = callFx(() -> factory.header("全局知识库"));
        KnowledgeMenuEntryView separator = callFx(factory::separator);

        runFx(() -> {
            ((CheckMenuItem) check.root()).setSelected(true);
            check.root().fire();
        });
        runFx(action.root()::fire);
        assertEquals(Boolean.TRUE, checked.get());
        assertEquals(1, actions.get());

        KnowledgeCheckItemController checkController =
                check.controller(KnowledgeCheckItemController.class);
        KnowledgeActionItemController actionController =
                action.controller(KnowledgeActionItemController.class);
        KnowledgeHeaderItemController headerController =
                header.controller(KnowledgeHeaderItemController.class);
        runFx(check::close);
        runFx(action::close);
        runFx(header::close);
        runFx(separator::close);
        assertTrue(checkController.isClosed());
        assertTrue(actionController.isClosed());
        assertTrue(headerController.isClosed());
    }

    private CheckMenuItem checkItem(String prefix) {
        return handle.root().getItems().stream()
                .filter(CheckMenuItem.class::isInstance)
                .map(CheckMenuItem.class::cast)
                .filter(item -> item.getText().startsWith(prefix))
                .findFirst()
                .orElseThrow();
    }

    private void prepareContext() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.registerBean(KnowledgeMenuEntryFactory.class,
                () -> new KnowledgeMenuEntryFactory(context.getBean(SpringFxmlLoader.class)));
        context.refresh();
    }

    private static KnowledgeMenuSnapshot snapshot() {
        return new KnowledgeMenuSnapshot(
                true,
                List.of(new KnowledgeMenuSnapshot.Document("global.md", 3)),
                List.of(new KnowledgeMenuSnapshot.Document("workspace.md", 5)),
                Set.of("global.md"));
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

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
