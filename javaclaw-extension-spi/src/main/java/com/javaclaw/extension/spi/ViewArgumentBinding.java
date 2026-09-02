package com.javaclaw.extension.spi;

/**
 * 数据源 query 参数到已选择主表行字段的安全直接绑定。
 *
 * @param argument query 参数名
 * @param sourceId 已在当前数据源之前加载的主数据源
 * @param rowField 主数据源选中行字段
 */
public record ViewArgumentBinding(String argument, String sourceId, String rowField) {
    /** 校验绑定；字段仅保存名称，不支持表达式或脚本。 */
    public ViewArgumentBinding {
        argument = ViewSchemaText.required(argument, "argument");
        sourceId = ViewSchemaText.required(sourceId, "sourceId");
        rowField = ViewSchemaText.required(rowField, "rowField");
    }
}
