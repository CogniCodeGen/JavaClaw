package com.javaclaw.desktop.settings;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.appearance.AppearancePreferenceStore;
import com.javaclaw.desktop.appearance.AppearancePreferences;
import com.javaclaw.desktop.appearance.DesktopAppearanceManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementCenterInteractionTest {
    @Test
    void 聊天首次新增在模型服务目录读取完成后进入编辑器() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture("appearance")) {
                fixture.gateway.providerReading = new CompletableFuture<>();

                fixture.center.showProviderCreation(fixture.owner);

                fixture.assertPage("模型服务");
                fixture.layout();
                assertNull(fixture.window.getScene().lookup("#providerConfigurationEditor"));
                assertNull(fixture.window.getScene().lookup("#providerWizardName"));
                fixture.gateway.providerReading.complete(List.copyOf(fixture.gateway.providers));
                fixture.layout();
                assertTrue(fixture.window.getScene().lookup("#providerWizardName") instanceof TextField);
                assertNull(fixture.gateway.lastProviderCreateOptions);
            }
        });
    }

    @Test
    void 聊天新增入口进入统一设置编辑且再次新增保留已有草稿() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture("appearance")) {
                fixture.center.showProviderCreation(fixture.owner);
                fixture.assertPage("模型服务");
                fixture.layout();
                TextField name = (TextField) fixture.window.getScene().lookup("#providerWizardName");
                name.setText("保留这份模型草稿");

                fixture.center.showProviderCreation(fixture.owner);
                fixture.layout();

                assertSame(name, fixture.window.getScene().lookup("#providerWizardName"));
                assertEquals("保留这份模型草稿", name.getText());
                assertNull(fixture.gateway.lastProviderCreateOptions);
            }
        });
    }

    @Test
    void 其他页面草稿阻止聊天新增时不会在隐藏模型页创建配置() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture("roles")) {
                fixture.roleName().setText("原页面草稿");

                fixture.center.showProviderCreation(fixture.owner);

                fixture.assertPage("Agent Studio");
                assertEquals("原页面草稿", fixture.roleName().getText());
                fixture.button("放弃更改").fire();
                fixture.center.show(fixture.owner, "providers");
                fixture.assertPage("模型服务");
                assertNull(fixture.window.getScene().lookup("#providerWizardName"));
                assertNull(fixture.gateway.lastProviderCreateOptions);
            }
        });
    }

    @Test
    void 页面自行管理视口时不包装外层滚动且普通页面仍保留滚动位置() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture("appearance")) {
                ScrollPane ordinary = (ScrollPane) fixture.pageSlot().getCenter();
                ordinary.setVvalue(0.65);

                fixture.center.show(fixture.owner, "providers");

                assertFalse(fixture.pageSlot().getCenter() instanceof ScrollPane);
                fixture.center.show(fixture.owner, "appearance");
                assertSame(ordinary, fixture.pageSlot().getCenter());
                assertEquals(0.65, ordinary.getVvalue(), 0.001);
            }
        });
    }

    @Test
    void 在途操作即使没有草稿也阻止导航和关闭且搜索不改变当前页面() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture("appearance")) {
                fixture.gateway.reading = new CompletableFuture<>();
                fixture.center.show(fixture.owner, "roles");
                Object selected = fixture.navigation().getSelectionModel().getSelectedItem();

                fixture.center.show(fixture.owner, "appearance");
                fixture.assertPage("Agent Studio");
                fixture.closePendingNotice();
                fixture.navigation().getSelectionModel().selectFirst();
                fixture.assertPage("Agent Studio");
                assertSame(selected, fixture.navigation().getSelectionModel().getSelectedItem());
                fixture.closePendingNotice();
                fixture.center.showProviderCreation(fixture.owner);
                fixture.assertPage("Agent Studio");
                fixture.closePendingNotice();
                fixture.search().setText("外观");
                fixture.assertPage("Agent Studio");
                fixture.search().clear();
                assertSame(selected, fixture.navigation().getSelectionModel().getSelectedItem());

                fixture.center.close();
                assertTrue(fixture.center.isShowing());
                fixture.closePendingNotice();
                fixture.window.fireEvent(new WindowEvent(fixture.window, WindowEvent.WINDOW_CLOSE_REQUEST));
                assertTrue(fixture.center.isShowing());
                fixture.closePendingNotice();

                fixture.gateway.reading.complete(List.copyOf(fixture.gateway.profiles));
                fixture.center.show(fixture.owner, "appearance");
                fixture.assertPage("外观");
                fixture.center.show(fixture.owner, "providers");
                assertNull(fixture.window.getScene().lookup("#providerWizardName"));
                fixture.center.close();
                assertFalse(fixture.center.isShowing());
            }
        });
    }

    @Test
    void 保存与草稿同时存在时离页提示不覆盖在途保存状态() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture("roles")) {
                TextField name = fixture.roleName();
                name.setText("保存中的名称");
                fixture.button("保存 Agent").fire();
                assertTrue(fixture.hasLabel("正在保存 Agent…"));

                fixture.center.show(fixture.owner, "appearance");

                fixture.assertPage("Agent Studio");
                assertTrue(fixture.hasLabel("正在保存 Agent…"));
                assertEquals("保存中的名称", name.getText());
                fixture.closePendingNotice();
                fixture.center.close();
                assertTrue(fixture.center.isShowing());
                assertTrue(fixture.hasLabel("正在保存 Agent…"));
                fixture.closePendingNotice();
            }
        });
    }

    /** 使用真实管理窗口和可控内存角色回执，避免离页测试依赖远端服务。 */
    private static final class Fixture implements AutoCloseable {
        private final DeferredGateway gateway = new DeferredGateway();
        private final RoleSettingsTestGateway rolePanels = new RoleSettingsTestGateway();
        private final DesktopPresenter desktop = new DesktopPresenter(
                ignored -> {
                    throw new IOException("窗口交互测试不连接服务");
                },
                Platform::runLater,
                Clock.systemUTC());
        private final Stage owner = new Stage();
        private final ManagementCenterWindow center;
        private final Stage window;

        private Fixture(String initialPage) {
            ManagementSettingsGateways base = SdkManagementSettingsGateways.create(desktop);
            ManagementSettingsGateways gateways = new ManagementSettingsGateways(
                    gateway,
                    rolePanels.prompts,
                    rolePanels.optimization,
                    base.mcp(),
                    base.instructions(),
                    base.bundles(),
                    base.builtins(),
                    base.jobs(),
                    base.coding(),
                    base.schedules(),
                    base.extensions(),
                    Optional::empty);
            center = new ManagementCenterWindow(
                    new DesktopAppearanceManager(new AppearanceStore()), gateways, new WindowStore());
            owner.setScene(new Scene(new VBox(), 600, 400));
            owner.show();
            center.show(owner, initialPage);
            window = Window.getWindows().stream()
                    .filter(Stage.class::isInstance)
                    .map(Stage.class::cast)
                    .filter(candidate -> candidate.getOwner() == owner)
                    .findFirst()
                    .orElseThrow();
            window.getScene().getRoot().applyCss();
            window.getScene().getRoot().layout();
        }

        private BorderPane pageSlot() {
            return (BorderPane) window.getScene().lookup(".settings-content-area");
        }

        private void layout() {
            window.getScene().getRoot().applyCss();
            window.getScene().getRoot().layout();
        }

        private ListView<?> navigation() {
            return (ListView<?>) window.getScene().lookup(".management-navigation-list");
        }

        private TextField search() {
            return (TextField) window.getScene().lookup(".settings-search-field");
        }

        private TextField roleName() {
            return window.getScene().getRoot().lookupAll(".text-field").stream()
                    .filter(TextField.class::isInstance)
                    .map(TextField.class::cast)
                    .filter(field -> "Agent 名称".equals(field.getPromptText()))
                    .findFirst()
                    .orElseThrow();
        }

        private void assertPage(String title) {
            assertEquals(title, ((Label) window.getScene().lookup(".settings-crumb-cur")).getText());
        }

        private boolean hasLabel(String text) {
            return window.getScene().getRoot().lookupAll(".label").stream()
                    .filter(Label.class::isInstance)
                    .map(Label.class::cast)
                    .anyMatch(label -> text.equals(label.getText()));
        }

        private Button button(String text) {
            return window.getScene().getRoot().lookupAll(".button").stream()
                    .filter(Button.class::isInstance)
                    .map(Button.class::cast)
                    .filter(button -> text.equals(button.getText()))
                    .findFirst()
                    .orElseThrow();
        }

        private void closePendingNotice() {
            DialogPane dialog = Window.getWindows().stream()
                    .filter(candidate -> candidate.getScene() != null)
                    .map(candidate -> candidate.getScene().getRoot())
                    .filter(DialogPane.class::isInstance)
                    .map(DialogPane.class::cast)
                    .filter(pane -> "暂时无法离开当前页面".equals(pane.getHeaderText()))
                    .findFirst()
                    .orElseThrow();
            ((Button) dialog.lookupButton(ButtonType.OK)).fire();
        }

        @Override
        public void close() {
            center.dispose();
            owner.hide();
            rolePanels.configurationEvents.close();
            try {
                desktop.close();
            } catch (Exception failure) {
                throw new AssertionError("关闭测试 SDK 失败", failure);
            }
        }
    }

    private static final class DeferredGateway extends TestCoreSettingsGateway {
        private CompletableFuture<List<AgentRole>> reading;
        private CompletableFuture<List<ProviderEndpoint>> providerReading;
        private final CompletableFuture<AgentRole> saving = new CompletableFuture<>();

        private DeferredGateway() {
            profiles.add(RoleSettingsTestGateway.role("reviewer", 1));
        }

        @Override
        public CompletionStage<List<AgentRole>> roles() {
            return reading == null ? super.roles() : reading;
        }

        @Override
        public CompletionStage<List<ProviderEndpoint>> providers() {
            return providerReading == null ? super.providers() : providerReading;
        }

        @Override
        public CompletionStage<Boolean> providerConfigurationSupported() {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<AgentRole> updateRole(
                String id, AgentRoleSpec spec, RoleLifecycle lifecycle, CommandOptions options) {
            return saving;
        }
    }

    private static final class AppearanceStore implements AppearancePreferenceStore {
        @Override
        public AppearancePreferences load() {
            return AppearancePreferences.defaults();
        }

        @Override
        public void save(AppearancePreferences preferences) {}
    }

    private static final class WindowStore implements ManagementWindowPreferenceStore {
        @Override
        public ManagementWindowPreferences load() {
            return ManagementWindowPreferences.defaults();
        }

        @Override
        public void save(ManagementWindowPreferences preferences) {}
    }
}
