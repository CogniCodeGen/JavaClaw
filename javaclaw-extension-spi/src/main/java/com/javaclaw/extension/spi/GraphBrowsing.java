package com.javaclaw.extension.spi;

/**
 * 图谱浏览的通用 query 声明，只携带受限 operation 与字段标识，不包含脚本、URL 或权限授予。
 *
 * @param neighborsQuery 扩展已注册的邻居查询 operation
 * @param windowParameter 查询返回的窗口位置参数名
 * @param queryField 文本筛选字段名
 * @param includeInactiveField 是否包含历史项的布尔字段名
 * @param admittedIdsField 已纳入窗口的节点 ID 集合字段名
 */
public record GraphBrowsing(
        String neighborsQuery,
        String windowParameter,
        String queryField,
        String includeInactiveField,
        String admittedIdsField) {
    /** 校验 operation 和参数字段，避免将元数据解释为任意可执行内容。 */
    public GraphBrowsing {
        neighborsQuery = ViewSchemaText.required(neighborsQuery, "neighborsQuery");
        if (neighborsQuery.length() > 128
                || !neighborsQuery.matches("[a-z][a-z0-9._-]{0,63}(/[a-z][a-z0-9._-]{0,63}){0,3}")) {
            throw new IllegalArgumentException("neighborsQuery must be a fixed operation identifier");
        }
        windowParameter = field(windowParameter);
        queryField = field(queryField);
        includeInactiveField = field(includeInactiveField);
        admittedIdsField = field(admittedIdsField);
        if (queryField.equals(includeInactiveField)
                || queryField.equals(admittedIdsField)
                || includeInactiveField.equals(admittedIdsField)) {
            throw new IllegalArgumentException("graph browsing query fields must be distinct");
        }
    }

    private static String field(String value) {
        String checked = ViewSchemaText.required(value, "graph browsing field");
        if (!checked.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("graph browsing field must be an identifier");
        }
        return checked;
    }
}
