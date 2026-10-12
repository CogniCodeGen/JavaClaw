package com.javaclaw.ui.javafx.desktop;

import com.javaclaw.desktop.api.DesktopActionResult;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopFrameGeometry;
import com.javaclaw.desktop.api.DesktopVirtualInputState;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice.WindowTranslucency;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JPanel;
import javax.swing.JWindow;

/** Passive, nonactivating native host. All methods and all window lifetime run on AWT EDT. */
final class JavaDesktopPreviewHost implements DesktopPreviewHost {
    static final int HEADER = 36;
    static final int FOOTER = 64;
    static final int BORDER = 1;
    private static final int MIN_WIDTH = 240;
    private static final int MIN_HEIGHT = HEADER + FOOTER + 80;
    private static final int GAP = 12;
    private static final AtomicBoolean AWT_USED = new AtomicBoolean();
    private static final Color INK = new Color(39, 37, 31);
    private static final Color CHROME = new Color(250, 249, 245);

    enum Mode { NORMAL, MINI, MAXIMIZED }

    private final JWindow window;
    private final Canvas canvas = new Canvas();
    private final Runnable stop;
    private final Runnable takeover;
    private final Runnable manualInput;
    private final boolean translucent;
    private DesktopFrame frame;
    private BufferedImage image;
    private DesktopVirtualInputState input;
    private String application;
    private String title;
    private String status = "等待画面";
    private String action = "预览不接收键盘输入";
    private String state = "已连接";
    private boolean warning;
    private boolean takeoverEnabled;
    private Mode mode = Mode.NORMAL;
    private Mode beforeMini = Mode.NORMAL;
    private double logicalWidth = 518;
    private double logicalHeight = 348;
    private double manualScale = 1;
    private boolean followSource = true;
    private boolean disposed;

    JavaDesktopPreviewHost(String application, String title, int position,
            Runnable stop, Runnable takeover, Runnable manualInput) {
        requireEdt();
        this.application = application;
        this.title = title;
        this.stop = stop;
        this.takeover = takeover;
        this.manualInput = manualInput;
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("当前环境没有桌面显示服务");
        AWT_USED.set(true);
        window = new JWindow((Window) null);
        // Set before a peer exists and before every show. No focus request is ever issued.
        window.setFocusableWindowState(false);
        window.setAutoRequestFocus(false);
        window.setAlwaysOnTop(true);
        window.setName("JavaClaw · " + application);
        translucent = window.getGraphicsConfiguration().getDevice()
                .isWindowTranslucencySupported(WindowTranslucency.PERPIXEL_TRANSLUCENT);
        window.setBackground(translucent ? new Color(0, 0, 0, 0) : CHROME);
        canvas.setFocusable(false);
        canvas.setOpaque(false);
        canvas.setToolTipText("拖动标题移动；拖动右下角调整大小；× 停止会话。人工输入请使用主界面面板。");
        canvas.getAccessibleContext().setAccessibleName("桌面会话被动预览");
        window.setContentPane(canvas);
        installMouseControls();
        resizeToSource();
        Rectangle screen = availableBounds();
        int offset = Math.floorMod(position, 6) * 28;
        window.setLocation(Math.max(screen.x + GAP, screen.x + screen.width - window.getWidth() - GAP - offset),
                Math.max(screen.y + GAP, Math.min(screen.y + screen.height - window.getHeight(),
                        screen.y + GAP + offset)));
    }

    static boolean wasAwtUsed() { return AWT_USED.get(); }

    static void dispatch(Runnable task) {
        // Even a queued show cancelled before peer creation initializes the AWT event queue.
        AWT_USED.set(true);
        EventQueue.invokeLater(task);
    }

    @Override public void show() {
        requireEdt();
        if (disposed) return;
        window.setFocusableWindowState(false);
        window.setAutoRequestFocus(false);
        window.setVisible(true);
    }

    @Override public void hide() { requireEdt(); window.setVisible(false); }

    @Override public void close() {
        requireEdt();
        disposed = true;
        image = null;
        frame = null;
        input = null;
        window.dispose();
    }

    @Override public void frame(DesktopFrame next, BufferedImage pixels) {
        requireEdt();
        if (disposed) return;
        frame = next;
        image = pixels;
        DesktopFrameGeometry geometry = next.geometry();
        double width = geometry == null ? next.width() : geometry.logicalWidth();
        double height = geometry == null ? next.height() : geometry.logicalHeight();
        boolean changed = width != logicalWidth || height != logicalHeight;
        logicalWidth = width;
        logicalHeight = height;
        canvas.setToolTipText((geometry == null ? "尺寸信息不可用，按画面比例预览。"
                : !geometry.alphaReliable() || !translucent ? "透明轮廓不可用，当前为矩形预览。"
                : "保留采集画面的透明轮廓。")
                + "拖动标题移动；右下角调整大小；× 停止会话；人工输入使用主界面面板。");
        if (changed && mode == Mode.NORMAL) resizeToSource();
        canvas.repaint();
    }

    @Override public void clear() {
        requireEdt(); image = null; frame = null; input = null; canvas.repaint();
    }
    @Override public void status(String text) { requireEdt(); status = text; canvas.repaint(); }
    @Override public void action(String text) { requireEdt(); action = text; canvas.repaint(); }
    @Override public void state(String text, boolean attention) {
        requireEdt(); state = text; warning = attention; canvas.repaint();
    }
    @Override public void input(DesktopVirtualInputState next) {
        requireEdt(); input = next; canvas.repaint();
    }
    @Override public void target(String app, String nextTitle) {
        requireEdt(); application = app; title = nextTitle;
        window.setName("JavaClaw · " + app); canvas.repaint();
    }
    @Override public void takeoverEnabled(boolean enabled) {
        requireEdt(); takeoverEnabled = enabled; canvas.repaint();
    }

    void toggleMini() {
        requireEdt();
        if (mode == Mode.MINI) {
            mode = beforeMini;
            if (mode == Mode.MAXIMIZED) maximize();
            else restore();
        } else {
            beforeMini = mode;
            mode = Mode.MINI;
            setSizeWithinScreen(280, HEADER + FOOTER + 140);
        }
        canvas.repaint();
    }

    void toggleMaximized() {
        requireEdt();
        if (mode == Mode.MAXIMIZED) restore();
        else { mode = Mode.MAXIMIZED; maximize(); }
        canvas.repaint();
    }

    void restore() {
        requireEdt();
        mode = Mode.NORMAL;
        followSource = true;
        manualScale = 1;
        resizeToSource();
        canvas.repaint();
    }

    private void maximize() {
        Rectangle screen = availableBounds();
        window.setBounds(screen);
    }

    private void resizeToSource() {
        double scale = followSource ? 1 : manualScale;
        Rectangle screen = availableBounds();
        double availableWidth = Math.max(1, screen.width - GAP * 2 - BORDER * 2);
        double availableHeight = Math.max(1, screen.height - GAP * 2 - HEADER - FOOTER);
        scale = Math.min(scale, Math.min(availableWidth / logicalWidth, availableHeight / logicalHeight));
        setSizeWithinScreen((int) Math.ceil(logicalWidth * scale) + BORDER * 2,
                (int) Math.ceil(logicalHeight * scale) + HEADER + FOOTER);
    }

    private void setSizeWithinScreen(int width, int height) {
        Rectangle screen = availableBounds();
        width = Math.min(screen.width, Math.max(MIN_WIDTH, width));
        height = Math.min(screen.height, Math.max(MIN_HEIGHT, height));
        int x = Math.max(screen.x, Math.min(window.getX(), screen.x + screen.width - width));
        int y = Math.max(screen.y, Math.min(window.getY(), screen.y + screen.height - height));
        window.setBounds(x, y, width, height);
    }

    private Rectangle availableBounds() {
        GraphicsConfiguration config = window.getGraphicsConfiguration();
        Rectangle bounds = new Rectangle(config.getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(config);
        bounds.x += insets.left;
        bounds.y += insets.top;
        bounds.width -= insets.left + insets.right;
        bounds.height -= insets.top + insets.bottom;
        return bounds;
    }

    private void installMouseControls() {
        MouseAdapter mouse = new MouseAdapter() {
            private Point initialPoint;
            private Rectangle initialBounds;
            private boolean resizing;
            private boolean dragging;

            @Override public void mousePressed(MouseEvent event) {
                if (event.getButton() != MouseEvent.BUTTON1) return;
                resizing = mode == Mode.NORMAL && event.getX() >= canvas.getWidth() - 18
                        && event.getY() >= canvas.getHeight() - 18;
                dragging = event.getY() < HEADER && event.getX() < canvas.getWidth() - 92;
                if (resizing || dragging) {
                    initialPoint = event.getLocationOnScreen();
                    initialBounds = window.getBounds();
                }
            }

            @Override public void mouseDragged(MouseEvent event) {
                if (initialPoint == null) return;
                int dx = event.getXOnScreen() - initialPoint.x;
                int dy = event.getYOnScreen() - initialPoint.y;
                if (resizing) {
                    setSizeWithinScreen(initialBounds.width + dx, initialBounds.height + dy);
                    followSource = false;
                    manualScale = Math.min((window.getWidth() - BORDER * 2) / logicalWidth,
                            (window.getHeight() - HEADER - FOOTER) / logicalHeight);
                } else if (dragging) window.setLocation(initialBounds.x + dx, initialBounds.y + dy);
            }

            @Override public void mouseReleased(MouseEvent event) {
                initialPoint = null; resizing = false; dragging = false;
            }

            @Override public void mouseClicked(MouseEvent event) {
                if (event.getButton() != MouseEvent.BUTTON1) return;
                int width = canvas.getWidth();
                if (event.getY() < HEADER) {
                    if (event.getX() >= width - 30) stop.run();
                    else if (event.getX() >= width - 60) toggleMaximized();
                    else if (event.getX() >= width - 90) toggleMini();
                    else if (event.getClickCount() == 2) toggleMaximized();
                } else if (event.getY() >= canvas.getHeight() - FOOTER + 18
                        && event.getY() < canvas.getHeight() - FOOTER + 42) {
                    if (event.getX() >= width - 90) restore();
                    else if (event.getX() >= width - 142 && takeoverEnabled) takeover.run();
                    else if (event.getX() >= width - 200 && event.getX() < width - 142) manualInput.run();
                }
            }

            @Override public void mouseMoved(MouseEvent event) {
                boolean resize = mode == Mode.NORMAL && event.getX() >= canvas.getWidth() - 18
                        && event.getY() >= canvas.getHeight() - 18;
                canvas.setCursor(Cursor.getPredefinedCursor(resize ? Cursor.SE_RESIZE_CURSOR : Cursor.DEFAULT_CURSOR));
            }
        };
        canvas.addMouseListener(mouse);
        canvas.addMouseMotionListener(mouse);
    }

    DesktopViewportTransform transform() {
        return frame == null ? null : DesktopViewportTransform.fit(frame, BORDER, HEADER,
                Math.max(1, canvas.getWidth() - BORDER * 2),
                Math.max(1, canvas.getHeight() - HEADER - FOOTER),
                mode == Mode.NORMAL && followSource ? 1 : Double.POSITIVE_INFINITY);
    }

    JWindow window() { return window; }
    Mode mode() { return mode; }

    private final class Canvas extends JPanel {
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                int width = getWidth();
                int height = getHeight();
                if (translucent) {
                    g.setComposite(AlphaComposite.Clear);
                    g.fillRect(0, 0, width, height);
                    g.setComposite(AlphaComposite.SrcOver);
                }
                g.setColor(CHROME);
                g.fillRect(0, 0, width, HEADER);
                g.fillRect(0, height - FOOTER, width, FOOTER);
                g.setColor(new Color(220, 217, 207));
                g.drawLine(0, HEADER - 1, width, HEADER - 1);
                g.drawLine(0, height - FOOTER, width, height - FOOTER);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
                g.setColor(INK);
                text(g, application + " · " + title, 12, 23, width - 110);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
                g.drawString("−", width - 80, 24);
                g.drawString(mode == Mode.MAXIMIZED ? "▣" : "□", width - 51, 24);
                g.drawString("×", width - 22, 24);

                DesktopViewportTransform transform = transform();
                String geometryStatus = "等待画面";
                if (image != null && frame != null && transform != null) {
                    boolean reliableAlpha = frame.geometry() != null && frame.geometry().alphaReliable();
                    if (!translucent || !reliableAlpha) {
                        g.setColor(new Color(238, 237, 232));
                        g.fill(new Rectangle2D.Double(transform.x(), transform.y(), transform.width(), transform.height()));
                    }
                    g.drawImage(image, (int) Math.round(transform.x()), (int) Math.round(transform.y()),
                            (int) Math.round(transform.x() + transform.width()),
                            (int) Math.round(transform.y() + transform.height()),
                            transform.sourceX(), transform.sourceY(),
                            transform.sourceX() + transform.sourceWidth(),
                            transform.sourceY() + transform.sourceHeight(), null);
                    paintPointer(g, transform);
                    int percent = (int) Math.round(100 * transform.width() / transform.logicalWidth());
                    geometryStatus = !transform.geometryKnown() ? "尺寸信息不可用 · 按画面比例预览"
                            : !reliableAlpha || !translucent ? "透明轮廓不可用 · 矩形预览 · " + percent + "%"
                            : "窗口轮廓 · " + percent + "%";
                } else {
                    g.setColor(new Color(238, 237, 232));
                    g.fillRect(BORDER, HEADER, width - BORDER * 2, Math.max(0, height - HEADER - FOOTER));
                    g.setColor(new Color(117, 113, 101));
                    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
                    text(g, status, 16, HEADER + Math.max(26, (height - HEADER - FOOTER) / 2), width - 32);
                }
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                g.setColor(new Color(117, 113, 101));
                text(g, geometryStatus, 10, height - FOOTER + 14, width - 20);
                g.setColor(warning ? new Color(164, 99, 11) : new Color(31, 126, 84));
                text(g, state + " · " + status, 10, height - FOOTER + 33, width - 210);
                g.setColor(new Color(117, 113, 101));
                text(g, action, 10, height - 11, width - 30);
                g.setColor(takeoverEnabled ? new Color(40, 84, 156) : new Color(165, 162, 153));
                g.drawString("接管", width - 139, height - FOOTER + 33);
                g.setColor(new Color(40, 84, 156));
                g.drawString("人工输入", width - 197, height - FOOTER + 33);
                g.drawString("原始尺寸", width - 83, height - FOOTER + 33);
                if (mode == Mode.NORMAL) {
                    g.setColor(new Color(165, 162, 153));
                    g.drawLine(width - 10, height - 3, width - 3, height - 10);
                    g.drawLine(width - 6, height - 3, width - 3, height - 6);
                }
            } finally { g.dispose(); }
        }
    }

    private void paintPointer(Graphics2D graphics, DesktopViewportTransform transform) {
        if (input == null || !input.visible() || input.frameGeneration() != frame.windowGeneration()
                || !transform.containsSource(input.frameX(), input.frameY())) return;
        Point2D.Double point = transform.sourceToView(input.frameX(), input.frameY());
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.clip(new Rectangle2D.Double(transform.x(), transform.y(), transform.width(), transform.height()));
            g.translate(point.x, point.y);
            // This marks an intended target, never a real mouse button or a verified business outcome.
            g.setColor(input.delivery() == DesktopActionResult.Delivery.MAYBE_SENT
                    ? new Color(184, 113, 16) : input.phase() == DesktopVirtualInputState.Phase.FINISHED
                    ? new Color(117, 113, 101) : new Color(40, 84, 156));
            g.setStroke(new BasicStroke(2));
            g.drawOval(-7, -7, 14, 14);
            g.drawLine(-11, 0, -4, 0); g.drawLine(4, 0, 11, 0);
            g.drawLine(0, -11, 0, -4); g.drawLine(0, 4, 0, 11);
        } finally { g.dispose(); }
    }

    private static void text(Graphics2D g, String value, int x, int y, int width) {
        if (width < 12 || value == null) return;
        if (g.getFontMetrics().stringWidth(value) <= width) { g.drawString(value, x, y); return; }
        int end = value.length();
        while (end > 0 && g.getFontMetrics().stringWidth(value.substring(0, end) + "…") > width) end--;
        g.drawString(value.substring(0, end) + "…", x, y);
    }

    private static void requireEdt() {
        if (!EventQueue.isDispatchThread()) throw new IllegalStateException("preview host must run on AWT EDT");
    }
}
