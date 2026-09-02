package com.javaclaw.launcher.tray;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.javaclaw.nativehost.tray.SystemTrayFeature;

/** 使用平台原生 AWT SystemTray 呈现固定 App Server 命令，不创建独立窗口。 */
public final class AwtSystemTrayView implements TrayView {
    private static final Color BRAND = new Color(0x2E9A6A);
    private static final Color SUCCESS = new Color(0x10B981);
    private static final Color DANGER = new Color(0xEF4444);
    private static final Color WARNING = new Color(0xF59E0B);
    private static final Color NEUTRAL = new Color(0x706B5F);

    private final SystemTray tray;
    private final TrayIcon icon;
    private final MenuItem status;
    private final MenuItem openMain;
    private final MenuItem startServer;
    private final MenuItem stopServer;
    private final MenuItem restartServer;
    private final MenuItem refresh;
    private final AtomicReference<Consumer<TrayCommand>> commands = new AtomicReference<>(ignored -> {});
    private final AtomicBoolean bound = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 在已经通过发行 launcher feature detection 的环境安装托盘图标。
     *
     * @param feature SystemTray 平台探测
     * @throws AWTException 操作系统拒绝安装图标
     */
    public AwtSystemTrayView(SystemTrayFeature.Status feature) throws AWTException {
        SystemTrayFeature.Status checked = Objects.requireNonNull(feature, "feature");
        if (!checked.available()) {
            throw new IllegalStateException(checked.unavailableReason().orElse("SystemTray 不可用"));
        }
        status = new MenuItem("App Server：读取中");
        openMain = new MenuItem("打开主窗口");
        startServer = new MenuItem("启动 App Server");
        stopServer = new MenuItem("停止 App Server");
        restartServer = new MenuItem("重启 App Server");
        refresh = new MenuItem("刷新状态");
        tray = SystemTray.getSystemTray();
        PopupMenu menu = menu();
        icon = new TrayIcon(image(BRAND), "JavaClaw", menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(event -> dispatch(TrayCommand.OPEN_MAIN));
        tray.add(icon);
    }

    private PopupMenu menu() {
        status.setEnabled(false);
        bind(openMain, TrayCommand.OPEN_MAIN);
        bind(startServer, TrayCommand.START_SERVER);
        bind(stopServer, TrayCommand.STOP_SERVER);
        bind(restartServer, TrayCommand.RESTART_SERVER);
        bind(refresh, TrayCommand.REFRESH);
        PopupMenu menu = new PopupMenu();
        menu.add(status);
        menu.addSeparator();
        menu.add(openMain);
        menu.add(startServer);
        menu.add(stopServer);
        menu.add(restartServer);
        menu.addSeparator();
        menu.add(refresh);
        return menu;
    }

    private void bind(MenuItem item, TrayCommand command) {
        item.addActionListener(event -> dispatch(command));
    }

    private void dispatch(TrayCommand command) {
        if (!closed.get()) {
            commands.get().accept(command);
        }
    }

    @Override
    public void bind(Consumer<TrayCommand> commandConsumer) {
        Consumer<TrayCommand> checked = Objects.requireNonNull(commandConsumer, "commandConsumer");
        if (!bound.compareAndSet(false, true)) {
            throw new IllegalStateException("tray view is already bound");
        }
        commands.set(checked);
    }

    @Override
    public void render(TrayState state) {
        TrayState checked = Objects.requireNonNull(state, "state");
        if (closed.get()) {
            return;
        }
        EventQueue.invokeLater(() -> renderOnAwt(checked));
    }

    private void renderOnAwt(TrayState state) {
        if (closed.get()) {
            return;
        }
        status.setLabel("App Server：" + serverLabel(state));
        icon.setToolTip("JavaClaw · " + state.message());
        icon.setImage(image(color(state)));
        boolean idle = !state.pending();
        openMain.setEnabled(idle);
        startServer.setEnabled(idle && state.server() != TrayState.ServerState.RUNNING);
        stopServer.setEnabled(idle && state.server() == TrayState.ServerState.RUNNING);
        restartServer.setEnabled(idle && state.server() == TrayState.ServerState.RUNNING);
        refresh.setEnabled(idle);
        state.error().ifPresent(error -> icon.displayMessage("JavaClaw", error, TrayIcon.MessageType.ERROR));
    }

    static String serverLabel(TrayState state) {
        if (state.pending()) {
            return "处理中";
        }
        return switch (state.server()) {
            case UNKNOWN -> "未知";
            case RUNNING -> "运行中";
            case STOPPED -> "已停止";
        };
    }

    static Color color(TrayState state) {
        if (state.error().isPresent()) {
            return DANGER;
        }
        if (state.pending()) {
            return WARNING;
        }
        return switch (state.server()) {
            case UNKNOWN -> NEUTRAL;
            case RUNNING -> SUCCESS;
            case STOPPED -> NEUTRAL;
        };
    }

    static Image image(Color color) {
        BufferedImage image = new BufferedImage(18, 18, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(color);
            graphics.fillRoundRect(1, 1, 16, 16, 6, 6);
            graphics.setColor(Color.WHITE);
            graphics.fillOval(6, 6, 6, 6);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /** 删除原生图标；不会停止 App Server。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        EventQueue.invokeLater(() -> tray.remove(icon));
    }
}
