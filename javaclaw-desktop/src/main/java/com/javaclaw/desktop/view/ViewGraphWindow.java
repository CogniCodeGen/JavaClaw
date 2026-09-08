package com.javaclaw.desktop.view;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.extension.spi.GraphBrowsing;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.protocol.CanonicalJson;

/** 单个 ViewRenderSession 的图谱窗口；FX 线程独占，最多 500 节点，不写服务端配置。 */
final class ViewGraphWindow {
    private final ViewSchema.Graph graph;
    private final GraphBrowsing browsing;
    private final LinkedHashSet<String> ids = new LinkedHashSet<>();
    private final Map<String, String> cursors = new LinkedHashMap<>();
    private ViewGraphAction.Filter filter = new ViewGraphAction.Filter("", false);

    ViewGraphWindow(ViewSchema.Graph graph, GraphBrowsing browsing) {
        this.graph = graph;
        this.browsing = browsing;
    }

    void filter(ViewGraphAction.Filter next) {
        filter = next;
        ids.clear();
        cursors.clear();
    }

    void observe(ViewData data) {
        ids.clear();
        data.source(graph.nodeSourceId()).rows().stream()
                .limit(500)
                .map(row -> Objects.toString(row.get(graph.nodeIdField()), ""))
                .filter(id -> !id.isEmpty())
                .forEach(ids::add);
    }

    String encode() {
        return new CanonicalJson()
                .encode(Map.of(
                        browsing.queryField(), filter.query(),
                        browsing.includeInactiveField(), filter.includeInactive(),
                        browsing.admittedIdsField(), List.copyOf(ids)))
                .json();
    }

    CanonicalPayload neighbors(String nodeId) {
        if (!ids.contains(nodeId) || ids.size() >= 500) {
            throw new IllegalArgumentException("只能展开当前窗口内的节点，最多 500 个节点");
        }
        String cursor = cursors.getOrDefault(nodeId, "");
        if (cursor.equals("@end")) {
            throw new IllegalArgumentException("该节点的邻居已全部展示");
        }
        return new CanonicalJson()
                .encode(Map.of(
                        "id",
                        nodeId,
                        "afterId",
                        cursor,
                        "includeInactive",
                        filter.includeInactive(),
                        "limit",
                        Math.min(50, 500 - ids.size())));
    }

    void accept(String nodeId, CanonicalPayload payload) {
        Map<?, ?> page = new CanonicalJson().decode(payload, Map.class);
        if (!(page.get("graph") instanceof Map<?, ?> result)
                || !(result.get("nodes") instanceof List<?> nodes)
                || nodes.size() > 50) {
            throw new IllegalArgumentException("邻接查询结果不符合受限图谱协议");
        }
        LinkedHashSet<String> next = new LinkedHashSet<>(ids);
        for (Object value : nodes) {
            if (!(value instanceof Map<?, ?> row)
                    || !(row.get(graph.nodeIdField()) instanceof String id)
                    || id.isBlank()) {
                throw new IllegalArgumentException("邻接节点缺少稳定标识");
            }
            next.add(id);
        }
        if (next.size() > 500) {
            throw new IllegalArgumentException("邻接查询超出 500 节点预算");
        }
        String nextCursor = Objects.toString(page.get("nextCursor"), "");
        if (Boolean.TRUE.equals(page.get("hasMore"))) {
            if (nextCursor.isBlank() || nextCursor.equals(cursors.get(nodeId))) {
                throw new IllegalArgumentException("邻接查询游标未推进");
            }
            cursors.put(nodeId, nextCursor);
        } else {
            cursors.put(nodeId, "@end");
        }
        ids.clear();
        ids.addAll(next);
    }
}
