package com.javaclaw.extension.spi;

/**
 * 标量值绑定，只允许数据源和直接字段名，不支持路径表达式。
 *
 * @param sourceId 数据源标识
 * @param field 结果 values 或平台当前单选行中的直接字段名
 */
public record ViewBinding(String sourceId, String field) {
    /** 校验绑定。 */
    public ViewBinding {
        sourceId = ViewSchemaText.required(sourceId, "sourceId");
        field = ViewSchemaText.required(field, "field");
    }
}
