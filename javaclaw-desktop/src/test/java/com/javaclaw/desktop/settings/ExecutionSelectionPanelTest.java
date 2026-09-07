package com.javaclaw.desktop.settings;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextField;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionSelectionPanelTest {
    @Test
    void 保存工作区名称不会刷新或替换执行配置草稿() {
        FxTestSupport.run(() -> {
            ExecutionSelectionTestGateway gateway = new ExecutionSelectionTestGateway();
            Workspace workspace = gateway.workspace;
            put(gateway, workspace, reasoning(ReasoningPreference.LOW), 1);
            WorkspaceSettingsPage page = new WorkspaceSettingsPage(gateway);
            new Scene(page);
            page.workspaceChanged(Optional.of(workspace));
            page.applyCss();
            page.layout();
            @SuppressWarnings("unchecked")
            ComboBox<ReasoningPreference> field = (ComboBox<ReasoningPreference>) page.lookup("#executionReasoning");
            field.setValue(ReasoningPreference.HIGH);
            TextField name = page.lookupAll(".text-field").stream()
                    .filter(TextField.class::isInstance)
                    .map(TextField.class::cast)
                    .filter(value -> "工作区名称".equals(value.getAccessibleText()))
                    .findFirst()
                    .orElseThrow();
            name.setText("重命名后");
            put(gateway, workspace, reasoning(ReasoningPreference.MEDIUM), 2);
            button((Parent) page.actionContent().orElseThrow(), "保存名称").fire();
            assertEquals("重命名后", gateway.workspace.name());
            assertEquals(1, gateway.catalogReads);
            assertEquals(ReasoningPreference.HIGH, field.getValue());
            assertTrue(page.dirty());
        });
    }

    @Test
    void 默认配置刷新与单字段编辑不会丢失隐藏的权限审批预算和能力() {
        FxTestSupport.run(() -> {
            ExecutionSelectionTestGateway gateway = new ExecutionSelectionTestGateway();
            Workspace workspace = gateway.workspace;
            ExecutionOverrides complete = new ExecutionOverrides(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new PermissionProfileRef("narrow", 4)),
                    Optional.of(ApprovalPolicy.EVERY_CALL),
                    Optional.of(new TurnBudget(500, 200, 3, 1, Duration.ofSeconds(40))),
                    Optional.of(Set.of("read_file")),
                    Optional.of(ReasoningPreference.LOW));
            put(gateway, workspace, complete, 5);
            ExecutionSelectionPanel panel = panel(gateway, workspace, true);
            panel.refresh();
            chooseReasoning(panel, ReasoningPreference.HIGH);
            panel.save();
            assertEquals(5, gateway.writes.getFirst().expectedRevision());
            assertEquals(complete.permissionProfile(), gateway.submitted.permissionProfile());
            assertEquals(complete.approvalPolicy(), gateway.submitted.approvalPolicy());
            assertEquals(complete.budget(), gateway.submitted.budget());
            assertEquals(complete.visibleCapabilities(), gateway.submitted.visibleCapabilities());
        });
    }

    @Test
    void 同作用域绑定保持幂等而主动刷新采用新配置及其版本() {
        FxTestSupport.run(() -> {
            ExecutionSelectionTestGateway gateway = new ExecutionSelectionTestGateway();
            Workspace workspace = DesktopTestFixtures.workspace();
            put(gateway, workspace, reasoning(ReasoningPreference.LOW), 1);
            ExecutionSelectionPanel panel = panel(gateway, workspace, true);
            panel.bind(Optional.of(workspace), Optional.empty(), true);
            assertEquals(1, gateway.catalogReads);

            put(gateway, workspace, reasoning(ReasoningPreference.HIGH), 4);
            panel.refresh();
            assertEquals(2, gateway.catalogReads);
            assertEquals(ReasoningPreference.HIGH, panel.execution().reasoning().orElseThrow());
            chooseReasoning(panel, ReasoningPreference.MEDIUM);
            panel.save();
            assertEquals(4, gateway.writes.getFirst().expectedRevision());
            assertFalse(panel.dirty());
        });
    }

    @Test
    void 保存冲突保留草稿且重新读取需要明确放弃() {
        FxTestSupport.run(() -> {
            ExecutionSelectionTestGateway gateway = new ExecutionSelectionTestGateway();
            Workspace workspace = DesktopTestFixtures.workspace();
            put(gateway, workspace, reasoning(ReasoningPreference.LOW), 1);
            ExecutionSelectionPanel panel = panel(gateway, workspace, true);
            chooseReasoning(panel, ReasoningPreference.HIGH);
            put(gateway, workspace, reasoning(ReasoningPreference.MEDIUM), 2);
            panel.save();
            assertTrue(panel.dirty());
            assertFalse(panel.canSave());
            panel.refresh();
            assertEquals(1, gateway.catalogReads);
            assertEquals(ReasoningPreference.HIGH, panel.execution().reasoning().orElseThrow());

            answerNextDialog(ButtonType.CANCEL);
            button(panel, "重新读取").fire();
            assertEquals(1, gateway.catalogReads);
            assertTrue(panel.dirty());
            answerNextDialog(ButtonType.CLOSE);
            button(panel, "比较差异").fire();
            assertEquals(ReasoningPreference.HIGH, panel.execution().reasoning().orElseThrow());
            assertFalse(panel.canSave());

            answerNextDialog(ButtonType.OK);
            button(panel, "重新读取").fire();
            assertEquals(
                    ReasoningPreference.MEDIUM, panel.execution().reasoning().orElseThrow());
            chooseReasoning(panel, ReasoningPreference.HIGH);
            panel.save();
            assertEquals(2, gateway.writes.getLast().expectedRevision());
            assertFalse(panel.dirty());
        });
    }

    @Test
    void 初次读取失败不可保存且刷新失败保留最近基线() {
        FxTestSupport.run(() -> {
            ExecutionSelectionTestGateway gateway = new ExecutionSelectionTestGateway();
            Workspace workspace = DesktopTestFixtures.workspace();
            put(gateway, workspace, reasoning(ReasoningPreference.LOW), 3);
            gateway.readFailure = new IllegalStateException("连接失败");
            ExecutionSelectionPanel panel = panel(gateway, workspace, true);
            assertFalse(panel.ready());
            panel.save();
            assertTrue(gateway.writes.isEmpty());
            panel.refresh();
            assertTrue(panel.ready());

            gateway.readFailure = new IllegalStateException("暂时断线");
            panel.refresh();
            assertEquals(reasoning(ReasoningPreference.LOW), panel.execution());
            chooseReasoning(panel, ReasoningPreference.HIGH);
            panel.save();
            assertEquals(3, gateway.writes.getFirst().expectedRevision());
        });
    }

    @Test
    void 精确继承角色完成前保持加载且旧作用域响应不能覆盖新选择() {
        FxTestSupport.run(() -> {
            ExecutionSelectionTestGateway gateway = new ExecutionSelectionTestGateway();
            Workspace first = DesktopTestFixtures.workspace();
            ExecutionOverrides role = new ExecutionOverrides(
                    Optional.of(new AgentRoleRef("default", 1)),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
            gateway.defaults.put(
                    Optional.empty(), ExecutionSelectionTestGateway.configuration(Optional.empty(), role, 1));
            CompletableFuture<com.javaclaw.api.AgentRole> delayedRole = new CompletableFuture<>();
            gateway.roleResponse = delayedRole;
            ExecutionSelectionPanel panel = panel(gateway, first, true);
            assertTrue(panel.pending());
            assertFalse(panel.ready());

            Workspace second = new Workspace(
                    WorkspaceId.random(),
                    "第二作用域",
                    first.root(),
                    first.lifecycle(),
                    first.revision(),
                    first.createdAt(),
                    first.updatedAt());
            put(gateway, second, reasoning(ReasoningPreference.MEDIUM), 2);
            panel.bind(Optional.of(second), Optional.empty(), true);
            assertFalse(panel.pending());
            delayedRole.complete(gateway.profiles.getFirst());
            assertEquals(
                    ReasoningPreference.MEDIUM, panel.execution().reasoning().orElseThrow());
        });
    }

    @Test
    void 输入区刷新继承和目录仍保留全部显式覆盖() {
        FxTestSupport.run(() -> {
            ExecutionSelectionTestGateway gateway = new ExecutionSelectionTestGateway();
            Workspace workspace = DesktopTestFixtures.workspace();
            ExecutionSelectionPanel panel = panel(gateway, workspace, false);
            ExecutionOverrides selected = new ExecutionOverrides(
                    Optional.of(new AgentRoleRef("missing", 7)),
                    Optional.of(new ProviderRef("provider-main", 1, "fake-model")),
                    Optional.of(new PermissionProfileRef("missing-permission", 3)),
                    Optional.of(ApprovalPolicy.EVERY_CALL),
                    Optional.of(new TurnBudget(1000, 500, 5, 1, Duration.ofSeconds(30))),
                    Optional.of(Set.of("read_file")),
                    Optional.of(ReasoningPreference.HIGH));
            control(panel).setValue(selected);
            panel.refresh();
            assertEquals(selected, panel.execution());
            assertFalse(panel.dirty());
            assertFalse(panel.canSave());
        });
    }

    @Test
    void 保存期间丢弃无效且旧保存响应不能覆盖另一个作用域() {
        FxTestSupport.run(() -> {
            ExecutionSelectionTestGateway gateway = new ExecutionSelectionTestGateway();
            Workspace first = DesktopTestFixtures.workspace();
            ExecutionSelectionPanel panel = panel(gateway, first, true);
            chooseReasoning(panel, ReasoningPreference.HIGH);
            CompletableFuture<ExecutionConfiguration> saved = new CompletableFuture<>();
            gateway.writeResponse = saved;
            panel.save();
            panel.discard();
            assertEquals(ReasoningPreference.HIGH, panel.execution().reasoning().orElseThrow());

            panel.bind(Optional.empty(), Optional.empty(), false);
            saved.complete(ExecutionSelectionTestGateway.configuration(
                    Optional.of(first.id()), reasoning(ReasoningPreference.HIGH), 1));
            assertEquals(ExecutionOverrides.empty(), panel.execution());
            assertFalse(panel.pending());
        });
    }

    @Test
    void 创建向导完整加载前不可用且重新打开设置刷新当前作用域() {
        FxTestSupport.run(() -> {
            ExecutionSelectionTestGateway gateway = new ExecutionSelectionTestGateway();
            ExecutionSelectionPanel creation = new ExecutionSelectionPanel(gateway);
            creation.prepareWorkspaceCreation();
            assertTrue(creation.ready());
            assertEquals("default", creation.execution().role().orElseThrow().id());
            Workspace workspace = DesktopTestFixtures.workspace();
            put(gateway, workspace, reasoning(ReasoningPreference.LOW), 1);
            WorkspaceSettingsPage page = new WorkspaceSettingsPage(gateway);
            new Scene((Parent) page.content());
            page.workspaceChanged(Optional.of(workspace));
            put(gateway, workspace, reasoning(ReasoningPreference.HIGH), 2);
            page.activate();
            page.applyCss();
            page.layout();
            @SuppressWarnings("unchecked")
            ComboBox<ReasoningPreference> field = (ComboBox<ReasoningPreference>) page.lookup("#executionReasoning");
            assertEquals(ReasoningPreference.HIGH, field.getValue());
        });
    }

    private static ExecutionSelectionPanel panel(
            ExecutionSelectionTestGateway gateway, Workspace workspace, boolean defaults) {
        ExecutionSelectionPanel panel = new ExecutionSelectionPanel(gateway);
        new Scene(panel);
        panel.bind(Optional.of(workspace), Optional.empty(), defaults);
        return panel;
    }

    private static void put(
            ExecutionSelectionTestGateway gateway, Workspace workspace, ExecutionOverrides value, long revision) {
        gateway.defaults.put(
                Optional.of(workspace.id()),
                ExecutionSelectionTestGateway.configuration(Optional.of(workspace.id()), value, revision));
    }

    private static ExecutionOverrides reasoning(ReasoningPreference value) {
        return new ExecutionOverrides(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(value));
    }

    private static ExecutionSelectionControl control(ExecutionSelectionPanel panel) {
        return (ExecutionSelectionControl) panel.getChildren().getFirst();
    }

    private static void chooseReasoning(ExecutionSelectionPanel panel, ReasoningPreference value) {
        @SuppressWarnings("unchecked")
        ComboBox<ReasoningPreference> field = (ComboBox<ReasoningPreference>) panel.lookup("#executionReasoning");
        field.setValue(value);
    }

    private static Button button(Parent parent, String text) {
        return parent.lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(value -> text.equals(value.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static void answerNextDialog(ButtonType answer) {
        Platform.runLater(() -> List.copyOf(Window.getWindows()).stream()
                .filter(Window::isShowing)
                .map(Window::getScene)
                .map(Scene::getRoot)
                .filter(DialogPane.class::isInstance)
                .map(DialogPane.class::cast)
                .forEach(dialog -> ((Button) dialog.lookupButton(answer)).fire()));
    }
}
