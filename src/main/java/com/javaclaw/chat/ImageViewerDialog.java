package com.javaclaw.chat;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ZoomEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * 图片查看弹窗：双击对话中的图片后弹出，支持放大、缩小、拖拽平移查看原图。
 *
 * <p>交互：
 * <ul>
 *   <li>滚轮 — 以光标为中心缩放</li>
 *   <li>拖拽 — 在放大后平移图片</li>
 *   <li>底部按钮 / 键盘 +、-、0 — 放大 / 缩小 / 复位适应窗口</li>
 *   <li>Esc 或关闭按钮 — 关闭弹窗</li>
 * </ul>
 * 窗口主题由 {@link com.javaclaw.ui.javafx.theme.ThemeManager} 监听 Window 列表自动应用，
 * 故此处仅需把 chat.css 挂到 Scene 即可解析 {@code -jc-*} 令牌。</p>
 */
public final class ImageViewerDialog {

    private static final Logger log = LoggerFactory.getLogger(ImageViewerDialog.class);

    private static final double MIN_SCALE = 0.1;
    private static final double MAX_SCALE = 12.0;
    private static final double ZOOM_STEP = 1.2;
    /** 滚轮缩放指数底数：factor = base^deltaY，兼顾鼠标分格与触摸板细腻滚动 */
    private static final double SCROLL_ZOOM_BASE = 1.004;

    private ImageViewerDialog() {
    }

    /**
     * 打开图片查看弹窗。
     *
     * @param owner 拥有者窗口（用于主题继承与模态定位）；为 null 时自动选取当前聚焦/可见窗口
     * @param file  图片文件
     */
    public static void show(Window owner, File file) {
        if (file == null || !file.exists() || !file.isFile()) return;
        if (owner == null) owner = resolveOwner();

        Image image;
        try {
            // 加载原图（不缩放），由 ImageView 与缩放变换控制显示尺寸
            image = new Image(file.toURI().toString(), false);
        } catch (Exception e) {
            log.warn("打开图片查看弹窗失败: {}", file, e);
            return;
        }
        if (image.isError()) {
            log.warn("图片加载出错，无法查看: {}", file);
            return;
        }

        ImageView imageView = new ImageView(image);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);

        // Group 承载 ImageView，缩放/平移施加在 Group 上，置于 Pane 中以支持自由定位
        Group group = new Group(imageView);
        Pane canvas = new Pane(group);
        canvas.getStyleClass().add("image-viewer-canvas");
        canvas.setMinSize(0, 0);

        StackPane viewport = new StackPane(canvas);
        viewport.getStyleClass().add("image-viewer-viewport");
        viewport.setMinSize(0, 0);

        // 缩放状态（fit 表示让图片完整适应视口的基准缩放）
        double[] scale = {1.0};
        boolean[] fitted = {false};

        Runnable fitToWindow = () -> {
            double vw = viewport.getWidth();
            double vh = viewport.getHeight();
            double iw = image.getWidth();
            double ih = image.getHeight();
            if (vw <= 0 || vh <= 0 || iw <= 0 || ih <= 0) return;
            double s = Math.min(vw / iw, vh / ih);
            s = Math.min(s, 1.0); // 小图不放大，保持原始像素
            scale[0] = s;
            group.setScaleX(s);
            group.setScaleY(s);
            // 居中
            group.setTranslateX((vw - iw) / 2.0);
            group.setTranslateY((vh - ih) / 2.0);
            fitted[0] = true;
        };

        // 以视口某点为锚进行缩放
        java.util.function.BiConsumer<Double, double[]> zoomAt = (factor, pivot) -> {
            double oldScale = scale[0];
            double newScale = clamp(oldScale * factor);
            if (newScale == oldScale) return;
            double px = pivot[0];
            double py = pivot[1];
            // 保持锚点在缩放前后于视口中的位置不变
            double dx = px - group.getTranslateX();
            double dy = py - group.getTranslateY();
            double ratio = newScale / oldScale;
            group.setTranslateX(px - dx * ratio);
            group.setTranslateY(py - dy * ratio);
            group.setScaleX(newScale);
            group.setScaleY(newScale);
            scale[0] = newScale;
        };

        // 鼠标滚轮 / 触摸板两指滚动缩放（以光标为锚）
        // 用 deltaY 指数化得到缩放系数：鼠标每格步进明显、触摸板细腻滚动平滑
        canvas.addEventFilter(ScrollEvent.SCROLL, e -> {
            double delta = e.getDeltaY();
            if (delta == 0) return;
            double factor = Math.pow(SCROLL_ZOOM_BASE, delta);
            zoomAt.accept(factor, new double[]{e.getX(), e.getY()});
            e.consume();
        });

        // 触摸板捏合手势缩放（以手势中心为锚）
        canvas.addEventFilter(ZoomEvent.ZOOM, e -> {
            double factor = e.getZoomFactor();
            if (factor <= 0 || factor == 1.0) return;
            zoomAt.accept(factor, new double[]{e.getX(), e.getY()});
            e.consume();
        });

        // 拖拽平移
        double[] dragAnchor = new double[2];
        double[] translateAnchor = new double[2];
        canvas.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                dragAnchor[0] = e.getSceneX();
                dragAnchor[1] = e.getSceneY();
                translateAnchor[0] = group.getTranslateX();
                translateAnchor[1] = group.getTranslateY();
                canvas.setCursor(javafx.scene.Cursor.CLOSED_HAND);
            }
        });
        canvas.setOnMouseDragged(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                group.setTranslateX(translateAnchor[0] + (e.getSceneX() - dragAnchor[0]));
                group.setTranslateY(translateAnchor[1] + (e.getSceneY() - dragAnchor[1]));
            }
        });
        canvas.setOnMouseReleased(e -> canvas.setCursor(javafx.scene.Cursor.OPEN_HAND));
        canvas.setCursor(javafx.scene.Cursor.OPEN_HAND);

        // 工具栏
        Button zoomOutBtn = new Button("－");
        Button zoomInBtn = new Button("＋");
        Button fitBtn = new Button("适应窗口");
        Button closeBtn = new Button("关闭");
        for (Button b : new Button[]{zoomOutBtn, zoomInBtn, fitBtn, closeBtn}) {
            b.getStyleClass().add("jc-btn-ghost");
        }
        Label nameLabel = new Label(file.getName());
        nameLabel.getStyleClass().add("image-viewer-name");

        Stage stage = new Stage();
        zoomInBtn.setOnAction(e -> zoomAt.accept(ZOOM_STEP,
                new double[]{viewport.getWidth() / 2, viewport.getHeight() / 2}));
        zoomOutBtn.setOnAction(e -> zoomAt.accept(1.0 / ZOOM_STEP,
                new double[]{viewport.getWidth() / 2, viewport.getHeight() / 2}));
        fitBtn.setOnAction(e -> fitToWindow.run());
        closeBtn.setOnAction(e -> stage.close());

        HBox toolbar = new HBox(8, nameLabel, spacer(), zoomOutBtn, zoomInBtn, fitBtn, closeBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(8, 12, 8, 12));
        toolbar.getStyleClass().add("image-viewer-toolbar");

        BorderPane rootPane = new BorderPane();
        rootPane.setCenter(viewport);
        rootPane.setBottom(toolbar);
        rootPane.getStyleClass().add("image-viewer-root");

        Scene scene = new Scene(rootPane, 900, 680);
        var cssUrl = ImageViewerDialog.class.getResource("/css/chat.css");
        if (cssUrl != null) scene.getStylesheets().add(cssUrl.toExternalForm());

        scene.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ESCAPE -> stage.close();
                case PLUS, EQUALS, ADD -> zoomInBtn.fire();
                case MINUS, SUBTRACT -> zoomOutBtn.fire();
                case DIGIT0, NUMPAD0 -> fitToWindow.run();
                default -> {
                }
            }
        });

        stage.setTitle("图片查看 — " + file.getName());
        stage.setScene(scene);
        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(Modality.NONE);
        }

        // 视口尺寸就绪后执行一次适应；尚未布局时延迟到首帧
        viewport.widthProperty().addListener((o, ov, nv) -> {
            if (!fitted[0]) fitToWindow.run();
        });
        viewport.heightProperty().addListener((o, ov, nv) -> {
            if (!fitted[0]) fitToWindow.run();
        });

        stage.show();
        javafx.application.Platform.runLater(fitToWindow);
    }

    /** 自动选取一个合适的拥有者窗口：优先聚焦窗口，其次任意可见窗口 */
    private static Window resolveOwner() {
        Window focused = null;
        Window anyShowing = null;
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            if (anyShowing == null) anyShowing = w;
            if (w.isFocused()) {
                focused = w;
                break;
            }
        }
        return focused != null ? focused : anyShowing;
    }

    private static double clamp(double s) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, s));
    }

    private static Pane spacer() {
        Pane p = new Pane();
        HBox.setHgrow(p, javafx.scene.layout.Priority.ALWAYS);
        return p;
    }
}
