package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Optional;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.appearance.AppearancePreferences;
import com.javaclaw.desktop.appearance.AppearanceTheme;
import com.javaclaw.desktop.appearance.DesktopAppearanceManager;
import com.javaclaw.desktop.appearance.FontScale;
import com.javaclaw.desktop.appearance.InterfaceDensity;
import com.javaclaw.desktop.component.ManagementPageShell;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.protocol.InputJobRpcContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationJobSettingsLayoutTest {
    @Test
    void 深色大字号最小窗口保留任务列表宽度且长标题换行详情与动作仍可达() {
        FxTestSupport.run(() -> {
            TestAutomationJobSettingsGateway gateway = gateway();
            AutomationJobSettingsPage page = new AutomationJobSettingsPage(gateway);
            page.workspaceChanged(Optional.of(gateway.workspace));
            ManagementPageShell shell = new ManagementPageShell("设置与管理");
            ScrollPane scroll = new ScrollPane(page);
            scroll.setFitToWidth(true);
            scroll.getStyleClass().addAll("settings-scroll-pane", "platform-content-scroll");
            shell.showPage("后台任务", scroll);
            shell.setActionContent(page.actionContent());
            Scene scene = new Scene(shell, 880, 590);
            DesktopStylesheets.apply(scene);
            DesktopAppearanceManager.apply(
                    scene,
                    new AppearancePreferences(
                            AppearanceTheme.MIDNIGHT, FontScale.EXTRA_LARGE, InterfaceDensity.COMPACT));
            try {
                page.activate();
                layout(scene);
                assertEquals(620, page.getMinWidth());
                Region detail = (Region) page.lookup(".platform-detail-pane .platform-page");
                assertEquals(0, detail.getMinWidth());
                assertEquals(page.getPadding(), detail.getPadding(), "详情应继续沿用页面间距令牌");
                ListView<?> jobs = (ListView<?>) page.lookup("#automationJobList");
                assertEquals(2, jobs.getItems().size());
                assertTrue(jobs.getWidth() >= 219, "详情不能挤走列表的可辨识宽度");
                assertTrue(jobs.getWidth() < page.getWidth() * 0.55, "列表需给详情保留空间");
                assertCells(jobs);
                assertDetail(page, gateway.firstPage.getFirst());
                jobs.getSelectionModel().select(1);
                layout(scene);
                assertDetail(page, gateway.firstPage.get(1));
                Button resume = (Button) shell.lookup("#automationJobResumeButton");
                assertFalse(resume.isDisabled());
                for (String id : List.of("Pause", "Resume", "Cancel")) {
                    Button action = (Button) shell.lookup("#automationJob" + id + "Button");
                    assertInsideScene(action);
                    assertFalse(action.isTextTruncated(), action.getText());
                }
                resume.fire();
                assertEquals("resume", gateway.lastAction);
            } finally {
                page.deactivate();
            }
        });
    }

    private static void assertCells(ListView<?> jobs) {
        List<? extends ListCell<?>> cells = jobs.lookupAll(".list-cell").stream()
                .filter(ListCell.class::isInstance)
                .map(node -> (ListCell<?>) node)
                .filter(cell -> !cell.isEmpty())
                .toList();
        assertEquals(2, cells.size());
        for (ListCell<?> cell : cells) {
            Label title = (Label) cell.lookup(".platform-detail-title");
            assertTrue(title.isWrapText());
            assertTrue(title.getHeight() > title.getFont().getSize() * 1.5, "长任务标题需要真实换行");
            assertFalse(title.isTextTruncated(), title.getText());
            assertTrue(cell.getTooltip().getText().contains(title.getText()));
            assertTrue(title.localToScene(title.getLayoutBounds()).getMaxX()
                    <= jobs.localToScene(jobs.getLayoutBounds()).getMaxX() + 1);
        }
    }

    private static void assertDetail(AutomationJobSettingsPage page, ExtensionExecutionReceipt receipt) {
        Label identity = page.lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .filter(label -> receipt.id().equals(label.getText()))
                .findFirst()
                .orElseThrow();
        Bounds bounds = identity.localToScene(identity.getLayoutBounds());
        assertTrue(bounds.getMaxX() <= page.getScene().getWidth() + 1, "详情不能被横向裁切");
        assertTrue(identity.getWidth() > 60, "任务标识需要可读的值列");
        assertFalse(identity.isTextTruncated());
    }

    private static void assertInsideScene(Node node) {
        Bounds bounds = node.localToScene(node.getLayoutBounds());
        assertTrue(bounds.getMinX() >= -1 && bounds.getMaxX() <= node.getScene().getWidth() + 1);
        assertTrue(bounds.getMinY() >= -1 && bounds.getMaxY() <= node.getScene().getHeight() + 1);
    }

    private static void layout(Scene scene) {
        scene.getRoot().applyCss();
        scene.getRoot().resize(scene.getWidth(), scene.getHeight());
        scene.getRoot().layout();
    }

    private static TestAutomationJobSettingsGateway gateway() {
        TestAutomationJobSettingsGateway gateway = new TestAutomationJobSettingsGateway();
        for (int index = 0; index < gateway.firstPage.size(); index++) {
            ExtensionExecutionReceipt original = gateway.firstPage.get(index);
            ExtensionExecutionReceipt job = new ExtensionExecutionReceipt(
                    "064df32b-2e36-44f2-ab74-b8bb64600df" + index,
                    new ExtensionId("com.javaclaw.memory"),
                    original.workspaceId(),
                    "conversation-learning",
                    "conversation-learning-项目记忆学习-验收任务-" + index,
                    original.definitionRevision(),
                    original.state(),
                    original.revision(),
                    original.errorCode(),
                    original.createdAt(),
                    original.updatedAt());
            gateway.firstPage.set(index, job);
            gateway.details.put(job.id(), new InputJobRpcContracts.JobReadResult(job, List.of()));
        }
        return gateway;
    }
}
