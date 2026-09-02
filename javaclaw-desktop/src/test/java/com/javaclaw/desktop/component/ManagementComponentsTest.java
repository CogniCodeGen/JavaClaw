package com.javaclaw.desktop.component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.component.AsyncActionBar.ActionState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementComponentsTest {
    @Test
    void 管理组件共享状态和布局语义() {
        FxTestSupport.run(() -> {
            FormSection section = new FormSection("配置", "说明");
            section.addField("名称", new TextField());
            section.addFullWidth(new Label("完整行"));
            assertTrue(section.getStyleClass().contains("platform-form-section"));

            ListDetailPane<String> listDetail = new ListDetailPane<>();
            listDetail.list().getItems().add("一");
            listDetail.showDetail(new Label("详情"));
            assertEquals(2, listDetail.getItems().size());

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

        DangerZone danger = new DangerZone("永久清除", "操作不可撤销", "清除", invocations::incrementAndGet);
        danger.setActionDisabled(false);
        ((Button) danger.getChildren().getLast()).fire();
        assertTrue(danger.getStyleClass().contains("platform-danger-zone"));
        assertEquals(5, invocations.get());

        ExecutionTimeline timeline = new ExecutionTimeline();
        timeline.setEntries(List.of(new ExecutionTimeline.Entry(Instant.EPOCH, "已启动", "开始执行")));
        assertEquals(1, timeline.getChildren().size());

        ManagementPageShell shell = new ManagementPageShell("设置与管理");
        shell.setNavigationContent(new Label("导航"));
        shell.showPage("外观", new Label("内容"));
        assertTrue(shell.getStyleClass().contains("management-center"));
    }

    private static void assertInvalidInputsFailFast() {
        assertThrows(IllegalArgumentException.class, () -> new FormSection(" ", "说明"));
        assertThrows(NullPointerException.class, () -> new AsyncActionBar((javafx.scene.Node[]) null));
        assertThrows(NullPointerException.class, () -> new DangerZone("危险", "说明", "执行", null));
        assertThrows(NullPointerException.class, () -> new RevisionConflictPane(null, () -> {}));
        assertThrows(NullPointerException.class, () -> new SecretStatusField(() -> {}, null));
        assertThrows(NullPointerException.class, () -> new ExecutionTimeline.Entry(null, "状态", "说明"));

        AsyncActionBar actionBar = new AsyncActionBar();
        assertThrows(NullPointerException.class, () -> actionBar.show(null, "状态"));
        ListDetailPane<String> listDetail = new ListDetailPane<>();
        assertThrows(NullPointerException.class, () -> listDetail.showDetail(null));
        ExecutionTimeline timeline = new ExecutionTimeline();
        assertThrows(NullPointerException.class, () -> timeline.setEntries(null));
        ManagementPageShell shell = new ManagementPageShell("设置");
        assertThrows(NullPointerException.class, () -> shell.setNavigationContent(null));
        assertThrows(NullPointerException.class, () -> shell.showPage("外观", null));
    }
}
