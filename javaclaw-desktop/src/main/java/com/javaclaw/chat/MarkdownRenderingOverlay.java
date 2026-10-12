package com.javaclaw.chat;

import javafx.scene.Node;
import javafx.scene.layout.Pane;

/**
 * 不参与宿主首选尺寸计算的气泡排版提示层；实际标签由 FXML 声明。
 */
public final class MarkdownRenderingOverlay extends Pane {

    public MarkdownRenderingOverlay() {
        setMouseTransparent(true);
        setPickOnBounds(false);
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
    }

    @Override
    protected double computeMinWidth(double height) {
        return 0;
    }

    @Override
    protected double computeMinHeight(double width) {
        return 0;
    }

    @Override
    protected double computePrefWidth(double height) {
        return 0;
    }

    @Override
    protected double computePrefHeight(double width) {
        return 0;
    }

    @Override
    protected void layoutChildren() {
        if (getChildren().isEmpty()) return;
        Node hint = getChildren().getFirst();
        double width = snapSizeX(hint.prefWidth(-1));
        double height = snapSizeY(hint.prefHeight(width));
        hint.resizeRelocate(Math.max(0, getWidth() - width), 0, width, height);
    }
}
