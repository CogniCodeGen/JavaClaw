package com.javaclaw.chat;

import javafx.application.Platform;
import javafx.scene.layout.Region;
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
        area.setAutoHeight(true);
        area.setPrefHeight(Region.USE_COMPUTED_SIZE);
        area.setMinHeight(Region.USE_PREF_SIZE);
        area.setMaxHeight(Region.USE_PREF_SIZE);
        // RichTextFX measures wrapped paragraphs at the area's current width.
        // Defer until the parent's current resize pass ends so the whole row is remeasured.
        area.widthProperty().addListener((observable, previous, width) ->
                Platform.runLater(area::requestLayout));
        area.requestLayout();
    }
}
