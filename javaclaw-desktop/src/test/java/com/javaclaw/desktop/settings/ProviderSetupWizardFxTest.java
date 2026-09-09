package com.javaclaw.desktop.settings;

import java.nio.file.Path;
import java.time.Instant;
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
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.component.PlatformComponentFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderSetupWizardFxTest {
    @Test
    void 应用回执到达前锁定工作区选择且失败后允许保持模型版本重试() {
        FxTestSupport.run(() -> {
            ApplyingGateway gateway = new ApplyingGateway();
            gateway.pendingApply = new CompletableFuture<>();
            AtomicInteger used = new AtomicInteger();
            ProviderSetupWizard.show(
                    null,
                    gateway,
                    new ProviderSetupTarget(Optional.empty(), Optional.empty(), ""),
                    () -> {},
                    used::incrementAndGet);
            Window window = wizardWindow();
            Parent root = window.getScene().getRoot();
            try {
                fillConnection(root);
                button(root, "providerWizardContinue").fire();
                text(root, "providerWizardManualModel").setText("pending-model");
                button(root, "providerWizardAddManual").fire();
                ComboBox<?> selector = (ComboBox<?>) root.lookup("#providerWizardWorkspace");
                selector.getSelectionModel().selectFirst();
                Workspace selected = (Workspace) selector.getValue();

                button(root, "providerWizardContinue").fire();

                assertTrue(selector.isDisabled());
                assertWorkspaceActionsDisabled(root);
                assertEquals(Optional.of(selected.id()), gateway.appliedWorkspace);
                assertEquals(0, used.get());
                ProviderRef saved = gateway.applied;
                gateway.pendingApply.completeExceptionally(new IllegalStateException("应用暂未完成"));
                assertTrue(window.isShowing());
                assertFalse(selector.isDisabled());
                gateway.pendingApply = null;
                button(root, "providerWizardContinue").fire();
                assertEquals(saved, gateway.applied);
                assertEquals(1, used.get());
                assertFalse(window.isShowing());
            } finally {
                if (gateway.pendingApply != null) {
                    gateway.pendingApply.completeExceptionally(new IllegalStateException("测试结束"));
                }
                window.hide();
            }
        });
    }

    @Test
    void 两步窗口保存并使用精确模型后返回调用页且清除密钥控件() {
        FxTestSupport.run(() -> {
            ApplyingGateway gateway = new ApplyingGateway();
            AtomicInteger completed = new AtomicInteger();
            ProviderSetupTarget target =
                    new ProviderSetupTarget(Optional.of(WorkspaceId.random()), Optional.of(ThreadId.random()), "项目 A");
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

    @Test
    void 仅保存不返回聊天且已配置模型成功使用后才触发返回回调() {
        FxTestSupport.run(() -> {
            ApplyingGateway gateway = new ApplyingGateway();
            AtomicInteger completed = new AtomicInteger();
            AtomicInteger used = new AtomicInteger();
            ProviderSetupTarget target =
                    new ProviderSetupTarget(Optional.of(WorkspaceId.random()), Optional.empty(), "目标项目");
            ProviderSetupWizard.show(null, gateway, target, completed::incrementAndGet, used::incrementAndGet);
            Window window = wizardWindow();
            Parent root = window.getScene().getRoot();
            fillConnection(root);
            button(root, "providerWizardContinue").fire();
            text(root, "providerWizardManualModel").setText("saved-only-model");
            button(root, "providerWizardAddManual").fire();

            button(root, "providerWizardSaveOnly").fire();

            assertFalse(window.isShowing());
            assertEquals(1, completed.get());
            assertEquals(0, used.get());
            ProviderSetupWizard.useModel(
                    null,
                    gateway,
                    target,
                    new ProviderRef("provider-main", 1, "fake-model"),
                    completed::incrementAndGet,
                    used::incrementAndGet);
            assertEquals(2, completed.get());
            assertEquals(1, used.get());
        });
    }

    @Test
    void 应用失败保留模型选择和窗口再次点击只重试应用() {
        FxTestSupport.run(() -> {
            ApplyingGateway gateway = new ApplyingGateway();
            gateway.failApply = true;
            AtomicInteger completed = new AtomicInteger();
            AtomicInteger used = new AtomicInteger();
            ProviderSetupWizard.show(
                    null,
                    gateway,
                    new ProviderSetupTarget(Optional.of(WorkspaceId.random()), Optional.of(ThreadId.random()), "目标项目"),
                    completed::incrementAndGet,
                    used::incrementAndGet);
            Window window = wizardWindow();
            Parent root = window.getScene().getRoot();
            fillConnection(root);
            button(root, "providerWizardContinue").fire();
            text(root, "providerWizardManualModel").setText("retry-model");
            button(root, "providerWizardAddManual").fire();

            button(root, "providerWizardContinue").fire();

            assertTrue(window.isShowing());
            assertEquals(0, completed.get());
            assertEquals(0, used.get());
            ProviderRef saved = gateway.applied;
            button(root, "providerWizardContinue").fire();
            assertEquals(saved, gateway.applied);
            assertEquals(2, gateway.applyCalls);
            assertEquals(1, gateway.providerCredentialSetCalls);
            assertFalse(window.isShowing());
            assertEquals(1, completed.get());
            assertEquals(1, used.get());
        });
    }

    @Test
    void 在窗口内创建工作区后固定新目标并允许继续使用模型() {
        FxTestSupport.run(() -> {
            ApplyingGateway gateway = new ApplyingGateway();
            ProviderSetupWorkspacePicker picker = new ProviderSetupWorkspacePicker(
                    null, gateway, new ProviderSetupTarget(Optional.empty(), Optional.empty(), ""));
            Path root = Path.of("/tmp/wizard-workspace-test");

            picker.createWorkspace(root);

            assertEquals("wizard-workspace-test", picker.target().workspaceName());
            assertEquals(gateway.created.id(), picker.target().workspaceId().orElseThrow());
            assertTrue(picker.target().threadId().isEmpty());
            assertFalse(picker.pending());
        });
    }

    private static void fillConnection(Parent root) {
        text(root, "providerWizardAddress").setText("http://localhost:11434/v1");
        text(root, "providerWizardSecret").setText("temporary-key");
    }

    private static void assertWorkspaceActionsDisabled(Parent root) {
        List<Button> actions = descendants(root).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> Set.of("创建工作区", "刷新工作区").contains(button.getText()))
                .toList();
        assertEquals(2, actions.size());
        assertTrue(actions.stream().allMatch(Button::isDisabled));
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
        private Workspace created;
        private boolean failApply;
        private int applyCalls;
        private Optional<WorkspaceId> appliedWorkspace = Optional.empty();
        private CompletableFuture<Void> pendingApply;

        @Override
        public CompletionStage<Void> useModel(
                Optional<WorkspaceId> workspace, Optional<ThreadId> thread, ProviderRef model) {
            applied = model;
            appliedWorkspace = workspace;
            applyCalls++;
            if (failApply) {
                failApply = false;
                return CompletableFuture.failedFuture(new IllegalStateException("对话暂不可用"));
            }
            return pendingApply == null ? CompletableFuture.completedFuture(null) : pendingApply;
        }

        @Override
        public CompletionStage<Workspace> createModelWorkspace(String name, Path root) {
            Instant now = Instant.now();
            created = new Workspace(WorkspaceId.random(), name, root, WorkspaceLifecycle.ACTIVE, 1, now, now);
            return CompletableFuture.completedFuture(created);
        }

        @Override
        public CompletionStage<List<Workspace>> workspaces() {
            return CompletableFuture.completedFuture(workspaceSettings.catalog);
        }
    }
}
