package com.javaclaw.desktop.appearance;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JavaPreferencesAppearanceStoreTest {
    @Test
    void 保存后可完整读取三类外观值() {
        MemoryPreferences preferences = new MemoryPreferences();
        JavaPreferencesAppearanceStore store = new JavaPreferencesAppearanceStore(preferences);
        AppearancePreferences expected =
                new AppearancePreferences(AppearanceTheme.PLUM, FontScale.EXTRA_LARGE, InterfaceDensity.SPACIOUS);

        store.save(expected);

        assertEquals(expected, store.load());
    }

    @Test
    void 损坏值回退默认且写入失败明确报告() {
        MemoryPreferences preferences = new MemoryPreferences();
        preferences.put("theme", "unknown");
        preferences.put("fontScale", "not-a-number");
        preferences.put("density", "unknown");
        JavaPreferencesAppearanceStore store = new JavaPreferencesAppearanceStore(preferences);
        assertEquals(AppearancePreferences.defaults(), store.load());

        preferences.failRead = true;
        assertEquals(AppearancePreferences.defaults(), store.load());
        preferences.failRead = false;
        preferences.failFlush = true;
        assertThrows(IllegalStateException.class, () -> store.save(AppearancePreferences.defaults()));
    }

    private static final class MemoryPreferences extends AbstractPreferences {
        private final Map<String, String> values = new HashMap<>();
        private boolean failRead;
        private boolean failFlush;

        private MemoryPreferences() {
            super(null, "");
        }

        @Override
        protected void putSpi(String key, String value) {
            values.put(key, value);
        }

        @Override
        protected String getSpi(String key) {
            if (failRead) {
                throw new IllegalStateException("test read failure");
            }
            return values.get(key);
        }

        @Override
        protected void removeSpi(String key) {
            values.remove(key);
        }

        @Override
        protected void removeNodeSpi() {
            values.clear();
        }

        @Override
        protected String[] keysSpi() {
            return values.keySet().toArray(String[]::new);
        }

        @Override
        protected String[] childrenNamesSpi() {
            return new String[0];
        }

        @Override
        protected AbstractPreferences childSpi(String name) {
            throw new UnsupportedOperationException("测试偏好不创建子节点");
        }

        @Override
        protected void syncSpi() {}

        @Override
        protected void flushSpi() throws BackingStoreException {
            if (failFlush) {
                throw new BackingStoreException("test failure");
            }
        }
    }
}
