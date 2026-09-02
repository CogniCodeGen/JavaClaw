package com.javaclaw.extension.spi;

/**
 * 静态选项。
 *
 * @param value 提交值
 * @param label 展示标签
 */
public record ViewOption(String value, String label) {
    /** 校验选项。 */
    public ViewOption {
        value = ViewSchemaText.required(value, "value");
        label = ViewSchemaText.required(label, "label");
    }
}
