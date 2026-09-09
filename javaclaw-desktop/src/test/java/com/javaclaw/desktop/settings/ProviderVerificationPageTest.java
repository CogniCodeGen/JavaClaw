package com.javaclaw.desktop.settings;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.ModelContextLimits;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderVerificationResult;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopConfigurationChange;
import com.javaclaw.desktop.DesktopConfigurationEvents;
import com.javaclaw.desktop.DesktopNotificationSubscription;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.protocol.ProviderVerificationRpcContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderVerificationPageTest {
    @Test
    void 取消确认不会发送模型请求且关闭窗口后的刷新可以完成() {
        Fixture fixture = FxTestSupport.call(() -> new Fixture(true));
        CompletableFuture<List<ProviderEndpoint>> refreshedCatalog = new CompletableFuture<>();
        AtomicBoolean refreshTriggered = new AtomicBoolean();
        try {
            FxTestSupport.run(() -> {
                fixture.gateway.completeContext();
                fixture.gateway.nextProviderRead = refreshedCatalog;
                fixture.completeConfirmation(
                        ProviderModelPurpose.CHAT,
                        dialog -> {
                            assertSame(fixture.owner.orElseThrow(), ((Stage) dialog).getOwner());
                            dialog.addEventHandler(WindowEvent.WINDOW_HIDING, event -> {
                                refreshTriggered.set(true);
                                fixture.page.activate();
                            });
                        },
                        false);
                assertTrue(refreshTriggered.get());
                assertEquals(0, fixture.gateway.calls.size());
                assertFalse(refreshedCatalog.isDone());
            });
            FxTestSupport.await(() -> FxTestSupport.call(() -> fixture.gateway.providerReads > 1));
            FxTestSupport.run(() -> refreshedCatalog.complete(List.copyOf(fixture.gateway.providers)));
            FxTestSupport.await(() -> FxTestSupport.call(() -> !fixture.page.pending()));
            FxTestSupport.run(() -> {
                assertEquals(0, fixture.gateway.calls.size());
                assertFalse(fixture.chatButton().isDisabled());
                assertFalse(fixture.chatStatus().contains("验证成功"));
            });
        } finally {
            FxTestSupport.run(() -> {
                fixture.page.dispose();
                fixture.owner.ifPresent(Stage::close);
            });
        }
    }

    @Test
    void 确认窗口关闭时所属页面重新加载仍只提交一次已确认的精确模型请求() {
        Fixture fixture = FxTestSupport.call(() -> new Fixture(true));
        AtomicBoolean refreshTriggered = new AtomicBoolean();
        CompletableFuture<List<ProviderEndpoint>> refreshedCatalog = new CompletableFuture<>();
        try {
            FxTestSupport.run(() -> {
                fixture.gateway.completeContext();
                fixture.gateway.nextProviderRead = refreshedCatalog;
                fixture.confirmAndSend(ProviderModelPurpose.CHAT, dialog -> {
                    assertSame(fixture.owner.orElseThrow(), ((Stage) dialog).getOwner());
                    // 模拟所属窗口恢复焦点时的刷新，不依赖操作系统的实际焦点调度。
                    dialog.addEventHandler(WindowEvent.WINDOW_HIDING, event -> {
                        refreshTriggered.set(true);
                        fixture.page.activate();
                    });
                });
                assertTrue(refreshTriggered.get());
                assertFalse(refreshedCatalog.isDone());
                fixture.assertRequest(ProviderModelPurpose.CHAT);
                assertTrue(fixture.page.pending());
                assertTrue(fixture.chatStatus().contains("正在执行"));
                fixture.gateway.completeVerification();
            });
            FxTestSupport.await(() -> FxTestSupport.call(() -> fixture.gateway.providerReads > 1));
            FxTestSupport.run(() -> refreshedCatalog.complete(List.copyOf(fixture.gateway.providers)));
            FxTestSupport.await(
                    () -> FxTestSupport.call(() -> fixture.chatStatus().contains("验证成功")));
            FxTestSupport.run(() -> {
                assertEquals(1, fixture.gateway.calls.size());
                assertFalse(fixture.page.pending());
                assertFalse(fixture.chatButton().isDisabled());
            });
        } finally {
            FxTestSupport.run(() -> {
                fixture.page.dispose();
                fixture.owner.ifPresent(Stage::close);
            });
        }
    }

    @Test
    void 已保存配置等待容量读取完成后自动启用验证且刷新不会重复发送或丢失结果() {
        Fixture fixture = FxTestSupport.call(Fixture::new);
        try {
            FxTestSupport.run(() -> {
                assertFalse(fixture.page.dirty());
                assertTrue(fixture.page.pending());
                assertTrue(fixture.chatButton().isDisabled());
                assertTrue(fixture.chatStatus().contains("请稍候"));
                assertFalse(fixture.chatStatus().contains("草稿"));
                assertEquals(0, fixture.gateway.calls.size());
                fixture.gateway.completeContext();
            });
            FxTestSupport.await(
                    () -> FxTestSupport.call(() -> !fixture.chatButton().isDisabled()));
            FxTestSupport.run(() -> {
                assertFalse(fixture.embeddingButton().isDisabled());
                fixture.confirmAndSend(ProviderModelPurpose.CHAT);
                fixture.assertRequest(ProviderModelPurpose.CHAT);
                assertTrue(fixture.page.pending());
                assertTrue(fixture.chatButton().isDisabled());
                assertTrue(fixture.chatStatus().contains("正在执行"));
                fixture.gateway.changed();
                fixture.page.activate();
            });
            FxTestSupport.run(() -> {
                fixture.chatButton().fire();
                assertTrue(fixture.page.pending());
                assertTrue(fixture.chatStatus().contains("正在执行"));
                assertEquals(1, fixture.gateway.calls.size());
                assertFalse(fixture.gateway.verification.isDone());
                fixture.gateway.completeVerification();
            });
            FxTestSupport.await(() -> FxTestSupport.call(() -> fixture.gateway.providerReads > 1));
            FxTestSupport.run(() -> {
                assertFalse(fixture.page.pending());
                assertFalse(fixture.chatButton().isDisabled());
                assertTrue(fixture.chatStatus().contains("验证成功"));
                assertTrue(fixture.chatStatus().contains("输入/输出令牌 2/1"));
                assertEquals(1, fixture.gateway.calls.size());
            });
        } finally {
            FxTestSupport.run(fixture.page::dispose);
        }
    }

    @Test
    void 向量验证使用独立模型和用途并显示向量校验结果() {
        Fixture fixture = FxTestSupport.call(Fixture::new);
        try {
            FxTestSupport.run(fixture.gateway::completeContext);
            FxTestSupport.await(
                    () -> FxTestSupport.call(() -> !fixture.embeddingButton().isDisabled()));
            FxTestSupport.run(() -> {
                fixture.confirmAndSend(ProviderModelPurpose.EMBEDDING);
                fixture.assertRequest(ProviderModelPurpose.EMBEDDING);
                assertTrue(fixture.embeddingButton().isDisabled());
                fixture.embeddingButton().fire();
                assertEquals(1, fixture.gateway.calls.size());
                fixture.gateway.completeVerification();
            });
            FxTestSupport.await(
                    () -> FxTestSupport.call(() -> fixture.embeddingStatus().contains("验证成功")));
            FxTestSupport.run(() -> {
                assertTrue(fixture.embeddingStatus().contains("已校验单向量及维度"));
                assertFalse(fixture.chatStatus().contains("验证成功"));
                assertFalse(fixture.embeddingButton().isDisabled());
            });
        } finally {
            FxTestSupport.run(fixture.page::dispose);
        }
    }

    @Test
    void 真实连接草稿阻止验证并提示保存草稿() {
        Fixture fixture = FxTestSupport.call(Fixture::new);
        try {
            FxTestSupport.run(() -> {
                fixture.gateway.completeContext();
                TextField name = fixture.root.lookupAll(".text-field").stream()
                        .filter(TextField.class::isInstance)
                        .map(TextField.class::cast)
                        .filter(field -> "用户可见名称".equals(field.getPromptText()))
                        .findFirst()
                        .orElseThrow();
                name.setText("尚未保存的连接名称");
                fixture.assertDraftBlocksVerification();
                fixture.page.discardDraft();
                assertFalse(fixture.page.dirty());
                assertFalse(fixture.chatButton().isDisabled());
            });
        } finally {
            FxTestSupport.run(fixture.page::dispose);
        }
    }

    private static final class Fixture {
        private final Gateway gateway = new Gateway();
        private final ProviderSettingsPage page = new ProviderSettingsPage(gateway);
        private final BorderPane root = new BorderPane(page.content());
        private final Optional<Stage> owner;

        private Fixture() {
            this(false);
        }

        private Fixture(boolean owned) {
            root.setBottom(page.actionContent().orElseThrow());
            Scene scene = new Scene(root, 1040, 720);
            owner = owned ? Optional.of(new Stage()) : Optional.empty();
            owner.ifPresent(stage -> {
                stage.setScene(scene);
                stage.show();
            });
            page.activate();
            root.applyCss();
            root.layout();
            ((TableView<?>) root.lookup(".platform-data-table"))
                    .getSelectionModel()
                    .selectFirst();
        }

        private Button chatButton() {
            return (Button) root.lookup("#providerVerifyChatButton");
        }

        private Button embeddingButton() {
            return (Button) root.lookup("#providerVerifyEmbeddingButton");
        }

        private String chatStatus() {
            return ((Label) root.lookup("#providerChatVerificationStatus")).getText();
        }

        private String embeddingStatus() {
            return ((Label) root.lookup("#providerEmbeddingVerificationStatus")).getText();
        }

        private void assertRequest(ProviderModelPurpose purpose) {
            assertEquals(1, gateway.calls.size());
            Request request = gateway.calls.getFirst();
            assertEquals(gateway.reference(purpose), request.provider());
            assertEquals(purpose, request.purpose());
            assertTrue(request.billingConfirmed());
            assertEquals(ProviderVerificationRpcContracts.BILLING_CONFIRMATION, request.confirmation());
            assertEquals(
                    request.provider().endpointRevision(), request.options().expectedRevision());
        }

        private void assertDraftBlocksVerification() {
            assertTrue(page.dirty());
            assertTrue(chatButton().isDisabled());
            assertTrue(embeddingButton().isDisabled());
            assertTrue(chatStatus().contains("草稿"));
            assertFalse(chatStatus().contains("请稍候"));
            assertEquals(0, gateway.calls.size());
        }

        private void confirmAndSend(ProviderModelPurpose purpose) {
            confirmAndSend(purpose, ignored -> {});
        }

        private void confirmAndSend(ProviderModelPurpose purpose, Consumer<Window> configureDialog) {
            completeConfirmation(purpose, configureDialog, true);
        }

        private void completeConfirmation(
                ProviderModelPurpose purpose, Consumer<Window> configureDialog, boolean accept) {
            Button button = purpose == ProviderModelPurpose.CHAT ? chatButton() : embeddingButton();
            assertFalse(button.isDisabled());
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Platform.runLater(() -> confirmDialog(failure, configureDialog, accept));
            button.fire();
            if (failure.get() != null) {
                throw new AssertionError("验证确认窗口未正确提交", failure.get());
            }
        }

        private void confirmDialog(
                AtomicReference<Throwable> failure, Consumer<Window> configureDialog, boolean accept) {
            Parent dialog = Window.getWindows().stream()
                    .filter(window -> window.getScene() != null)
                    .map(window -> window.getScene().getRoot())
                    .filter(candidate -> candidate.lookup(".dialog-confirmation-editor") != null)
                    .findFirst()
                    .orElseThrow();
            try {
                configureDialog.accept(dialog.getScene().getWindow());
                TextField editor = (TextField) dialog.lookup(".dialog-confirmation-editor");
                Button submit = (Button) ((DialogPane) dialog).lookupButton(accept ? ButtonType.OK : ButtonType.CANCEL);
                assertEquals(0, gateway.calls.size());
                if (accept) {
                    assertTrue(submit.isDisabled());
                    editor.setText(ProviderVerificationRpcContracts.BILLING_CONFIRMATION);
                    assertFalse(submit.isDisabled());
                }
                submit.fire();
            } catch (Throwable thrown) {
                failure.set(thrown);
                dialog.getScene().getWindow().hide();
            }
        }
    }

    /** 容量读取与验证响应分别由测试推进，避免把本地状态检查误计为模型请求。 */
    private static final class Gateway extends TestCoreSettingsGateway {
        private final DesktopConfigurationEvents events = new DesktopConfigurationEvents();
        private final CompletableFuture<ModelContextLimits> context = new CompletableFuture<>();
        private final CompletableFuture<ProviderVerificationResult> verification = new CompletableFuture<>();
        private final List<Request> calls = new ArrayList<>();
        private ProviderRef contextReference;
        private int providerReads;
        private CompletableFuture<List<ProviderEndpoint>> nextProviderRead;

        private Gateway() {
            ProviderEndpointSpec spec = new ProviderEndpointSpec(
                    "Local verification fake",
                    ProviderAdapter.OPENAI_COMPATIBLE,
                    Optional.of(URI.create("http://127.0.0.1:19001/v1")),
                    ProviderAuthentication.NONE,
                    List.of(
                            new ProviderModelSpec(
                                    "fake-chat", "测试对话", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty()),
                            new ProviderModelSpec(
                                    "fake-embedding",
                                    "测试向量",
                                    Set.of(ProviderModelPurpose.EMBEDDING),
                                    OptionalInt.of(3))),
                    Optional.empty(),
                    Duration.ofSeconds(30),
                    0,
                    ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
            providers.set(0, TestCoreSettingsFixtures.provider(7, spec, ProviderLifecycle.ACTIVE));
        }

        @Override
        public CompletionStage<List<ProviderEndpoint>> providers() {
            providerReads++;
            if (nextProviderRead != null) {
                CompletableFuture<List<ProviderEndpoint>> response = nextProviderRead;
                nextProviderRead = null;
                return response;
            }
            return super.providers();
        }

        @Override
        public DesktopNotificationSubscription onConfigurationChanged(Consumer<DesktopConfigurationChange> listener) {
            return events.subscribe(listener);
        }

        @Override
        public CompletionStage<ModelContextLimits> modelContextLimits(ProviderRef provider) {
            contextReference = provider;
            return context;
        }

        @Override
        public CompletionStage<ProviderVerificationResult> verifyProviderRoundTrip(
                ProviderRef provider,
                ProviderModelPurpose purpose,
                boolean billingConfirmed,
                String confirmation,
                CommandOptions options) {
            calls.add(new Request(provider, purpose, billingConfirmed, confirmation, options));
            return verification;
        }

        private ProviderRef reference(ProviderModelPurpose purpose) {
            return new ProviderRef(
                    "provider-main", 7, purpose == ProviderModelPurpose.CHAT ? "fake-chat" : "fake-embedding");
        }

        private void completeContext() {
            assertEquals(reference(ProviderModelPurpose.CHAT), contextReference);
            context.complete(ModelContextLimits.unknown(contextReference));
        }

        private void completeVerification() {
            Request request = calls.getFirst();
            verification.complete(TestCoreSettingsFixtures.verification(
                    request.provider(), request.purpose(), Instant.parse("2026-09-08T01:00:00Z")));
        }

        private void changed() {
            events.publish(new DesktopConfigurationChange(
                    DesktopConfigurationChange.Kind.PROVIDERS, Optional.empty(), Optional.empty()));
        }
    }

    private record Request(
            ProviderRef provider,
            ProviderModelPurpose purpose,
            boolean billingConfirmed,
            String confirmation,
            CommandOptions options) {}
}
