package com.javaclaw.ui.javafx;

import com.javaclaw.platform.fx.FxDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Dimension;
import java.awt.Desktop;
import java.awt.EventQueue;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.desktop.AppReopenedListener;
import java.beans.PropertyChangeListener;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Owns JavaClaw's AWT system-tray registration and repairs registrations removed by the desktop.
 *
 * <p>AWT work is always queued on its event-dispatch thread. Menu actions cross back to the JavaFX
 * Application Thread through {@link FxDispatcher}; neither UI toolkit blocks waiting for the
 * other. Installation is idempotent and concurrent repair requests share one in-flight future.</p>
 */
public class SystemTrayManager {

    private static final Logger log = LoggerFactory.getLogger(SystemTrayManager.class);

    private final String tooltip;
    private final Runnable onShowWindow;
    private final Runnable onNewTask;
    private final Runnable onOpenSettings;
    private final Runnable onExit;
    private final FxDispatcher fx;
    private final TrayBackend backend;
    private final Consumer<Runnable> awtDispatcher;
    private final Object stateLock = new Object();

    private volatile TrayHandle trayIcon;
    private volatile AutoCloseable trayWatcher;
    private volatile boolean registered;
    private volatile boolean everInstalled;
    private volatile boolean closed;
    private CompletableFuture<Boolean> installation;

    public SystemTrayManager(String tooltip, Runnable onShowWindow, Runnable onNewTask,
                             Runnable onOpenSettings, Runnable onExit, FxDispatcher fx) {
        this(tooltip, onShowWindow, onNewTask, onOpenSettings, onExit, fx,
                new AwtTrayBackend(), EventQueue::invokeLater);
    }

    SystemTrayManager(String tooltip, Runnable onShowWindow, Runnable onNewTask,
                      Runnable onOpenSettings, Runnable onExit, FxDispatcher fx,
                      TrayBackend backend, Consumer<Runnable> awtDispatcher) {
        this.tooltip = Objects.requireNonNull(tooltip, "tooltip");
        this.onShowWindow = onShowWindow;
        this.onNewTask = onNewTask;
        this.onOpenSettings = onOpenSettings;
        this.onExit = onExit;
        this.fx = Objects.requireNonNull(fx, "fx");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.awtDispatcher = Objects.requireNonNull(awtDispatcher, "awtDispatcher");
    }

    /**
     * Ensures a live tray icon is registered. Repeated/concurrent calls are safe and never create
     * duplicate icons.
     */
    public CompletableFuture<Boolean> ensureInstalled() {
        synchronized (stateLock) {
            if (closed) return CompletableFuture.completedFuture(false);
            if (installation != null && !installation.isDone()) return installation;
            CompletableFuture<Boolean> requested = new CompletableFuture<>();
            installation = requested;
            try {
                awtDispatcher.accept(() -> completeInstallation(requested));
            } catch (Throwable schedulingFailure) {
                installation = null;
                registered = false;
                requested.complete(false);
                log.warn("提交系统托盘安装任务失败: {}",
                        schedulingFailure.getMessage(), schedulingFailure);
            }
            return requested;
        }
    }

    /** Last state confirmed from {@code SystemTray.getTrayIcons()} on the AWT thread. */
    public boolean isInstalled() {
        return registered;
    }

    /** Whether this manager activated AWT tray infrastructure during the current process. */
    public boolean wasEverInstalled() {
        return everInstalled;
    }

    /** Queues a tray notification only when the icon is still registered. */
    public void displayInfo(String title, String message) {
        if (closed) return;
        try {
            awtDispatcher.accept(() -> {
                TrayHandle current = trayIcon;
                if (closed || current == null || !safeContains(current)) return;
                try {
                    backend.displayInfo(current, title, message);
                } catch (Throwable failure) {
                    log.debug("托盘通知发送失败: {}", failure.getMessage());
                }
            });
        } catch (Throwable failure) {
            log.debug("提交托盘通知任务失败: {}", failure.getMessage());
        }
    }

    /**
     * Permanently closes this manager and asynchronously removes its icon/listeners. Once called,
     * availability events can no longer reinstall the icon.
     */
    public CompletableFuture<Void> remove() {
        synchronized (stateLock) {
            if (closed) return CompletableFuture.completedFuture(null);
            closed = true;
        }
        CompletableFuture<Void> removed = new CompletableFuture<>();
        try {
            awtDispatcher.accept(() -> {
                try {
                    closeOnAwt();
                    removed.complete(null);
                } catch (Throwable failure) {
                    removed.completeExceptionally(failure);
                }
            });
        } catch (Throwable schedulingFailure) {
            removed.completeExceptionally(schedulingFailure);
        }
        return removed;
    }

    private void completeInstallation(CompletableFuture<Boolean> requested) {
        boolean installed = false;
        try {
            installed = ensureOnAwt();
            requested.complete(installed);
        } catch (Throwable failure) {
            registered = false;
            requested.complete(false);
            log.warn("系统托盘安装或修复失败: {}", failure.getMessage(), failure);
        } finally {
            synchronized (stateLock) {
                if (installation == requested) installation = null;
            }
        }
        if (installed) log.debug("系统托盘状态检查通过");
    }

    private boolean ensureOnAwt() throws Exception {
        if (closed) return false;
        if (!backend.isSupported()) {
            registered = false;
            log.info("当前平台不支持系统托盘");
            return false;
        }

        ensureWatcherOnAwt();
        TrayHandle current = trayIcon;
        if (current != null && backend.contains(current)) {
            registered = true;
            return true;
        }

        if (current != null) {
            backend.remove(current);
            trayIcon = null;
        }

        TrayHandle candidate = backend.create(
                tooltip,
                () -> dispatch(onShowWindow),
                () -> dispatch(onNewTask),
                () -> dispatch(onOpenSettings),
                () -> dispatch(onExit));
        try {
            backend.add(candidate);
            if (!backend.contains(candidate)) {
                throw new IllegalStateException("系统托盘未确认新图标注册");
            }
            trayIcon = candidate;
            registered = true;
            boolean repaired = everInstalled;
            everInstalled = true;
            log.info(repaired ? "系统托盘图标已恢复" : "系统托盘已安装");
            return true;
        } catch (Throwable failure) {
            backend.remove(candidate);
            registered = false;
            throw failure;
        }
    }

    private void ensureWatcherOnAwt() throws Exception {
        if (trayWatcher != null) return;
        trayWatcher = backend.watch(this::trayStateChanged);
    }

    private void trayStateChanged() {
        if (closed) return;
        TrayHandle current = trayIcon;
        boolean present = current != null && safeContains(current);
        registered = present;
        if (!present) {
            log.warn("检测到系统托盘图标丢失，尝试自动恢复");
            ensureInstalled();
        }
    }

    private boolean safeContains(TrayHandle handle) {
        try {
            return backend.isSupported() && backend.contains(handle);
        } catch (Throwable failure) {
            log.debug("检查系统托盘状态失败: {}", failure.getMessage());
            return false;
        }
    }

    private void closeOnAwt() {
        AutoCloseable watcher = trayWatcher;
        trayWatcher = null;
        if (watcher != null) {
            try {
                watcher.close();
            } catch (Exception failure) {
                log.debug("移除系统托盘监听器失败: {}", failure.getMessage());
            }
        }
        TrayHandle current = trayIcon;
        trayIcon = null;
        registered = false;
        if (current != null) {
            try {
                backend.remove(current);
            } catch (Throwable failure) {
                log.debug("移除托盘图标失败: {}", failure.getMessage());
            }
        }
    }

    private void dispatch(Runnable action) {
        if (action == null) return;
        fx.dispatch(() -> {
            try {
                action.run();
            } catch (Exception failure) {
                log.warn("托盘菜单动作执行失败: {}", failure.getMessage(), failure);
            }
        });
    }

    interface TrayHandle { }

    interface TrayBackend {
        boolean isSupported();

        TrayHandle create(String tooltip, Runnable onShowWindow, Runnable onNewTask,
                          Runnable onOpenSettings, Runnable onExit) throws Exception;

        boolean contains(TrayHandle handle);

        void add(TrayHandle handle) throws Exception;

        void remove(TrayHandle handle);

        void displayInfo(TrayHandle handle, String title, String message);

        AutoCloseable watch(Runnable listener) throws Exception;
    }

    private static final class AwtTrayBackend implements TrayBackend {
        @Override
        public boolean isSupported() {
            return SystemTray.isSupported();
        }

        @Override
        public TrayHandle create(String tooltip, Runnable onShowWindow, Runnable onNewTask,
                                 Runnable onOpenSettings, Runnable onExit) {
            SystemTray tray = SystemTray.getSystemTray();
            PopupMenu menu = new PopupMenu();
            menu.add(item("显示主窗口", onShowWindow));
            menu.add(item("新建任务", onNewTask));
            menu.add(item("打开设置", onOpenSettings));
            menu.addSeparator();
            menu.add(item("退出", onExit));

            Dimension size = tray.getTrayIconSize();
            boolean templateImages = Boolean.getBoolean("apple.awt.enableTemplateImages");
            Image image = templateImages
                    ? TrayIconImageLoader.loadTemplate(size)
                    : TrayIconImageLoader.load(size);
            TrayIcon icon = new TrayIcon(image, tooltip, menu);
            // Let the native peer fit the logical 20x20 image to the actual Retina status item.
            icon.setImageAutoSize(true);
            icon.addActionListener(ignored -> onShowWindow.run());
            AppReopenedListener reopen = ignored -> onShowWindow.run();
            registerReopenListener(reopen);
            return new AwtTrayHandle(icon, reopen);
        }

        @Override
        public boolean contains(TrayHandle handle) {
            TrayIcon icon = awt(handle).icon();
            return Arrays.stream(SystemTray.getSystemTray().getTrayIcons())
                    .anyMatch(existing -> existing == icon);
        }

        @Override
        public void add(TrayHandle handle) throws Exception {
            SystemTray.getSystemTray().add(awt(handle).icon());
        }

        @Override
        public void remove(TrayHandle handle) {
            AwtTrayHandle awt = awt(handle);
            try {
                SystemTray.getSystemTray().remove(awt.icon());
            } finally {
                unregisterReopenListener(awt.reopenListener());
            }
        }

        @Override
        public void displayInfo(TrayHandle handle, String title, String message) {
            awt(handle).icon().displayMessage(title, message, TrayIcon.MessageType.INFO);
        }

        @Override
        public AutoCloseable watch(Runnable listener) {
            SystemTray tray = SystemTray.getSystemTray();
            PropertyChangeListener change = ignored -> listener.run();
            tray.addPropertyChangeListener("trayIcons", change);
            tray.addPropertyChangeListener("systemTray", change);
            return () -> {
                tray.removePropertyChangeListener("trayIcons", change);
                tray.removePropertyChangeListener("systemTray", change);
            };
        }

        private static MenuItem item(String label, Runnable action) {
            MenuItem item = new MenuItem(label);
            item.addActionListener(ignored -> action.run());
            return item;
        }

        private static void registerReopenListener(AppReopenedListener listener) {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().addAppEventListener(listener);
                }
            } catch (UnsupportedOperationException | SecurityException failure) {
                log.debug("当前桌面不支持 Dock 重新打开监听: {}", failure.getMessage());
            }
        }

        private static void unregisterReopenListener(AppReopenedListener listener) {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().removeAppEventListener(listener);
                }
            } catch (UnsupportedOperationException | SecurityException failure) {
                log.debug("移除 Dock 重新打开监听失败: {}", failure.getMessage());
            }
        }

        private static AwtTrayHandle awt(TrayHandle handle) {
            if (handle instanceof AwtTrayHandle awt) return awt;
            throw new IllegalArgumentException("不是 AWT 托盘句柄");
        }
    }

    private record AwtTrayHandle(TrayIcon icon, AppReopenedListener reopenListener)
            implements TrayHandle { }
}
