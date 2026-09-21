package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.ModelContextLimits;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderSettingsDraftProtectionTest {
    @Test
    void 容量草稿阻止同服务内模型切换并在丢弃后恢复正常选择() {
        FxTestSupport.run(() -> {
            Fixture fixture = new Fixture();
            try {
                fixture.models().getSelectionModel().selectFirst();
                Object first = fixture.models().getSelectionModel().getSelectedItem();
                ProviderRef original = fixture.gateway.contextProvider;
                fixture.capacity().setText("65536");
                assertTrue(fixture.page.dirty());

                fixture.models().getSelectionModel().select(1);

                assertEquals(first, fixture.models().getSelectionModel().getSelectedItem());
                assertEquals(0, fixture.models().getSelectionModel().getSelectedIndex());
                assertEquals(
                        List.of(first), fixture.models().getSelectionModel().getSelectedItems());
                fixture.models().getSelectionModel().clearAndSelect(1);
                assertEquals(first, fixture.models().getSelectionModel().getSelectedItem());
                assertEquals(0, fixture.models().getSelectionModel().getSelectedIndex());
                assertEquals(
                        List.of(first), fixture.models().getSelectionModel().getSelectedItems());
                assertEquals(original, fixture.gateway.contextProvider, "未切换模型时不应读取其他模型容量");
                assertEquals("65536", fixture.capacity().getText());
                fixture.page.discardDraft();
                fixture.models().getSelectionModel().select(1);
                assertEquals("second-model", fixture.gateway.contextProvider.model());
                assertEquals("", fixture.capacity().getText());
                assertFalse(fixture.page.dirty());
                assertEquals(0, fixture.gateway.contextWrites);
            } finally {
                fixture.page.dispose();
            }
        });
    }

    @Test
    void 容量草稿阻止服务切换时恢复列表选择且丢弃动作清理所有草稿() {
        FxTestSupport.run(() -> {
            Fixture fixture = new Fixture();
            try {
                ProviderEndpoint first = (ProviderEndpoint)
                        fixture.providers().getSelectionModel().getSelectedItem();
                fixture.models().getSelectionModel().selectFirst();
                fixture.capacity().setText("65536");
                assertTrue(fixture.page.dirty());

                fixture.providers().getSelectionModel().select(1);
                assertEquals(first, fixture.providers().getSelectionModel().getSelectedItem());
                assertEquals("65536", fixture.capacity().getText());

                fixture.page.discardDraft();
                assertEquals("", fixture.capacity().getText());
                assertFalse(fixture.page.dirty(), "离页确认必须同时丢弃模型容量草稿");
                fixture.providers().getSelectionModel().select(1);
                assertEquals(
                        fixture.gateway.second,
                        fixture.providers().getSelectionModel().getSelectedItem());
                assertEquals(0, fixture.gateway.contextWrites);
            } finally {
                fixture.page.dispose();
            }
        });
    }

    private static final class Fixture {
        private final Gateway gateway = new Gateway();
        private final ProviderSettingsPage page = new ProviderSettingsPage(gateway);
        private final BorderPane root = new BorderPane(page.content());

        private Fixture() {
            root.setBottom(page.actionContent().orElseThrow());
            new Scene(root, 1040, 720);
            page.activate();
            root.applyCss();
            root.layout();
        }

        private ListView<?> providers() {
            return (ListView<?>) root.lookup("#providerServicesList");
        }

        private TableView<?> models() {
            return (TableView<?>) root.lookup("#providerModelsTable");
        }

        private TextField capacity() {
            return (TextField) root.lookup("#providerContextWindowTokens");
        }
    }

    private static final class Gateway extends TestCoreSettingsGateway {
        private final CompletableFuture<ProviderEndpoint> write = new CompletableFuture<>();
        private final ProviderEndpoint second;
        private ProviderRef contextProvider;
        private int contextWrites;

        private Gateway() {
            ProviderEndpoint previous = providers.getFirst();
            ProviderEndpointSpec spec = ProviderDraft.from(previous)
                    .withModels(List.of(
                            previous.spec().models().getFirst(),
                            new ProviderModelSpec(
                                    "second-model", "第二模型", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty())))
                    .toSpec();
            ProviderEndpoint first = new ProviderEndpoint(
                    previous.id(),
                    previous.revision(),
                    previous.lifecycle(),
                    spec,
                    previous.createdAt(),
                    previous.updatedAt());
            providers.set(0, first);
            second = new ProviderEndpoint(
                    "provider-other", 1, first.lifecycle(), first.spec(), first.createdAt(), first.updatedAt());
            providers.add(second);
        }

        @Override
        public CompletionStage<ModelContextLimits> modelContextLimits(ProviderRef provider) {
            contextProvider = provider;
            return CompletableFuture.completedFuture(ModelContextLimits.unknown(provider));
        }

        @Override
        public CompletionStage<ModelContextLimits> updateModelContextLimits(
                ModelContextLimits limits, CommandOptions options) {
            contextWrites++;
            return CompletableFuture.completedFuture(limits);
        }

        @Override
        public CompletionStage<ProviderEndpoint> updateProvider(
                String id, ProviderEndpointSpec spec, ProviderLifecycle lifecycle, CommandOptions options) {
            return write;
        }
    }
}
