package com.javaclaw.ui.javafx.control;

import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.ui.javafx.JfxUserInteractionPort;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * 通用「窗内 Toast」浮层控件：底部居中、鼠标穿透、淡入 → 停留 → 淡出。
 *
 * <p>用法：把 {@link #node()} 叠到窗口 Scene 根的 {@link StackPane} 顶层，调 {@link #show(String)}
 * 弹出一条非阻塞提示。样式见 {@code /css/controls.css}（{@code .jc-toast} / {@code .jc-toast-layer}），
 * 颜色走 {@code -jc-*} 令牌随主题换肤。</p>
 *
 * <p>若窗口希望复用应用统一的 {@link UserInteractionPort#notify} 通道（而非直接调 {@link #show}），
 * 可再调 {@link #bindToPort}，让本浮层在窗口显示期间临时接管端口的 Toast 渲染器、隐藏时自动还原；
 * 这对置顶的模态窗口尤为有用——主窗横幅会被模态窗遮挡，改由窗内浮层呈现。</p>
 *
 * @author JavaClaw
 */
public final class WindowToast {

    private final Label label = new Label();
    private final StackPane layer = new StackPane(label);
    private SequentialTransition anim;
    /** bindToPort 接管前的渲染器，隐藏时还原。 */
    private Consumer<String> prevHandler;

    public WindowToast() {
        label.getStyleClass().add("jc-toast");
        label.setWrapText(true);
        label.setMaxWidth(560);
        label.setVisible(false);
        layer.getStyleClass().add("jc-toast-layer");
        layer.setMouseTransparent(true);
        StackPane.setAlignment(label, Pos.BOTTOM_CENTER);
    }

    /** 叠加到 Scene 根 {@link StackPane} 顶层的浮层节点。 */
    public Region node() {
        return layer;
    }

    /** 渲染一条 Toast（自动切回 FX 线程）。 */
    public void show(String text) {
        if (text == null || text.isBlank()) return;
        if (Platform.isFxApplicationThread()) {
            animate(text);
        } else {
            Platform.runLater(() -> animate(text));
        }
    }

    private void animate(String text) {
        if (anim != null) anim.stop();
        label.setText(text);
        label.setOpacity(0);
        label.setVisible(true);
        FadeTransition in = new FadeTransition(Duration.millis(140), label);
        in.setFromValue(0);
        in.setToValue(1);
        PauseTransition hold = new PauseTransition(Duration.seconds(2.4));
        FadeTransition out = new FadeTransition(Duration.millis(280), label);
        out.setFromValue(1);
        out.setToValue(0);
        out.setOnFinished(e -> label.setVisible(false));
        anim = new SequentialTransition(in, hold, out);
        anim.play();
    }

    /**
     * 让本浮层在 {@code stage} 显示期间临时接管端口的 Toast 渲染器，隐藏时还原。
     *
     * <p>仅对 {@link JfxUserInteractionPort} 生效；采用附加式事件监听，不覆盖窗口既有的
     * {@code setOnShown} / {@code setOnHidden}。</p>
     */
    public void bindToPort(Stage stage, UserInteractionPort interaction) {
        if (stage == null || !(interaction instanceof JfxUserInteractionPort jfx)) return;
        stage.addEventHandler(WindowEvent.WINDOW_SHOWN, e -> {
            prevHandler = jfx.getToastHandler();
            jfx.setToastHandler(this::show);
        });
        stage.addEventHandler(WindowEvent.WINDOW_HIDDEN, e -> jfx.setToastHandler(prevHandler));
    }
}
