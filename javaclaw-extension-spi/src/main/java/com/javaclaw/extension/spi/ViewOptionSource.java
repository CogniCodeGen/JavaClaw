package com.javaclaw.extension.spi;

import java.util.Objects;
import java.util.Optional;

/**
 * 从标准 query 行生成选项的声明。
 *
 * @param sourceId 数据源标识
 * @param valueField 选项值字段
 * @param labelField 展示标签字段
 * @param filter 可选的同表单或同行字段过滤
 */
public record ViewOptionSource(
        String sourceId, String valueField, String labelField, Optional<ViewOptionFilter> filter) {
    /** 校验字段名。 */
    public ViewOptionSource {
        sourceId = ViewSchemaText.required(sourceId, "sourceId");
        valueField = ViewSchemaText.required(valueField, "valueField");
        labelField = ViewSchemaText.required(labelField, "labelField");
        filter = Objects.requireNonNull(filter, "filter");
    }
}
