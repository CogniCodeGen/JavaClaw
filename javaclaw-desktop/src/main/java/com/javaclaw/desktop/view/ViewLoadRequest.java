package com.javaclaw.desktop.view;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ViewSchema v2 页面读取状态。
 *
 * @param cursors 各数据源当前游标
 * @param selectedKeys 各数据源当前选择键
 * @param pageIndexes 各数据源从 0 开始的页序号
 */
public record ViewLoadRequest(
        Map<String, String> cursors, Map<String, String> selectedKeys, Map<String, Integer> pageIndexes) {
    /** 复制并校验状态。 */
    public ViewLoadRequest {
        cursors = Map.copyOf(Objects.requireNonNull(cursors, "cursors"));
        selectedKeys = Map.copyOf(Objects.requireNonNull(selectedKeys, "selectedKeys"));
        pageIndexes = Map.copyOf(Objects.requireNonNull(pageIndexes, "pageIndexes"));
        if (pageIndexes.values().stream().anyMatch(index -> index == null || index < 0)) {
            throw new IllegalArgumentException("page index must not be negative");
        }
    }

    /** @return 首页且没有选择的读取状态 */
    public static ViewLoadRequest initial() {
        return new ViewLoadRequest(Map.of(), Map.of(), Map.of());
    }

    /** 返回数据源游标。 */
    public String cursor(String sourceId) {
        return cursors.getOrDefault(sourceId, "");
    }

    /** 返回数据源选择。 */
    public Optional<String> selectedKey(String sourceId) {
        return Optional.ofNullable(selectedKeys.get(sourceId));
    }

    /** 返回数据源页序号。 */
    public int pageIndex(String sourceId) {
        return pageIndexes.getOrDefault(sourceId, 0);
    }
}
