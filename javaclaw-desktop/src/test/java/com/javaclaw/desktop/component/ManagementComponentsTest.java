package com.javaclaw.desktop.component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.component.AsyncActionBar.ActionState;
import com.javaclaw.desktop.component.FormSection.FieldHandle;
import com.javaclaw.desktop.component.ListDetailPane.Projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementComponentsTest {
    @Test
    void 管理组件共享状态和布局语义() {
        FxTestSupport.run(() -> {
            FormSection section = new FormSection("配置", "说明");
            TextField name = new TextField();
            section.addField("名称", name);
            assertLabelTargetsEditor(section, name);

            FieldHandle required = section.addRequiredField("标识", new TextField(), "保存后不可修改。");
            assertFalse(required.hasError());
            required.updateError("标识不能为空");
            assertTrue(required.hasError());
            assertEquals("标识不能为空", required.errorMessage());
            required.clearError();
            assertFalse(required.hasError());

            FieldHandle optional = section.addOptionalField("备注", new TextField(), null);
            optional.updateError("   ");
            assertFalse(optional.hasError());
            section.addFullWidth(new Label("完整行"));
            assertTrue(section.getStyleClass().contains("platform-form-section"));

            assertListDetailProjections();

            AsyncActionBar actionBar = new AsyncActionBar(new Button("保存"));
            actionBar.show(ActionState.PENDING, null);
            assertTrue(actionBar.getChildren().getFirst().isVisible());
            actionBar.show(ActionState.SUCCESS, "已保存");
            assertFalse(actionBar.getChildren().getFirst().isVisible());
            actionBar.show(ActionState.DIRTY, "未保存");
            actionBar.show(ActionState.ERROR, "失败");
            actionBar.show(ActionState.IDLE, "");

            assertConflictSecretDangerAndTimeline();
            assertInvalidInputsFailFast();
        });
    }

    private static void assertLabelTargetsEditor(FormSection section, TextField editor) {
        GridPane fields = (GridPane) section.getChildren().getLast();
        HBox labelRow = (HBox) fields.getChildren().getFirst();
        Label label = (Label) labelRow.getChildren().getFirst();
        assertEquals(editor, label.getLabelFor());
    }

    private static void assertListDetailProjections() {
        AtomicInteger retries = new AtomicInteger();
        ListDetailPane<String> listDetail = new ListDetailPane<>();
        listDetail.list().getItems().add("一");
        assertEquals(Projection.EMPTY, listDetail.projection());

        listDetail.showLoading("正在读取模型服务。");
        assertEquals(Projection.LOADING, listDetail.projection());
        assertTrue(listDetail.list().isDisabled());

        listDetail.showError("读取失败", "请检查连接后重试。", retries::incrementAndGet);
        assertEquals(Projection.ERROR, listDetail.projection());
        assertFalse(listDetail.list().isDisabled());
        StackPane detail = (StackPane) listDetail.getItems().getLast();
        VBox feedback = (VBox) detail.getChildren().getFirst();
        ((Button) feedback.getChildren().getLast()).fire();
        assertEquals(1, retries.get());

        listDetail.showEmpty("暂无数据", "可以创建第一条记录。");
        assertEquals(Projection.EMPTY, listDetail.projection());

        listDetail.showReady(new Label("详情"));
        assertEquals(Projection.READY, listDetail.projection());
        listDetail.showDetail(new Label("更新详情"));
        assertEquals(Projection.READY, listDetail.projection());
        assertEquals(2, listDetail.getItems().size());
    }

    private static void assertConflictSecretDangerAndTimeline() {
        AtomicInteger invocations = new AtomicInteger();
        RevisionConflictPane conflict =
                new RevisionConflictPane(invocations::incrementAndGet, invocations::incrementAndGet);
        conflict.show(3, 4);
        assertTrue(conflict.isManaged());
        HBox conflictActions = (HBox) conflict.getChildren().getLast();
        ((Button) conflictActions.getChildren().getFirst()).fire();
        ((Button) conflictActions.getChildren().getLast()).fire();
        conflict.hide();
        assertFalse(conflict.isManaged());
        conflict.showUnknownActual(5);
        assertTrue(conflict.isManaged());

        SecretStatusField secret = new SecretStatusField(invocations::incrementAndGet, invocations::incrementAndGet);
        assertTrue(((Button) secret.getChildren().getLast()).isDisabled());
        secret.setConfigured(true);
        secret.setStatusText("已配置 v1");
        secret.setActionsDisabled(false);
        ((Button) secret.getChildren().get(1)).fire();
        ((Button) secret.getChildren().getLast()).fire();
        assertTrue(secret.getStyleClass().contains("platform-secret-field"));

        AtomicBoolean allowed = new AtomicBoolean();
        AtomicReference<DangerConfirmationRequest> request = new AtomicReference<>();
        DangerZone danger = new DangerZone(
                "永久清除",
                "操作不可撤销",
                "清除",
                value -> {
                    request.set(value);
                    return allowed.get();
                },
                invocations::incrementAndGet);
        danger.setActionDisabled(false);
        ((Button) danger.getChildren().getLast()).fire();
        assertEquals(4, invocations.get());
        assertEquals(new DangerConfirmationRequest("永久清除", "操作不可撤销", "清除"), request.get());
        allowed.set(true);
        ((Button) danger.getChildren().getLast()).fire();
        assertTrue(danger.getStyleClass().contains("platform-danger-zone"));
        assertEquals(5, invocations.get());

        ExecutionTimeline timeline = new ExecutionTimeline();
        timeline.setEntries(List.of(new ExecutionTimeline.Entry(Instant.EPOCH, "已启动", "开始执行")));
        assertEquals(1, timeline.getChildren().size());

        assertManagementShellAvailabilityGate();
    }

    private static void assertManagementShellAvailabilityGate() {
        ManagementPageShell shell = new ManagementPageShell("设置与管理");
        shell.setNavigationContent(new Label("导航"));
        shell.showPage("外观", new Label("内容"));
        Node pageContent = ((javafx.scene.layout.BorderPane) shell.getCenter()).getCenter();
        AsyncActionBar actionBar = new AsyncActionBar(new Button("保存"));
        shell.setActionContent(Optional.of(actionBar));
        StackPane actionSlot = (StackPane) ((javafx.scene.layout.BorderPane) shell.getCenter()).getBottom();
        shell.setPageInteractionEnabled(false);
        assertTrue(pageContent.isDisabled());
        assertTrue(actionSlot.isDisabled());
        Label replacement = new Label("另一个页面");
        shell.showPage("计划", replacement);
        assertTrue(replacement.isDisabled());
        shell.setPageInteractionEnabled(true);
        assertFalse(replacement.isDisabled());
        assertFalse(actionSlot.isDisabled());
        assertTrue(actionSlot.isManaged());
        assertEquals(actionBar, actionSlot.getChildren().getFirst());
        shell.setActionContent(Optional.empty());
        assertFalse(actionSlot.isManaged());
        assertTrue(actionSlot.getChildren().isEmpty());
        assertTrue(shell.getStyleClass().contains("management-center"));
    }

    private static void assertInvalidInputsFailFast() {
        assertThrows(IllegalArgumentException.class, () -> new FormSection(" ", "说明"));
        assertThrows(NullPointerException.class, () -> new AsyncActionBar((javafx.scene.Node[]) null));
        assertThrows(NullPointerException.class, () -> new DangerZone("危险", "说明", "执行", ignored -> true, null));
        assertThrows(NullPointerException.class, () -> new DangerZone("危险", "说明", "执行", null, () -> {}));
        assertThrows(IllegalArgumentException.class, () -> new DangerConfirmationRequest(" ", "说明", "执行"));
        assertThrows(NullPointerException.class, () -> new RevisionConflictPane(null, () -> {}));
        assertThrows(NullPointerException.class, () -> new SecretStatusField(() -> {}, null));
        assertThrows(NullPointerException.class, () -> new ExecutionTimeline.Entry(null, "状态", "说明"));

        AsyncActionBar actionBar = new AsyncActionBar();
        assertThrows(NullPointerException.class, () -> actionBar.show(null, "状态"));
        ListDetailPane<String> listDetail = new ListDetailPane<>();
        assertThrows(NullPointerException.class, () -> listDetail.showDetail(null));
        assertThrows(NullPointerException.class, () -> listDetail.showLoading(null));
        assertThrows(NullPointerException.class, () -> listDetail.showError("读取失败", "请重试。", null));
        ExecutionTimeline timeline = new ExecutionTimeline();
        assertThrows(NullPointerException.class, () -> timeline.setEntries(null));
        ManagementPageShell shell = new ManagementPageShell("设置");
        assertThrows(NullPointerException.class, () -> shell.setNavigationContent(null));
        assertThrows(NullPointerException.class, () -> shell.showPage("外观", null));
        assertThrows(NullPointerException.class, () -> shell.setActionContent(null));
    }
}
