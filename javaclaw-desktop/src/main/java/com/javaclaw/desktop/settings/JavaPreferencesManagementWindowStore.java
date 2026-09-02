package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/** 使用 Java Preferences 保存管理中心本机窗口状态。 */
final class JavaPreferencesManagementWindowStore implements ManagementWindowPreferenceStore {
    private static final String PAGE = "last-page";
    private static final String POSITIONED = "positioned";
    private static final String X = "x";
    private static final String Y = "y";
    private static final String WIDTH = "width";
    private static final String HEIGHT = "height";

    private final Preferences preferences;

    JavaPreferencesManagementWindowStore() {
        this(Preferences.userNodeForPackage(JavaPreferencesManagementWindowStore.class)
                .node("management-window-v5"));
    }

    JavaPreferencesManagementWindowStore(Preferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
    }

    @Override
    public ManagementWindowPreferences load() {
        ManagementWindowPreferences defaults = ManagementWindowPreferences.defaults();
        String page = preferences.get(PAGE, defaults.lastPageKey()).strip();
        if (page.isEmpty()) {
            page = defaults.lastPageKey();
        }
        return new ManagementWindowPreferences(page, readBounds());
    }

    @Override
    public void save(ManagementWindowPreferences value) {
        ManagementWindowPreferences checked = Objects.requireNonNull(value, "preferences");
        preferences.put(PAGE, checked.lastPageKey());
        preferences.putBoolean(POSITIONED, checked.bounds().isPresent());
        checked.bounds().ifPresent(bounds -> {
            preferences.putDouble(X, bounds.x());
            preferences.putDouble(Y, bounds.y());
            preferences.putDouble(WIDTH, bounds.width());
            preferences.putDouble(HEIGHT, bounds.height());
        });
        try {
            preferences.flush();
        } catch (BackingStoreException failure) {
            throw new IllegalStateException("无法保存设置中心窗口偏好", failure);
        }
    }

    private Optional<ManagementWindowPreferences.WindowBounds> readBounds() {
        if (!preferences.getBoolean(POSITIONED, false)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ManagementWindowPreferences.WindowBounds(
                    preferences.getDouble(X, Double.NaN),
                    preferences.getDouble(Y, Double.NaN),
                    preferences.getDouble(WIDTH, Double.NaN),
                    preferences.getDouble(HEIGHT, Double.NaN)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
