package com.javaclaw.extension.spi;

/**
 * 动态选项的同表单或同行过滤条件。
 *
 * <p>平台只保留数据源行中 {@code sourceField} 等于当前 {@code inputField} 值的选项。该声明不能读取任意表达式，也不能跨表单访问控件。
 *
 * @param sourceField 动态选项数据源中的匹配字段
 * @param inputField 当前表单或结构化列表行中的依赖字段
 */
public record ViewOptionFilter(String sourceField, String inputField) {
    /** 校验两端字段名。 */
    public ViewOptionFilter {
        sourceField = ViewSchemaText.required(sourceField, "sourceField");
        inputField = ViewSchemaText.required(inputField, "inputField");
    }
}
