package com.javaclaw.desktop.settings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPresetOnboardingDialogTest {
    @Test
    void 向导在固定Workspace内完成显式模型权限与工具选择() {
        FxTestSupport.run(() -> {
            TestAgentPresetOnboardingGateway gateway =
                    new TestAgentPresetOnboardingGateway(DesktopTestFixtures.workspace());
            AtomicInteger completions = new AtomicInteger();
            AgentPresetOnboardingDialog dialog =
                    new AgentPresetOnboardingDialog(null, gateway.workspace, gateway, completions::incrementAndGet);

            assertEquals(gateway.workspace, dialog.workspace());
            assertFalse(dialog.dirty());
            assertFalse(dialog.pending());
            dialog.open();
            Parent root = dialog.getDialogPane();
            root.applyCss();

            List<ComboBox<?>> providers = providerChoices(root);
            assertEquals(3, providers.size());
            providers.forEach(choice -> choice.getSelectionModel().selectFirst());
            assertTrue(dialog.dirty());

            CheckBox confirmation = checkBox(root, "我已核对上述文件、网络、进程和工具边界");
            Button confirm = button(root, "确认并创建权限方案");
            assertTrue(confirm.isDisabled());
            confirmation.setSelected(true);
            assertFalse(confirm.isDisabled());
            confirm.fire();

            List<ListView<?>> toolLists = toolLists(root);
            assertEquals(2, toolLists.size());
            toolLists.getFirst().getSelectionModel().selectFirst();
            toolLists.getLast().getSelectionModel().selectAll();
            button(root, "完成初始化").fire();

            assertEquals(1, completions.get());
            assertEquals(3, gateway.profiles.size());
            assertTrue(gateway.binding.isPresent());
            assertFalse(dialog.pending());
            assertFalse(dialog.dirty());

            button(root, "重新检查").fire();
            assertEquals(1, completions.get(), "重复读取已完成状态不能重复通知宿主");
            assertFalse(dialog.dirty());
            dialog.focus();
            dialog.close();
        });
    }

    private static List<ComboBox<?>> providerChoices(Parent root) {
        List<ComboBox<?>> result = new ArrayList<>();
        for (Node node : nodes(root, Node.class)) {
            if (node instanceof ComboBox<?> combo
                    && combo.getPromptText() != null
                    && combo.getPromptText().contains("精确 Chat 模型")) {
                result.add(combo);
            }
        }
        return List.copyOf(result);
    }

    private static List<ListView<?>> toolLists(Parent root) {
        List<ListView<?>> result = new ArrayList<>();
        for (Node node : nodes(root, Node.class)) {
            if (node instanceof ListView<?> list
                    && !list.getItems().isEmpty()
                    && list.getItems().stream().allMatch(ToolDescriptor.class::isInstance)) {
                result.add(list);
            }
        }
        return List.copyOf(result);
    }

    private static Button button(Parent root, String text) {
        return nodes(root, Button.class).stream()
                .filter(value -> text.equals(value.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少按钮: " + text));
    }

    private static CheckBox checkBox(Parent root, String text) {
        return nodes(root, CheckBox.class).stream()
                .filter(value -> text.equals(value.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少复选框: " + text));
    }

    private static <T extends Node> List<T> nodes(Parent root, Class<T> type) {
        List<T> result = new ArrayList<>();
        Queue<Node> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Node current = pending.remove();
            if (type.isInstance(current)) {
                result.add(type.cast(current));
            }
            if (current instanceof Parent parent) {
                pending.addAll(parent.getChildrenUnmodifiable());
            }
        }
        return List.copyOf(result);
    }
}
