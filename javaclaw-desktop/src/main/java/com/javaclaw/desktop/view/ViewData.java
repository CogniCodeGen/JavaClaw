package com.javaclaw.desktop.view;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.extension.spi.ViewBinding;

/**
 * 已规范化的 ViewSchema v2 页面数据。
 *
 * @param sources 数据源标识到当前页的映射
 */
public record ViewData(Map<String, Source> sources) {
    /** 深复制数据源，避免渲染期间被调用方修改。 */
    public ViewData {
        sources = Map.copyOf(Objects.requireNonNull(sources, "sources"));
    }

    /**
     * 读取标量绑定。
     *
     * @param binding 安全直接绑定
     * @return 不存在时为 {@code null}
     */
    public Object value(ViewBinding binding) {
        Objects.requireNonNull(binding, "binding");
        Source source = sources.get(binding.sourceId());
        return source == null ? null : source.values().get(binding.field());
    }

    /**
     * 读取数据源；不存在时返回空页。
     *
     * @param sourceId 数据源标识
     * @return 当前页
     */
    public Source source(String sourceId) {
        return sources.getOrDefault(Objects.requireNonNull(sourceId, "sourceId"), Source.empty());
    }

    /** @return 空页面数据 */
    public static ViewData empty() {
        return new ViewData(Map.of());
    }

    /**
     * 单个数据源当前页。
     *
     * @param rows 行
     * @param values 标量值
     * @param cursor 当前排他游标
     * @param nextCursor 下一页游标
     * @param hasMore 是否有下一页
     * @param revision 资源版本
     * @param pageIndex 从 0 开始的页序号
     * @param selectedKey 当前页被平台选中的稳定键
     */
    public record Source(
            List<Map<String, Object>> rows,
            Map<String, Object> values,
            String cursor,
            String nextCursor,
            boolean hasMore,
            long revision,
            int pageIndex,
            Optional<String> selectedKey) {
        /** 深复制行并校验分页元数据。 */
        public Source {
            rows = Objects.requireNonNull(rows, "rows").stream()
                    .map(row -> Map.copyOf(new LinkedHashMap<>(row)))
                    .toList();
            values = Map.copyOf(Objects.requireNonNull(values, "values"));
            cursor = Objects.requireNonNull(cursor, "cursor");
            nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
            selectedKey =
                    Objects.requireNonNull(selectedKey, "selectedKey").map(value -> requireText(value, "selectedKey"));
            if (hasMore && nextCursor.isBlank()) {
                throw new IllegalArgumentException("hasMore source requires nextCursor");
            }
            if (revision < 0 || pageIndex < 0) {
                throw new IllegalArgumentException("revision and pageIndex must not be negative");
            }
        }

        /** @return 空首页 */
        public static Source empty() {
            return new Source(List.of(), Map.of(), "", "", false, 0, 0, Optional.empty());
        }

        private static String requireText(String value, String name) {
            String normalized = Objects.requireNonNull(value, name).strip();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return normalized;
        }
    }
}
