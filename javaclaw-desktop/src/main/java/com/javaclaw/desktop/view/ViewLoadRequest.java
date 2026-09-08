package com.javaclaw.desktop.view;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;

/**
 * ViewSchema v2 页面读取状态。
 *
 * @param cursors 各数据源当前游标
 * @param selectedKeys 各数据源当前选择键；空字符串表示已初始化但未选择，缺少键才允许使用初选提示
 * @param pageIndexes 各数据源从 0 开始的页序号
 * @param graphWindows 页面会话持有的各 Graph JSON 窗口；不进入持久配置
 * @param initialSelections 页面首次接收的初选提示，按精确版本持续核对；与用户显式选择分离，不进入 RPC
 */
public record ViewLoadRequest(
        Map<String, String> cursors,
        Map<String, String> selectedKeys,
        Map<String, Integer> pageIndexes,
        Map<String, String> graphWindows,
        Map<String, CanonicalPayload> initialSelections) {
    /** 保留未携带初选会话状态的调用，初选只在页面内保存。 */
    public ViewLoadRequest(
            Map<String, String> cursors,
            Map<String, String> selectedKeys,
            Map<String, Integer> pageIndexes,
            Map<String, String> graphWindows) {
        this(cursors, selectedKeys, pageIndexes, graphWindows, Map.of());
    }

    /** 保留没有图谱局部窗口的读取构造。 */
    public ViewLoadRequest(
            Map<String, String> cursors, Map<String, String> selectedKeys, Map<String, Integer> pageIndexes) {
        this(cursors, selectedKeys, pageIndexes, Map.of());
    }

    /** 复制并校验状态。 */
    public ViewLoadRequest {
        initialSelections = Map.copyOf(Objects.requireNonNull(initialSelections, "initialSelections"));
        graphWindows = Map.copyOf(Objects.requireNonNull(graphWindows, "graphWindows"));
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
        return Optional.ofNullable(selectedKeys.get(sourceId)).filter(value -> !value.isBlank());
    }

    /** 返回页面是否已经初始化或明确清空该数据源选择，避免刷新重新套用服务端初选。 */
    public boolean selectionInitialized(String sourceId) {
        return selectedKeys.containsKey(sourceId);
    }

    /** 返回数据源页序号。 */
    public int pageIndex(String sourceId) {
        return pageIndexes.getOrDefault(sourceId, 0);
    }
}
