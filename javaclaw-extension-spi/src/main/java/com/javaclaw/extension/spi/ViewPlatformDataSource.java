package com.javaclaw.extension.spi;

import java.util.Set;

/** 平台为 ViewSchema v2 提供的受限只读数据源。 */
public final class ViewPlatformDataSource {
    /** 当前 Workspace 默认 Agent Role 实际可见的精确工具目录。 */
    public static final String TOOL_CATALOG = "platform/tool-catalog";

    /** 当前工具目录中各工具 outputSchema 的可断言标量 JSON Pointer。 */
    public static final String TOOL_OUTPUT_FIELDS = "platform/tool-output-fields";

    /** 工具全局名称字段。 */
    public static final String TOOL_NAME_FIELD = "toolName";

    /** 带 revision 与 schema hash 摘要的工具显示字段。 */
    public static final String TOOL_LABEL_FIELD = "toolLabel";

    /** RFC 6901 JSON Pointer 字段。 */
    public static final String POINTER_FIELD = "fieldPointer";

    /** 带标量类型的 JSON Pointer 显示字段。 */
    public static final String POINTER_LABEL_FIELD = "fieldLabel";

    private static final Set<String> QUERIES = Set.of(TOOL_CATALOG, TOOL_OUTPUT_FIELDS);

    private ViewPlatformDataSource() {}

    /**
     * 判断 query 是否由平台而非扩展解析。
     *
     * @param query ViewSchema 数据源 query
     * @return 是否为已知平台数据源
     */
    public static boolean supports(String query) {
        return QUERIES.contains(query);
    }

    /**
     * 判断 query 是否占用平台命名空间。
     *
     * @param query ViewSchema 数据源 query
     * @return 是否以 {@code platform/} 开头
     */
    public static boolean claimsNamespace(String query) {
        return query != null && query.startsWith("platform/");
    }
}
