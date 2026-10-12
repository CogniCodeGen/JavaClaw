package com.javaclaw.ui.javafx;

import com.javaclaw.platform.fx.FxDispatcher;
import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemTrayManagerTest {

    @Test
    void transparentTrayArtworkFillsNativeSizeAndRemainsVisibleOnLightAndDark() {
        BufferedImage icon = TrayIconImageLoader.load(new Dimension(22, 22));

        assertEquals(22, icon.getWidth());
        assertEquals(22, icon.getHeight());
        assertTrue(icon.getColorModel().hasAlpha());
        int minX = icon.getWidth();
        int minY = icon.getHeight();
        int maxX = -1;
        int maxY = -1;
        int transparent = 0;
        int visibleOnLight = 0;
        int visibleOnDark = 0;
        for (int y = 0; y < icon.getHeight(); y++) {
            for (int x = 0; x < icon.getWidth(); x++) {
                int argb = icon.getRGB(x, y);
                int alpha = argb >>> 24 & 0xff;
                if (alpha == 0) {
                    transparent++;
                    continue;
                }
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                int red = argb >>> 16 & 0xff;
                int green = argb >>> 8 & 0xff;
                int blue = argb & 0xff;
                if (distance(red, green, blue, 245, 245, 245) > 55) visibleOnLight++;
                if (distance(red, green, blue, 32, 32, 32) > 55) visibleOnDark++;
            }
        }

        assertTrue(transparent > 0, "托盘图标必须拥有真实透明背景");
        assertTrue(maxX - minX + 1 >= 17 && maxY - minY + 1 >= 17,
                "品牌符号应占据至少约 77% 的托盘画布");
        assertTrue(visibleOnLight >= 80, "浅色菜单栏上应有足够可辨识像素");
        assertTrue(visibleOnDark >= 80, "深色菜单栏上应有足够可辨识像素");
    }

    @Test
    void macTemplateArtworkUsesOnlyAlphaAndBlackPixels() {
        BufferedImage icon = TrayIconImageLoader.loadTemplate(new Dimension(20, 20));
        int visible = 0;
        int transparent = 0;
        for (int y = 0; y < icon.getHeight(); y++) {
            for (int x = 0; x < icon.getWidth(); x++) {
                int argb = icon.getRGB(x, y);
                int alpha = argb >>> 24 & 0xff;
                if (alpha == 0) {
                    transparent++;
                } else {
                    visible++;
                    assertEquals(0, argb & 0x00ffffff,
                            "macOS 模板图标不得携带颜色信息");
                }
            }
        }
        assertTrue(visible >= 70);
        assertTrue(transparent > 0);
    }

    @Test
    void installationIsIdempotentAndConcurrentRequestsShareOneAwtTask() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        FakeTrayBackend backend = new FakeTrayBackend();
        SystemTrayManager manager = manager(backend, queued::set);

        var first = manager.ensureInstalled();
        var second = manager.ensureInstalled();
        assertSame(first, second);
        assertFalse(first.isDone());

        queued.get().run();
        assertTrue(first.join());
        assertTrue(manager.isInstalled());
        assertEquals(1, backend.created.get());
        assertEquals(1, backend.added.get());

        var health = manager.ensureInstalled();
        queued.get().run();
        assertTrue(health.join());
        assertEquals(1, backend.created.get(), "健康检查不得创建重复图标");
        var removed = manager.remove();
        queued.get().run();
        removed.join();
    }

    @Test
    void externallyRemovedIconIsAutomaticallyRecreatedOnce() {
        FakeTrayBackend backend = new FakeTrayBackend();
        SystemTrayManager manager = manager(backend, Runnable::run);
        assertTrue(manager.ensureInstalled().join());

        backend.loseRegisteredIcon();

        assertTrue(manager.isInstalled());
        assertEquals(2, backend.created.get());
        assertEquals(2, backend.added.get());
        assertEquals(1, backend.registered.size());
        manager.remove().join();
    }

    @Test
    void failedRepairReturnsFalseWithoutClaimingInstallation() {
        FakeTrayBackend backend = new FakeTrayBackend();
        backend.failAdd = true;
        SystemTrayManager manager = manager(backend, Runnable::run);

        assertFalse(manager.ensureInstalled().join());
        assertFalse(manager.isInstalled());
        assertFalse(manager.wasEverInstalled());
        assertEquals(1, backend.created.get());
        manager.remove().join();
    }

    @Test
    void removalDisablesWatcherAndPreventsReinstallation() {
        FakeTrayBackend backend = new FakeTrayBackend();
        SystemTrayManager manager = manager(backend, Runnable::run);
        assertTrue(manager.ensureInstalled().join());

        manager.remove().join();
        backend.fireChange();

        assertFalse(manager.ensureInstalled().join());
        assertFalse(manager.isInstalled());
        assertEquals(1, backend.added.get());
        assertEquals(1, backend.watcherClosed.get());
    }

    @Test
    void trayMenuActionsCrossFromAwtToFxDispatcher() {
        AtomicReference<Runnable> fxTask = new AtomicReference<>();
        AtomicInteger shown = new AtomicInteger();
        FakeTrayBackend backend = new FakeTrayBackend();
        FxDispatcher fx = new FxDispatcher(() -> false, fxTask::set);
        SystemTrayManager manager = new SystemTrayManager(
                "JavaClaw", shown::incrementAndGet, () -> { }, () -> { }, () -> { },
                fx, backend, Runnable::run);
        assertTrue(manager.ensureInstalled().join());

        backend.lastHandle.show.run();
        assertEquals(0, shown.get());
        fxTask.get().run();
        assertEquals(1, shown.get());
        manager.remove().join();
    }

    private static int distance(int r1, int g1, int b1, int r2, int g2, int b2) {
        return Math.abs(r1 - r2) + Math.abs(g1 - g2) + Math.abs(b1 - b2);
    }

    private static SystemTrayManager manager(
            FakeTrayBackend backend, Consumer<Runnable> awtDispatcher) {
        return new SystemTrayManager("JavaClaw", () -> { }, () -> { }, () -> { }, () -> { },
                new FxDispatcher(() -> true, Runnable::run), backend, awtDispatcher);
    }

    private static final class FakeTrayBackend implements SystemTrayManager.TrayBackend {
        private final AtomicInteger created = new AtomicInteger();
        private final AtomicInteger added = new AtomicInteger();
        private final AtomicInteger watcherClosed = new AtomicInteger();
        private final Set<SystemTrayManager.TrayHandle> registered = new HashSet<>();
        private Runnable watcher;
        private FakeHandle lastHandle;
        private boolean failAdd;

        @Override
        public boolean isSupported() {
            return true;
        }

        @Override
        public SystemTrayManager.TrayHandle create(
                String tooltip, Runnable onShowWindow, Runnable onNewTask,
                Runnable onOpenSettings, Runnable onExit) {
            created.incrementAndGet();
            lastHandle = new FakeHandle(onShowWindow, onNewTask, onOpenSettings, onExit);
            return lastHandle;
        }

        @Override
        public boolean contains(SystemTrayManager.TrayHandle handle) {
            return registered.contains(handle);
        }

        @Override
        public void add(SystemTrayManager.TrayHandle handle) throws Exception {
            if (failAdd) throw new Exception("simulated tray failure");
            registered.add(handle);
            added.incrementAndGet();
        }

        @Override
        public void remove(SystemTrayManager.TrayHandle handle) {
            registered.remove(handle);
        }

        @Override
        public void displayInfo(
                SystemTrayManager.TrayHandle handle, String title, String message) { }

        @Override
        public AutoCloseable watch(Runnable listener) {
            watcher = listener;
            return () -> {
                watcher = null;
                watcherClosed.incrementAndGet();
            };
        }

        private void loseRegisteredIcon() {
            registered.clear();
            fireChange();
        }

        private void fireChange() {
            Runnable current = watcher;
            if (current != null) current.run();
        }
    }

    private record FakeHandle(
            Runnable show, Runnable task, Runnable settings, Runnable exit)
            implements SystemTrayManager.TrayHandle { }
}
