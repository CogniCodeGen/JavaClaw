package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderModelDiscoveryCandidate;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.component.PlatformComponentFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderSetupWizardFxTest {
    @Test
    void 两步窗口保存并使用精确模型后返回调用页且清除密钥控件() {
        FxTestSupport.run(() -> {
            ApplyingGateway gateway = new ApplyingGateway();
            AtomicInteger completed = new AtomicInteger();
            ProviderSetupTarget target = new ProviderSetupTarget(
                    Optional.of(new WorkspaceId("workspace-a")), Optional.of(new ThreadId("thread-a")), "项目 A");
            ProviderSetupWizard.show(null, gateway, target, completed::incrementAndGet);
            Window window = wizardWindow();
            Parent root = window.getScene().getRoot();
            fillConnection(root);

            button(root, "providerWizardContinue").fire();

            assertEquals("", ((PasswordField) root.lookup("#providerWizardSecret")).getText());
            text(root, "providerWizardManualModel").setText("my-chat-model");
            button(root, "providerWizardAddManual").fire();
            assertTrue(button(root, "providerWizardContinue").getText().contains("项目 A"));
            button(root, "providerWizardContinue").fire();

            assertEquals("my-chat-model", gateway.applied.model());
            assertEquals(3, gateway.applied.endpointRevision());
            assertEquals(1, completed.get());
            assertFalse(window.isShowing());
        });
    }

    @Test
    void 无工作区保存后保留弹窗并在补选工作区后接续应用() {
        FxTestSupport.run(() -> {
            ApplyingGateway gateway = new ApplyingGateway();
            AtomicInteger completed = new AtomicInteger();
            ProviderSetupWizard.show(
                    null,
                    gateway,
                    new ProviderSetupTarget(Optional.empty(), Optional.empty(), ""),
                    completed::incrementAndGet);
            Window window = wizardWindow();
            Parent root = window.getScene().getRoot();
            fillConnection(root);
            button(root, "providerWizardContinue").fire();
            text(root, "providerWizardManualModel").setText("my-chat-model");
            button(root, "providerWizardAddManual").fire();

            button(root, "providerWizardContinue").fire();

            assertTrue(window.isShowing());
            assertEquals(0, completed.get());
            ComboBox<?> selector = (ComboBox<?>) root.lookup("#providerWizardWorkspace");
            selector.getSelectionModel().selectFirst();
            button(root, "providerWizardContinue").fire();

            assertEquals(3, gateway.applied.endpointRevision());
            assertEquals(1, completed.get());
            assertFalse(window.isShowing());
        });
    }

    @Test
    void 搜索不丢失多选模型且目录未知用途明确展示() {
        FxTestSupport.run(() -> {
            ProviderSetupModelForm form = new ProviderSetupModelForm(new PlatformComponentFactory(), () -> {});
            new Scene(form);
            form.candidates(List.of(
                    new ProviderModelDiscoveryCandidate("model-a", "模型 A", Set.of(), OptionalInt.empty()),
                    new ProviderModelDiscoveryCandidate(
                            "model-b", "模型 B", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty())));
            form.applyCss();
            form.layout();
            List<CheckBox> choices = descendants(form).stream()
                    .filter(CheckBox.class::isInstance)
                    .map(CheckBox.class::cast)
                    .toList();
            assertTrue(choices.getFirst().getText().contains("用途未知"));
            choices.forEach(CheckBox::fire);

            text(form, "providerWizardSearch").setText("no-match");
            text(form, "providerWizardSearch").clear();

            assertEquals(2, form.selectedModels().size());
            assertTrue(descendants(form).stream()
                    .filter(CheckBox.class::isInstance)
                    .map(CheckBox.class::cast)
                    .allMatch(CheckBox::isSelected));
            assertEquals(
                    List.of("model-a", "model-b"),
                    form.selectedModels().stream()
                            .map(ProviderModelSpec::modelId)
                            .toList());
        });
    }

    private static void fillConnection(Parent root) {
        text(root, "providerWizardAddress").setText("http://localhost:11434/v1");
        text(root, "providerWizardSecret").setText("temporary-key");
    }

    private static Window wizardWindow() {
        return Window.getWindows().stream()
                .filter(window -> window.getScene().lookup("#providerWizardAddress") != null)
                .findFirst()
                .orElseThrow();
    }

    private static TextField text(Parent root, String id) {
        return (TextField) root.lookup("#" + id);
    }

    private static Button button(Parent root, String id) {
        return (Button) root.lookup("#" + id);
    }

    private static List<Node> descendants(Parent parent) {
        return parent.getChildrenUnmodifiable().stream()
                .flatMap(child -> child instanceof Parent nested
                        ? java.util.stream.Stream.concat(
                                java.util.stream.Stream.of(child), descendants(nested).stream())
                        : java.util.stream.Stream.of(child))
                .toList();
    }

    private static final class ApplyingGateway extends TestCoreSettingsGateway {
        private ProviderRef applied;

        @Override
        public CompletionStage<Void> useModel(
                Optional<WorkspaceId> workspace, Optional<ThreadId> thread, ProviderRef model) {
            applied = model;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<List<Workspace>> workspaces() {
            return CompletableFuture.completedFuture(workspaceSettings.catalog);
        }
    }
}
