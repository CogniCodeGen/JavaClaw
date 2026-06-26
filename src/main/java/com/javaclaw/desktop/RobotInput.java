package com.javaclaw.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTException;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * 跨平台键鼠与截屏基座（Tier 0）—— "操作任意软件"的底线，纯 {@link java.awt.Robot} 实现。
 *
 * <p>这一层不依赖任何原生库、不区分操作系统：只要 JVM 能跑桌面图形环境，截屏与键鼠注入就可用。
 * 因此它是整个桌面自动化能力栈的"地板"——窗口枚举、无障碍树等增强即便全部缺失，
 * 靠"整屏截图 + 视觉模型 + 坐标点击"依然能操作任意应用。</p>
 *
 * <p>坐标系约定：全程使用<b>逻辑像素（point）</b>。Robot 的 {@code mouseMove} 与
 * {@code createScreenCapture}、各 OS CLI 返回的窗口 bounds 都在同一逻辑坐标系下，三者一致，
 * 从而规避 Retina / 高 DPI 下"点击偏移"的常见坑。</p>
 *
 * <p>与 {@code system.SystemTools} 的关系：后者也持有一个 Robot 做全屏鼠键，二者各自独立、互不影响。
 * 本类专为桌面自动化场景做了多屏 / DPI 友好的封装，职责更聚焦。</p>
 */
public final class RobotInput {

    private static final Logger log = LoggerFactory.getLogger(RobotInput.class);

    /** 是否运行在 macOS（决定"粘贴 / 全选"等组合键用 Cmd 还是 Ctrl）。 */
    private static final boolean IS_MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    /** AWT Robot；在无图形环境（headless）下为 {@code null}，此时本基座不可用。 */
    private final Robot robot;

    public RobotInput() {
        Robot r = null;
        try {
            if (!GraphicsEnvironment.isHeadless()) {
                r = new Robot();
                r.setAutoDelay(40);
            }
        } catch (AWTException e) {
            log.warn("AWT Robot 初始化失败，键鼠 / 截屏基座不可用: {}", e.getMessage());
        }
        this.robot = r;
    }

    /** 基座是否可用（headless 或 Wayland 禁注入等情况下为 false）。 */
    public boolean isAvailable() {
        return robot != null;
    }

    // ==================== 截屏 ====================

    /**
     * 截取整个虚拟桌面（跨所有显示器的并集区域）。
     *
     * <p>多屏时取各 {@link GraphicsDevice} 边界的并集，确保副屏内容不漏。</p>
     */
    public BufferedImage captureVirtualScreen() {
        ensureAvailable();
        return robot.createScreenCapture(virtualBounds());
    }

    /** 截取屏幕上的指定矩形区域（用于"单窗口截图"：传入窗口 bounds 即可）。 */
    public BufferedImage captureRegion(Rectangle region) {
        ensureAvailable();
        return robot.createScreenCapture(region);
    }

    /** 计算虚拟桌面的并集边界（所有显示器拼成的整体矩形）。 */
    public Rectangle virtualBounds() {
        Rectangle all = new Rectangle();
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            all = all.union(gd.getDefaultConfiguration().getBounds());
        }
        // 极端情况下（无设备信息）退回主屏尺寸
        return all.isEmpty() ? new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()) : all;
    }

    // ==================== 鼠标 ====================

    /** 移动鼠标到屏幕逻辑坐标 (x, y)。 */
    public void moveTo(int x, int y) {
        ensureAvailable();
        robot.mouseMove(x, y);
    }

    /**
     * 在 (x, y) 处点击。
     *
     * @param button left / right / middle
     * @param clicks 点击次数（1 单击、2 双击）
     */
    public void clickAt(int x, int y, String button, int clicks) {
        ensureAvailable();
        int mask = buttonMask(button);
        robot.mouseMove(x, y);
        for (int i = 0; i < Math.max(1, clicks); i++) {
            robot.mousePress(mask);
            robot.mouseRelease(mask);
            if (i < clicks - 1) {
                robot.delay(60);
            }
        }
    }

    /** 滚轮滚动：正数向下、负数向上（单位为"刻度"）。 */
    public void scroll(int amount) {
        ensureAvailable();
        robot.mouseWheel(amount);
    }

    private static int buttonMask(String button) {
        return switch (button == null ? "" : button.toLowerCase(Locale.ROOT)) {
            case "right" -> InputEvent.BUTTON3_DOWN_MASK;
            case "middle" -> InputEvent.BUTTON2_DOWN_MASK;
            default -> InputEvent.BUTTON1_DOWN_MASK;
        };
    }

    // ==================== 键盘 ====================

    /**
     * 输入一段文本（含中文）。
     *
     * <p>走"系统剪贴板 + 粘贴"而非逐字符按键：既能输入任意 Unicode（中文 / Emoji），
     * 又比逐键映射稳健。会激活目标窗口后调用——粘贴落在当前焦点控件。</p>
     */
    public void typeText(String text) {
        ensureAvailable();
        if (text == null || text.isEmpty()) {
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
        robot.delay(60);
        int modifier = IS_MAC ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;
        robot.keyPress(modifier);
        robot.keyPress(KeyEvent.VK_V);
        robot.keyRelease(KeyEvent.VK_V);
        robot.keyRelease(modifier);
    }

    /**
     * 按下一个组合键，如 {@code "ctrl+c"}、{@code "cmd+v"}、{@code "alt+tab"}、{@code "enter"}。
     *
     * <p>顺序按下、逆序释放。{@code cmd} 在非 macOS 上回退为 {@code ctrl}。</p>
     */
    public void pressCombo(String combo) {
        ensureAvailable();
        if (combo == null || combo.isBlank()) {
            return;
        }
        String[] parts = combo.toLowerCase(Locale.ROOT).split("\\+");
        int[] codes = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            codes[i] = resolveKeyCode(parts[i].trim());
        }
        for (int code : codes) {
            robot.keyPress(code);
        }
        for (int i = codes.length - 1; i >= 0; i--) {
            robot.keyRelease(codes[i]);
        }
    }

    /** 把按键名映射为 AWT {@link KeyEvent} 虚拟码（覆盖常用功能键 / 字母 / 数字）。 */
    private static int resolveKeyCode(String key) {
        return switch (key) {
            case "ctrl", "control" -> KeyEvent.VK_CONTROL;
            case "cmd", "command", "meta", "win", "super" -> IS_MAC ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;
            case "alt", "option" -> KeyEvent.VK_ALT;
            case "shift" -> KeyEvent.VK_SHIFT;
            case "enter", "return" -> KeyEvent.VK_ENTER;
            case "tab" -> KeyEvent.VK_TAB;
            case "esc", "escape" -> KeyEvent.VK_ESCAPE;
            case "space" -> KeyEvent.VK_SPACE;
            case "backspace" -> KeyEvent.VK_BACK_SPACE;
            case "delete", "del" -> KeyEvent.VK_DELETE;
            case "up" -> KeyEvent.VK_UP;
            case "down" -> KeyEvent.VK_DOWN;
            case "left" -> KeyEvent.VK_LEFT;
            case "right" -> KeyEvent.VK_RIGHT;
            case "home" -> KeyEvent.VK_HOME;
            case "end" -> KeyEvent.VK_END;
            case "pageup" -> KeyEvent.VK_PAGE_UP;
            case "pagedown" -> KeyEvent.VK_PAGE_DOWN;
            default -> {
                if (key.length() == 1) {
                    char c = Character.toUpperCase(key.charAt(0));
                    int code = KeyEvent.getExtendedKeyCodeForChar(c);
                    if (code != KeyEvent.VK_UNDEFINED) {
                        yield code;
                    }
                }
                if (key.matches("f([1-9]|1[0-2])")) { // F1~F12
                    yield KeyEvent.VK_F1 + Integer.parseInt(key.substring(1)) - 1;
                }
                throw new DesktopException("无法识别的按键名: " + key);
            }
        };
    }

    private void ensureAvailable() {
        if (robot == null) {
            throw new DesktopException("键鼠 / 截屏基座不可用（headless 环境或权限不足）");
        }
    }
}
