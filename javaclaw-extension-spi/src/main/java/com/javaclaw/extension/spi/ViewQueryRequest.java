package com.javaclaw.extension.spi;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ViewSchema v2 数据源的标准查询参数。
 *
 * @param dataSourceId 页面数据源标识
 * @param arguments Schema 固定参数
 * @param cursor 排他分页游标；首页为空字符串
 * @param limit 页大小，1 到 200
 * @param selectedKey 可选稳定选择键
 */
public record ViewQueryRequest(
        String dataSourceId, Map<String, String> arguments, String cursor, int limit, Optional<String> selectedKey) {
    /** 校验查询。 */
    public ViewQueryRequest {
        dataSourceId = ViewSchemaText.required(dataSourceId, "dataSourceId");
        arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments"));
        cursor = cursor == null ? "" : cursor;
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        selectedKey = Objects.requireNonNull(selectedKey, "selectedKey")
                .map(value -> ViewSchemaText.required(value, "selectedKey"));
    }
}
