package com.javaclaw.extension.spi;

/**
 * 从标准 query 行生成选项的声明。
 *
 * @param sourceId 数据源标识
 * @param valueField 选项值字段
 * @param labelField 展示标签字段
 */
public record ViewOptionSource(String sourceId, String valueField, String labelField) {
    /** 校验字段名。 */
    public ViewOptionSource {
        sourceId = ViewSchemaText.required(sourceId, "sourceId");
        valueField = ViewSchemaText.required(valueField, "valueField");
        labelField = ViewSchemaText.required(labelField, "labelField");
    }
}
