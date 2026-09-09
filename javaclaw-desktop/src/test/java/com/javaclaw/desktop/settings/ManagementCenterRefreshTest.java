package com.javaclaw.desktop.settings;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.DesktopConfigurationChange;
import com.javaclaw.desktop.DesktopConfigurationEvents;
import com.javaclaw.desktop.DesktopNotificationSubscription;
import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.appearance.AppearancePreferenceStore;
import com.javaclaw.desktop.appearance.AppearancePreferences;
import com.javaclaw.desktop.appearance.DesktopAppearanceManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementCenterRefreshTest {
    @Test
    void 新增登记自动出现且重开设置更新名称并保持独立选择() {
        FxTestSupport.run(() -> {
            try (DesktopPresenter desktop = new DesktopPresenter(
                    ignored -> {
                        throw new IOException("测试仅使用工作区内存目录");
                    },
                    Platform::runLater,
                    Clock.systemUTC())) {
                EventGateway core = new EventGateway();
                Workspace original = core.workspaceSettings.catalog.getFirst();
                AtomicReference<WorkspaceId> mainSelection = new AtomicReference<>(original.id());
                ManagementSettingsGateways gateways = gateways(desktop, core, mainSelection);
                ManagementCenterWindow center = new ManagementCenterWindow(appearance(), gateways, new WindowStore());
                Stage owner = new Stage();
                owner.setScene(new Scene(new VBox(), 600, 400));
                owner.show();
                try {
                    center.show(owner, "appearance");
                    ComboBox<?> selector = selector();
                    assertEquals(1, selector.getItems().size());
                    Workspace other = new Workspace(
                            WorkspaceId.parse("740a130c-806e-4c2d-a7c1-b636580f7429"),
                            "新增工作区",
                            Path.of("/tmp/new-workspace"),
                            original.lifecycle(),
                            1,
                            original.createdAt(),
                            original.updatedAt());
                    core.workspaceSettings.catalog.add(other);
                    mainSelection.set(other.id());

                    core.changed();

                    assertEquals(2, selector.getItems().size());
                    assertEquals(original.id(), ((Workspace) selector.getValue()).id());
                    verifyReopen(center, owner, core, original, selector);
                    center.dispose();
                    int disposedReads = core.workspaceSettings.reads;
                    core.changed();
                    assertEquals(disposedReads, core.workspaceSettings.reads);
                } finally {
                    center.dispose();
                    owner.hide();
                    core.events.close();
                }
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });
    }

    private static void verifyReopen(
            ManagementCenterWindow center, Stage owner, EventGateway core, Workspace original, ComboBox<?> selector) {
        center.close();
        Workspace renamed = new Workspace(
                original.id(),
                "更新的工作区名称",
                original.root(),
                original.lifecycle(),
                original.revision() + 1,
                original.createdAt(),
                original.updatedAt());
        core.workspaceSettings.catalog.set(0, renamed);
        int before = core.workspaceSettings.reads;
        core.changed();
        assertEquals(before, core.workspaceSettings.reads, "隐藏窗口只在下次显示时重验");

        center.show(owner);

        assertTrue(core.workspaceSettings.reads > before);
        assertEquals(renamed, selector.getValue());
    }

    private static ManagementSettingsGateways gateways(
            DesktopPresenter desktop, CoreSettingsGateway core, AtomicReference<WorkspaceId> mainSelection) {
        ManagementSettingsGateways base = SdkManagementSettingsGateways.create(desktop);
        return new ManagementSettingsGateways(
                core,
                base.promptPreview(),
                base.promptOptimization(),
                base.mcp(),
                base.instructions(),
                base.bundles(),
                base.builtins(),
                base.jobs(),
                base.coding(),
                base.schedules(),
                base.extensions(),
                () -> Optional.of(mainSelection.get()));
    }

    private static DesktopAppearanceManager appearance() {
        return new DesktopAppearanceManager(new AppearancePreferenceStore() {
            @Override
            public AppearancePreferences load() {
                return AppearancePreferences.defaults();
            }

            @Override
            public void save(AppearancePreferences preferences) {}
        });
    }

    private static ComboBox<?> selector() {
        Stage window = Window.getWindows().stream()
                .filter(Stage.class::isInstance)
                .map(Stage.class::cast)
                .filter(stage -> "JavaClaw 设置与管理中心".equals(stage.getTitle()))
                .findFirst()
                .orElseThrow();
        window.getScene().getRoot().applyCss();
        return window.getScene().getRoot().lookupAll(".combo-box").stream()
                .filter(ComboBox.class::isInstance)
                .map(ComboBox.class::cast)
                .filter(combo -> "设置中心固定工作区".equals(combo.getAccessibleText()))
                .findFirst()
                .orElseThrow();
    }

    private static final class EventGateway extends TestCoreSettingsGateway {
        private final DesktopConfigurationEvents events = new DesktopConfigurationEvents();

        @Override
        public DesktopNotificationSubscription onConfigurationChanged(Consumer<DesktopConfigurationChange> listener) {
            return events.subscribe(listener);
        }

        private void changed() {
            events.publish(new DesktopConfigurationChange(
                    DesktopConfigurationChange.Kind.WORKSPACES, Optional.empty(), Optional.empty()));
        }
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
