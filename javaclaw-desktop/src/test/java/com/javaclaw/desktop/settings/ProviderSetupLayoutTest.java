package com.javaclaw.desktop.settings;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ProviderModelDiscoveryCandidate;
import com.javaclaw.api.ProviderModelDiscoveryResult;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformStylesheets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderSetupLayoutTest {
    private static final String MODEL_ID = "vendor/" + "long-model-identifier-".repeat(20);
    private static final String WORKSPACE_NAME = "用于验证长名称换行的工作区".repeat(12);

    @Test
    void 默认宽度下长模型和工作区名称不挤出操作按钮且保存目标不变() {
        assertModelStepLayout(WORKSPACE_NAME);
    }

    @Test
    void 默认宽度下实际项目名称的四个底部按钮完整显示() {
        assertModelStepLayout("功能验收 2026-09-09");
    }

    private static void assertModelStepLayout(String workspaceName) {
        LayoutGateway gateway = new LayoutGateway(workspaceName);
        Stage window = FxTestSupport.call(() -> openModelStep(gateway));
        try {
            FxTestSupport.run(() -> selectModelAndWorkspace(window));
            // 让窗口约束与向导的异步 sizeToScene 完成，再按默认上限检查真实 Dialog 布局。
            FxTestSupport.run(() -> {
                window.setWidth(720);
                window.setHeight(680);
                Parent root = window.getScene().getRoot();
                root.applyCss();
                root.layout();
                assertInsideWindow(root.lookup("#providerWizardDiscoverModels"));
                assertInsideWindow(root.lookup("#providerWizardAddManual"));
                assertInsideWindow(root.lookup("#providerWizardCurrentModel"));
                assertInsideWindow(root.lookup("#providerWizardWorkspace"));
                assertFooterButtons(root);
                Button save = (Button) root.lookup("#providerWizardContinue");
                assertInsideWindow(save);
                assertEquals("保存并在「" + workspaceName + "」中使用", save.getText());
                assertEquals(save.getText(), save.getTooltip().getText());
                assertEquals(save.getText(), ((Text) save.lookup(".text")).getText());
                assertTrue(save.getHeight() + 1 >= save.prefHeight(save.getWidth()));
                assertTrue(save.localToScene(save.getLayoutBounds()).getMaxY()
                        <= root.getScene().getHeight() + 1);
                assertEquals(
                        MODEL_ID,
                        ((ComboBox<?>) root.lookup("#providerWizardCurrentModel"))
                                .getTooltip()
                                .getText());
                assertEquals(
                        workspaceName,
                        ((ComboBox<?>) root.lookup("#providerWizardWorkspace"))
                                .getTooltip()
                                .getText());
                save.fire();
                assertEquals(MODEL_ID, gateway.applied.model());
                assertEquals(Optional.of(gateway.workspace.id()), gateway.appliedWorkspace);
                assertFalse(window.isShowing());
            });
        } finally {
            FxTestSupport.run(window::hide);
        }
    }

    @Test
    void 长候选名称换行且同名ID只显示一次并在搜索后保留选择() {
        FxTestSupport.run(() -> {
            ProviderSetupModelForm form = new ProviderSetupModelForm(new PlatformComponentFactory(), () -> {});
            new Scene(form, 460, 500);
            PlatformStylesheets.applyTo(form);
            form.candidates(List.of(candidate(MODEL_ID, MODEL_ID), candidate("different-model", "易读显示名称")));
            form.applyCss();
            form.resize(460, 500);
            form.layout();
            CheckBox choice = choice(form, MODEL_ID);
            assertEquals(MODEL_ID, choice.getText());
            assertEquals(MODEL_ID, choice.getTooltip().getText());
            assertTrue(choice.isWrapText());
            assertInsideWindow(choice);
            assertTrue(choice.getHeight() > choice.getFont().getSize() * 2);
            choice.fire();
            assertEquals(MODEL_ID, form.currentModel());
            assertEquals(
                    "易读显示名称 · different-model",
                    choice(form, "易读显示名称 · different-model").getText());
            ((TextField) form.lookup("#providerWizardSearch")).setText("no-match");
            ((TextField) form.lookup("#providerWizardSearch")).clear();
            assertTrue(choice(form, MODEL_ID).isSelected());
            assertEquals(MODEL_ID, form.selectedModels().getFirst().modelId());
        });
    }

    private static Stage openModelStep(LayoutGateway gateway) {
        ProviderSetupWizard.show(
                null, gateway, new ProviderSetupTarget(Optional.empty(), Optional.empty(), ""), () -> {});
        Stage window = Window.getWindows().stream()
                .filter(candidate -> candidate.getScene().lookup("#providerWizardAddress") != null)
                .map(Stage.class::cast)
                .findFirst()
                .orElseThrow();
        Parent root = window.getScene().getRoot();
        ((TextField) root.lookup("#providerWizardAddress")).setText("http://localhost:11434/v1");
        ((TextField) root.lookup("#providerWizardSecret")).setText("test-only-secret");
        ((Button) root.lookup("#providerWizardContinue")).fire();
        return window;
    }

    private static void selectModelAndWorkspace(Stage window) {
        Parent root = window.getScene().getRoot();
        root.applyCss();
        root.layout();
        choice(root, MODEL_ID).fire();
        ((ComboBox<?>) root.lookup("#providerWizardWorkspace"))
                .getSelectionModel()
                .selectFirst();
    }

    private static CheckBox choice(Parent parent, String text) {
        return parent.lookupAll(".check-box").stream()
                .filter(CheckBox.class::isInstance)
                .map(CheckBox.class::cast)
                .filter(choice -> text.equals(choice.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static void assertInsideWindow(Node node) {
        Bounds bounds = node.localToScene(node.getLayoutBounds());
        assertTrue(bounds.getMinX() >= -1, () -> node.getId() + " 越过窗口左边界: " + bounds);
        assertTrue(bounds.getMaxX() <= node.getScene().getWidth() + 1, () -> node.getId() + " 越过窗口右边界: " + bounds);
    }

    private static void assertFooterButtons(Parent root) {
        List<Button> buttons = root.lookupAll(".button-bar .button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .toList();
        assertEquals(4, buttons.size());
        for (Button button : buttons) {
            assertTrue(button.isVisible() && button.isManaged(), button.getText());
            assertInsideWindow(button);
            Text rendered = (Text) button.lookup(".text");
            assertEquals(button.getText(), rendered.getText(), () -> "底部按钮文字被省略：" + button.getText());
            assertTrue(button.getHeight() + 1 >= button.prefHeight(button.getWidth()), button.getText());
            Bounds bounds = button.localToScene(button.getLayoutBounds());
            assertTrue(bounds.getMinY() >= -1, button.getText());
            assertTrue(bounds.getMaxY() <= root.getScene().getHeight() + 1, button.getText());
        }
    }

    private static ProviderModelDiscoveryCandidate candidate(String id, String name) {
        return new ProviderModelDiscoveryCandidate(id, name, Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty());
    }

    private static final class LayoutGateway extends TestCoreSettingsGateway {
        private final Workspace workspace;
        private ProviderRef applied;
        private Optional<WorkspaceId> appliedWorkspace;

        private LayoutGateway(String workspaceName) {
            workspace = new Workspace(
                    WorkspaceId.random(),
                    workspaceName,
                    Path.of("/tmp/provider-setup-layout-test"),
                    WorkspaceLifecycle.ACTIVE,
                    1,
                    Instant.EPOCH,
                    Instant.EPOCH);
        }

        @Override
        public CompletionStage<List<Workspace>> workspaces() {
            return CompletableFuture.completedFuture(List.of(workspace));
        }

        @Override
        public CompletionStage<ProviderModelDiscoveryResult> discoverProviderModels(
                String id, long revision, CancellationToken cancellation) {
            return CompletableFuture.completedFuture(new ProviderModelDiscoveryResult(
                    id, revision, List.of(candidate(MODEL_ID, MODEL_ID)), false, Instant.EPOCH));
        }

        @Override
        public CompletionStage<Void> useModel(
                Optional<WorkspaceId> workspace, Optional<ThreadId> thread, ProviderRef model) {
            appliedWorkspace = workspace;
            applied = model;
            return CompletableFuture.completedFuture(null);
        }
    }
}
