package com.javaclaw.desktop.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.javaclaw.protocol.CanonicalJson;

/**
 * 后台生成的不可变展示内容；聊天仅在实际提交时相对已提交内容生成增量。
 *
 * <p>行 JSON 与顺序 JSON 均在后台编码，FX 线程只比较有界身份和版本并拼接变化行。文档和图谱继续提交原完整载荷。
 */
public final class WebSurfaceContent {
    private static final CanonicalJson JSON = new CanonicalJson();
    private final String serialized;
    private final List<Row> rows;
    private final Map<String, Row> indexed;
    private final List<String> order;
    private final String encodedOrder;
    private final boolean hasEarlier;

    private WebSurfaceContent(String serialized, List<Row> rows, boolean hasEarlier) {
        this.serialized = serialized;
        this.rows = List.copyOf(rows);
        this.hasEarlier = hasEarlier;
        LinkedHashMap<String, Row> index = new LinkedHashMap<>();
        for (Row row : rows) {
            if (index.put(row.id(), row) != null) {
                throw new IllegalArgumentException("聊天投影包含重复行身份");
            }
        }
        indexed = Map.copyOf(index);
        order = rows.stream().map(Row::id).toList();
        encodedOrder = encodeIds(order);
    }

    /**
     * 包装现有完整载荷，不改变其协议。
     *
     * @param serialized 已在后台编码的非空 JSON 对象
     * @return 不使用行增量的展示内容
     */
    public static WebSurfaceContent serialized(String serialized) {
        return new WebSurfaceContent(Objects.requireNonNull(serialized, "serialized"), List.of(), false);
    }

    /**
     * 创建最多 500 行的聊天逻辑快照；由后台投影线程调用。
     *
     * @param rows 按展示顺序排列、身份唯一的非空行列表
     * @param hasEarlier 是否可加载更早历史
     * @return 与行动作映射共同提交的不可变内容
     */
    public static WebSurfaceContent chat(List<Row> rows, boolean hasEarlier) {
        if (rows.size() > 500) {
            throw new IllegalArgumentException("聊天投影超过 500 行窗口");
        }
        return new WebSurfaceContent(null, rows, hasEarlier);
    }

    /**
     * 比较业务可见内容，不把后台请求序号变化视为重新绘制。
     *
     * @param other 另一份非空内容
     * @return 行顺序、版本和历史状态都相同时返回 true
     */
    public boolean sameAs(WebSurfaceContent other) {
        return Objects.equals(serialized, other.serialized)
                && hasEarlier == other.hasEarlier
                && order.equals(other.order)
                && rows.stream()
                        .allMatch(row ->
                                row.version().equals(other.indexed.get(row.id()).version()));
    }

    /**
     * 在首次显示、上下文切换或 WebKit 恢复时编码完整载荷。
     *
     * @return 已编码的完整 JSON 对象
     */
    public String fullPayload() {
        if (serialized != null) {
            return serialized;
        }
        return "{\"mode\":\"replace\",\"hasEarlier\":" + hasEarlier + ",\"items\":[" + join(rows) + "]}";
    }

    String payload(WebSurfaceContent previous, long baseRevision) {
        if (serialized != null || previous == null || previous.serialized != null) {
            return fullPayload();
        }
        List<Row> changed = rows.stream()
                .filter(row -> !previous.indexed.containsKey(row.id())
                        || !previous.indexed.get(row.id()).version().equals(row.version()))
                .toList();
        List<String> removed = new ArrayList<>(previous.order);
        removed.removeAll(indexed.keySet());
        StringBuilder payload = new StringBuilder("{\"mode\":\"patch\",\"baseRevision\":")
                .append(baseRevision)
                .append(",\"upserts\":[")
                .append(join(changed))
                .append("],\"removedIds\":")
                .append(encodeIds(removed));
        if (!order.equals(previous.order)) {
            payload.append(",\"order\":").append(encodedOrder);
        }
        if (hasEarlier != previous.hasEarlier) {
            payload.append(",\"hasEarlier\":").append(hasEarlier);
        }
        return payload.append('}').toString();
    }

    private static String encodeIds(List<String> ids) {
        if (ids.isEmpty()) {
            return "[]";
        }
        String object = JSON.encode(Map.of("value", ids)).json();
        return object.substring(9, object.length() - 1);
    }

    private static String join(List<Row> rows) {
        return rows.stream().map(Row::json).collect(Collectors.joining(","));
    }

    /**
     * 一条后台编码的聊天展示行。
     *
     * @param id 非空稳定行身份
     * @param version 非空内容与精确引用共同决定的版本
     * @param json 已安全编码的非空完整行对象，包含相同 id 和 version
     */
    public record Row(String id, String version, String json) {
        /**
         * @param id 非空稳定行身份
         * @param version 非空内容与精确引用版本
         * @param json 非空完整行 JSON
         */
        public Row {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(json, "json");
        }
    }
}
