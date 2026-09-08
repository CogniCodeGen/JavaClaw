package com.javaclaw.desktop.web;

import java.util.LinkedHashMap;
import java.util.Map;

import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;

/** 读取现有 CSS 级联计算后的颜色与字号，外观预览和取消自然复用同一令牌，不建立第二套调色板。 */
final class WebThemeProbe extends Pane {
    private static final String[] NAMES = {
        "page",
        "card",
        "panel",
        "sunken",
        "body",
        "title",
        "muted",
        "border",
        "primary",
        "warning",
        "danger",
        "user",
        "userBorder",
        "assistantBorder",
        "warningBg",
        "dangerBg"
    };
    private final Map<String, Label> labels = new LinkedHashMap<>();

    WebThemeProbe() {
        setManaged(false);
        setMouseTransparent(true);
        setOpacity(0);
        for (String name : NAMES) {
            Label label = new Label("M");
            label.getStyleClass().add("web-theme-" + name);
            labels.put(name, label);
            getChildren().add(label);
        }
    }

    Map<String, String> values() {
        Map<String, String> result = new LinkedHashMap<>();
        labels.forEach((name, label) -> result.put(name, css(label.getTextFill())));
        var font = labels.get("body").getFont();
        result.put("fontSize", Double.toString(font.getSize()) + "px");
        result.put("fontFamily", "\"" + font.getFamily().replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        return Map.copyOf(result);
    }

    Color background() {
        Paint paint = labels.get("page").getTextFill();
        return paint instanceof Color color ? color : Color.TRANSPARENT;
    }

    private static String css(Paint paint) {
        if (!(paint instanceof Color color)) {
            return "transparent";
        }
        return "rgba(" + Math.round(color.getRed() * 255) + ',' + Math.round(color.getGreen() * 255) + ','
                + Math.round(color.getBlue() * 255) + ',' + color.getOpacity() + ')';
    }
}
