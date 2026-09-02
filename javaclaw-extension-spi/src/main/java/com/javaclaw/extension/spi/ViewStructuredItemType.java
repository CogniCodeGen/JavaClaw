package com.javaclaw.extension.spi;

/** 结构化列表行内允许的平台标量类型；枚举中不存在嵌套结构化列表。 */
public enum ViewStructuredItemType {
    /** 单行文本。 */
    TEXT,
    /** 多行文本。 */
    MULTILINE,
    /** 十进制数值。 */
    NUMBER,
    /** 布尔值。 */
    BOOLEAN,
    /** 固定选项。 */
    CHOICE,
    /** 以字符串数组提交的一维文本列表。 */
    TEXT_LIST
}
