package com.javaclaw.extension.spi;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ViewSchema v2 的标准 query 数据源。
 *
 * @param id 页面内唯一数据源标识
 * @param query 扩展 query operation
 * @param arguments 固定字符串参数
 * @param argumentBindings 从已选择主表行读取的动态参数
 * @param pageSize 每页行数，1 到 200
 */
public record ViewDataSource(
        String id,
        String query,
        Map<String, String> arguments,
        List<ViewArgumentBinding> argumentBindings,
        int pageSize) {
    /** 校验数据源。 */
    public ViewDataSource {
        id = ViewSchemaText.required(id, "id");
        query = ViewSchemaText.required(query, "query");
        arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments"));
        argumentBindings = List.copyOf(Objects.requireNonNull(argumentBindings, "argumentBindings"));
        if (pageSize < 1 || pageSize > 200) {
            throw new IllegalArgumentException("pageSize must be between 1 and 200");
        }
    }
}
