package com.javaclaw.desktop.settings;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.EmbeddingBinding;
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
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.protocol.ProviderVerificationRpcContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderSettingsReadNavigationTest {
    @Test
    void 容量读取允许切换模型和服务且旧读取不能覆盖新选择() {
        FxTestSupport.run(() -> {
            Gateway gateway = new Gateway();
            gateway.contextRead = new CompletableFuture<>();
            CompletableFuture<ModelContextLimits> firstRead = gateway.contextRead;
            try (Fixture fixture = new Fixture(gateway)) {
                fixture.models().getSelectionModel().selectFirst();
                ProviderRef first = gateway.contextReference;
                assertFalse(fixture.page.pending());
                assertTrue(fixture.capacity().isDisabled());
                gateway.contextRead = null;
                fixture.models().getSelectionModel().select(1);
                assertEquals("chat-second", gateway.contextReference.model());
                fixture.services().getSelectionModel().select(1);
                assertEquals("provider-other", gateway.contextReference.endpointId());
                firstRead.complete(new ModelContextLimits(first, OptionalLong.of(65536), OptionalLong.empty()));
                assertFalse(fixture.page.pending());
                assertEquals("provider-other", fixture.selectedService().id());
                assertEquals("", fixture.capacity().getText(), "旧读取必须被请求代次隔离");
            }
        });
    }

    @Test
    void 初次添加在目录读取期间排队且不被默认向量读取吞掉() {
        FxTestSupport.run(() -> {
            Gateway gateway = new Gateway();
            gateway.providerRead = new CompletableFuture<>();
            gateway.embeddingRead = new CompletableFuture<>();
            try (Fixture fixture = new Fixture(gateway)) {
                fixture.page.beginCreate();
                assertFalse(fixture.page.pending());
                assertNull(fixture.root.lookup("#providerConfigurationEditor"));
                gateway.providerRead.complete(List.copyOf(gateway.providers));
                assertNotNull(fixture.root.lookup("#providerConfigurationEditor"));
                assertTrue(fixture.page.dirty());
                gateway.embeddingRead.complete(Optional.empty());
                assertNotNull(fixture.root.lookup("#providerConfigurationEditor"));
            }
        });
    }

    @Test
    void 容量保存期间模型与服务选择保持原目标直到回执() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture(new Gateway())) {
                fixture.models().getSelectionModel().selectFirst();
                fixture.capacity().setText("65536");
                fixture.button("#providerContextSaveButton").fire();
                fixture.assertWriteKeepsSelection();
                fixture.gateway.contextWrite.complete(fixture.gateway.submittedLimits);
                assertFalse(fixture.page.pending());
                assertFalse(fixture.page.dirty());
                fixture.models().getSelectionModel().select(1);
                assertEquals("chat-second", fixture.gateway.contextReference.model());
            }
        });
    }

    @Test
    void 默认向量读取允许选择而绑定写入阻止切换直到回执() {
        FxTestSupport.run(() -> {
            Gateway gateway = new Gateway();
            gateway.embeddingRead = new CompletableFuture<>();
            try (Fixture fixture = new Fixture(gateway)) {
                fixture.models().getSelectionModel().select(2);
                assertFalse(fixture.page.pending());
                assertTrue(fixture.bindingButton().isDisabled());
                gateway.embeddingRead.complete(Optional.empty());
                assertFalse(fixture.bindingButton().isDisabled());
                fixture.bindingButton().fire();
                fixture.assertWriteKeepsSelection();
                gateway.embeddingWrite.complete(new EmbeddingBinding(gateway.embeddingReference, 1, Instant.EPOCH));
                assertFalse(fixture.page.pending());
                fixture.models().getSelectionModel().select(3);
                assertEquals(
                        "embedding-second",
                        ((ProviderModelSpec)
                                        fixture.models().getSelectionModel().getSelectedItem())
                                .modelId());
            }
        });
    }

    @Test
    void 计费验证期间拒绝换行和服务且收到回执后保留验证结果() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture(new Gateway())) {
                fixture.models().getSelectionModel().selectFirst();
                fixture.confirmVerification("#providerVerifyChatButton");
                fixture.assertWriteKeepsSelection();
                fixture.gateway.completeVerification(ProviderModelPurpose.CHAT);
                assertFalse(fixture.page.pending());
                assertTrue(((Label) fixture.root.lookup("#providerChatVerificationStatus"))
                        .getText()
                        .contains("验证成功"));
                assertEquals(1, fixture.gateway.providerVerificationCalls);
            }
        });
    }

    @Test
    void 连续选择向量模型时验证身份随当前行更新() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture(new Gateway())) {
                fixture.models().getSelectionModel().select(2);
                fixture.models().getSelectionModel().select(3);
                fixture.confirmVerification("#providerVerifyEmbeddingButton");
                assertEquals("embedding-second", fixture.gateway.verificationReference.model());
                fixture.gateway.completeVerification(ProviderModelPurpose.EMBEDDING);
                assertFalse(fixture.page.pending());
            }
        });
    }

    private static final class Fixture implements AutoCloseable {
        private final Gateway gateway;
        private final ProviderSettingsPage page;
        private final BorderPane root;

        private Fixture(Gateway gateway) {
            this.gateway = gateway;
            page = new ProviderSettingsPage(gateway);
            root = new BorderPane(page.content());
            root.setBottom(page.actionContent().orElseThrow());
            new Scene(root, 1040, 720);
            page.activate();
            root.applyCss();
            root.layout();
        }

        private TableView<?> models() {
            return (TableView<?>) root.lookup("#providerModelsTable");
        }

        private ListView<?> services() {
            return (ListView<?>) root.lookup("#providerServicesList");
        }

        private ProviderEndpoint selectedService() {
            return (ProviderEndpoint) services().getSelectionModel().getSelectedItem();
        }

        private TextField capacity() {
            return (TextField) root.lookup("#providerContextWindowTokens");
        }

        private Button button(String selector) {
            return (Button) root.lookup(selector);
        }

        private Button bindingButton() {
            return root.lookupAll(".button").stream()
                    .filter(Button.class::isInstance)
                    .map(Button.class::cast)
                    .filter(value -> value.getText().equals("设为默认向量模型"))
                    .findFirst()
                    .orElseThrow();
        }

        private void assertWriteKeepsSelection() {
            Object model = models().getSelectionModel().getSelectedItem();
            ProviderEndpoint service = selectedService();
            assertTrue(page.pending());
            models().getSelectionModel().clearAndSelect(1);
            services().getSelectionModel().select(1);
            assertEquals(model, models().getSelectionModel().getSelectedItem());
            assertEquals(service, selectedService());
            assertTrue(page.pending(), "未决写入不得因换行或服务选择丢失回执身份");
        }

        private void confirmVerification(String selector) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Platform.runLater(() -> {
                Parent dialog = Window.getWindows().stream()
                        .filter(window -> window.getScene() != null)
                        .map(window -> window.getScene().getRoot())
                        .filter(candidate -> candidate.lookup(".dialog-confirmation-editor") != null)
                        .findFirst()
                        .orElseThrow();
                try {
                    ((TextField) dialog.lookup(".dialog-confirmation-editor"))
                            .setText(ProviderVerificationRpcContracts.BILLING_CONFIRMATION);
                    ((Button) ((DialogPane) dialog).lookupButton(ButtonType.OK)).fire();
                } catch (Throwable thrown) {
                    failure.set(thrown);
                    dialog.getScene().getWindow().hide();
                }
            });
            assertFalse(button(selector).isDisabled());
            button(selector).fire();
            if (failure.get() != null) {
                throw new AssertionError("内存替身验证确认失败", failure.get());
            }
        }

        @Override
        public void close() {
            page.dispose();
        }
    }

    /** 各类读取和写入分别持有 Future；测试从不调用真实模型或凭据服务。 */
    private static final class Gateway extends ProviderConfigurationTestGateway {
        private CompletableFuture<List<ProviderEndpoint>> providerRead;
        private CompletableFuture<Optional<EmbeddingBinding>> embeddingRead;
        private CompletableFuture<ModelContextLimits> contextRead;
        private final CompletableFuture<ModelContextLimits> contextWrite = new CompletableFuture<>();
        private final CompletableFuture<EmbeddingBinding> embeddingWrite = new CompletableFuture<>();
        private final CompletableFuture<ProviderVerificationResult> verification = new CompletableFuture<>();
        private ProviderRef contextReference;
        private ProviderRef embeddingReference;
        private ProviderRef verificationReference;
        private ModelContextLimits submittedLimits;

        private Gateway() {
            ProviderEndpointSpec spec = new ProviderEndpointSpec(
                    "测试服务",
                    ProviderAdapter.OPENAI_COMPATIBLE,
                    Optional.of(URI.create("http://127.0.0.1:19001/v1")),
                    ProviderAuthentication.NONE,
                    List.of(
                            model("chat-first", ProviderModelPurpose.CHAT),
                            model("chat-second", ProviderModelPurpose.CHAT),
                            model("embedding-first", ProviderModelPurpose.EMBEDDING),
                            model("embedding-second", ProviderModelPurpose.EMBEDDING)),
                    Optional.empty(),
                    Duration.ofSeconds(30),
                    0,
                    ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
            providers.set(0, TestCoreSettingsFixtures.provider(1, spec, ProviderLifecycle.ACTIVE));
            providers.add(new ProviderEndpoint(
                    "provider-other", 1, ProviderLifecycle.ACTIVE, spec, Instant.EPOCH, Instant.EPOCH));
        }

        private static ProviderModelSpec model(String id, ProviderModelPurpose purpose) {
            return new ProviderModelSpec(id, id, Set.of(purpose), OptionalInt.empty());
        }

        @Override
        public CompletionStage<List<ProviderEndpoint>> providers() {
            return providerRead == null ? super.providers() : providerRead;
        }

        @Override
        public CompletionStage<Optional<EmbeddingBinding>> embeddingBinding() {
            return embeddingRead == null ? super.embeddingBinding() : embeddingRead;
        }

        @Override
        public CompletionStage<ModelContextLimits> modelContextLimits(ProviderRef provider) {
            contextReference = provider;
            return contextRead == null
                    ? CompletableFuture.completedFuture(ModelContextLimits.unknown(provider))
                    : contextRead;
        }

        @Override
        public CompletionStage<ModelContextLimits> updateModelContextLimits(
                ModelContextLimits limits, CommandOptions options) {
            submittedLimits = limits;
            return contextWrite;
        }

        @Override
        public CompletionStage<EmbeddingBinding> bindEmbedding(ProviderRef provider, CommandOptions options) {
            embeddingReference = provider;
            return embeddingWrite;
        }

        @Override
        public CompletionStage<ProviderVerificationResult> verifyProviderRoundTrip(
                ProviderRef provider,
                ProviderModelPurpose purpose,
                boolean billingConfirmed,
                String confirmation,
                CommandOptions options) {
            providerVerificationCalls++;
            verificationReference = provider;
            return verification;
        }

        private void completeVerification(ProviderModelPurpose purpose) {
            verification.complete(TestCoreSettingsFixtures.verification(verificationReference, purpose, Instant.EPOCH));
        }
    }
}
