package com.javaclaw.desktop;

import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.config.DataManager;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 桌面自动化工具集 —— "操作任意软件"能力对智能体暴露的 {@code @Tool} 门面。
 *
 * <p>本类只做编排与策略，不含平台逻辑：把跨平台的 {@link DesktopAutomationPort}（窗口枚举 / 激活 /
 * 启动，按 OS 自动选适配器）与纯 Java 的 {@link RobotInput}（截屏 / 键鼠基座）组合成一组工具，
 * 并在每个动作前接入 {@link ToolConfirmationManager} 风险确认、统一返回 {@link ToolResponse} 格式。</p>
 *
 * <p>典型工作流（视觉路线，跨平台通用）：
 * {@code desktop_launch 打开目标程序 → desktop_capture 截图 → 视觉模型看懂界面定位坐标 →
 * desktop_click / desktop_type 操作}。窗口枚举 / 激活在支持的平台上让定位更准，缺失则降级整屏截图。</p>
 *
 * @see DesktopAutomation 适配器工厂
 */
public class DesktopTools {

    private static final Logger log = LoggerFactory.getLogger(DesktopTools.class);

    private final DesktopAutomationPort port;
    private final RobotInput input;

    /**
     * 最近一次 {@code desktop_inspect} 产出的 {@code @ref → 元素} 映射（屏幕绝对坐标）。
     *
     * <p>承载 Tier 2 的"先标注、后按编号操作"两步交互：inspect 时填充，{@code desktop_click_ref} /
     * {@code desktop_type_ref} 据此解析坐标。每次 inspect 全量重建，避免窗口变化后引用失效误点。
     * 用并发 Map 以防工具在不同线程被调用。</p>
     */
    private final Map<String, UiElement> lastElements = new ConcurrentHashMap<>();

    public DesktopTools() {
        this.port = DesktopAutomation.get();
        this.input = new RobotInput();
        log.info("桌面自动化工具初始化完成: 适配器={}, 键鼠基座可用={}", port.platform(), input.isAvailable());
    }

    // ==================== 能力探测 ====================

    @Tool(name = "desktop_probe",
            description = "探测当前系统的桌面自动化能力（能否枚举窗口、激活窗口、启动程序、截屏、键鼠注入），"
                    + "并给出权限 / 依赖缺失的提示。操作其他软件前建议先调用以确认可用路径。")
    public String probe() {
        log.debug("工具调用: desktop_probe()");
        try {
            Capabilities caps = port.probe();
            String report = caps.report()
                    + "\n  键鼠基座实际可用: " + (input.isAvailable() ? "是" : "否（headless / 权限不足）");
            return ToolResponse.success("desktop_probe", "\n" + report);
        } catch (Exception e) {
            log.error("desktop_probe 执行异常", e);
            return ToolResponse.fromException("desktop_probe", e);
        }
    }

    // ==================== 启动 / 窗口管理 ====================

    @Tool(name = "desktop_launch",
            description = "启动一个桌面程序，可选同时打开指定文件 / 目录。app 与 path 至少提供一个。"
                    + "例如启动 VS Code 并打开项目：app=\"Visual Studio Code\", path=\"/path/to/project\"。")
    public String launch(
            @ToolParam(name = "app", description = "应用名或可执行文件路径，可空", required = false) String app,
            @ToolParam(name = "path", description = "要打开的文件 / 目录路径，可空", required = false) String path) {
        log.debug("工具调用: desktop_launch(app={}, path={})", app, path);
        if (!ToolConfirmationManager.requestConfirmation("desktop_launch",
                "启动程序: " + firstNonBlank(app, path))) {
            return ToolResponse.error("desktop_launch", "用户取消了操作");
        }
        try {
            port.launch(app, path);
            return ToolResponse.success("desktop_launch", "已启动: " + firstNonBlank(app, path));
        } catch (Exception e) {
            log.error("desktop_launch 执行异常", e);
            return ToolResponse.fromException("desktop_launch", e);
        }
    }

    @Tool(name = "desktop_list_windows",
            description = "枚举当前可见的应用窗口，返回每个窗口的应用名、标题与屏幕位置尺寸。"
                    + "用于在操作前了解有哪些窗口、确定目标窗口。某些平台可能只返回标题而无位置。")
    public String listWindows() {
        log.debug("工具调用: desktop_list_windows()");
        try {
            List<WindowRef> windows = port.listWindows();
            if (windows.isEmpty()) {
                return ToolResponse.success("desktop_list_windows", "未枚举到可见窗口");
            }
            StringBuilder sb = new StringBuilder("共 ").append(windows.size()).append(" 个窗口:\n");
            for (int i = 0; i < windows.size(); i++) {
                sb.append(i + 1).append(". ").append(windows.get(i).describe()).append('\n');
            }
            return ToolResponse.success("desktop_list_windows", sb.toString().stripTrailing());
        } catch (Exception e) {
            log.error("desktop_list_windows 执行异常", e);
            return ToolResponse.fromException("desktop_list_windows", e);
        }
    }

    @Tool(name = "desktop_activate",
            description = "把匹配的窗口激活到前台（按应用名或标题模糊匹配）。注入键鼠前应先激活目标窗口，"
                    + "确保输入落在正确的程序上。")
    public String activate(
            @ToolParam(name = "target", description = "目标应用名或窗口标题的关键词（模糊匹配）") String target) {
        log.debug("工具调用: desktop_activate(target={})", target);
        if (!ToolConfirmationManager.requestConfirmation("desktop_activate", "激活窗口: " + target)) {
            return ToolResponse.error("desktop_activate", "用户取消了操作");
        }
        try {
            Optional<WindowRef> match = findWindow(target);
            if (match.isEmpty()) {
                return ToolResponse.error("desktop_activate", "未找到匹配窗口: " + target);
            }
            port.activate(match.get());
            return ToolResponse.success("desktop_activate", "已激活: " + match.get().describe());
        } catch (Exception e) {
            log.error("desktop_activate 执行异常", e);
            return ToolResponse.fromException("desktop_activate", e);
        }
    }

    // ==================== 读取界面（截图 → 视觉模型） ====================

    @Tool(name = "desktop_capture",
            description = "截取屏幕并保存为 PNG，返回文件路径，供视觉模型理解界面内容。"
                    + "提供 target 时尝试激活并只截该窗口区域；不提供或位置未知时截取整个屏幕。"
                    + "这是读取其他程序界面内容的主要方式。")
    public String capture(
            @ToolParam(name = "target", description = "目标应用名或窗口标题关键词，可空（空则整屏截图）",
                    required = false) String target) {
        log.debug("工具调用: desktop_capture(target={})", target);
        if (!ToolConfirmationManager.requestConfirmation("desktop_capture",
                target == null || target.isBlank() ? "截取整个屏幕" : "截取窗口: " + target)) {
            return ToolResponse.error("desktop_capture", "用户取消了操作");
        }
        try {
            BufferedImage image;
            String scope;
            Optional<WindowRef> match = (target == null || target.isBlank())
                    ? Optional.empty() : findWindow(target);
            if (match.isPresent() && match.get().hasBounds()) {
                // 先激活再按窗口区域截图，避免被其他窗口遮挡
                tryActivate(match.get());
                image = input.captureRegion(match.get().bounds());
                scope = "窗口 " + match.get().describe();
            } else {
                image = input.captureVirtualScreen();
                scope = match.isPresent() ? "整屏（目标窗口位置未知）" : "整屏";
            }
            Path saved = saveImage(image);
            return ToolResponse.success("desktop_capture",
                    scope + " 截图已保存: " + saved.toAbsolutePath());
        } catch (Exception e) {
            log.error("desktop_capture 执行异常", e);
            return ToolResponse.fromException("desktop_capture", e);
        }
    }

    // ==================== 结构化定位（Set-of-Mark，Tier 2） ====================

    @Tool(name = "desktop_inspect",
            description = "检视目标窗口的可交互元素（按钮/输入框等），为每个元素分配编号 @ref，"
                    + "生成带编号方框的标注截图并返回元素清单。随后用 desktop_click_ref / desktop_type_ref "
                    + "按编号精确操作，无需猜坐标。若当前系统无法读取元素（无障碍未授权/平台不支持），"
                    + "会提示改用 desktop_capture + 坐标方式。")
    public String inspect(
            @ToolParam(name = "target", description = "目标应用名或窗口标题关键词，可空（空则检视当前前台窗口）",
                    required = false) String target) {
        log.debug("工具调用: desktop_inspect(target={})", target);
        if (!ToolConfirmationManager.requestConfirmation("desktop_inspect",
                "检视窗口元素: " + (target == null || target.isBlank() ? "前台窗口" : target))) {
            return ToolResponse.error("desktop_inspect", "用户取消了操作");
        }
        try {
            // 1. 定位并激活目标窗口（激活后它成为前台，无障碍快照才取到正确窗口）
            Optional<WindowRef> match = (target == null || target.isBlank())
                    ? Optional.empty() : findWindow(target);
            if (target != null && !target.isBlank() && match.isEmpty()) {
                return ToolResponse.error("desktop_inspect", "未找到匹配窗口: " + target);
            }
            WindowRef window = match.orElse(null);
            if (window != null) {
                tryActivate(window);
            }

            // 2. 读无障碍元素树；为空则降级到纯截图 + 坐标路线
            List<UiElement> raw = port.snapshot(window);
            if (raw.isEmpty()) {
                Path shot = saveImage(captureFor(window));
                return ToolResponse.success("desktop_inspect",
                        "当前窗口未读取到结构化元素（无障碍未授权或平台不支持）。已保存普通截图: "
                                + shot.toAbsolutePath() + "\n请改用 desktop_capture 看图后以 desktop_click 坐标方式操作。");
            }

            // 3. 统一编号 e1..eN，刷新 ref 映射（屏幕绝对坐标用于后续点击）
            lastElements.clear();
            List<UiElement> numbered = new ArrayList<>(raw.size());
            for (int i = 0; i < raw.size(); i++) {
                UiElement e = raw.get(i);
                String ref = "e" + (i + 1);
                UiElement withRef = new UiElement(ref, e.role(), e.name(), e.bounds(), e.confidence());
                numbered.add(withRef);
                lastElements.put(ref, withRef);
            }

            // 4. 截图 + Set-of-Mark 标注。元素坐标是屏幕逻辑坐标，需：①减去截图区域原点得本地坐标；
            //    ②再按"截图像素 / 区域逻辑尺寸"缩放——Retina / 高 DPI 下截图像素是逻辑尺寸的整数倍，
            //    不缩放会导致方框偏到左上角并缩小。元素的逻辑坐标仍原样保留用于后续 Robot 点击。
            BufferedImage shot = captureFor(window);
            Rectangle region = (window != null && window.hasBounds())
                    ? window.bounds() : input.virtualBounds();
            Point origin = region.getLocation();
            double sx = region.width > 0 ? (double) shot.getWidth() / region.width : 1.0;
            double sy = region.height > 0 ? (double) shot.getHeight() / region.height : 1.0;
            List<SetOfMarkRenderer.Mark> marks = new ArrayList<>(numbered.size());
            for (UiElement e : numbered) {
                Rectangle b = e.bounds();
                Rectangle local = new Rectangle(
                        (int) Math.round((b.x - origin.x) * sx),
                        (int) Math.round((b.y - origin.y) * sy),
                        (int) Math.round(b.width * sx),
                        (int) Math.round(b.height * sy));
                marks.add(new SetOfMarkRenderer.Mark(e.ref(), local));
            }
            Path saved = saveImage(SetOfMarkRenderer.render(shot, marks));

            // 5. 返回文字图例（模型可仅凭名称/角色选编号，无需依赖视觉）+ 标注图路径
            StringBuilder legend = new StringBuilder("可交互元素 ")
                    .append(numbered.size()).append(" 个（标注截图: ")
                    .append(saved.toAbsolutePath()).append("）:\n");
            for (UiElement e : numbered) {
                legend.append("  ").append(e.ref()).append(": ").append(e.role());
                if (e.name() != null && !e.name().isBlank()) {
                    legend.append(" 「").append(e.name()).append("」");
                }
                legend.append('\n');
            }
            return ToolResponse.success("desktop_inspect", legend.toString().stripTrailing());
        } catch (Exception e) {
            log.error("desktop_inspect 执行异常", e);
            return ToolResponse.fromException("desktop_inspect", e);
        }
    }

    @Tool(name = "desktop_click_ref",
            description = "点击 desktop_inspect 标注过的某个元素（按其 @ref 编号，如 e7），自动定位到元素中心。"
                    + "比坐标点击更精确，应优先使用。")
    public String clickRef(
            @ToolParam(name = "ref", description = "元素编号，来自最近一次 desktop_inspect，如 e3") String ref,
            @ToolParam(name = "clicks", description = "点击次数: 1 单击、2 双击，默认 1",
                    required = false) int clicks) {
        int n = clicks <= 0 ? 1 : clicks;
        log.debug("工具调用: desktop_click_ref(ref={}, clicks={})", ref, n);
        UiElement element = lastElements.get(ref == null ? "" : ref.trim());
        if (element == null) {
            return ToolResponse.error("desktop_click_ref",
                    "无效编号 " + ref + "，请先调用 desktop_inspect 获取元素编号");
        }
        if (!ToolConfirmationManager.requestConfirmation("desktop_click_ref",
                "点击元素 " + ref + " " + elementLabel(element))) {
            return ToolResponse.error("desktop_click_ref", "用户取消了操作");
        }
        try {
            Point c = element.center();
            input.clickAt(c.x, c.y, "left", n);
            return ToolResponse.success("desktop_click_ref",
                    "已点击 " + ref + " " + elementLabel(element));
        } catch (Exception e) {
            log.error("desktop_click_ref 执行异常", e);
            return ToolResponse.fromException("desktop_click_ref", e);
        }
    }

    @Tool(name = "desktop_type_ref",
            description = "向 desktop_inspect 标注过的某个输入框（按 @ref 编号）输入文本：先点击聚焦该元素再输入，"
                    + "支持中文。")
    public String typeRef(
            @ToolParam(name = "ref", description = "目标输入框编号，来自最近一次 desktop_inspect") String ref,
            @ToolParam(name = "text", description = "要输入的文本") String text) {
        log.debug("工具调用: desktop_type_ref(ref={}, len={})", ref, text == null ? 0 : text.length());
        UiElement element = lastElements.get(ref == null ? "" : ref.trim());
        if (element == null) {
            return ToolResponse.error("desktop_type_ref",
                    "无效编号 " + ref + "，请先调用 desktop_inspect 获取元素编号");
        }
        if (!ToolConfirmationManager.requestConfirmation("desktop_type_ref",
                "向元素 " + ref + " 输入: " + preview(text))) {
            return ToolResponse.error("desktop_type_ref", "用户取消了操作");
        }
        try {
            Point c = element.center();
            input.clickAt(c.x, c.y, "left", 1); // 先聚焦输入框
            input.typeText(text);
            return ToolResponse.success("desktop_type_ref",
                    "已向 " + ref + " 输入文本（" + (text == null ? 0 : text.length()) + " 字）");
        } catch (Exception e) {
            log.error("desktop_type_ref 执行异常", e);
            return ToolResponse.fromException("desktop_type_ref", e);
        }
    }

    // ==================== 控制输入（键鼠） ====================

    @Tool(name = "desktop_click",
            description = "在屏幕坐标 (x, y) 处点击。坐标可由 desktop_capture 截图后经视觉模型判读得到。"
                    + "支持左右中键与单 / 双击。")
    public String click(
            @ToolParam(name = "x", description = "屏幕 X 坐标（逻辑像素）") int x,
            @ToolParam(name = "y", description = "屏幕 Y 坐标（逻辑像素）") int y,
            @ToolParam(name = "button", description = "鼠标键: left / right / middle，默认 left",
                    required = false) String button,
            @ToolParam(name = "clicks", description = "点击次数: 1 单击、2 双击，默认 1",
                    required = false) int clicks) {
        int n = clicks <= 0 ? 1 : clicks;
        String btn = button == null || button.isBlank() ? "left" : button;
        log.debug("工具调用: desktop_click({}, {}, {}, {})", x, y, btn, n);
        if (!ToolConfirmationManager.requestConfirmation("desktop_click",
                String.format("%s键点击 (%d,%d) %d 次", btn, x, y, n))) {
            return ToolResponse.error("desktop_click", "用户取消了操作");
        }
        try {
            input.clickAt(x, y, btn, n);
            return ToolResponse.success("desktop_click",
                    String.format("已在 (%d,%d) %s键点击 %d 次", x, y, btn, n));
        } catch (Exception e) {
            log.error("desktop_click 执行异常", e);
            return ToolResponse.fromException("desktop_click", e);
        }
    }

    @Tool(name = "desktop_type",
            description = "向当前焦点控件输入文本（经系统剪贴板粘贴，支持中文与任意 Unicode）。"
                    + "输入前通常先 desktop_activate 激活目标窗口、再 desktop_click 聚焦输入框。")
    public String type(
            @ToolParam(name = "text", description = "要输入的文本") String text) {
        log.debug("工具调用: desktop_type(len={})", text == null ? 0 : text.length());
        if (!ToolConfirmationManager.requestConfirmation("desktop_type",
                "输入文本: " + preview(text))) {
            return ToolResponse.error("desktop_type", "用户取消了操作");
        }
        try {
            input.typeText(text);
            return ToolResponse.success("desktop_type", "已输入文本（" + (text == null ? 0 : text.length()) + " 字）");
        } catch (Exception e) {
            log.error("desktop_type 执行异常", e);
            return ToolResponse.fromException("desktop_type", e);
        }
    }

    @Tool(name = "desktop_key",
            description = "按下一个按键或组合键，如 enter、tab、esc、ctrl+c、cmd+v、alt+tab。"
                    + "cmd 在非 macOS 上自动等价为 ctrl。")
    public String key(
            @ToolParam(name = "combo", description = "按键 / 组合键，用 + 连接，如 ctrl+s") String combo) {
        log.debug("工具调用: desktop_key(combo={})", combo);
        if (!ToolConfirmationManager.requestConfirmation("desktop_key", "按键: " + combo)) {
            return ToolResponse.error("desktop_key", "用户取消了操作");
        }
        try {
            input.pressCombo(combo);
            return ToolResponse.success("desktop_key", "已按下: " + combo);
        } catch (Exception e) {
            log.error("desktop_key 执行异常", e);
            return ToolResponse.fromException("desktop_key", e);
        }
    }

    // ==================== 内部辅助 ====================

    /** 按应用名或标题关键词（不区分大小写）模糊匹配第一个窗口。 */
    private Optional<WindowRef> findWindow(String target) {
        if (target == null || target.isBlank()) {
            return Optional.empty();
        }
        String key = target.toLowerCase(Locale.ROOT);
        return port.listWindows().stream()
                .filter(w -> contains(w.app(), key) || contains(w.title(), key))
                .findFirst();
    }

    private static boolean contains(String s, String lowerKey) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(lowerKey);
    }

    /** 尽力激活窗口，失败仅记日志不阻断截图（窗口可能本就在前台）。 */
    private void tryActivate(WindowRef window) {
        try {
            port.activate(window);
            Thread.sleep(150); // 给窗口管理器一点时间完成前台切换，避免截到切换中的过渡帧
        } catch (Exception e) {
            log.debug("截图前激活窗口失败（忽略）: {}", e.getMessage());
        }
    }

    /** 按窗口截图：已知包围盒则截该窗口区域，否则截整个虚拟桌面（假定窗口已被激活到前台）。 */
    private BufferedImage captureFor(WindowRef window) {
        if (window != null && window.hasBounds()) {
            return input.captureRegion(window.bounds());
        }
        return input.captureVirtualScreen();
    }

    /** 元素的人类可读标签：角色 +（可选）名称，用于操作结果回显。 */
    private static String elementLabel(UiElement e) {
        if (e.name() != null && !e.name().isBlank()) {
            return e.role() + " 「" + e.name() + "」";
        }
        return e.role();
    }

    /** 把截图按时间戳保存到工作区截图目录。 */
    private Path saveImage(BufferedImage image) throws Exception {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        Path savePath = DataManager.getInstance().getScreenshotsDir().resolve("desktop_" + ts + ".png");
        ImageIO.write(image, "png", savePath.toFile());
        log.info("桌面截图已保存: {}", savePath);
        return savePath;
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : (b == null ? "" : b);
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 30 ? oneLine : oneLine.substring(0, 30) + "…";
    }
}
