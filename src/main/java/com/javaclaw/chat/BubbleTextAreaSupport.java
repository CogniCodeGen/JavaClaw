package com.javaclaw.chat;

import org.fxmisc.richtext.InlineCssTextArea;

/** 只读消息文本的统一配置和内容高度适配。 */
final class BubbleTextAreaSupport {

    private BubbleTextAreaSupport() {
    }

    static void configure(
            InlineCssTextArea area, String content, double prefWidth, String textCss) {
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefWidth(prefWidth);
        if (!area.getStyleClass().contains("bubble-text-area")) {
            area.getStyleClass().add("bubble-text-area");
        }
        if (textCss != null && !textCss.isBlank()) {
            area.setTextInsertionStyle(textCss);
        }
        String value = content == null ? "" : content;
        area.replaceText(value);
        if (!value.isEmpty() && textCss != null && !textCss.isBlank()) {
            area.setStyle(0, value.length(), textCss);
        }
        fitHeight(area);
    }

    private static void fitHeight(InlineCssTextArea area) {
        Runnable estimate = () -> setHeight(area,
                Math.max(28, area.getParagraphs().size() * 20.0 + 8));
        area.totalHeightEstimateProperty().addListener((observable, previous, height) -> {
            if (height != null && height.doubleValue() > 0) {
                setHeight(area, height.doubleValue() + 4);
            } else {
                estimate.run();
            }
        });
        estimate.run();
    }

    private static void setHeight(InlineCssTextArea area, double height) {
        area.setPrefHeight(height);
        area.setMinHeight(height);
        area.setMaxHeight(height);
    }
}
