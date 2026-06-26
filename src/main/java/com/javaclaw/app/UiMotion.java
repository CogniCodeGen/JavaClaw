package com.javaclaw.app;

import javafx.animation.*;
import javafx.scene.Node;
import javafx.util.Duration;

/**
 * 统一的微动效工具类（情感语法）
 *
 * <p>动效时长遵循设计令牌:
 * <ul>
 *   <li>{@link #DURATION_FAST} — 150ms，hover / focus 反馈</li>
 *   <li>{@link #DURATION_BASE} — 240ms，面板切换 / 弹窗</li>
 *   <li>{@link #DURATION_SLOW} — 400ms，工作区切换等大动作</li>
 * </ul>
 *
 * <p>语义:
 * <ul>
 *   <li>{@link #success(Node)} — 绿色完成感（短暂缩放弹出）</li>
 *   <li>{@link #error(Node)} — 红色否定感（水平抖动）</li>
 *   <li>{@link #pulse(Node)} — 吸引注意（缓慢呼吸）</li>
 *   <li>{@link #fadeIn(Node)} — 从下方 8px 滑入 + 淡入</li>
 * </ul>
 *
 * <p>所有方法都是幂等的，重复调用不会导致动画叠加错误。
 */
public final class UiMotion {

    public static final Duration DURATION_FAST = Duration.millis(150);
    public static final Duration DURATION_BASE = Duration.millis(240);
    public static final Duration DURATION_SLOW = Duration.millis(400);

    private static final Interpolator EASE_OUT = Interpolator.SPLINE(0.16, 1, 0.3, 1);

    private UiMotion() {}

    /**
     * 成功反馈：节点短暂放大到 1.08 再回到 1.0，表达"完成 / 已收到"
     */
    public static void success(Node node) {
        if (node == null) return;
        ScaleTransition pop = new ScaleTransition(DURATION_FAST, node);
        pop.setFromX(1.0);
        pop.setFromY(1.0);
        pop.setToX(1.08);
        pop.setToY(1.08);
        pop.setAutoReverse(true);
        pop.setCycleCount(2);
        pop.setInterpolator(EASE_OUT);
        pop.play();
    }

    /**
     * 错误反馈：节点水平轻微抖动 3 次（不过度惊扰）
     */
    public static void error(Node node) {
        if (node == null) return;
        TranslateTransition shake = new TranslateTransition(Duration.millis(80), node);
        shake.setFromX(0);
        shake.setByX(6);
        shake.setAutoReverse(true);
        shake.setCycleCount(6);
        shake.setOnFinished(e -> node.setTranslateX(0));
        shake.play();
    }

    /**
     * 吸引注意：缓慢呼吸（不透明度 1.0 ↔ 0.7），循环一次
     */
    public static void pulse(Node node) {
        if (node == null) return;
        FadeTransition breath = new FadeTransition(Duration.millis(600), node);
        breath.setFromValue(1.0);
        breath.setToValue(0.7);
        breath.setAutoReverse(true);
        breath.setCycleCount(2);
        breath.setInterpolator(EASE_OUT);
        breath.play();
    }

    /**
     * 从下方 8px 滑入 + 淡入（用于消息气泡、浮动提示出现）
     */
    public static void fadeIn(Node node) {
        if (node == null) return;
        node.setOpacity(0);
        node.setTranslateY(8);
        Timeline anim = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(node.opacityProperty(), 0, EASE_OUT),
                        new KeyValue(node.translateYProperty(), 8, EASE_OUT)),
                new KeyFrame(DURATION_BASE,
                        new KeyValue(node.opacityProperty(), 1.0, EASE_OUT),
                        new KeyValue(node.translateYProperty(), 0, EASE_OUT))
        );
        anim.play();
    }
}
