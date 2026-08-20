package com.javaclaw.ui.javafx.site;

import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.application.error.NotFoundException;
import com.javaclaw.application.site.SiteCredentialApplicationService;
import com.javaclaw.application.site.SiteCredentialApplicationService.Credential;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class SiteCredentialFxmlLoadTest {

    private static final long TIMEOUT_SECONDS = 5;
    private AnnotationConfigApplicationContext context;
    private SiteCredentialPanel panel;
    private SiteCredentialController controller;

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
        if (panel != null) runFx(panel::close);
        if (context != null) context.close();
    }

    @Test
    void loadsCardsResetsDeletesAndDestroysAllControllers() throws Exception {
        FakeService service = new FakeService();
        createContext(service);
        runFx(() -> {
            panel = context.getBean(SiteCredentialPanelFactory.class).create();
            new Scene(panel.root(), 760, 600);
            panel.root().applyCss();
            controller = panel.controller();
            panel.activate();
        });

        awaitFx(() -> list().getChildren().size() == 1);
        assertEquals("配置存储: fake-h2", callFx(() -> label("storageLabel").getText()));
        assertEquals("GitHub", callFx(() -> label("nameLabel").getText()));

        runFx(() -> cardButton("重置会话").fire());
        awaitFx(() -> service.snapshot().require("site-1").hasSession() == false
                && cardButton("重置会话").isDisabled());

        runFx(() -> cardButton("删除").fire());
        awaitFx(() -> list().getChildren().isEmpty()
                && label("emptyLabel").isVisible());

        runFx(panel::close);
        panel = null;
        assertTrue(controller.isClosed());
    }

    @Test
    void editorLoadsExistingSecretAndProducesImmutableSaveCommand() throws Exception {
        createContext(new FakeService());
        runFx(() -> {
            try {
                ViewHandle<VBox> handle = context.getBean(SpringFxmlLoader.class).load(
                        getClass().getResource("/fxml/site/site-credential-editor.fxml"));
                try {
                    SiteCredentialEditorController editor =
                            handle.controller(SiteCredentialEditorController.class);
                    Credential existing = new FakeService().snapshot().require("site-1");
                    editor.configure(existing);
                    assertEquals("GitHub", ((TextField) handle.root()
                            .lookup("#nameField")).getText());
                    assertEquals("secret", editor.command().password());
                    assertTrue(editor.validBinding().get());
                } finally {
                    handle.close();
                }
            } catch (java.io.IOException failure) {
                throw new AssertionError(failure);
            }
        });
    }

    private void createContext(FakeService service) {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(SiteCredentialApplicationService.class, () -> service);
        context.registerBean(UserInteractionPort.class, AllowInteraction::new);
        context.registerBean(ManagedTaskExecutor.class, () -> new ManagedTaskExecutor(),
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean(FxDispatcher.class, FxDispatcher::new);
        context.registerBean(DialogService.class,
                () -> new DialogService(context.getBean(UserInteractionPort.class)));
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getAutowireCapableBeanFactory()));
        context.registerBean(SiteCredentialCardFactory.class,
                () -> new SiteCredentialCardFactory(context.getBean(SpringFxmlLoader.class)));
        context.registerBean(SiteCredentialEditorFactory.class,
                () -> new SiteCredentialEditorFactory(
                        context.getBean(SpringFxmlLoader.class),
                        new com.javaclaw.app.UIHelper(context.getBean(FxDispatcher.class))));
        context.registerBean(SiteCredentialPanelFactory.class,
                () -> new SiteCredentialPanelFactory(context.getBean(SpringFxmlLoader.class)));
        context.refresh();
    }

    private VBox list() { return (VBox) panel.root().lookup("#credentialList"); }
    private Label label(String id) { return (Label) panel.root().lookup("#" + id); }

    private Button cardButton(String text) {
        return list().lookupAll(".button").stream()
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst().orElseThrow();
    }

    private static void awaitFx(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (callFx(condition::getAsBoolean)) return;
            Thread.sleep(10);
        }
        assertTrue(callFx(condition::getAsBoolean), "等待 JavaFX 状态超时");
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

    private static final class AllowInteraction implements UserInteractionPort {
        @Override public boolean confirm(ConfirmRequest request) { return true; }
        @Override public void notify(ToastRequest request) {}
    }

    private static final class FakeService implements SiteCredentialApplicationService {
        private final List<Credential> credentials = new ArrayList<>(List.of(new Credential(
                "site-1", "GitHub", "github.com", "https://github.com/login",
                "octo", "secret", "测试", 100, 200, true)));

        @Override public synchronized Snapshot snapshot() {
            return new Snapshot(credentials, "fake-h2");
        }

        @Override public synchronized Snapshot save(SaveCommand command) { return snapshot(); }

        @Override public synchronized Snapshot delete(String id) {
            if (!credentials.removeIf(item -> item.id().equals(id))) throw new NotFoundException(id);
            return snapshot();
        }

        @Override public synchronized Snapshot clearSession(String id) {
            Credential old = snapshot().require(id);
            credentials.remove(old);
            credentials.add(new Credential(old.id(), old.name(), old.hostPattern(), old.loginUrl(),
                    old.username(), old.password(), old.notes(), old.createdAt(), old.lastUsedAt(), false));
            return snapshot();
        }
    }
}
