package com.javaclaw.ui.javafx.interaction;

import com.javaclaw.api.interaction.ChoiceOption;
import com.javaclaw.api.interaction.ChoiceRequest;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.SecretRequest;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.application.Platform;
import javafx.scene.control.ComboBox;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class InteractionDialogFxmlLoadTest {

    private static final long TIMEOUT_SECONDS = 5;
    private final List<ViewHandle<VBox>> handles = new ArrayList<>();
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

    @BeforeEach
    void createContext() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.refresh();
    }

    @AfterEach
    void tearDown() throws Exception {
        runFx(() -> handles.forEach(ViewHandle::close));
        context.close();
    }

    @Test
    void injectsAllControllersAndKeepsTheirStateInViewModels() throws Exception {
        ViewHandle<VBox> confirm = load("confirm-dialog.fxml");
        ConfirmDialogController confirmController =
                confirm.controller(ConfirmDialogController.class);
        runFx(() -> confirmController.configure(new ConfirmRequest(
                "delete", "不可逆", "删除文件", ConfirmKind.DOUBLE_CONFIRM,
                30, "DELETE", true)));
        TextField keyword = callFx(() -> (TextField) confirm.root().lookup("#keywordField"));
        assertNotNull(keyword);
        assertFalse(callFx(confirmController.validBinding()::get));
        runFx(() -> keyword.setText("DELETE"));
        assertTrue(callFx(confirmController.validBinding()::get));

        ViewHandle<VBox> choice = load("choice-dialog.fxml");
        ChoiceDialogController choiceController = choice.controller(ChoiceDialogController.class);
        runFx(() -> choiceController.configure(new ChoiceRequest(
                "选择", "请选择", List.of(new ChoiceOption("first", "主账号", "")), 30)));
        ComboBox<?> optionBox = callFx(() -> (ComboBox<?>) choice.root().lookup("#optionBox"));
        assertNotNull(optionBox);
        assertEquals("first", callFx(choiceController::selectedId));

        ViewHandle<VBox> secret = load("secret-dialog.fxml");
        SecretDialogController secretController = secret.controller(SecretDialogController.class);
        runFx(() -> secretController.configure(new SecretRequest("密码", "请输入", 30, 3)));
        PasswordField input = callFx(() -> (PasswordField) secret.root().lookup("#secretField"));
        runFx(() -> input.setText("abcdef"));
        assertEquals("abc", callFx(input::getText));
        assertArrayEquals(new char[]{'a', 'b', 'c'}, callFx(secretController::takeSecret));
        assertEquals("", callFx(input::getText));
    }

    private ViewHandle<VBox> load(String name) throws Exception {
        URL resource = Objects.requireNonNull(getClass().getResource(
                "/fxml/interaction/" + name));
        ViewHandle<VBox> handle = callFx(() ->
                context.getBean(SpringFxmlLoader.class).load(resource));
        handles.add(handle);
        return handle;
    }

    private static void runFx(Runnable action) throws Exception {
        callFx(() -> { action.run(); return null; });
    }

    private static <T> T callFx(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) return action.call();
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
