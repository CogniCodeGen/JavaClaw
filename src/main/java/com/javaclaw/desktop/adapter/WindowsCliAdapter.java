package com.javaclaw.desktop.adapter;

import com.javaclaw.desktop.Capabilities;
import com.javaclaw.desktop.DesktopException;
import com.javaclaw.desktop.UiElement;
import com.javaclaw.desktop.WindowRef;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Windows 桌面自动化适配器 —— 基于系统自带的 {@code cmd start} 与 {@code PowerShell}。
 *
 * <p>无需额外安装：PowerShell 可内联调用 .NET / Win32（本适配器用其枚举带标题的进程窗口、
 * 用 WScript.Shell 激活窗口）。精确的"单窗口包围盒"需要 P/Invoke user32（{@code GetWindowRect}），
 * 属后续增强；v1 仅取标题，{@link WindowRef#bounds()} 留空，上层据此降级为整屏截图 + 视觉定位。</p>
 */
public final class WindowsCliAdapter extends AbstractCliAdapter {

    /** 枚举有可见标题的进程窗口，输出 {@code 进程名 \t 标题}（[char]9 即制表符）。 */
    private static final String PS_LIST_WINDOWS =
            "Get-Process | Where-Object { $_.MainWindowTitle -ne '' } | "
            + "ForEach-Object { \"$($_.ProcessName)$([char]9)$($_.MainWindowTitle)\" }";

    /**
     * 读取<b>当前前台窗口</b>的可交互元素的 PowerShell 脚本，基于 .NET 内置的 UI Automation
     * （{@code System.Windows.Automation}，Windows 自带，无需安装；Windows PowerShell 5.1 即可加载）。
     *
     * <p>流程：内联 P/Invoke 取前台窗口句柄 → {@code AutomationElement.FromHandle} → 在控件视图下
     * 枚举所有后代控件 → 逐个输出 {@code 控件类型 \t x \t y \t w \t h \t 名称}（坐标为屏幕<b>物理像素</b>，
     * 由 Java 侧按缩放比转换为逻辑坐标）。每个元素读取用 try 包裹，单个失败不影响整体。</p>
     */
    private static final String PS_SNAPSHOT = """
            $ErrorActionPreference = 'SilentlyContinue'
            Add-Type -AssemblyName UIAutomationClient
            Add-Type -AssemblyName UIAutomationTypes
            $sig = '[DllImport("user32.dll")] public static extern System.IntPtr GetForegroundWindow();'
            $null = Add-Type -MemberDefinition $sig -Name U -Namespace W -PassThru
            $hwnd = [W.U]::GetForegroundWindow()
            if ($hwnd -eq [System.IntPtr]::Zero) { return }
            $root = [System.Windows.Automation.AutomationElement]::FromHandle($hwnd)
            if ($root -eq $null) { return }
            $cond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::IsControlElementProperty, $true)
            $els = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $cond)
            $t = [char]9
            foreach ($el in $els) {
                try {
                    $r = $el.Current.BoundingRectangle
                    if ($r.Width -le 0 -or $r.Height -le 0 -or [double]::IsInfinity($r.X)) { continue }
                    $ct = $el.Current.ControlType.ProgrammaticName
                    $nm = $el.Current.Name
                    [Console]::Out.WriteLine(("{0}{5}{1}{5}{2}{5}{3}{5}{4}{5}{6}" -f $ct,[int]$r.X,[int]$r.Y,[int]$r.Width,[int]$r.Height,$t,$nm))
                } catch {}
            }
            """;

    /** 仅保留这些 UIA 控件类型（已去掉 ControlType. 前缀、小写），过滤静态文本/容器等噪声。 */
    private static final Set<String> INTERACTIVE_TYPES = Set.of(
            "button", "edit", "checkbox", "radiobutton", "combobox", "hyperlink",
            "menuitem", "tabitem", "listitem", "splitbutton", "slider", "spinner", "treeitem");

    /** 单次快照最多返回的元素数，避免复杂窗口产出过长图例。 */
    private static final int MAX_ELEMENTS = 60;

    @Override
    public String platform() {
        return "windows";
    }

    @Override
    public Capabilities probe() {
        boolean ps = commandExists("powershell");
        String note = ps
                ? "结构化元素经 UI Automation 读取（desktop_inspect）；单窗口精确截图（窗口 bounds）需后续 P/Invoke 支持，"
                + "当前 inspect 以整屏标注为主。"
                : "未找到 powershell，窗口枚举 / 激活 / 元素读取不可用，仅能整屏截图 + 键鼠（Robot 基座）。";
        return new Capabilities(
                "windows",
                ps,     // 枚举窗口
                ps,     // 激活窗口
                true,   // 启动程序（cmd start）
                true,   // 截屏（Robot 基座）
                true,   // 键鼠（Robot 基座）
                ps,     // 无障碍树（UI Automation，随 powershell 可用）
                note);
    }

    @Override
    public void launch(String app, String path) {
        boolean hasApp = app != null && !app.isBlank();
        boolean hasPath = path != null && !path.isBlank();
        if (!hasApp && !hasPath) {
            throw new DesktopException("启动程序需至少提供 app 或 path 之一");
        }
        // start 的第一个引号参数是窗口标题占位（必须保留，否则带空格的路径会被当成标题）
        List<String> cmd = new ArrayList<>(List.of("cmd", "/c", "start", ""));
        if (hasApp) {
            cmd.add(app);
        }
        if (hasPath) {
            cmd.add(path);
        }
        CliResult r = exec(cmd.toArray(String[]::new));
        if (!r.ok()) {
            throw new DesktopException("启动失败: " + r.stderr().trim());
        }
    }

    @Override
    public List<WindowRef> listWindows() {
        CliResult r = exec("powershell", "-NoProfile", "-Command", PS_LIST_WINDOWS);
        if (!r.ok()) {
            throw new DesktopException("枚举窗口失败: " + r.stderr().trim());
        }
        List<WindowRef> windows = new ArrayList<>();
        for (String line : r.stdout().split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split("\t", 2);
            String app = f[0].trim();
            String title = f.length > 1 ? f[1].trim() : "";
            // Windows 纯标题枚举拿不到包围盒，bounds 留空 → 上层降级整屏截图
            windows.add(new WindowRef(app, app, title, null));
        }
        return windows;
    }

    @Override
    public void activate(WindowRef window) {
        if (window == null || window.title() == null || window.title().isBlank()) {
            throw new DesktopException("激活窗口需要有效的窗口标题");
        }
        String title = escapeDoubling(window.title(), '\'');   // PowerShell 单引号字符串以双写转义
        String script = "$w = New-Object -ComObject WScript.Shell; $null = $w.AppActivate('" + title + "')";
        CliResult r = exec("powershell", "-NoProfile", "-Command", script);
        if (!r.ok()) {
            throw new DesktopException("激活失败: " + r.stderr().trim());
        }
    }

    @Override
    public List<UiElement> snapshot(WindowRef window) {
        // window 参数不直接使用：UIA 以"前台窗口"为粒度，调用方应先 activate 目标窗口。
        // 复杂窗口的 UIA 遍历可能较慢，给更宽裕的超时。
        CliResult r;
        try {
            r = exec(20, "powershell", "-NoProfile", "-Command", PS_SNAPSHOT);
        } catch (DesktopException e) {
            log.debug("UIA 快照失败（降级为空，回退视觉路线）: {}", e.getMessage());
            return List.of();
        }
        if (!r.ok()) {
            log.debug("UIA 快照返回非零（可能无前台窗口或缺少 UIAutomation 程序集）: {}", r.stderr().trim());
            return List.of();
        }
        // UIA 给出的是物理像素坐标；AWT Robot（DPI 感知进程）按逻辑坐标点击，故按屏幕缩放比换算为逻辑坐标。
        double scale = screenScale();
        List<UiElement> elements = new ArrayList<>();
        for (String line : r.stdout().split("\n")) {
            if (line.isBlank() || elements.size() >= MAX_ELEMENTS) {
                continue;
            }
            String[] f = line.split("\t", 6);
            if (f.length < 6) {
                continue;
            }
            String type = stripControlTypePrefix(f[0].trim());
            if (!INTERACTIVE_TYPES.contains(type)) {
                continue;
            }
            Rectangle bounds = parsePhysicalBounds(f[1], f[2], f[3], f[4], scale);
            if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
                continue;
            }
            // ref 由上层（DesktopTools）统一编号；置信度 1.0 表示来自 UIA 的精确坐标
            elements.add(new UiElement("", type, f[5].trim(), bounds, 1.0));
        }
        return elements;
    }

    /** 把 UIA 的 ProgrammaticName（如 {@code ControlType.Button}）去前缀并小写为通用分类（button）。 */
    private static String stripControlTypePrefix(String programmaticName) {
        String s = programmaticName.toLowerCase(Locale.ROOT);
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    /** 物理像素坐标按缩放比换算为逻辑坐标矩形；任一字段非法返回 null。 */
    private static Rectangle parsePhysicalBounds(String x, String y, String w, String h, double scale) {
        try {
            double s = scale <= 0 ? 1.0 : scale;
            return new Rectangle(
                    (int) Math.round(Integer.parseInt(x.trim()) / s),
                    (int) Math.round(Integer.parseInt(y.trim()) / s),
                    (int) Math.round(Integer.parseInt(w.trim()) / s),
                    (int) Math.round(Integer.parseInt(h.trim()) / s));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 主屏缩放比（如 150% → 1.5）；用于物理像素 → 逻辑坐标换算。无图形环境时回退 1.0。 */
    private static double screenScale() {
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration()
                    .getDefaultTransform().getScaleX();
        } catch (Exception e) {
            return 1.0;
        }
    }
}
