package com.javaclaw.desktop.adapter;

import com.javaclaw.desktop.Capabilities;
import com.javaclaw.desktop.DesktopException;
import com.javaclaw.desktop.UiElement;
import com.javaclaw.desktop.WindowRef;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * macOS 桌面自动化适配器 —— 基于系统自带的 {@code open} / {@code osascript}（AppleScript）。
 *
 * <p>无需任何额外安装：{@code osascript} 本质是 Apple 一等公民维护的 AppleEvent + 无障碍 API 的
 * 命令行入口，能力上接近 JNA 直调 AXUIElement，但子进程隔离、崩溃不波及 JVM。</p>
 *
 * <p>权限须知：枚举 / 激活窗口经 System Events，需在「系统设置 → 隐私与安全 → 辅助功能」中
 * 授权运行 JVM 的进程；未授权时相关命令报错，{@link #probe()} 会探测到并在提示里引导授权。</p>
 */
public final class MacCliAdapter extends AbstractCliAdapter {

    /**
     * 枚举可见窗口的 AppleScript：遍历非后台进程的每个窗口，逐行输出
     * {@code 应用名 \t x \t y \t w \t h \t 标题}（标题放末尾，即便含制表符也不破坏前 5 个字段）。
     */
    private static final String SCRIPT_LIST_WINDOWS = """
            set TAB to (ASCII character 9)
            set NL to (ASCII character 10)
            set output to ""
            tell application "System Events"
                repeat with proc in (every process whose background only is false)
                    set procName to name of proc
                    repeat with win in (windows of proc)
                        try
                            set {wx, wy} to position of win
                            set {ww, wh} to size of win
                            set output to output & procName & TAB & wx & TAB & wy & TAB & ww & TAB & wh & TAB & (name of win) & NL
                        end try
                    end repeat
                end repeat
            end tell
            return output
            """;

    /** 探测无障碍授权用的轻量脚本：能数出前台进程数即说明已授权。 */
    private static final String SCRIPT_PROBE =
            "tell application \"System Events\" to return count of (every process whose background only is false)";

    /**
     * 读取<b>当前前台窗口</b>所有 UI 元素的 AppleScript：遍历窗口的 entire contents，逐行输出
     * {@code 角色 \t x \t y \t w \t h \t 名称}（名称优先取 title，退而取 value、再退 description）。
     * 各属性用 try 包裹——不同控件支持的属性不同，缺失即跳过该项，绝不中断整体枚举。
     */
    private static final String SCRIPT_SNAPSHOT = """
            set TAB to (ASCII character 9)
            set NL to (ASCII character 10)
            set output to ""
            tell application "System Events"
                set frontApp to first process whose frontmost is true
                tell frontApp
                    if (count of windows) is 0 then return ""
                    set theWindow to front window
                    repeat with el in (entire contents of theWindow)
                        try
                            set elRole to role of el
                            set elPos to position of el
                            set elSize to size of el
                            set elName to ""
                            try
                                set elName to title of el
                            end try
                            if elName is "" then
                                try
                                    set elName to (value of el) as string
                                end try
                            end if
                            if elName is "" then
                                try
                                    set elName to description of el
                                end try
                            end if
                            set output to output & elRole & TAB & (item 1 of elPos) & TAB & (item 2 of elPos) & TAB & (item 1 of elSize) & TAB & (item 2 of elSize) & TAB & elName & NL
                        end try
                    end repeat
                end tell
            end tell
            return output
            """;

    /** 仅保留这些可交互角色，过滤掉静态文本 / 分组容器等噪声，控制标注数量。 */
    private static final Set<String> INTERACTIVE_ROLES = Set.of(
            "axbutton", "axtextfield", "axtextarea", "axcheckbox", "axradiobutton",
            "axmenubutton", "axpopupbutton", "axcombobox", "axlink", "axslider",
            "axmenuitem", "axtab", "axincrementor", "axdisclosuretriangle");

    /** 单次快照最多返回的元素数，避免复杂窗口产出过长图例淹没模型。 */
    private static final int MAX_ELEMENTS = 60;

    @Override
    public String platform() {
        return "mac";
    }

    @Override
    public Capabilities probe() {
        boolean accessibilityGranted = false;
        String note = "";
        try {
            CliResult r = exec(8, "osascript", "-e", SCRIPT_PROBE);
            accessibilityGranted = r.ok();
            if (!r.ok()) {
                note = "窗口枚举 / 激活需在「系统设置 → 隐私与安全 → 辅助功能」授权当前程序；"
                        + "截屏需在「屏幕录制」授权。当前未授权时仅能整屏截图 + 视觉定位。";
            }
        } catch (DesktopException e) {
            note = "osascript 调用异常: " + e.getMessage();
        }
        // 启动程序仅依赖 open，恒可用；截屏 / 键鼠由 RobotInput 基座提供（此处报告为 true，实际可用性由基座决定）
        return new Capabilities(
                "mac",
                accessibilityGranted,   // 枚举窗口
                accessibilityGranted,   // 激活窗口
                true,                   // 启动程序（open）
                true,                   // 截屏（Robot 基座）
                true,                   // 键鼠（Robot 基座）
                accessibilityGranted,   // 无障碍树（Tier 2，与窗口枚举同一授权）
                note);
    }

    @Override
    public void launch(String app, String path) {
        boolean hasApp = app != null && !app.isBlank();
        boolean hasPath = path != null && !path.isBlank();
        if (!hasApp && !hasPath) {
            throw new DesktopException("启动程序需至少提供 app 或 path 之一");
        }
        CliResult r;
        if (hasApp && hasPath) {
            r = exec("open", "-a", app, path);
        } else if (hasApp) {
            r = exec("open", "-a", app);
        } else {
            r = exec("open", path);
        }
        if (!r.ok()) {
            throw new DesktopException("启动失败: " + firstNonBlank(r.stderr(), r.stdout()));
        }
    }

    @Override
    public List<WindowRef> listWindows() {
        CliResult r = exec("osascript", "-e", SCRIPT_LIST_WINDOWS);
        if (!r.ok()) {
            throw new DesktopException("枚举窗口失败（可能未授予辅助功能权限）: "
                    + firstNonBlank(r.stderr(), r.stdout()));
        }
        List<WindowRef> windows = new ArrayList<>();
        for (String line : r.stdout().split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            // 标题在末尾，限制 split 为 6 段以保留标题中可能含的制表符
            String[] f = line.split("\t", 6);
            if (f.length < 6) {
                continue;
            }
            Rectangle bounds = parseBounds(f[1], f[2], f[3], f[4]);
            // id 用应用名：macOS 的窗口激活以应用为粒度（activate 把整个 App 带到前台）
            windows.add(new WindowRef(f[0], f[0], f[5], bounds));
        }
        return windows;
    }

    @Override
    public void activate(WindowRef window) {
        if (window == null || window.app() == null || window.app().isBlank()) {
            throw new DesktopException("激活窗口需要有效的应用名");
        }
        String script = "tell application \"" + escapeDoubling(window.app(), '"') + "\" to activate";
        CliResult r = exec("osascript", "-e", script);
        if (!r.ok()) {
            throw new DesktopException("激活失败: " + firstNonBlank(r.stderr(), r.stdout()));
        }
    }

    @Override
    public List<UiElement> snapshot(WindowRef window) {
        // window 参数在 macOS 上不直接使用：AX 以"前台窗口"为粒度，调用方应先 activate 目标窗口。
        // 复杂窗口 entire contents 可能较慢，给更宽裕的超时。
        CliResult r;
        try {
            r = exec(20, "osascript", "-e", SCRIPT_SNAPSHOT);
        } catch (DesktopException e) {
            log.debug("无障碍快照失败（降级为空，回退视觉路线）: {}", e.getMessage());
            return List.of();
        }
        if (!r.ok()) {
            log.debug("无障碍快照返回非零（可能未授权或窗口无元素）: {}", r.stderr().trim());
            return List.of();
        }
        List<UiElement> elements = new ArrayList<>();
        for (String line : r.stdout().split("\n")) {
            if (line.isBlank() || elements.size() >= MAX_ELEMENTS) {
                continue;
            }
            String[] f = line.split("\t", 6);
            if (f.length < 6) {
                continue;
            }
            String role = f[0].trim();
            if (!INTERACTIVE_ROLES.contains(role.toLowerCase(Locale.ROOT))) {
                continue; // 跳过静态文本 / 容器等非交互元素
            }
            Rectangle bounds = parseBounds(f[1], f[2], f[3], f[4]);
            if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
                continue; // 无有效包围盒的元素无法点击，丢弃
            }
            // ref 由上层（DesktopTools）统一编号，这里留空；置信度 1.0 表示来自无障碍树的精确坐标
            elements.add(new UiElement("", simplifyRole(role), f[5].trim(), bounds, 1.0));
        }
        return elements;
    }

    /** 把 AX 角色名（如 AXButton）简化为通用分类（button），便于跨平台统一与图例可读。 */
    private static String simplifyRole(String axRole) {
        String r = axRole.toLowerCase(Locale.ROOT);
        if (r.startsWith("ax")) {
            r = r.substring(2);
        }
        return r;
    }

    /** 解析四个坐标字段为矩形；任一非法则返回 null（视为"位置未知"，上层降级整屏截图）。 */
    private static Rectangle parseBounds(String x, String y, String w, String h) {
        try {
            return new Rectangle(
                    Integer.parseInt(x.trim()), Integer.parseInt(y.trim()),
                    Integer.parseInt(w.trim()), Integer.parseInt(h.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        return b == null ? "" : b.trim();
    }
}
