package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.component.PlatformComponentFactory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderSecretSectionTest {
    @Test
    void 未配置密钥时直接提供输入并在提交前清空控件和提交后清零数组() {
        FxTestSupport.run(() -> {
            AtomicReference<Fixture> current = new AtomicReference<>();
            AtomicReference<char[]> submitted = new AtomicReference<>();
            Fixture fixture = new Fixture(value -> {
                assertEquals("", current.get().input().getText(), "调用保存边界前应清除控件中的秘密");
                assertArrayEquals("temporary-api-key".toCharArray(), value);
                submitted.set(value);
            });
            current.set(fixture);
            fixture.render(unconfigured());

            assertTrue(visible(fixture.input()));
            assertFalse(fixture.input().isDisabled());
            fixture.input().setText("temporary-api-key");
            fixture.apply().fire();

            assertEquals("", fixture.input().getText());
            assertArrayEquals(new char[17], submitted.get());
        });
    }

    @Test
    void 密钥保存同步失败也不保留明文或调用方数组() {
        FxTestSupport.run(() -> {
            AtomicReference<char[]> submitted = new AtomicReference<>();
            Fixture fixture = new Fixture(value -> {
                submitted.set(value);
                throw new IllegalStateException("保存失败");
            });
            fixture.render(unconfigured());
            fixture.input().setText("temporary");

            assertThrows(IllegalStateException.class, () -> fixture.apply().fire());

            assertEquals("", fixture.input().getText());
            assertArrayEquals(new char[9], submitted.get());
        });
    }

    @Test
    void 已配置密钥只显示状态且轮换和重置均不回显保存值() {
        FxTestSupport.run(() -> {
            Fixture fixture = new Fixture(ignored -> {});
            fixture.render(configured());
            assertFalse(visible(fixture.input()));

            fixture.button("轮换").fire();
            assertTrue(visible(fixture.input()));
            assertEquals("", fixture.input().getText());
            fixture.input().setText("replacement");
            fixture.section.reset();

            assertEquals("", fixture.input().getText());
            assertFalse(visible(fixture.input()));
        });
    }

    @Test
    void 改为无鉴权立即清空并隐藏密钥输入() {
        FxTestSupport.run(() -> {
            Fixture fixture = new Fixture(ignored -> {});
            ProviderSettingsState before = unconfigured();
            fixture.render(before);
            fixture.input().setText("temporary");

            ProviderDraft none = target(
                    before.draft(),
                    ProviderAdapter.OPENAI_COMPATIBLE,
                    before.draft().baseUri(),
                    ProviderAuthentication.NONE);
            fixture.render(withDraft(before, none));

            assertEquals("", fixture.input().getText());
            assertFalse(visible(fixture.input()));
            fixture.render(before);
            assertTrue(visible(fixture.input()));
            assertEquals("", fixture.input().getText());
        });
    }

    @Test
    void 服务切换清除临时密钥避免提交给另一服务() {
        FxTestSupport.run(() -> {
            AtomicReference<char[]> submitted = new AtomicReference<>();
            Fixture fixture = new Fixture(submitted::set);
            ProviderEndpoint first = unconfigured().selected().orElseThrow();
            ProviderEndpoint second = new ProviderEndpoint(
                    "provider-other", 1, first.lifecycle(), first.spec(), first.createdAt(), first.updatedAt());
            fixture.render(state(first, Optional.empty()));
            fixture.input().setText("first-provider-key");

            fixture.render(state(second, Optional.empty()));

            assertEquals("", fixture.input().getText());
            assertTrue(fixture.apply().isDisabled());
            fixture.apply().fire();
            assertNull(submitted.get(), "服务切换后不得提交前一服务的秘密");
        });
    }

    @Test
    void 服务地址或接口协议变更也清理原连接的临时密钥() {
        FxTestSupport.run(() -> {
            Fixture fixture = new Fixture(ignored -> {});
            ProviderSettingsState original = unconfigured();
            ProviderDraft draft = original.draft();
            List<ProviderDraft> changed = List.of(
                    target(draft, draft.adapter(), "https://other.example.test/v1", draft.authentication()),
                    target(draft, ProviderAdapter.ANTHROPIC, draft.baseUri(), draft.authentication()));
            for (ProviderDraft next : changed) {
                fixture.render(original);
                fixture.input().setText("original-connection-key");

                fixture.render(withDraft(original, next));

                assertEquals("", fixture.input().getText());
                assertTrue(fixture.input().isDisabled());
                assertTrue(fixture.hint().getText().contains("保存"));
            }
        });
    }

    @Test
    void 普通重绘和同目标刷新保留已输入内容且忙碌时不可提交() {
        FxTestSupport.run(() -> {
            Fixture fixture = new Fixture(ignored -> {});
            ProviderSettingsState original = unconfigured();
            fixture.render(original);
            fixture.input().setText("still-editing");
            fixture.render(original);
            assertEquals("still-editing", fixture.input().getText());

            fixture.section.render(ProviderCatalogRefresh.loading(original), true);

            assertEquals("still-editing", fixture.input().getText());
            assertTrue(fixture.input().isDisabled());
            assertTrue(fixture.apply().isDisabled());
            fixture.render(original);
            assertEquals("still-editing", fixture.input().getText());
            assertFalse(fixture.apply().isDisabled());
        });
    }

    @Test
    void 未保存归档和引用不可用时禁用密钥写入并给出指引() {
        FxTestSupport.run(() -> {
            Fixture fixture = new Fixture(ignored -> {});
            ProviderSettingsState original = unconfigured();
            ProviderEndpoint archived =
                    TestCoreSettingsFixtures.provider(1, original.draft().toSpec(), ProviderLifecycle.ARCHIVED);
            ProviderEndpoint referenced = configured().selected().orElseThrow();
            List<ProviderSettingsState> unavailable = List.of(
                    ProviderSettingsState.initial(),
                    withDraft(original, original.draft().withLifecycle(ProviderLifecycle.DISABLED)),
                    state(archived, Optional.empty()),
                    state(referenced, Optional.empty()));
            for (ProviderSettingsState next : unavailable) {
                fixture.render(next);
                assertTrue(fixture.input().isDisabled());
                assertTrue(fixture.apply().isDisabled());
                assertFalse(fixture.hint().getText().isBlank());
            }
        });
    }

    @Test
    void 清理未配置编辑器后仍可直接输入新密钥() {
        FxTestSupport.run(() -> {
            Fixture fixture = new Fixture(ignored -> {});
            fixture.render(unconfigured());
            fixture.input().setText("temporary");

            fixture.section.reset();

            assertTrue(visible(fixture.input()));
            assertEquals("", fixture.input().getText());
            assertFalse(fixture.input().isDisabled());
        });
    }

    private static ProviderSettingsState unconfigured() {
        return state(
                TestCoreSettingsFixtures.provider(
                        1, TestCoreSettingsFixtures.providerSpec(Optional.empty()), ProviderLifecycle.ACTIVE),
                Optional.empty());
    }

    private static ProviderSettingsState configured() {
        CredentialRef reference = new CredentialRef("provider", "key-main");
        ProviderEndpoint endpoint = TestCoreSettingsFixtures.provider(
                1, TestCoreSettingsFixtures.providerSpec(Optional.of(reference)), ProviderLifecycle.ACTIVE);
        return state(endpoint, Optional.of(new CredentialMetadata(reference, 1, endpoint.updatedAt())));
    }

    private static ProviderSettingsState state(ProviderEndpoint endpoint, Optional<CredentialMetadata> credential) {
        ProviderDraft draft = ProviderDraft.from(endpoint);
        return new ProviderSettingsState(
                SettingsLoadState.READY,
                List.of(endpoint),
                Optional.of(endpoint),
                draft,
                draft,
                credential,
                Optional.empty(),
                "",
                false,
                1);
    }

    private static ProviderSettingsState withDraft(ProviderSettingsState state, ProviderDraft draft) {
        return new ProviderSettingsState(
                state.phase(),
                state.providers(),
                state.selected(),
                state.baseline(),
                draft,
                state.credential(),
                state.providerStatus(),
                state.message(),
                state.revisionConflict(),
                state.epoch());
    }

    private static ProviderDraft target(
            ProviderDraft draft, ProviderAdapter adapter, String uri, ProviderAuthentication authentication) {
        return new ProviderDraft(
                draft.id(),
                draft.displayName(),
                adapter,
                uri,
                authentication,
                draft.models(),
                draft.credential(),
                draft.timeoutSeconds(),
                draft.maximumRetries(),
                draft.organization(),
                draft.project(),
                draft.apiVersion(),
                draft.reasoningSummary(),
                draft.lifecycle());
    }

    private static boolean visible(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (!current.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static final class Fixture {
        private final ProviderSecretSection section;
        private final VBox root;

        private Fixture(Consumer<char[]> writer) {
            section = new ProviderSecretSection(new PlatformComponentFactory(), writer, () -> {});
            root = new VBox(section.content());
            new Scene(root, 620, 400);
            root.applyCss();
            root.layout();
        }

        private void render(ProviderSettingsState state) {
            section.render(state, false);
        }

        private PasswordField input() {
            return (PasswordField) root.lookup("#providerSecretInput");
        }

        private Button apply() {
            return (Button) root.lookup("#providerSecretApply");
        }

        private Label hint() {
            return (Label) root.lookup("#providerSecretHint");
        }

        private Button button(String text) {
            return root.lookupAll(".button").stream()
                    .filter(Button.class::isInstance)
                    .map(Button.class::cast)
                    .filter(button -> text.equals(button.getText()))
                    .findFirst()
                    .orElseThrow();
        }
    }
}
