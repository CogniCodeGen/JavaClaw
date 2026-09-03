package com.javaclaw.desktop.component;

import java.util.Objects;

import javafx.scene.control.TextField;

/** 展示可用鼠标或键盘选择、复制，但不能原地修改的非敏感文本。 */
public final class CopyableTextField extends TextField {
    /**
     * 创建可复制只读字段。
     *
     * @param text 公开展示且允许复制的文本
     * @param accessibleText 辅助技术读取的用途说明
     */
    public CopyableTextField(String text, String accessibleText) {
        super(requireText(text, "text"));
        setEditable(false);
        setAccessibleText(requireText(accessibleText, "accessibleText"));
        getStyleClass().add("copyable-text-field");
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return checked;
    }
}
