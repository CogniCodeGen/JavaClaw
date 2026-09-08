package com.javaclaw.desktop.view;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.javaclaw.extension.spi.GraphBrowsing;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewGraphWindowTest {
    private final CanonicalJson json = new CanonicalJson();

    @Test
    void 页面窗口独立且筛选重置已展开节点和邻接游标() {
        ViewGraphWindow first = window();
        ViewGraphWindow second = window();
        first.observe(data(200));
        second.observe(data(200));
        first.accept(
                "n0",
                json.encode(Map.of(
                        "graph", Map.of("nodes", List.of(Map.of("id", "new"))), "hasMore", true, "nextCursor", "new")));
        assertTrue(first.neighbors("n0").json().contains("new"));
        assertEquals(201, ids(first).size());
        assertEquals(200, ids(second).size());
        first.filter(new ViewGraphAction.Filter("查找", true));
        assertEquals(List.of(), ids(first));
        assertTrue(first.encode().contains("查找"));
        assertTrue(first.encode().contains("true"));
        assertThrows(IllegalArgumentException.class, () -> first.neighbors("n0"));
    }

    @Test
    void 五百节点预算与未知节点检查在查询前生效() {
        ViewGraphWindow value = window();
        value.observe(data(499));
        assertTrue(value.neighbors("n0").json().contains("\"limit\":1"));
        assertThrows(IllegalArgumentException.class, () -> value.neighbors("unknown"));
        assertThrows(
                IllegalArgumentException.class,
                () -> value.accept(
                        "n0",
                        json.encode(Map.of(
                                "graph",
                                Map.of("nodes", List.of(Map.of("id", "x"), Map.of("id", "y"))),
                                "hasMore",
                                false,
                                "nextCursor",
                                ""))));
        assertEquals(499, ids(value).size());
        value.observe(data(500));
        assertThrows(IllegalArgumentException.class, () -> value.neighbors("n0"));
    }

    @Test
    void 拒绝超页结果和重复游标且不污染已接受窗口() {
        ViewGraphWindow value = window();
        value.observe(data(2));
        var accepted = json.encode(
                Map.of("graph", Map.of("nodes", List.of(Map.of("id", "new"))), "hasMore", true, "nextCursor", "c1"));
        value.accept("n0", accepted);
        assertThrows(IllegalArgumentException.class, () -> value.accept("n0", accepted));
        assertThrows(
                IllegalArgumentException.class,
                () -> value.accept(
                        "n0",
                        json.encode(Map.of(
                                "graph",
                                Map.of(
                                        "nodes",
                                        IntStream.range(0, 51)
                                                .mapToObj(i -> Map.of("id", "x" + i))
                                                .toList())))));
        assertEquals(3, ids(value).size());
        value.accept("n0", json.encode(Map.of("graph", Map.of("nodes", List.of()), "hasMore", false)));
        assertThrows(IllegalArgumentException.class, () -> value.neighbors("n0"));
    }

    private List<?> ids(ViewGraphWindow value) {
        return (List<?>) json.decode(new com.javaclaw.api.CanonicalPayload(value.encode()), Map.class)
                .get("nodeIds");
    }

    private static ViewGraphWindow window() {
        return new ViewGraphWindow(
                new ViewSchema.Graph("g", "关系", "nodes", "edges", "id", "label", "state", "source", "target"),
                new GraphBrowsing("graph/neighbors", "graphWindow", "query", "includeInactive", "nodeIds"));
    }

    private static ViewData data(int count) {
        var rows = IntStream.range(0, count)
                .mapToObj(i -> Map.<String, Object>of("id", "n" + i))
                .toList();
        return new ViewData(
                Map.of("nodes", new ViewData.Source(rows, Map.of(), "", "", false, 1, 0, Optional.empty())));
    }
}
