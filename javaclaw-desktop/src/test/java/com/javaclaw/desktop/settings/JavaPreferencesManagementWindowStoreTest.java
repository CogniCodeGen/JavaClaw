package com.javaclaw.desktop.settings;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaPreferencesManagementWindowStoreTest {
    @Test
    void 保存后可完整恢复页面和窗口边界() {
        MemoryPreferences preferences = new MemoryPreferences();
        JavaPreferencesManagementWindowStore store = new JavaPreferencesManagementWindowStore(preferences);
        ManagementWindowPreferences expected = new ManagementWindowPreferences(
                "diagnostics", Optional.of(new ManagementWindowPreferences.WindowBounds(40, 50, 1_100, 740)));

        store.save(expected);

        assertEquals(expected, store.load());
    }

    @Test
    void 损坏边界安全丢弃且写入失败明确报告() {
        MemoryPreferences preferences = new MemoryPreferences();
        preferences.put("last-page", "");
        preferences.putBoolean("positioned", true);
        preferences.putDouble("x", Double.NaN);
        JavaPreferencesManagementWindowStore store = new JavaPreferencesManagementWindowStore(preferences);

        ManagementWindowPreferences restored = store.load();

        assertEquals("appearance", restored.lastPageKey());
        assertTrue(restored.bounds().isEmpty());
        preferences.failFlush = true;
        assertThrows(IllegalStateException.class, () -> store.save(ManagementWindowPreferences.defaults()));
    }

    private static final class MemoryPreferences extends AbstractPreferences {
        private final Map<String, String> values = new HashMap<>();
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
