package com.javaclaw.desktop;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.InputRequest;
import com.javaclaw.api.InputRequestRecord;
import com.javaclaw.desktop.component.InputRequestPanel;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.state.InputInteractionState;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputRequestPanelTest {
    @Test
    void rendersSafePlatformFormPreservesDraftAndRoutesResolveAndCancel() {
        AtomicReference<CanonicalPayload> resolved = new AtomicReference<>();
        AtomicReference<InputRequestRecord> cancelled = new AtomicReference<>();
        AtomicReference<InputRequestPanel> panel = new AtomicReference<>();
        AtomicReference<Stage> stage = new AtomicReference<>();
        InputRequestRecord request = DesktopTestFixtures.input();

        FxTestSupport.run(() -> {
            InputRequestPanel value = new InputRequestPanel(
                    new CanonicalJson(),
                    new PlatformComponentFactory(),
                    (input, response) -> resolved.set(response),
                    cancelled::set);
            Scene scene = new Scene(value, 380, 560);
            DesktopStylesheets.apply(scene);
            Stage window = new Stage();
            window.setScene(scene);
            window.show();
            value.render(state(request, false));
            panel.set(value);
            stage.set(window);
        });

        FxTestSupport.run(() -> {
            TextField mode = textField(panel.get(), "请输入mode");
            TextField retries = textField(panel.get(), "请输入整数");
            CheckBox confirmed = (CheckBox) panel.get().lookup(".settings-checkbox");
            mode.setText("safe");
            retries.setText("3");
            confirmed.setSelected(true);
            panel.get().render(state(request, false));
            assertEquals("safe", textField(panel.get(), "请输入mode").getText());
            assertTrue(panel.get().lookup(".input-request-card").getStyleClass().contains("jc-card"));

            button(panel.get(), "提交输入").fire();
            assertEquals(
                    "{\"confirmed\":true,\"mode\":\"safe\",\"retries\":3}",
                    resolved.get().json());
            button(panel.get(), "取消所属任务").fire();
            assertEquals(request, cancelled.get());

            panel.get().render(state(request, true));
            assertTrue(button(panel.get(), "正在提交…").isDisabled());
            assertTrue(button(panel.get(), "取消所属任务").isDisabled());
        });
        FxTestSupport.run(stage.get()::hide);
    }

    @Test
    void showsPollingFailureWithoutInventingAnInputForm() {
        AtomicReference<InputRequestPanel> panel = new AtomicReference<>();
        FxTestSupport.run(() -> {
            InputRequestPanel value = new InputRequestPanel(
                    new CanonicalJson(), new PlatformComponentFactory(), (input, response) -> {}, input -> {});
            value.render(new InputInteractionState(List.of(), Optional.empty(), Optional.of("连接已断开"), 2));
            panel.set(value);
        });

        FxTestSupport.run(() -> {
            assertTrue(panel.get().lookupAll(".progress-empty").stream()
                    .map(javafx.scene.control.Label.class::cast)
                    .anyMatch(label -> label.getText().contains("连接已断开")));
            assertFalse(panel.get().lookup(".input-request-scroll").isVisible());
        });
    }

    @Test
    void keepsUnsafeSchemaFailureVisibleAndDisablesSubmission() {
        AtomicReference<InputRequestPanel> panel = new AtomicReference<>();
        InputRequestRecord request = unsafeRequest();
        FxTestSupport.run(() -> {
            InputRequestPanel value = new InputRequestPanel(
                    new CanonicalJson(), new PlatformComponentFactory(), (input, response) -> {}, input -> {});
            value.render(state(request, false));
            panel.set(value);
        });

        FxTestSupport.run(() -> {
            javafx.scene.control.ScrollPane scroll =
                    (javafx.scene.control.ScrollPane) panel.get().lookup(".input-request-scroll");
            assertTrue(((javafx.scene.Parent) scroll.getContent())
                    .lookupAll(".platform-action-error").stream()
                            .filter(javafx.scene.control.Label.class::isInstance)
                            .map(javafx.scene.control.Label.class::cast)
                            .anyMatch(label ->
                                    label.isVisible() && label.getText().contains("无法安全渲染")));
            assertTrue(button((javafx.scene.Parent) scroll.getContent(), "提交输入").isDisabled());
        });
    }

    private static InputInteractionState state(InputRequestRecord request, boolean submitting) {
        return new InputInteractionState(
                List.of(request),
                submitting ? Optional.of(request.request().id()) : Optional.empty(),
                Optional.empty(),
                submitting ? 2 : 1);
    }

    private static InputRequestRecord unsafeRequest() {
        InputRequestRecord source = DesktopTestFixtures.input();
        InputRequest request = new InputRequest(
                source.request().id(),
                source.request().turnId(),
                source.request().producerId(),
                source.request().prompt(),
                new CanonicalJson()
                        .parse(
                                "{\"additionalProperties\":false,\"properties\":{\"password\":{\"type\":\"string\"}},\"type\":\"object\"}"),
                source.request().createdAt(),
                source.request().expiresAt());
        return new InputRequestRecord(
                request,
                source.state(),
                source.revision(),
                source.response(),
                source.resolutionReason(),
                source.updatedAt());
    }

    private static TextField textField(InputRequestPanel panel, String prompt) {
        return panel.lookupAll(".settings-field").stream()
                .filter(TextField.class::isInstance)
                .map(TextField.class::cast)
                .filter(field -> prompt.equals(field.getPromptText()))
                .findFirst()
                .orElseThrow();
    }

    private static Button button(javafx.scene.Parent parent, String text) {
        return parent.lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow();
    }
}
