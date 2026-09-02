package com.javaclaw.extension.spi;

/**
 * View command 的 expected revision 来源。
 *
 * <p>平台只读取数据源元数据或当前选中行的直接字段，不解释表达式。
 */
public sealed interface ExpectedRevisionBinding
        permits ExpectedRevisionBinding.None, ExpectedRevisionBinding.SourceRevision, ExpectedRevisionBinding.RowField {
    /** 不携带资源版本，适用于创建命令。 */
    record None() implements ExpectedRevisionBinding {}

    /**
     * 使用一个数据源返回的整体 revision。
     *
     * @param sourceId 数据源标识
     */
    record SourceRevision(String sourceId) implements ExpectedRevisionBinding {
        /** 校验数据源标识。 */
        public SourceRevision {
            sourceId = ViewSchemaText.required(sourceId, "sourceId");
        }
    }

    /**
     * 使用当前选中行的 revision 字段，适用于混合版本列表。
     *
     * @param field 非负整数所在的直接行字段
     */
    record RowField(String field) implements ExpectedRevisionBinding {
        /** 校验字段名。 */
        public RowField {
            field = ViewSchemaText.required(field, "field");
        }
    }
}
