package com.javaclaw.ui.javafx.control;

import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/**
 * 通用滑块开关控件（设计稿 kit.css {@code .toggle}）。
 *
 * <p>规格：38×22 圆角胶囊轨道 + 18×18 白色圆形滑块；
 * off 轨道色 {@code -jc-border-strong}、on 轨道色 {@code -jc-primary-500}；
 * 滑块带轻阴影，切换时约 150ms 平移过渡。所有颜色走 {@code -jc-*} 令牌
 * （样式见 {@code /css/controls.css}），深色主题自动适配。</p>
 *
 * <p>用法等价于 CheckBox：通过 {@link #selectedProperty()} 绑定状态，
 * {@link #isSelected()} / {@link #setSelected(boolean)} 读写。点击或空格键切换。</p>
 *
 * <p>样式加载：controls.css 依赖 chat.css 在 {@code .root} 上定义的令牌，
 * 须在 chat.css 之后追加（{@code scene.getStylesheets().add(controls.css)}）。</p>
 *
 * @author JavaClaw
 */
public class ToggleSwitch extends Region {

    /** 轨道与滑块尺寸（与设计稿一致，单位 px） */
    private static final double TRACK_WIDTH = 38;
    private static final double TRACK_HEIGHT = 22;
    private static final double THUMB_SIZE = 18;
    /** 滑块左右内边距：(轨道高 - 滑块) / 2 */
    private static final double PADDING = (TRACK_HEIGHT - THUMB_SIZE) / 2;
    /** 平移过渡时长 */
    private static final Duration ANIM = Duration.millis(150);

    /** 选中状态属性，供外部绑定 */
    private final BooleanProperty selected = new SimpleBooleanProperty(this, "selected", false);

    /** 轨道容器（承载滑块） */
    private final StackPane track = new StackPane();
    /** 圆形滑块 */
    private final Region thumb = new Region();

    private final TranslateTransition slide;

    public ToggleSwitch() {
        this(false);
    }

    public ToggleSwitch(boolean initiallySelected) {
        getStyleClass().add("jc-switch-root");

        // 轨道
        track.getStyleClass().add("jc-switch");
        track.setAlignment(Pos.CENTER_LEFT);
        track.setPadding(new javafx.geometry.Insets(0, PADDING, 0, PADDING));

        // 滑块
        thumb.getStyleClass().add("jc-switch-thumb");

        track.getChildren().add(thumb);
        getChildren().add(track);

        // 滑块平移动画（仅作用于 thumb 的 translateX）
        slide = new TranslateTransition(ANIM, thumb);
        slide.setInterpolator(Interpolator.EASE_BOTH);

        // 点击轨道切换
        track.setOnMouseClicked(e -> {
            if (!isDisabled()) {
                setSelected(!isSelected());
            }
        });

        // 键盘可达：聚焦后空格/回车切换
        setFocusTraversable(true);
        setOnKeyPressed(e -> {
            if ((e.getCode() == KeyCode.SPACE || e.getCode() == KeyCode.ENTER) && !isDisabled()) {
                setSelected(!isSelected());
                e.consume();
            }
        });
        // 焦点环挂在轨道上（视觉一致）
        focusedProperty().addListener((obs, o, n) -> track.pseudoClassStateChanged(
                javafx.css.PseudoClass.getPseudoClass("focused"), n));

        // 状态变化 -> 更新轨道样式 + 触发滑块平移
        selected.addListener((obs, o, n) -> updateVisual(true));

        // 初始状态（不播动画，直接就位）
        setSelected(initiallySelected);
        updateVisual(false);
    }

    /**
     * 根据当前选中态更新轨道颜色类与滑块位置。
     *
     * @param animate true 时滑块平移带过渡动画；false 时直接就位（初始化用）
     */
    private void updateVisual(boolean animate) {
        boolean on = isSelected();
        track.getStyleClass().remove("jc-switch-on");
        if (on) {
            track.getStyleClass().add("jc-switch-on");
        }
        double target = on ? (TRACK_WIDTH - THUMB_SIZE - 2 * PADDING) : 0;
        slide.stop();
        if (animate) {
            slide.setToX(target);
            slide.playFromStart();
        } else {
            thumb.setTranslateX(target);
        }
    }

    @Override
    protected double computePrefWidth(double height) {
        return TRACK_WIDTH + getInsets().getLeft() + getInsets().getRight();
    }

    @Override
    protected double computePrefHeight(double width) {
        return TRACK_HEIGHT + getInsets().getTop() + getInsets().getBottom();
    }

    @Override
    protected void layoutChildren() {
        layoutInArea(track, getInsets().getLeft(), getInsets().getTop(),
                TRACK_WIDTH, TRACK_HEIGHT, 0,
                javafx.geometry.HPos.LEFT, javafx.geometry.VPos.CENTER);
    }

    // ==================== 公共 API ====================

    public BooleanProperty selectedProperty() {
        return selected;
    }

    public boolean isSelected() {
        return selected.get();
    }

    public void setSelected(boolean value) {
        selected.set(value);
    }
}
