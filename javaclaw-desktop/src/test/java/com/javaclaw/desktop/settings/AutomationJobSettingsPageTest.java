package com.javaclaw.desktop.settings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationJobSettingsPageTest {
    @Test
    void 权威Job详情展示Checkpoint并按状态启用恢复动作() {
        TestAutomationJobSettingsGateway gateway = new TestAutomationJobSettingsGateway();

        FxTestSupport.run(() -> {
            AutomationJobSettingsPage page = new AutomationJobSettingsPage(gateway);
            page.workspaceChanged(Optional.of(DesktopTestFixtures.workspace()));
            VBox root = new VBox(page);
            page.actionContent().ifPresent(root.getChildren()::add);
            new Scene(root, 1_000, 720);
            page.activate();
            root.applyCss();
            root.layout();
            ListView<ExtensionExecutionReceipt> list = list(page);
            assertEquals(2, list.getItems().size());
            assertTrue(labels(page).stream().anyMatch(text -> text.contains("已提交至 #1 prepare")));
            assertTrue(labels(page).stream().anyMatch(text -> text.contains("副作用凭据 effect-1")));

            list.getSelectionModel().select(gateway.firstPage.get(1));
            Button resume = button(page, "automationJobResumeButton");
            assertFalse(resume.isDisabled());
            resume.fire();

            assertEquals("resume", gateway.lastAction);
            assertTrue(labels(page).contains("等待运行"));
            assertFalse(button(page, "automationJobPauseButton").isDisabled());
            page.deactivate();
        });
    }

    @SuppressWarnings("unchecked")
    private static ListView<ExtensionExecutionReceipt> list(Parent root) {
        return (ListView<ExtensionExecutionReceipt>) root.lookup("#automationJobList");
    }

    private static Button button(Parent root, String id) {
        return (Button) root.getScene().getRoot().lookup("#" + id);
    }

    private static List<String> labels(Parent root) {
        return descendants(root).stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .map(Label::getText)
                .toList();
    }

    private static List<Node> descendants(Parent root) {
        List<Node> result = new ArrayList<>();
        Queue<Node> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Node node = pending.remove();
            result.add(node);
            if (node instanceof Parent parent) {
                pending.addAll(parent.getChildrenUnmodifiable());
            }
        }
        return result;
    }
}
