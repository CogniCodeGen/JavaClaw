package com.javaclaw.desktop.settings;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.appearance.AppearancePreferenceStore;
import com.javaclaw.desktop.appearance.AppearancePreferences;
import com.javaclaw.desktop.appearance.DesktopAppearanceManager;

/** 为 Golden 测试打开真实设置中心，并隔离本机窗口与外观偏好。 */
public final class ProductionManagementCenterScene {
    private ProductionManagementCenterScene() {}

    /**
     * 从测试线程渲染生产设置中心的外观页，并返回权威导航目录。
     *
     * <p>先让离线目录请求在 FX 线程完成，再截图稳定的读取失败状态，避免把后台回执到达时机写入 Golden。
     *
     * @param preferences 本次截图外观
     * @param width Scene 宽度，单位为逻辑像素
     * @param height Scene 高度，单位为逻辑像素
     * @return 截图及生产导航目录文本
     */
    public static RenderedCenter render(AppearancePreferences preferences, int width, int height) {
        RenderSession session = FxTestSupport.call(() -> open(preferences, width, height));
        try {
            FxTestSupport.await(() -> FxTestSupport.call(session::ready));
            return FxTestSupport.call(session::capture);
        } finally {
            FxTestSupport.run(session::close);
        }
    }

    private static RenderSession open(AppearancePreferences preferences, int width, int height) {
        DesktopAppearanceManager appearance = new DesktopAppearanceManager(new FixedAppearanceStore(preferences));
        DesktopPresenter presenter = new DesktopPresenter(
                notifications -> {
                    throw new IOException("Golden 不连接 App Server");
                },
                Platform::runLater,
                Clock.systemUTC());
        Stage owner = new Stage();
        owner.setScene(new Scene(new VBox(), 1, 1));
        owner.setOpacity(0);
        owner.show();
        ManagementCenterWindow center = new ManagementCenterWindow(
                appearance, SdkManagementSettingsGateways.create(presenter), new MemoryWindowStore());
        try {
            center.show(owner, "appearance");
            Stage management = managementStage();
            Scene productionScene = management.getScene();
            Parent root = productionScene.getRoot();
            productionScene.setRoot(new VBox());
            Scene scene = new Scene(root, width, height);
            DesktopStylesheets.apply(scene);
            DesktopAppearanceManager.apply(scene, preferences);
            return new RenderSession(center, presenter, owner, scene);
        } catch (RuntimeException | Error failure) {
            close(center, owner, presenter);
            throw failure;
        }
    }

    private static Stage managementStage() {
        return Window.getWindows().stream()
                .filter(Stage.class::isInstance)
                .map(Stage.class::cast)
                .filter(stage -> "JavaClaw 设置与管理中心".equals(stage.getTitle()))
                .filter(Stage::isShowing)
                .findFirst()
                .orElseThrow();
    }

    private static void close(ManagementCenterWindow center, Stage owner, DesktopPresenter presenter) {
        center.dispose();
        owner.hide();
        try {
            presenter.close();
        } catch (Exception failure) {
            throw new AssertionError("Golden Presenter 关闭失败", failure);
        }
    }

    /** 只在 FX 线程访问真实控件；等待由外部测试线程承担，不能阻塞 SDK 回执调度。 */
    private record RenderSession(ManagementCenterWindow center, DesktopPresenter presenter, Stage owner, Scene scene) {
        private boolean ready() {
            Label status = (Label) scene.lookup(".management-scope-status");
            return status != null && status.getText().startsWith("工作区读取失败，");
        }

        private RenderedCenter capture() {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            ListView<?> navigation = (ListView<?>) scene.lookup(".management-navigation-list");
            List<String> entries =
                    navigation.getItems().stream().map(Object::toString).toList();
            return new RenderedCenter(scene.snapshot(null), entries);
        }

        private void close() {
            ProductionManagementCenterScene.close(center, owner, presenter);
        }
    }

    /**
     * 一次生产设置中心渲染结果。
     *
     * @param image Scene 截图
     * @param navigationEntries 权威导航目录文本
     */
    public record RenderedCenter(WritableImage image, List<String> navigationEntries) {
        /** 复制导航目录，避免测试修改渲染证据。 */
        public RenderedCenter {
            navigationEntries = List.copyOf(navigationEntries);
        }
    }

    private static final class FixedAppearanceStore implements AppearancePreferenceStore {
        private AppearancePreferences preferences;

        private FixedAppearanceStore(AppearancePreferences preferences) {
            this.preferences = preferences;
        }

        @Override
        public AppearancePreferences load() {
            return preferences;
        }

        @Override
        public void save(AppearancePreferences saved) {
            preferences = saved;
        }
    }

    private static final class MemoryWindowStore implements ManagementWindowPreferenceStore {
        private ManagementWindowPreferences preferences =
                new ManagementWindowPreferences("appearance", Optional.empty());

        @Override
        public ManagementWindowPreferences load() {
            return preferences;
        }

        @Override
        public void save(ManagementWindowPreferences saved) {
            preferences = saved;
        }
    }
}
