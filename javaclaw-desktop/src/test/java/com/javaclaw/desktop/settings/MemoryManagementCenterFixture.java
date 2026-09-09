package com.javaclaw.desktop.settings;

import javafx.scene.Scene;

import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.desktop.appearance.AppearancePreferenceStore;
import com.javaclaw.desktop.appearance.AppearancePreferences;
import com.javaclaw.desktop.appearance.DesktopAppearanceManager;

/** 真实主壳启动测试使用的内存偏好工厂，不创建或修改用户的 Java Preferences 节点。 */
public final class MemoryManagementCenterFixture {
    private MemoryManagementCenterFixture() {}

    /**
     * 为指定主 Scene 创建真实管理中心，并隔离窗口和外观偏好。
     *
     * @param presenter 本地假 RPC 服务对应的 Presenter
     * @param scene 尚未或已经挂载窗口的主 Scene
     * @return 使用内存偏好的生产管理中心；调用者负责 dispose
     */
    public static ManagementCenterWindow create(DesktopPresenter presenter, Scene scene) {
        DesktopAppearanceManager appearance = new DesktopAppearanceManager(new MemoryAppearanceStore());
        appearance.register(scene);
        return new ManagementCenterWindow(
                appearance, SdkManagementSettingsGateways.create(presenter), new MemoryWindowStore());
    }

    private static final class MemoryAppearanceStore implements AppearancePreferenceStore {
        private AppearancePreferences preferences = AppearancePreferences.defaults();

        @Override
        public AppearancePreferences load() {
            return preferences;
        }

        @Override
        public void save(AppearancePreferences value) {
            preferences = value;
        }
    }

    private static final class MemoryWindowStore implements ManagementWindowPreferenceStore {
        private ManagementWindowPreferences preferences = ManagementWindowPreferences.defaults();

        @Override
        public ManagementWindowPreferences load() {
            return preferences;
        }

        @Override
        public void save(ManagementWindowPreferences value) {
            preferences = value;
        }
    }
}
