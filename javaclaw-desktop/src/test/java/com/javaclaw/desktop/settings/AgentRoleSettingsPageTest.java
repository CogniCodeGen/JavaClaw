package com.javaclaw.desktop.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.PromptOptimizationDraft;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRoleSettingsPageTest {
    @Test
    void 编辑其他字段保留目录外的历史模型且显式切换和继承才修改引用() {
        FxTestSupport.run(() -> {
            RoleSettingsTestGateway gateway = new RoleSettingsTestGateway();
            AgentRoleSettingsPage page = page(gateway);
            Parent root = attach(page);
            ComboBox<ProviderEndpoint> provider = provider(root);
            assertTrue(provider.getPromptText().contains("provider-main@1"));
            name(root).setText("修改名称");
            button(root, "保存 Agent").fire();
            assertEquals(
                    1, gateway.lastWrittenSpec.model().orElseThrow().provider().endpointRevision());

            provider.setValue(gateway.providers.getFirst());
            button(root, "保存 Agent").fire();
            assertEquals(
                    2, gateway.lastWrittenSpec.model().orElseThrow().provider().endpointRevision());
            button(root, "继承模型与推理").fire();
            button(root, "保存 Agent").fire();
            assertTrue(gateway.lastWrittenSpec.model().isEmpty());
            assertTrue(gateway.lastWrittenSpec.reasoning().isEmpty());
            assertFalse(page.dirty());
        });
    }

    @Test
    void 服务端拒绝停用模型时仍保留精确引用和本地草稿() {
        FxTestSupport.run(() -> {
            RoleSettingsTestGateway gateway = new RoleSettingsTestGateway();
            gateway.providers.set(
                    0,
                    TestCoreSettingsFixtures.provider(
                            2, TestCoreSettingsFixtures.providerSpec(Optional.empty()), ProviderLifecycle.DISABLED));
            AgentRoleSettingsPage page = page(gateway);
            Parent root = attach(page);
            name(root).setText("仍要保留的名称");
            gateway.writes.add(CompletableFuture.failedFuture(new IllegalArgumentException("Provider 已停用")));
            button(root, "保存 Agent").fire();
            assertEquals(
                    1, gateway.lastWrittenSpec.model().orElseThrow().provider().endpointRevision());
            assertEquals("仍要保留的名称", name(root).getText());
            assertTrue(page.dirty());
            button(root, "刷新").fire();
            assertEquals(1, gateway.roleReadCalls);
            assertEquals("仍要保留的名称", name(root).getText());
            assertTrue(button(root, "选择文件并预览").isDisabled());
        });
    }

    @Test
    void 冲突比较和取消重读保留草稿且明确丢弃后才能采用新版本() {
        FxTestSupport.run(() -> {
            RoleSettingsTestGateway gateway = new RoleSettingsTestGateway();
            AgentRoleSettingsPage page = page(gateway);
            Parent root = attach(page);
            name(root).setText("本地名称");
            gateway.roles.set(0, RoleSettingsTestGateway.role("reviewer", 2));
            button(root, "保存 Agent").fire();
            AtomicReference<String> compared = new AtomicReference<>();
            respond(dialog -> {
                TextArea text = nodes(dialog, TextArea.class).getFirst();
                compared.set(text.getText());
                ((Button) dialog.lookupButton(ButtonType.CLOSE)).fire();
            });
            button(root, "比较差异").fire();
            assertTrue(compared.get().contains("本地名称"));
            assertTrue(compared.get().contains("版本 2"));
            int reads = gateway.roleReadCalls;
            respond(dialog -> ((Button) dialog.lookupButton(ButtonType.CANCEL)).fire());
            button(root, "重新读取").fire();
            assertEquals(reads, gateway.roleReadCalls);
            assertEquals("本地名称", name(root).getText());
            assertTrue(page.dirty());

            respond(dialog -> ((Button) dialog.lookupButton(ButtonType.OK)).fire());
            button(root, "重新读取").fire();
            assertFalse(page.dirty());
            name(root).setText("基于新版本编辑");
            button(root, "保存 Agent").fire();
            assertEquals(2, gateway.lastWriteOptions.expectedRevision());
        });
    }

    @Test
    void 优化面板异步状态即时更新角色编辑和导入按钮() {
        FxTestSupport.run(() -> {
            RoleSettingsTestGateway gateway = new RoleSettingsTestGateway();
            AgentRoleSettingsPage page = page(gateway);
            Parent root = attach(page);
            CompletableFuture<List<PromptOptimizationDraft>> response = new CompletableFuture<>();
            gateway.optimizationReads.add(response);
            page.workspaceChanged(Optional.of(DesktopTestFixtures.workspace()));
            assertTrue(page.pending());
            assertTrue(name(root).isDisabled());
            assertTrue(button(root, "选择文件并预览").isDisabled());
            assertTrue(button(root, "新建").isDisabled());
            response.complete(List.of());
            assertFalse(page.pending());
            assertFalse(name(root).isDisabled());
            assertFalse(button(root, "选择文件并预览").isDisabled());
        });
    }

    private static AgentRoleSettingsPage page(RoleSettingsTestGateway gateway) {
        return new AgentRoleSettingsPage(gateway.core, gateway.prompts, gateway.optimization);
    }

    private static Parent attach(AgentRoleSettingsPage page) {
        BorderPane root = new BorderPane(page.content());
        root.setBottom(page.actionContent().orElseThrow());
        new Scene(root, 1040, 720);
        page.activate();
        root.applyCss();
        root.layout();
        return root;
    }

    private static TextField name(Parent root) {
        return nodes(root, TextField.class).stream()
                .filter(field -> "Agent 名称".equals(field.getPromptText()))
                .findFirst()
                .orElseThrow();
    }

    private static Button button(Parent root, String label) {
        return nodes(root, Button.class).stream()
                .filter(value -> label.equals(value.getText()))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<ProviderEndpoint> provider(Parent root) {
        return (ComboBox<ProviderEndpoint>) root.lookup("#roleProvider");
    }

    private static void respond(Consumer<DialogPane> response) {
        Platform.runLater(() -> Window.getWindows().stream()
                .map(Window::getScene)
                .filter(scene -> scene != null)
                .map(Scene::getRoot)
                .filter(DialogPane.class::isInstance)
                .map(DialogPane.class::cast)
                .findFirst()
                .ifPresent(response));
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
