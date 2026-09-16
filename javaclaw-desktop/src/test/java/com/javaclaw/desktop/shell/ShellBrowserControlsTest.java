package com.javaclaw.desktop.shell;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BrowserGrantContracts;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.client.extension.BrowserClient;
import com.javaclaw.desktop.DesktopBrowserGateway;
import com.javaclaw.desktop.DesktopNotificationSubscription;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.NativeUiEvidence;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.state.InteractionState;
import com.javaclaw.desktop.state.NavigationState;
import com.javaclaw.desktop.state.ThreadState;
import com.javaclaw.desktop.state.TranscriptState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellBrowserControlsTest {
    @Test
    void 接管冻结代次防重复点击并按权威重读更新按钮() {
        FxTestSupport.run(() -> {
            Gateway gateway = new Gateway();
            try (ShellBrowserControls controls = new ShellBrowserControls(gateway)) {
                VBox root = new VBox(16, new Label("当前对话 · 浏览器"), controls);
                root.setStyle("-fx-padding: 20;");
                new Scene(root, 940, 220);
                controls.bind(connected());
                gateway.reads.getFirst().complete(status(BrowserContracts.ControlMode.ASSISTANT, 3));
                assertTrue(controls.isVisible());
                button(controls, "我来操作").fire();
                button(controls, "我来操作").fire();
                assertEquals(1, gateway.commands);
                assertEquals(3, gateway.generation);
                assertEquals(BrowserClient.Control.TAKE_OVER, gateway.control);
                gateway.controlled.complete(status(BrowserContracts.ControlMode.HUMAN, 4));
                assertEquals(2, gateway.reads.size());
                gateway.reads.getLast().complete(status(BrowserContracts.ControlMode.HUMAN, 4));
                assertTrue(button(controls, "我来操作").isDisabled());
                assertFalse(button(controls, "交还助手").isDisabled());
                assertFalse(button(controls, "保存网页登录").isDisabled());
                NativeUiEvidence.capture(root, "browser-human-controls.png");
            }
        });
    }

    @Test
    void 资源通知合并为重读且切换对话后丢弃旧状态() {
        FxTestSupport.run(() -> {
            Gateway gateway = new Gateway();
            try (ShellBrowserControls controls = new ShellBrowserControls(gateway)) {
                controls.bind(connected());
                gateway.invalidated.run();
                gateway.invalidated.run();
                assertEquals(1, gateway.reads.size());
                gateway.reads.getFirst().complete(status(BrowserContracts.ControlMode.HUMAN, 2));
                assertEquals(2, gateway.reads.size());
                controls.bind(DesktopState.initial());
                gateway.reads.getLast().complete(status(BrowserContracts.ControlMode.ASSISTANT, 3));
                assertFalse(controls.isVisible());
                assertFalse(controls.isManaged());
                assertEquals(1, gateway.unsubscribed);
            }
        });
    }

    @Test
    void 没有浏览器会话时仍展示持久预算失败并允许刷新() {
        FxTestSupport.run(() -> {
            Gateway gateway = new Gateway();
            try (ShellBrowserControls controls = new ShellBrowserControls(gateway)) {
                controls.bind(connected());
                String detail = "原任务剩余预算不足，请手动继续任务";
                var continuation = new BrowserCommands.ContinuationStatus(
                        BrowserCommands.ContinuationState.FAILED,
                        BrowserCommands.ContinuationFailure.BUDGET_EXHAUSTED,
                        detail,
                        com.javaclaw.api.TurnId.random(),
                        Optional.empty());
                gateway.reads
                        .getFirst()
                        .complete(
                                new BrowserCommands.Status(true, Optional.empty(), detail, Optional.of(continuation)));
                assertTrue(controls.isVisible());
                assertTrue(controls.getChildren().stream()
                        .filter(Label.class::isInstance)
                        .map(Label.class::cast)
                        .anyMatch(label -> label.getText().contains(detail)));
                assertFalse(button(controls, "我来操作").isVisible());
                assertFalse(button(controls, "刷新").isDisabled());
                button(controls, "刷新").fire();
                assertEquals(2, gateway.reads.size());
            }
        });
    }

    @Test
    void 没有会话或原生能力时仍可管理当前对话来源授权() {
        FxTestSupport.run(() -> {
            Gateway gateway = new Gateway();
            try (ShellBrowserControls controls = new ShellBrowserControls(gateway)) {
                controls.bind(connected());
                gateway.reads.getFirst().complete(new BrowserCommands.Status(false, Optional.empty(), "平台尚未验证"));
                assertTrue(controls.isVisible());
                assertFalse(button(controls, "来源授权").isDisabled());
                assertFalse(button(controls, "我来操作").isVisible());
            }
        });
    }

    private static Button button(ShellBrowserControls controls, String label) {
        return controls.getChildren().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> button.getText().equals(label))
                .findFirst()
                .orElseThrow();
    }

    private static DesktopState connected() {
        var workspace = DesktopTestFixtures.workspace();
        var thread = DesktopTestFixtures.thread(workspace);
        return new DesktopState(
                ConnectionState.connected("test", Instant.EPOCH),
                NavigationState.initial(),
                new ThreadState(
                        List.of(workspace),
                        Optional.of(workspace),
                        List.of(thread),
                        Optional.of(thread),
                        Optional.empty()),
                TranscriptState.empty(),
                InteractionState.initial());
    }

    private static BrowserCommands.Status status(BrowserContracts.ControlMode mode, long generation) {
        var owner = new BrowserContracts.Owner(
                DesktopTestFixtures.workspace().id(),
                DesktopTestFixtures.thread().id(),
                Optional.of(new BrowserContracts.AccountBinding("account", 1)));
        var lease = new BrowserContracts.AccessLease(
                mode, "lease", generation, Instant.MAX, Set.of(URI.create("https://example.com")));
        var session = new BrowserContracts.SessionView(
                UUID.randomUUID().toString(), owner, BrowserContracts.SessionState.OPEN, lease, List.of());
        return new BrowserCommands.Status(true, Optional.of(session), "当前网页已由你接管");
    }

    private static final class Gateway implements DesktopBrowserGateway {
        private final List<CompletableFuture<BrowserCommands.Status>> reads = new ArrayList<>();
        private final CompletableFuture<BrowserCommands.Status> controlled = new CompletableFuture<>();
        private BrowserClient.Control control;
        private long generation;
        private int commands;
        private int unsubscribed;
        private Runnable invalidated;

        @Override
        public CompletableFuture<BrowserCommands.Status> status(Scope scope) {
            CompletableFuture<BrowserCommands.Status> result = new CompletableFuture<>();
            reads.add(result);
            return result;
        }

        @Override
        public CompletableFuture<BrowserCommands.Status> control(
                Scope scope, BrowserClient.Control next, long revision) {
            commands++;
            control = next;
            generation = revision;
            return controlled;
        }

        @Override
        public CompletableFuture<BrowserCommands.LoginForms> loginForms(Scope scope, long revision) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletableFuture<SiteAccountContracts.AccountProjection> capture(
                Scope scope, long revision, BrowserContracts.CredentialsTarget target) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletableFuture<SiteAccountContracts.AccountProjection> saveLogin(Scope scope, long revision) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletableFuture<BrowserGrantContracts.GrantList> grants(Scope scope) {
            return CompletableFuture.completedFuture(new BrowserGrantContracts.GrantList(List.of()));
        }

        @Override
        public CompletableFuture<BrowserGrantContracts.Preview> previewGrant(Scope scope, URI origin) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletableFuture<BrowserGrantContracts.Grant> confirmGrant(
                Scope scope, BrowserGrantContracts.Preview preview) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletableFuture<BrowserGrantContracts.Grant> revokeGrant(
                Scope scope, BrowserGrantContracts.Grant grant) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public DesktopNotificationSubscription subscribe(Scope scope, Runnable listener) {
            invalidated = listener;
            return () -> unsubscribed++;
        }
    }
}
