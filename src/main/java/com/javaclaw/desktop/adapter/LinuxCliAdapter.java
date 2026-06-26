package com.javaclaw.desktop.adapter;

import com.javaclaw.desktop.Capabilities;
import com.javaclaw.desktop.DesktopException;
import com.javaclaw.desktop.WindowRef;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * Linux 桌面自动化适配器 —— 基于 {@code wmctrl}（枚举 / 激活，自带几何信息）与 {@code xdg-open}（启动）。
 *
 * <p>与 macOS / Windows 不同，这些工具<b>多数发行版默认未装</b>，{@link #probe()} 会检测并提示
 * {@code apt install wmctrl}；缺失时整体降级为整屏截图 + 视觉定位，应用仍可用。</p>
 *
 * <p>Wayland 注意：Wayland 出于安全策略禁止全局键鼠注入，且 wmctrl(X11) 在纯 Wayland 下失效。
 * probe 会读取 {@code XDG_SESSION_TYPE} 给出提示，引导用户改用 X11 会话或配置 ydotool。</p>
 */
public final class LinuxCliAdapter extends AbstractCliAdapter {

    @Override
    public String platform() {
        return "linux";
    }

    @Override
    public Capabilities probe() {
        boolean wmctrl = commandExists("wmctrl");
        boolean wayland = "wayland".equalsIgnoreCase(System.getenv("XDG_SESSION_TYPE"));
        StringBuilder note = new StringBuilder();
        if (!wmctrl) {
            note.append("未找到 wmctrl，窗口枚举 / 激活不可用，建议安装：apt install wmctrl。");
        }
        if (wayland) {
            note.append("检测到 Wayland 会话：全局键鼠注入可能被系统禁止，"
                    + "wmctrl 也仅在 X11 下可靠，建议改用 X11 会话或配置 ydotool。");
        }
        return new Capabilities(
                "linux",
                wmctrl,             // 枚举窗口
                wmctrl,             // 激活窗口
                true,               // 启动程序（xdg-open / 直接 exec）
                true,               // 截屏（Robot 基座；Wayland 下可能失效）
                !wayland,           // 键鼠（Wayland 下基座注入可能无效）
                false,              // 无障碍树（AT-SPI，暂未接入）
                note.toString());
    }

    @Override
    public void launch(String app, String path) {
        boolean hasApp = app != null && !app.isBlank();
        boolean hasPath = path != null && !path.isBlank();
        if (!hasApp && !hasPath) {
            throw new DesktopException("启动程序需至少提供 app 或 path 之一");
        }
        CliResult r;
        if (hasApp) {
            // 直接以可执行名启动，附带可选路径参数
            r = hasPath ? exec(app, path) : exec(app);
        } else {
            // 仅有路径：交给桌面环境用默认程序打开
            r = exec("xdg-open", path);
        }
        if (!r.ok()) {
            throw new DesktopException("启动失败: " + r.stderr().trim());
        }
    }

    @Override
    public List<WindowRef> listWindows() {
        // wmctrl -lG 输出：窗口id  desktop  x  y  w  h  host  标题...
        CliResult r = exec("wmctrl", "-lG");
        if (!r.ok()) {
            throw new DesktopException("枚举窗口失败（需要 wmctrl 且为 X11 会话）: " + r.stderr().trim());
        }
        List<WindowRef> windows = new ArrayList<>();
        for (String line : r.stdout().split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            // 前 7 个字段定长，标题在第 8 段（可含空格）
            String[] f = line.trim().split("\\s+", 8);
            if (f.length < 8) {
                continue;
            }
            Rectangle bounds = parseBounds(f[2], f[3], f[4], f[5]);
            String title = f[7];
            windows.add(new WindowRef(f[0], appNameOf(title), title, bounds));
        }
        return windows;
    }

    @Override
    public void activate(WindowRef window) {
        if (window == null || window.id() == null || window.id().isBlank()) {
            throw new DesktopException("激活窗口需要有效的窗口 id");
        }
        // -i 按窗口 id 激活，-a 切换到该窗口
        CliResult r = exec("wmctrl", "-ia", window.id());
        if (!r.ok()) {
            throw new DesktopException("激活失败: " + r.stderr().trim());
        }
    }

    /** 从标题粗取一个应用名（Linux 窗口标题常形如 "文档 - 应用名"，取末段近似）。 */
    private static String appNameOf(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        int dash = title.lastIndexOf(" - ");
        return dash >= 0 ? title.substring(dash + 3).trim() : title.trim();
    }

    private static Rectangle parseBounds(String x, String y, String w, String h) {
        try {
            return new Rectangle(
                    Integer.parseInt(x.trim()), Integer.parseInt(y.trim()),
                    Integer.parseInt(w.trim()), Integer.parseInt(h.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
