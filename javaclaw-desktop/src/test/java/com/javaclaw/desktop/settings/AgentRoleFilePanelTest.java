package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleFileFormat;
import com.javaclaw.api.AgentRoleFilePreview;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRoleFilePanelTest {
    @Test
    void 草稿阻止预览和确认入口并在解除保护后恢复操作() {
        FxTestSupport.run(() -> {
            RoleSettingsTestGateway gateway = new RoleSettingsTestGateway();
            AgentRoleFilePanel panel = panel(gateway, new AtomicReference<>());
            panel.blockImport(true);
            panel.previewImport("reviewer", "role", AgentRoleFileFormat.JAVACLAW_LOSSLESS);
            assertEquals(0, gateway.previewCalls);
            panel.blockImport(false);
            panel.previewImport("reviewer", "role", AgentRoleFileFormat.JAVACLAW_LOSSLESS);
            panel.blockImport(true);
            panel.commitPreview();
            assertEquals(0, gateway.importCalls);
            assertTrue(button((Parent) panel.content(), "确认导入").isDisabled());
            panel.blockImport(false);
            assertFalse(button((Parent) panel.content(), "确认导入").isDisabled());
        });
    }

    @Test
    void 提交保留预览前读取的目标revision并返回并发冲突() {
        FxTestSupport.run(() -> {
            RoleSettingsTestGateway gateway = new RoleSettingsTestGateway();
            AtomicReference<AgentRole> imported = new AtomicReference<>();
            AgentRoleFilePanel panel = panel(gateway, imported);
            panel.previewImport("reviewer", "role", AgentRoleFileFormat.JAVACLAW_LOSSLESS);
            gateway.roles.set(0, RoleSettingsTestGateway.role("reviewer", 2));
            panel.commitPreview();
            assertEquals(1, gateway.lastImportOptions.expectedRevision());
            assertEquals("preview-1", gateway.lastImportedPreview);
            assertNull(imported.get());
            assertFalse(panel.pending());
            panel.commitPreview();
            assertEquals(1, gateway.lastImportOptions.expectedRevision(), "重试不能自动换用最新版本");
        });
    }

    @Test
    void 旧目录和预览响应不能成为新选择的可提交预览() {
        FxTestSupport.run(() -> {
            RoleSettingsTestGateway gateway = new RoleSettingsTestGateway();
            AgentRoleFilePanel panel = panel(gateway, new AtomicReference<>());
            CompletableFuture<List<AgentRole>> roles = new CompletableFuture<>();
            gateway.roleReads.add(roles);
            panel.previewImport("reviewer", "old", AgentRoleFileFormat.JAVACLAW_LOSSLESS);
            panel.bind(Optional.of(RoleSettingsTestGateway.role("other", 1)), gateway.providers);
            roles.complete(List.copyOf(gateway.roles));
            assertEquals(0, gateway.previewCalls);

            CompletableFuture<AgentRoleFilePreview> preview = new CompletableFuture<>();
            gateway.previews.add(preview);
            panel.previewImport("other", "old", AgentRoleFileFormat.JAVACLAW_LOSSLESS);
            panel.bind(Optional.of(gateway.roles.getFirst()), gateway.providers);
            preview.complete(
                    RoleSettingsTestGateway.preview("other", "old-preview", AgentRoleFileFormat.JAVACLAW_LOSSLESS));
            assertTrue(button((Parent) panel.content(), "确认导入").isDisabled());
            assertFalse(panel.pending());
        });
    }

    @Test
    void 导入提交期间通知互斥状态且关闭后响应不再触发合并() {
        FxTestSupport.run(() -> {
            RoleSettingsTestGateway gateway = new RoleSettingsTestGateway();
            AtomicReference<AgentRole> imported = new AtomicReference<>();
            AgentRoleFilePanel panel = panel(gateway, imported);
            AtomicReference<Boolean> pending = new AtomicReference<>(false);
            panel.onPendingChanged(() -> pending.set(panel.pending()));
            panel.previewImport("new-role", "role", AgentRoleFileFormat.JAVACLAW_LOSSLESS);
            CompletableFuture<AgentRole> response = new CompletableFuture<>();
            gateway.imports.add(response);
            panel.commitPreview();
            assertTrue(pending.get());
            assertTrue(button((Parent) panel.content(), "选择文件并预览").isDisabled());
            panel.dispose();
            response.complete(RoleSettingsTestGateway.role("new-role", 1));
            assertNull(imported.get());
            assertFalse(panel.pending());
        });
    }

    @Test
    void 导入成功只返回权威角色而不重新读取角色目录() {
        FxTestSupport.run(() -> {
            RoleSettingsTestGateway gateway = new RoleSettingsTestGateway();
            AtomicReference<AgentRole> imported = new AtomicReference<>();
            AgentRoleFilePanel panel = panel(gateway, imported);
            panel.previewImport("new-role", "role", AgentRoleFileFormat.JAVACLAW_LOSSLESS);
            panel.commitPreview();
            assertEquals("new-role", imported.get().id());
            assertEquals(1, gateway.roleReadCalls);
            assertEquals(0, gateway.lastImportOptions.expectedRevision());
        });
    }

    private static AgentRoleFilePanel panel(RoleSettingsTestGateway gateway, AtomicReference<AgentRole> imported) {
        AgentRoleFilePanel panel = new AgentRoleFilePanel(gateway.core, imported::set);
        panel.bind(Optional.of(gateway.roles.getFirst()), gateway.providers);
        return panel;
    }

    private static Button button(Parent parent, String text) {
        for (Node node : parent.getChildrenUnmodifiable()) {
            if (node instanceof Button button && button.getText().equals(text)) {
                return button;
            }
            if (node instanceof Parent child) {
                Button found = findButton(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        throw new AssertionError("未找到按钮：" + text);
    }

    private static Button findButton(Parent parent, String text) {
        for (Node node : parent.getChildrenUnmodifiable()) {
            if (node instanceof Button button && button.getText().equals(text)) {
                return button;
            }
            if (node instanceof Parent child) {
                Button found = findButton(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
