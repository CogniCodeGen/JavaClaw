package com.javaclaw.desktop.settings;

import java.util.Optional;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteCredentialSettingsDialogTest {
    @Test
    void 共享凭据窗口保持独立并在确认关闭后清除秘密() {
        FxTestSupport.run(() -> {
            SiteCredentialSettingsSection section =
                    new SiteCredentialSettingsSection(new TestCoreSettingsGateway(), () -> {});
            SiteCredentialSettingsDialog dialog = new SiteCredentialSettingsDialog(section);
            VBox owner = new VBox();
            new Scene(owner, 880, 620);
            dialog.show(owner);
            DialogPane pane = sharedDialog().orElseThrow();
            dialog.show(owner);
            assertEquals(pane, sharedDialog().orElseThrow());
            PasswordField input = (PasswordField) pane.lookup(".password-field");
            input.setText("只存在本地窗口的临时值");
            assertTrue(section.dirty());

            completeConfirmation(ButtonType.CANCEL);
            ((Button) pane.lookupButton(ButtonType.CLOSE)).fire();
            assertTrue(sharedDialog().isPresent());
            assertFalse(input.getText().isEmpty());

            completeConfirmation(ButtonType.OK);
            ((Button) pane.lookupButton(ButtonType.CLOSE)).fire();
            assertFalse(sharedDialog().isPresent());
            assertEquals("", input.getText());
            assertFalse(section.dirty());
            dialog.dispose();
        });
    }

    @Test
    void 页面释放强制关闭凭据窗口且再次打开不恢复临时秘密() {
        FxTestSupport.run(() -> {
            SiteCredentialSettingsSection section =
                    new SiteCredentialSettingsSection(new TestCoreSettingsGateway(), () -> {});
            SiteCredentialSettingsDialog dialog = new SiteCredentialSettingsDialog(section);
            VBox owner = new VBox();
            new Scene(owner, 880, 620);
            dialog.show(owner);
            PasswordField input = (PasswordField) sharedDialog().orElseThrow().lookup(".password-field");
            input.setText("关闭时清除");
            dialog.dispose();
            assertFalse(section.dirty());
            assertFalse(section.pending());
            dialog.show(owner);
            assertEquals("", ((PasswordField) sharedDialog().orElseThrow().lookup(".password-field")).getText());
            dialog.dispose();
        });
    }

    private static Optional<DialogPane> sharedDialog() {
        return Window.getWindows().stream()
                .map(Window::getScene)
                .filter(java.util.Objects::nonNull)
                .map(Scene::getRoot)
                .filter(DialogPane.class::isInstance)
                .map(DialogPane.class::cast)
                .filter(pane -> "共享 HTTP 凭据库".equals(pane.getHeaderText()))
                .findFirst();
    }

    private static void completeConfirmation(ButtonType result) {
        Platform.runLater(() -> Window.getWindows().stream()
                .map(Window::getScene)
                .filter(java.util.Objects::nonNull)
                .map(Scene::getRoot)
                .filter(DialogPane.class::isInstance)
                .map(DialogPane.class::cast)
                .filter(pane -> pane.getButtonTypes().contains(result))
                .findFirst()
                .map(pane -> pane.lookupButton(result))
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .ifPresent(Button::fire));
    }
}
