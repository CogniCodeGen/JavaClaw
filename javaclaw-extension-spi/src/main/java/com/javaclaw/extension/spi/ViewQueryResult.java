package com.javaclaw.extension.spi;

import java.util.List;
import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;

/**
 * ViewSchema v2 数据源的标准结果。
 *
 * @param dataSourceId 请求中的数据源标识
 * @param rows 规范 JSON 行
 * @param values 表单和标量节点使用的规范 JSON 对象；可在 {@link ViewInitialSelection#VALUES_KEY} 中携带声明式初选
 * @param nextCursor 下一页游标；没有下一页为空字符串
 * @param hasMore 是否存在下一页
 * @param revision 数据源资源版本；无单资源版本时为 0
 */
public record ViewQueryResult(
        String dataSourceId,
        List<CanonicalPayload> rows,
        CanonicalPayload values,
        String nextCursor,
        boolean hasMore,
        long revision) {
    /** 校验结果。 */
    public ViewQueryResult {
        dataSourceId = ViewSchemaText.required(dataSourceId, "dataSourceId");
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        Objects.requireNonNull(values, "values");
        nextCursor = nextCursor == null ? "" : nextCursor;
        if (hasMore && nextCursor.isBlank()) {
            throw new IllegalArgumentException("hasMore result requires nextCursor");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
    }
}
