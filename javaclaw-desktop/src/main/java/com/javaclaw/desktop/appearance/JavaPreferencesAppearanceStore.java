package com.javaclaw.desktop.appearance;

import java.util.Objects;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/** 使用 JDK 用户偏好存储 Desktop 本机外观，不经过 App Server。 */
public final class JavaPreferencesAppearanceStore implements AppearancePreferenceStore {
    private static final String THEME_KEY = "theme";
    private static final String FONT_SCALE_KEY = "fontScale";
    private static final String DENSITY_KEY = "density";

    private final Preferences preferences;

    /** 创建 JavaClaw 5 独立命名空间的外观存储。 */
    public JavaPreferencesAppearanceStore() {
        this(Preferences.userNodeForPackage(JavaPreferencesAppearanceStore.class)
                .node("appearance-v5"));
    }

    JavaPreferencesAppearanceStore(Preferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
    }

    @Override
    public AppearancePreferences load() {
        AppearancePreferences defaults = AppearancePreferences.defaults();
        try {
            return new AppearancePreferences(
                    AppearanceTheme.fromId(
                            preferences.get(THEME_KEY, defaults.theme().id())),
                    FontScale.fromPercent(preferences.getInt(
                            FONT_SCALE_KEY, defaults.fontScale().percent())),
                    InterfaceDensity.fromId(
                            preferences.get(DENSITY_KEY, defaults.density().id())));
        } catch (RuntimeException unavailable) {
            // 外观偏好不可读不应阻止 Desktop 启动；后续保存仍会明确报告真实失败。
            return defaults;
        }
    }

    @Override
    public void save(AppearancePreferences value) {
        AppearancePreferences checked = Objects.requireNonNull(value, "preferences");
        try {
            preferences.put(THEME_KEY, checked.theme().id());
            preferences.putInt(FONT_SCALE_KEY, checked.fontScale().percent());
            preferences.put(DENSITY_KEY, checked.density().id());
            preferences.flush();
        } catch (BackingStoreException | RuntimeException failure) {
            throw new IllegalStateException("无法保存 Desktop 外观偏好", failure);
        }
    }
}
