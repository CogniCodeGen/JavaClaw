package com.javaclaw.ui.javafx;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 系统托盘管理器 — 让应用在主窗口隐藏后仍能从托盘恢复 / 操作 / 退出（后台常驻）。
 *
 * <p>基于 AWT {@link SystemTray}（JavaFX 无原生托盘 API）。所有托盘菜单点击在 AWT 事件线程
 * 触发，本类统一通过 {@link Platform#runLater} 桥接回 JavaFX Application Thread 再执行注入的动作，
 * 调用方无需自行切线程。</p>
 *
 * <p>不支持托盘的平台上 {@link #install()} 返回 false，调用方应回退为"关闭即退出"。</p>
 */
public class SystemTrayManager {

    private static final Logger log = LoggerFactory.getLogger(SystemTrayManager.class);

    private final String tooltip;
    private final Runnable onShowWindow;
    private final Runnable onNewTask;
    private final Runnable onOpenSettings;
    private final Runnable onExit;

    private TrayIcon trayIcon;
    private volatile boolean installed;

    /**
     * @param tooltip        托盘图标悬停提示
     * @param onShowWindow   "显示主窗口"动作（将在 FX 线程执行）
     * @param onNewTask      "新建任务"动作
     * @param onOpenSettings "打开设置"动作
     * @param onExit         "退出"动作（应触发应用真正退出）
     */
    public SystemTrayManager(String tooltip, Runnable onShowWindow, Runnable onNewTask,
                             Runnable onOpenSettings, Runnable onExit) {
        this.tooltip = tooltip;
        this.onShowWindow = onShowWindow;
        this.onNewTask = onNewTask;
        this.onOpenSettings = onOpenSettings;
        this.onExit = onExit;
    }

    /**
     * 安装托盘图标。
     *
     * @return true=安装成功；false=平台不支持或安装失败（调用方应回退为关闭即退出）
     */
    public boolean install() {
        if (!SystemTray.isSupported()) {
            log.info("当前平台不支持系统托盘，跳过托盘安装");
            return false;
        }
        try {
            SystemTray tray = SystemTray.getSystemTray();

            PopupMenu menu = new PopupMenu();
            menu.add(buildItem("显示主窗口", onShowWindow));
            menu.add(buildItem("新建任务", onNewTask));
            menu.add(buildItem("打开设置", onOpenSettings));
            menu.addSeparator();
            menu.add(buildItem("退出", onExit));

            trayIcon = new TrayIcon(buildTrayImage(), tooltip, menu);
            trayIcon.setImageAutoSize(true);
            // 双击托盘图标（Windows/Linux 支持）= 显示主窗口
            trayIcon.addActionListener(e -> dispatch(onShowWindow));

            tray.add(trayIcon);
            installed = true;
            log.info("系统托盘已安装");
            return true;
        } catch (Exception e) {
            log.warn("系统托盘安装失败，将回退为关闭即退出模式: {}", e.getMessage());
            trayIcon = null;
            installed = false;
            return false;
        }
    }

    public boolean isInstalled() {
        return installed;
    }

    /** 弹出托盘气泡通知（信息级）。未安装时静默忽略。 */
    public void displayInfo(String title, String message) {
        if (trayIcon == null) return;
        try {
            trayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO);
        } catch (Exception e) {
            log.debug("托盘通知发送失败: {}", e.getMessage());
        }
    }

    /** 移除托盘图标（应用退出时调用）。可安全重复调用。 */
    public void remove() {
        if (trayIcon != null && SystemTray.isSupported()) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
            } catch (Exception e) {
                log.debug("移除托盘图标失败: {}", e.getMessage());
            }
        }
        trayIcon = null;
        installed = false;
    }

    private MenuItem buildItem(String label, Runnable action) {
        MenuItem item = new MenuItem(label);
        item.addActionListener(e -> dispatch(action));
        return item;
    }

    /** 把托盘菜单动作切回 JavaFX 线程执行，吞异常避免 AWT 线程崩溃。 */
    private void dispatch(Runnable action) {
        if (action == null) return;
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Exception e) {
                log.warn("托盘菜单动作执行失败: {}", e.getMessage(), e);
            }
        });
    }

    /** 程序化绘制托盘图标：品牌绿圆角方块 + 白色 "JC" 字样，浅/深色菜单栏均可辨识。 */
    private static Image buildTrayImage() {
        int size = 32;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x2E, 0x9A, 0x6A));
        g.fillRoundRect(1, 1, size - 2, size - 2, 10, 10);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        FontMetrics fm = g.getFontMetrics();
        String text = "JC";
        int tx = (size - fm.stringWidth(text)) / 2;
        int ty = (size - fm.getHeight()) / 2 + fm.getAscent();
        g.drawString(text, tx, ty);
        g.dispose();
        return img;
    }
}
