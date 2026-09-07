package com.javaclaw.desktop.settings;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionSelectionPageRefreshTest {
    @Test
    void 连接恢复刷新干净页面并使后续保存基于新执行版本() {
        FxTestSupport.run(() -> {
            ExecutionSelectionTestGateway gateway = new ExecutionSelectionTestGateway();
            put(gateway, complete(ReasoningPreference.LOW), 1);
            WorkspaceSettingsPage page = new WorkspaceSettingsPage(gateway);
            Parent root = attach(page, gateway);
            put(gateway, complete(ReasoningPreference.MEDIUM), 7);

            page.refreshExecutionConfiguration();

            assertEquals(2, gateway.catalogReads);
            assertEquals(complete(ReasoningPreference.MEDIUM), selection(root).value());
            assertFalse(page.dirty());
            reasoning(root).setValue(ReasoningPreference.HIGH);
            button(root, "保存执行默认配置").fire();
            assertEquals(7, gateway.writes.getFirst().expectedRevision());
            assertEquals(complete(ReasoningPreference.HIGH), gateway.submitted);
            assertFalse(page.dirty());
        });
    }

    @Test
    void 连接恢复保留全部执行草稿和名称草稿且不偷偷推进保存版本() {
        FxTestSupport.run(() -> {
            ExecutionSelectionTestGateway gateway = new ExecutionSelectionTestGateway();
            put(gateway, complete(ReasoningPreference.LOW), 3);
            WorkspaceSettingsPage page = new WorkspaceSettingsPage(gateway);
            Parent root = attach(page, gateway);
            reasoning(root).setValue(ReasoningPreference.HIGH);
            name(root).setText("尚未保存的工作区名称");
            put(gateway, ExecutionOverrides.empty(), 4);

            page.refreshExecutionConfiguration();

            assertEquals(1, gateway.catalogReads, "恢复连接不能覆盖脏执行配置而自动刷新其基线");
            assertEquals(complete(ReasoningPreference.HIGH), selection(root).value());
            assertEquals("尚未保存的工作区名称", name(root).getText());
            assertTrue(page.dirty());
            button(root, "保存执行默认配置").fire();
            assertEquals(3, gateway.writes.getFirst().expectedRevision());
            assertEquals(complete(ReasoningPreference.HIGH), gateway.submitted);
            assertEquals("尚未保存的工作区名称", name(root).getText());
            assertTrue(page.dirty(), "旧版本写入冲突后两份草稿仍须保留");
        });
    }

    @Test
    void 只有名称草稿时连接恢复仍刷新独立执行配置并保留名称() {
        FxTestSupport.run(() -> {
            ExecutionSelectionTestGateway gateway = new ExecutionSelectionTestGateway();
            put(gateway, complete(ReasoningPreference.LOW), 2);
            WorkspaceSettingsPage page = new WorkspaceSettingsPage(gateway);
            Parent root = attach(page, gateway);
            name(root).setText("本地名称草稿");
            put(gateway, complete(ReasoningPreference.MEDIUM), 8);

            page.refreshExecutionConfiguration();

            assertEquals(2, gateway.catalogReads);
            assertEquals(complete(ReasoningPreference.MEDIUM), selection(root).value());
            assertEquals("本地名称草稿", name(root).getText());
            assertTrue(page.dirty());
            reasoning(root).setValue(ReasoningPreference.HIGH);
            button(root, "保存执行默认配置").fire();
            assertEquals(8, gateway.writes.getFirst().expectedRevision());
            assertEquals("本地名称草稿", name(root).getText());
            assertTrue(page.dirty(), "执行配置的刷新及保存都不能保存或丢弃名称草稿");
        });
    }

    private static Parent attach(WorkspaceSettingsPage page, ExecutionSelectionTestGateway gateway) {
        BorderPane root = new BorderPane(page.content());
        root.setBottom(page.actionContent().orElseThrow());
        new Scene(root, 1040, 720);
        page.workspaceChanged(Optional.of(gateway.workspace));
        root.applyCss();
        root.layout();
        return root;
    }

    private static void put(ExecutionSelectionTestGateway gateway, ExecutionOverrides value, long revision) {
        gateway.defaults.put(
                Optional.of(gateway.workspace.id()),
                ExecutionSelectionTestGateway.configuration(Optional.of(gateway.workspace.id()), value, revision));
    }

    private static ExecutionOverrides complete(ReasoningPreference reasoning) {
        return new ExecutionOverrides(
                Optional.of(new AgentRoleRef("default", 1)),
                Optional.of(new ProviderRef("provider-main", 1, "fake-model")),
                Optional.of(new PermissionProfileRef("workspace-permission", 4)),
                Optional.of(ApprovalPolicy.EVERY_CALL),
                Optional.of(new TurnBudget(1000, 500, 5, 1, Duration.ofSeconds(30))),
                Optional.of(Set.of("read_file")),
                Optional.of(reasoning));
    }

    private static ExecutionSelectionControl selection(Parent root) {
        return nodes(root, ExecutionSelectionControl.class).getFirst();
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<ReasoningPreference> reasoning(Parent root) {
        return (ComboBox<ReasoningPreference>) root.lookup("#executionReasoning");
    }

    private static TextField name(Parent root) {
        return nodes(root, TextField.class).stream()
                .filter(field -> "工作区名称".equals(field.getAccessibleText()))
                .findFirst()
                .orElseThrow();
    }

    private static Button button(Parent root, String text) {
        return nodes(root, Button.class).stream()
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static <T extends Node> List<T> nodes(Parent parent, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Node node : parent.getChildrenUnmodifiable()) {
            if (type.isInstance(node)) {
                result.add(type.cast(node));
            }
            if (node instanceof Parent child) {
                result.addAll(nodes(child, type));
            }
        }
        return result;
    }
}
