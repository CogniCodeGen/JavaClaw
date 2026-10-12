package com.javaclaw.chat;

import com.javaclaw.platform.fx.FxDispatcher;
import javafx.scene.layout.Region;
import org.fxmisc.richtext.InlineCssTextArea;

/** 只读消息文本的统一配置和内容高度适配。 */
final class BubbleTextAreaSupport {

    private static final FxDispatcher FX = new FxDispatcher();

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
        // RichTextFX 按当前宽度计算折行高度；等父容器本次布局结束后再触发行布局。
        area.widthProperty().addListener((observable, previous, width) ->
                FX.dispatchLater(area::requestLayout));
        area.requestLayout();
    }
}
