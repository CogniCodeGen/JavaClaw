package com.javaclaw.desktop.settings;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.event.ActionEvent;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderModelUseDialogTest {
    private static final ProviderRef MODEL = new ProviderRef("provider-main", 7, "fake-model");

    @Test
    void 工作区刷新期间拒绝应用且迟到目录投影不解除应用锁定() {
        FxTestSupport.run(() -> {
            DeferredGateway gateway = new DeferredGateway();
            AtomicInteger used = new AtomicInteger();
            Window window = open(gateway, used);
            try {
                Parent root = window.getScene().getRoot();
                ComboBox<?> selector = (ComboBox<?>) root.lookup("#providerWizardWorkspace");
                selector.getSelectionModel().selectFirst();
                Button use = useButton(root);
                gateway.directory = new CompletableFuture<>();
                button(root, "刷新工作区").fire();
                assertTrue(use.isDisabled());
                assertTrue(selector.isDisabled());
                // 即使外部已排队的动作绕过 Button.fire 的禁用检查，也不能提交旧目录目标。
                use.fireEvent(new ActionEvent());
                assertEquals(0, gateway.applyCalls);
                beginApplyBeforeDirectoryProjection(use);
                gateway.directory.complete(List.of(gateway.renamed, gateway.second));
                assertApplicationLocked(root, use, gateway, used);
                assertEquals(gateway.renamed, selector.getValue());
                gateway.application.completeExceptionally(new IllegalStateException("延迟的应用失败"));
                assertTrue(window.isShowing());
                assertFalse(selector.isDisabled());
                assertFalse(use.isDisabled());
                assertEquals("在「项目 A 新名称」中使用", use.getText());
                gateway.application = new CompletableFuture<>();
                use.fire();
                assertEquals(2, gateway.applyCalls);
                assertEquals(Optional.of(gateway.first.id()), gateway.appliedWorkspace);
                assertEquals(MODEL, gateway.appliedModel);
                gateway.application.complete(null);
                assertFalse(window.isShowing());
                assertEquals(1, used.get());
            } finally {
                gateway.application.completeExceptionally(new IllegalStateException("测试结束"));
                window.hide();
            }
        });
    }

    private static void beginApplyBeforeDirectoryProjection(Button use) {
        AtomicBoolean submitted = new AtomicBoolean();
        // 精确停在目录回执清除 pending 后、继续替换候选项前；无需线程睡眠或内部 JavaFX API。
        use.disabledProperty().addListener((ignored, before, disabled) -> {
            if (!disabled && submitted.compareAndSet(false, true)) {
                use.fire();
            }
        });
    }

    private static void assertApplicationLocked(Parent root, Button use, DeferredGateway gateway, AtomicInteger used) {
        assertEquals(1, gateway.applyCalls);
        assertEquals(Optional.of(gateway.first.id()), gateway.appliedWorkspace);
        assertEquals(MODEL, gateway.appliedModel);
        assertEquals("在「项目 A」中使用", use.getText());
        assertTrue(use.isDisabled());
        assertTrue(root.lookup("#providerWizardWorkspace").isDisabled());
        assertTrue(button(root, "刷新工作区").isDisabled());
        assertTrue(button(root, "创建工作区").isDisabled());
        assertEquals(0, used.get());
        use.fireEvent(new ActionEvent());
        assertEquals(1, gateway.applyCalls);
    }

    private static Window open(DeferredGateway gateway, AtomicInteger used) {
        ProviderModelUseDialog.show(
                null,
                gateway,
                new ProviderSetupTarget(Optional.empty(), Optional.empty(), ""),
                MODEL,
                () -> {},
                used::incrementAndGet);
        return Window.getWindows().stream()
                .filter(window -> window.getScene().lookup("#providerWizardWorkspace") != null)
                .findFirst()
                .orElseThrow();
    }

    private static Button useButton(Parent root) {
        return root.lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(Button::isDefaultButton)
                .findFirst()
                .orElseThrow();
    }

    private static Button button(Parent root, String label) {
        return root.lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> label.equals(button.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static Workspace workspace(WorkspaceId id, String name) {
        return new Workspace(
                id,
                name,
                Path.of("/tmp/model-use-dialog-test"),
                WorkspaceLifecycle.ACTIVE,
                1,
                Instant.EPOCH,
                Instant.EPOCH);
    }

    private static final class DeferredGateway extends TestCoreSettingsGateway {
        private final Workspace first = workspace(WorkspaceId.random(), "项目 A");
        private final Workspace second = workspace(WorkspaceId.random(), "项目 B");
        private final Workspace renamed = workspace(first.id(), "项目 A 新名称");
        private CompletableFuture<List<Workspace>> directory;
        private CompletableFuture<Void> application = new CompletableFuture<>();
        private int applyCalls;
        private Optional<WorkspaceId> appliedWorkspace;
        private ProviderRef appliedModel;

        @Override
        public CompletionStage<List<Workspace>> workspaces() {
            return directory == null ? CompletableFuture.completedFuture(List.of(first, second)) : directory;
        }

        @Override
        public CompletionStage<Void> useModel(
                Optional<WorkspaceId> workspace, Optional<ThreadId> thread, ProviderRef model) {
            applyCalls++;
            appliedWorkspace = workspace;
            appliedModel = model;
            return application;
        }
    }
}
