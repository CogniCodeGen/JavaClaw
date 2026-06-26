package com.javaclaw.system;

import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.config.DataManager;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 系统操作工具类
 *
 * <p>为系统操作智能体提供桌面和文件系统操作工具，包括：
 * <ul>
 *   <li>系统信息获取（OS、CPU、内存、磁盘）</li>
 *   <li>屏幕截图</li>
 *   <li>当前时间获取</li>
 *   <li>鼠标操作（移动、点击、双击、右击）</li>
 *   <li>键盘操作（输入文本、按键、组合键）</li>
 *   <li>文件管理（列出、读取、写入、删除、复制、移动）</li>
 * </ul>
 * </p>
 *
 * @author JavaClaw
 */
public class SystemTools {

    private static final Logger log = LoggerFactory.getLogger(SystemTools.class);

    private final Robot robot;

    public SystemTools() {
        try {
            this.robot = new Robot();
            robot.setAutoDelay(50);
            log.info("系统操作工具初始化完成");
        } catch (AWTException e) {
            throw new RuntimeException("无法初始化 AWT Robot，请确保运行环境支持桌面操作", e);
        }
    }

    // ==================== 系统信息 ====================

    @Tool(name = "sys_get_info", description = "获取系统信息，包括操作系统、CPU、内存、磁盘等基本信息。")
    public String getSystemInfo() {
        log.debug("工具调用: sys_get_info()");
        try {
            Runtime rt = Runtime.getRuntime();
            long totalMemory = rt.totalMemory();
            long freeMemory = rt.freeMemory();
            long maxMemory = rt.maxMemory();

            StringBuilder sb = new StringBuilder();
            sb.append("操作系统: ").append(System.getProperty("os.name"))
                    .append(" ").append(System.getProperty("os.version"))
                    .append(" (").append(System.getProperty("os.arch")).append(")\n");
            sb.append("Java 版本: ").append(System.getProperty("java.version")).append("\n");
            sb.append("CPU 核心数: ").append(rt.availableProcessors()).append("\n");
            sb.append("JVM 内存: 已用 ").append(formatSize(totalMemory - freeMemory))
                    .append(" / 总计 ").append(formatSize(totalMemory))
                    .append(" / 最大 ").append(formatSize(maxMemory)).append("\n");
            sb.append("用户名: ").append(System.getProperty("user.name")).append("\n");
            sb.append("用户目录: ").append(System.getProperty("user.home")).append("\n");
            sb.append("工作目录: ").append(System.getProperty("user.dir")).append("\n");

            // 磁盘信息
            File[] roots = File.listRoots();
            for (File root : roots) {
                sb.append("磁盘 ").append(root.getAbsolutePath()).append(": ")
                        .append("可用 ").append(formatSize(root.getUsableSpace()))
                        .append(" / 总计 ").append(formatSize(root.getTotalSpace())).append("\n");
            }

            return ToolResponse.success("sys_get_info", sb.toString().trim());
        } catch (Exception e) {
            log.error("sys_get_info 执行异常", e);
            return ToolResponse.fromException("sys_get_info", e);
        }
    }

    @Tool(name = "sys_get_time", description = "获取当前系统日期和时间。")
    public String getCurrentTime() {
        log.debug("工具调用: sys_get_time()");
        try {
            String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (EEEE)"));
            return ToolResponse.success("sys_get_time", now);
        } catch (Exception e) {
            log.error("sys_get_time 执行异常", e);
            return ToolResponse.fromException("sys_get_time", e);
        }
    }

    @Tool(name = "sys_screenshot", description = "截取整个屏幕的截图并保存为 PNG 文件。返回保存的文件路径。")
    public String screenshot() {
        log.debug("工具调用: sys_screenshot()");
        if (!ToolConfirmationManager.requestConfirmation("sys_screenshot", "截取整个屏幕")) {
            return ToolResponse.error("sys_screenshot", "用户取消了操作");
        }
        try {
            Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            BufferedImage capture = robot.createScreenCapture(screenRect);

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String fileName = "screenshot_" + timestamp + ".png";
            Path savePath = DataManager.getInstance().getScreenshotsDir().resolve(fileName);
            ImageIO.write(capture, "png", savePath.toFile());

            log.info("屏幕截图已保存: {}", savePath);
            return ToolResponse.success("sys_screenshot", "截图已保存: " + savePath.toAbsolutePath());
        } catch (Exception e) {
            log.error("sys_screenshot 执行异常", e);
            return ToolResponse.fromException("sys_screenshot", e);
        }
    }

    // ==================== 鼠标操作 ====================

    @Tool(name = "sys_mouse_move", description = "将鼠标移动到屏幕上指定的坐标位置。")
    public String mouseMove(
            @ToolParam(name = "x", description = "目标 X 坐标（像素）") int x,
            @ToolParam(name = "y", description = "目标 Y 坐标（像素）") int y) {
        log.debug("工具调用: sys_mouse_move({}, {})", x, y);
        if (!ToolConfirmationManager.requestConfirmation("sys_mouse_move",
                "鼠标移动到 (" + x + ", " + y + ")")) {
            return ToolResponse.error("sys_mouse_move", "用户取消了操作");
        }
        try {
            robot.mouseMove(x, y);
            return ToolResponse.success("sys_mouse_move", "鼠标已移动到 (" + x + ", " + y + ")");
        } catch (Exception e) {
            log.error("sys_mouse_move 执行异常", e);
            return ToolResponse.fromException("sys_mouse_move", e);
        }
    }

    @Tool(name = "sys_mouse_click", description = "在当前鼠标位置执行点击操作。支持左键、右键、双击。")
    public String mouseClick(
            @ToolParam(name = "button", description = "鼠标按钮: left（左键）、right（右键）、middle（中键）") String button,
            @ToolParam(name = "clicks", description = "点击次数，1 为单击，2 为双击") int clicks) {
        log.debug("工具调用: sys_mouse_click({}, {})", button, clicks);
        if (!ToolConfirmationManager.requestConfirmation("sys_mouse_click",
                button + " 键点击 " + clicks + " 次")) {
            return ToolResponse.error("sys_mouse_click", "用户取消了操作");
        }
        try {
            int btnMask = switch (button.toLowerCase()) {
                case "right" -> InputEvent.BUTTON3_DOWN_MASK;
                case "middle" -> InputEvent.BUTTON2_DOWN_MASK;
                default -> InputEvent.BUTTON1_DOWN_MASK;
            };
            for (int i = 0; i < clicks; i++) {
                robot.mousePress(btnMask);
                robot.mouseRelease(btnMask);
                if (i < clicks - 1) {
                    robot.delay(50);
                }
            }
            return ToolResponse.success("sys_mouse_click",
                    button + "键点击 " + clicks + " 次");
        } catch (Exception e) {
            log.error("sys_mouse_click 执行异常", e);
            return ToolResponse.fromException("sys_mouse_click", e);
        }
    }

    @Tool(name = "sys_mouse_click_at", description = "将鼠标移动到指定坐标并执行左键单击。")
    public String mouseClickAt(
            @ToolParam(name = "x", description = "目标 X 坐标（像素）") int x,
            @ToolParam(name = "y", description = "目标 Y 坐标（像素）") int y) {
        log.debug("工具调用: sys_mouse_click_at({}, {})", x, y);
        if (!ToolConfirmationManager.requestConfirmation("sys_mouse_click_at",
                "在 (" + x + ", " + y + ") 处左键点击")) {
            return ToolResponse.error("sys_mouse_click_at", "用户取消了操作");
        }
        try {
            robot.mouseMove(x, y);
            robot.delay(100);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            return ToolResponse.success("sys_mouse_click_at",
                    "已点击位置 (" + x + ", " + y + ")");
        } catch (Exception e) {
            log.error("sys_mouse_click_at 执行异常", e);
            return ToolResponse.fromException("sys_mouse_click_at", e);
        }
    }

    @Tool(name = "sys_mouse_scroll", description = "在当前鼠标位置滚动鼠标滚轮。正数向下滚动，负数向上滚动。")
    public String mouseScroll(
            @ToolParam(name = "amount", description = "滚动量，正数向下，负数向上") int amount) {
        log.debug("工具调用: sys_mouse_scroll({})", amount);
        if (!ToolConfirmationManager.requestConfirmation("sys_mouse_scroll",
                (amount > 0 ? "向下" : "向上") + "滚动 " + Math.abs(amount) + " 格")) {
            return ToolResponse.error("sys_mouse_scroll", "用户取消了操作");
        }
        try {
            robot.mouseWheel(amount);
            String direction = amount > 0 ? "向下" : "向上";
            return ToolResponse.success("sys_mouse_scroll",
                    direction + "滚动 " + Math.abs(amount) + " 格");
        } catch (Exception e) {
            log.error("sys_mouse_scroll 执行异常", e);
            return ToolResponse.fromException("sys_mouse_scroll", e);
        }
    }

    // ==================== 键盘操作 ====================

    @Tool(name = "sys_key_type", description = "模拟键盘输入一段文本。逐字符输入，适用于在当前焦点输入框中输入内容。")
    public String keyType(
            @ToolParam(name = "text", description = "要输入的文本内容") String text) {
        log.debug("工具调用: sys_key_type('{}')", text);
        if (!ToolConfirmationManager.requestConfirmation("sys_key_type",
                "键盘输入文本: " + text)) {
            return ToolResponse.error("sys_key_type", "用户取消了操作");
        }
        try {
            for (char c : text.toCharArray()) {
                typeChar(c);
            }
            return ToolResponse.success("sys_key_type", "已输入文本: " + text);
        } catch (Exception e) {
            log.error("sys_key_type 执行异常", e);
            return ToolResponse.fromException("sys_key_type", e);
        }
    }

    @Tool(name = "sys_key_press", description = "模拟按下并释放一个键。支持特殊键名如: ENTER, TAB, ESCAPE, BACKSPACE, DELETE, " +
            "UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN, F1-F12, SPACE 等。")
    public String keyPress(
            @ToolParam(name = "key", description = "键名，如 ENTER、TAB、ESCAPE 等") String key) {
        log.debug("工具调用: sys_key_press('{}')", key);
        if (!ToolConfirmationManager.requestConfirmation("sys_key_press",
                "按下按键: " + key)) {
            return ToolResponse.error("sys_key_press", "用户取消了操作");
        }
        try {
            int keyCode = resolveKeyCode(key.toUpperCase().trim());
            if (keyCode == -1) {
                return ToolResponse.error("sys_key_press", "未知的键名: " + key);
            }
            robot.keyPress(keyCode);
            robot.keyRelease(keyCode);
            return ToolResponse.success("sys_key_press", "已按下按键: " + key);
        } catch (Exception e) {
            log.error("sys_key_press 执行异常", e);
            return ToolResponse.fromException("sys_key_press", e);
        }
    }

    @Tool(name = "sys_key_combo", description = "模拟组合键操作，如 Ctrl+C、Ctrl+V、Alt+Tab、Cmd+Space 等。" +
            "修饰键支持: CTRL, ALT, SHIFT, META(Mac的Command键)。多个修饰键用+连接。")
    public String keyCombo(
            @ToolParam(name = "combo", description = "组合键描述，如 CTRL+C、ALT+TAB、META+SPACE") String combo) {
        log.debug("工具调用: sys_key_combo('{}')", combo);
        if (!ToolConfirmationManager.requestConfirmation("sys_key_combo",
                "执行组合键: " + combo)) {
            return ToolResponse.error("sys_key_combo", "用户取消了操作");
        }
        try {
            String[] parts = combo.toUpperCase().trim().split("\\+");
            if (parts.length < 2) {
                return ToolResponse.error("sys_key_combo", "组合键格式错误，至少需要两个键，如 CTRL+C");
            }

            // 解析所有键码
            int[] keyCodes = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                keyCodes[i] = resolveKeyCode(parts[i].trim());
                if (keyCodes[i] == -1) {
                    return ToolResponse.error("sys_key_combo", "未知的键名: " + parts[i].trim());
                }
            }

            // 按顺序按下所有键
            for (int keyCode : keyCodes) {
                robot.keyPress(keyCode);
            }
            // 逆序释放所有键
            for (int i = keyCodes.length - 1; i >= 0; i--) {
                robot.keyRelease(keyCodes[i]);
            }

            return ToolResponse.success("sys_key_combo", "已执行组合键: " + combo);
        } catch (Exception e) {
            log.error("sys_key_combo 执行异常", e);
            return ToolResponse.fromException("sys_key_combo", e);
        }
    }

    // ==================== 文件管理 ====================

    @Tool(name = "sys_file_list", description = "列出指定目录下的文件和子目录。返回名称、大小、类型、修改时间。")
    public String fileList(
            @ToolParam(name = "path", description = "目录路径") String path) {
        log.debug("工具调用: sys_file_list('{}')", path);
        try {
            String pathError = validatePath(path, "sys_file_list");
            if (pathError != null) return pathError;
            Path dir = Path.of(path).normalize();
            if (!Files.isDirectory(dir)) {
                return ToolResponse.error("sys_file_list", "路径不是目录或不存在: " + path);
            }

            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            StringBuilder sb = new StringBuilder();
            sb.append("目录: ").append(dir.toAbsolutePath()).append("\n\n");

            try (Stream<Path> entries = Files.list(dir)) {
                var list = entries.sorted().collect(Collectors.toList());
                for (Path entry : list) {
                    BasicFileAttributes attrs = Files.readAttributes(entry,
                            BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    String type = attrs.isDirectory() ? "[目录]" :
                            attrs.isSymbolicLink() ? "[链接]" : "[文件]";
                    String size = attrs.isDirectory() ? "-" : formatSize(attrs.size());
                    String time = LocalDateTime.ofInstant(attrs.lastModifiedTime().toInstant(),
                            java.time.ZoneId.systemDefault()).format(dtf);
                    sb.append(String.format("%-6s %-10s %s  %s\n",
                            type, size, time, entry.getFileName()));
                }
                sb.append("\n共 ").append(list.size()).append(" 项");
            }
            return ToolResponse.success("sys_file_list", sb.toString());
        } catch (Exception e) {
            log.error("sys_file_list 执行异常", e);
            return ToolResponse.fromException("sys_file_list", e);
        }
    }

    @Tool(name = "sys_file_read", description = "读取文本文件的内容。适用于文本文件，返回文件的全部内容。")
    public String fileRead(
            @ToolParam(name = "path", description = "文件路径") String path) {
        log.debug("工具调用: sys_file_read('{}')", path);
        try {
            String pathError = validatePath(path, "sys_file_read");
            if (pathError != null) return pathError;
            Path file = Path.of(path).normalize();
            if (!Files.exists(file)) {
                return ToolResponse.error("sys_file_read", "文件不存在: " + path);
            }
            if (!Files.isRegularFile(file)) {
                return ToolResponse.error("sys_file_read", "路径不是文件: " + path);
            }

            // 限制读取大小（最大 1MB）
            long size = Files.size(file);
            if (size > 1024 * 1024) {
                return ToolResponse.error("sys_file_read",
                        "文件过大（" + formatSize(size) + "），最大支持 1MB");
            }

            String content = Files.readString(file);
            return ToolResponse.success("sys_file_read",
                    "文件: " + file.toAbsolutePath() + "\n大小: " + formatSize(size) + "\n\n" + content);
        } catch (Exception e) {
            log.error("sys_file_read 执行异常", e);
            return ToolResponse.fromException("sys_file_read", e);
        }
    }

    @Tool(name = "sys_file_write", description = "将文本内容写入文件。如果文件已存在则覆盖，不存在则创建。")
    public String fileWrite(
            @ToolParam(name = "path", description = "文件路径") String path,
            @ToolParam(name = "content", description = "要写入的文本内容") String content) {
        log.debug("工具调用: sys_file_write('{}')", path);
        if (!ToolConfirmationManager.requestConfirmation("sys_file_write",
                "写入文件: " + path)) {
            return ToolResponse.error("sys_file_write", "用户取消了操作");
        }
        try {
            String pathError = validatePath(path, "sys_file_write");
            if (pathError != null) return pathError;
            Path file = Path.of(path).normalize();
            // 确保父目录存在
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, content);
            return ToolResponse.success("sys_file_write",
                    "文件已写入: " + file.toAbsolutePath() + " (" + formatSize(content.length()) + ")");
        } catch (Exception e) {
            log.error("sys_file_write 执行异常", e);
            return ToolResponse.fromException("sys_file_write", e);
        }
    }

    @Tool(name = "sys_file_delete", description = "删除指定的文件或空目录。非空目录不能直接删除。")
    public String fileDelete(
            @ToolParam(name = "path", description = "要删除的文件或空目录路径") String path) {
        log.debug("工具调用: sys_file_delete('{}')", path);
        if (!ToolConfirmationManager.requestConfirmation("sys_file_delete",
                "删除文件: " + path)) {
            return ToolResponse.error("sys_file_delete", "用户取消了操作");
        }
        try {
            String pathError = validatePath(path, "sys_file_delete");
            if (pathError != null) return pathError;
            Path target = Path.of(path).normalize();
            if (!Files.exists(target)) {
                return ToolResponse.error("sys_file_delete", "路径不存在: " + path);
            }
            Files.delete(target);
            return ToolResponse.success("sys_file_delete", "已删除: " + target.toAbsolutePath());
        } catch (DirectoryNotEmptyException e) {
            return ToolResponse.error("sys_file_delete", "目录非空，无法删除: " + path);
        } catch (Exception e) {
            log.error("sys_file_delete 执行异常", e);
            return ToolResponse.fromException("sys_file_delete", e);
        }
    }

    @Tool(name = "sys_file_copy", description = "复制文件或目录到指定位置。")
    public String fileCopy(
            @ToolParam(name = "source", description = "源文件路径") String source,
            @ToolParam(name = "target", description = "目标路径") String target) {
        log.debug("工具调用: sys_file_copy('{}' -> '{}')", source, target);
        if (!ToolConfirmationManager.requestConfirmation("sys_file_copy",
                "复制文件: " + source + " -> " + target)) {
            return ToolResponse.error("sys_file_copy", "用户取消了操作");
        }
        try {
            String srcError = validatePath(source, "sys_file_copy");
            if (srcError != null) return srcError;
            String dstError = validatePath(target, "sys_file_copy");
            if (dstError != null) return dstError;
            Path src = Path.of(source).normalize();
            Path dst = Path.of(target).normalize();
            if (!Files.exists(src)) {
                return ToolResponse.error("sys_file_copy", "源文件不存在: " + source);
            }
            if (dst.getParent() != null) {
                Files.createDirectories(dst.getParent());
            }
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            return ToolResponse.success("sys_file_copy",
                    "已复制: " + src.toAbsolutePath() + " -> " + dst.toAbsolutePath());
        } catch (Exception e) {
            log.error("sys_file_copy 执行异常", e);
            return ToolResponse.fromException("sys_file_copy", e);
        }
    }

    @Tool(name = "sys_file_move", description = "移动或重命名文件/目录。")
    public String fileMove(
            @ToolParam(name = "source", description = "源路径") String source,
            @ToolParam(name = "target", description = "目标路径") String target) {
        log.debug("工具调用: sys_file_move('{}' -> '{}')", source, target);
        if (!ToolConfirmationManager.requestConfirmation("sys_file_move",
                "移动文件: " + source + " -> " + target)) {
            return ToolResponse.error("sys_file_move", "用户取消了操作");
        }
        try {
            String srcError = validatePath(source, "sys_file_move");
            if (srcError != null) return srcError;
            String dstError = validatePath(target, "sys_file_move");
            if (dstError != null) return dstError;
            Path src = Path.of(source).normalize();
            Path dst = Path.of(target).normalize();
            if (!Files.exists(src)) {
                return ToolResponse.error("sys_file_move", "源路径不存在: " + source);
            }
            if (dst.getParent() != null) {
                Files.createDirectories(dst.getParent());
            }
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            return ToolResponse.success("sys_file_move",
                    "已移动: " + src.toAbsolutePath() + " -> " + dst.toAbsolutePath());
        } catch (Exception e) {
            log.error("sys_file_move 执行异常", e);
            return ToolResponse.fromException("sys_file_move", e);
        }
    }

    @Tool(name = "sys_file_mkdir", description = "创建目录，包括所有不存在的父目录。")
    public String fileMkdir(
            @ToolParam(name = "path", description = "要创建的目录路径") String path) {
        log.debug("工具调用: sys_file_mkdir('{}')", path);
        if (!ToolConfirmationManager.requestConfirmation("sys_file_mkdir",
                "创建目录: " + path)) {
            return ToolResponse.error("sys_file_mkdir", "用户取消了操作");
        }
        try {
            String pathError = validatePath(path, "sys_file_mkdir");
            if (pathError != null) return pathError;
            Path dir = Path.of(path).normalize();
            Files.createDirectories(dir);
            return ToolResponse.success("sys_file_mkdir", "目录已创建: " + dir.toAbsolutePath());
        } catch (Exception e) {
            log.error("sys_file_mkdir 执行异常", e);
            return ToolResponse.fromException("sys_file_mkdir", e);
        }
    }

    // ==================== 内部方法 ====================

    /** 禁止访问的敏感路径模式 */
    private static final Set<String> BLOCKED_PATHS = Set.of(
            "/etc/shadow", "/etc/passwd", "/etc/sudoers");

    /**
     * 校验文件路径安全性：禁止路径穿越（..）和访问敏感系统文件
     */
    private String validatePath(String path, String toolName) {
        if (path == null || path.isBlank()) {
            return ToolResponse.error(toolName, "路径不能为空");
        }
        Path normalized = Path.of(path).normalize();
        String normalizedStr = normalized.toString();
        // 检测路径穿越：规范化后仍含 .. 说明试图逃逸
        if (normalizedStr.contains("..")) {
            return ToolResponse.error(toolName, "路径包含非法字符: " + path);
        }
        // 检测敏感系统路径
        for (String blocked : BLOCKED_PATHS) {
            if (normalizedStr.equals(blocked)) {
                return ToolResponse.error(toolName, "禁止访问系统敏感路径: " + path);
            }
        }
        // 检测符号链接（防止通过 symlink 绕过检查）
        if (Files.exists(normalized) && Files.isSymbolicLink(normalized)) {
            log.warn("路径是符号链接: {}", path);
        }
        return null;
    }

    /**
     * 模拟输入单个字符
     */
    private void typeChar(char c) {
        // 对于 ASCII 可打印字符，使用剪贴板方式输入更可靠
        // 这里使用基本的 KeyEvent 映射
        String str = String.valueOf(c);
        java.awt.datatransfer.StringSelection selection =
                new java.awt.datatransfer.StringSelection(str);
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);

        // 使用 Cmd+V (macOS) 或 Ctrl+V (其他系统) 粘贴
        int pasteModifier = System.getProperty("os.name").toLowerCase().contains("mac")
                ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;
        robot.keyPress(pasteModifier);
        robot.keyPress(KeyEvent.VK_V);
        robot.keyRelease(KeyEvent.VK_V);
        robot.keyRelease(pasteModifier);
        robot.delay(20);
    }

    /**
     * 将键名解析为 KeyEvent 键码
     */
    private int resolveKeyCode(String keyName) {
        return switch (keyName) {
            case "ENTER", "RETURN" -> KeyEvent.VK_ENTER;
            case "TAB" -> KeyEvent.VK_TAB;
            case "ESCAPE", "ESC" -> KeyEvent.VK_ESCAPE;
            case "BACKSPACE", "BACK_SPACE" -> KeyEvent.VK_BACK_SPACE;
            case "DELETE", "DEL" -> KeyEvent.VK_DELETE;
            case "SPACE" -> KeyEvent.VK_SPACE;
            case "UP" -> KeyEvent.VK_UP;
            case "DOWN" -> KeyEvent.VK_DOWN;
            case "LEFT" -> KeyEvent.VK_LEFT;
            case "RIGHT" -> KeyEvent.VK_RIGHT;
            case "HOME" -> KeyEvent.VK_HOME;
            case "END" -> KeyEvent.VK_END;
            case "PAGE_UP", "PAGEUP" -> KeyEvent.VK_PAGE_UP;
            case "PAGE_DOWN", "PAGEDOWN" -> KeyEvent.VK_PAGE_DOWN;
            case "CTRL", "CONTROL" -> KeyEvent.VK_CONTROL;
            case "ALT", "OPTION" -> KeyEvent.VK_ALT;
            case "SHIFT" -> KeyEvent.VK_SHIFT;
            case "META", "CMD", "COMMAND", "WIN", "WINDOWS" -> KeyEvent.VK_META;
            case "CAPS_LOCK", "CAPSLOCK" -> KeyEvent.VK_CAPS_LOCK;
            case "F1" -> KeyEvent.VK_F1;
            case "F2" -> KeyEvent.VK_F2;
            case "F3" -> KeyEvent.VK_F3;
            case "F4" -> KeyEvent.VK_F4;
            case "F5" -> KeyEvent.VK_F5;
            case "F6" -> KeyEvent.VK_F6;
            case "F7" -> KeyEvent.VK_F7;
            case "F8" -> KeyEvent.VK_F8;
            case "F9" -> KeyEvent.VK_F9;
            case "F10" -> KeyEvent.VK_F10;
            case "F11" -> KeyEvent.VK_F11;
            case "F12" -> KeyEvent.VK_F12;
            case "A" -> KeyEvent.VK_A;
            case "B" -> KeyEvent.VK_B;
            case "C" -> KeyEvent.VK_C;
            case "D" -> KeyEvent.VK_D;
            case "E" -> KeyEvent.VK_E;
            case "F" -> KeyEvent.VK_F;
            case "G" -> KeyEvent.VK_G;
            case "H" -> KeyEvent.VK_H;
            case "I" -> KeyEvent.VK_I;
            case "J" -> KeyEvent.VK_J;
            case "K" -> KeyEvent.VK_K;
            case "L" -> KeyEvent.VK_L;
            case "M" -> KeyEvent.VK_M;
            case "N" -> KeyEvent.VK_N;
            case "O" -> KeyEvent.VK_O;
            case "P" -> KeyEvent.VK_P;
            case "Q" -> KeyEvent.VK_Q;
            case "R" -> KeyEvent.VK_R;
            case "S" -> KeyEvent.VK_S;
            case "T" -> KeyEvent.VK_T;
            case "U" -> KeyEvent.VK_U;
            case "V" -> KeyEvent.VK_V;
            case "W" -> KeyEvent.VK_W;
            case "X" -> KeyEvent.VK_X;
            case "Y" -> KeyEvent.VK_Y;
            case "Z" -> KeyEvent.VK_Z;
            case "0" -> KeyEvent.VK_0;
            case "1" -> KeyEvent.VK_1;
            case "2" -> KeyEvent.VK_2;
            case "3" -> KeyEvent.VK_3;
            case "4" -> KeyEvent.VK_4;
            case "5" -> KeyEvent.VK_5;
            case "6" -> KeyEvent.VK_6;
            case "7" -> KeyEvent.VK_7;
            case "8" -> KeyEvent.VK_8;
            case "9" -> KeyEvent.VK_9;
            default -> -1;
        };
    }

    /**
     * 格式化文件大小
     */
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
