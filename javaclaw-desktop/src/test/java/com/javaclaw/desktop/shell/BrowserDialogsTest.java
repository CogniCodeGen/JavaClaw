package com.javaclaw.desktop.shell;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BrowserGrantContracts;
import com.javaclaw.desktop.DesktopBrowserGateway;
import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserDialogsTest {
    private static final List<String> APPEARANCE = List.of("theme-sapphire", "font-scale-110", "density-spacious");

    @Test
    void 来源授权弹窗继承显示时外观且关闭仍废弃晚到回执() {
        FxTestSupport.run(() -> {
            Stage owner = owner();
            var gateway = new BrowserGrantGatewayFixture();
            var scope = new DesktopBrowserGateway.Scope(WorkspaceId.random(), ThreadId.random());
            AtomicInteger changes = new AtomicInteger();
            AtomicInteger closed = new AtomicInteger();
            try (var grant = new BrowserGrantDialog(
                    owner, gateway, scope, () -> true, changes::incrementAndGet, closed::incrementAndGet)) {
                appearance(owner);
                grant.show();
                DialogPane pane = Window.getWindows().stream()
                        .filter(window -> window instanceof Stage stage && "当前对话 · 来源授权".equals(stage.getTitle()))
                        .map(window -> (DialogPane) window.getScene().getRoot())
                        .findFirst()
                        .orElseThrow();
                assertAppearance(pane);
                grant.close();
                gateway.lists.getFirst().complete(new BrowserGrantContracts.GrantList(List.of()));
                assertEquals(1, closed.get());
                assertEquals(0, changes.get());
            } finally {
                owner.close();
            }
        });
    }

    @Test
    void 网页登录弹窗继承外观且仍要求用户明确选中当前表单() {
        FxTestSupport.run(() -> {
            Stage owner = owner();
            var target = new BrowserContracts.CredentialsTarget("page", "user", "password");
            var form = new BrowserContracts.LoginForm("工作账号登录", "用户名", "密码", target);
            var dialog = BrowserLoginCaptureDialog.create(owner, List.of(form));
            try {
                appearance(owner);
                dialog.show();
                assertAppearance(dialog.getDialogPane());
                assertNull(dialog.getGraphic());
                var save = dialog.getDialogPane().getButtonTypes().stream()
                        .filter(button -> button.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                        .findFirst()
                        .orElseThrow();
                Button action = (Button) dialog.getDialogPane().lookupButton(save);
                assertTrue(action.isDisabled());
                ComboBox<?> choices = (ComboBox<?>) dialog.getDialogPane().lookup(".combo-box");
                choices.getSelectionModel().selectFirst();
                assertFalse(action.isDisabled());
                action.fire();
                assertEquals(target, dialog.getResult());
                assertFalse(dialog.isShowing());
            } finally {
                dialog.close();
                owner.close();
            }
        });
    }

    private static Stage owner() {
        Stage owner = new Stage();
        owner.setScene(new Scene(new VBox(), 700, 500));
        DesktopStylesheets.apply(owner.getScene());
        owner.show();
        return owner;
    }

    private static void appearance(Stage owner) {
        var styles = owner.getScene().getRoot().getStyleClass();
        styles.removeIf(
                value -> value.startsWith("theme-") || value.startsWith("font-scale-") || value.startsWith("density-"));
        styles.addAll(APPEARANCE);
    }

    private static void assertAppearance(DialogPane pane) {
        assertTrue(pane.getStyleClass().containsAll(APPEARANCE));
        assertTrue(pane.getStyleClass().containsAll(List.of("root", "jc-dialog-pane")));
        assertTrue(pane.getStylesheets().stream().anyMatch(value -> value.endsWith("/css/interaction-overlays.css")));
        assertEquals(
                1,
                pane.getStyleClass().stream()
                        .filter(value -> value.startsWith("theme-"))
                        .count());
        assertEquals(
                1,
                pane.getStyleClass().stream()
                        .filter(value -> value.startsWith("font-scale-"))
                        .count());
        assertEquals(
                1,
                pane.getStyleClass().stream()
                        .filter(value -> value.startsWith("density-"))
                        .count());
    }
}
