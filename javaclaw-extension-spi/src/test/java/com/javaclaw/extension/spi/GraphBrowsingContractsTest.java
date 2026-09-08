package com.javaclaw.extension.spi;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphBrowsingContractsTest {
    @Test
    void 旧页面和Graph构造保持兼容且新声明不可被后续修改() {
        var graph = graph();
        var legacy = new ViewSchema(2, "view", "图谱", List.of(), List.of(graph));
        assertTrue(legacy.graphBrowsing().isEmpty());
        var declarations = new HashMap<String, GraphBrowsing>();
        declarations.put("graph", browsing());
        var schema = new ViewSchema(2, "view", "图谱", List.of(), List.of(graph), declarations);
        declarations.clear();
        assertEquals(browsing(), schema.graphBrowsing().get("graph"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> schema.graphBrowsing().clear());
        assertEquals("window", browsing().windowParameter());
        assertEquals("query", browsing().queryField());
        assertEquals("inactive", browsing().includeInactiveField());
        assertEquals("admitted", browsing().admittedIdsField());
    }

    @Test
    void 元数据只允许已存在Graph标识且不允许无限声明() {
        assertThrows(IllegalArgumentException.class, () -> view(Map.of("missing", browsing())));
        assertThrows(IllegalArgumentException.class, () -> view(Map.of("/unsafe", browsing())));
        var tooMany =
                IntStream.range(0, 33).boxed().collect(Collectors.toMap(index -> "g" + index, ignored -> browsing()));
        assertThrows(IllegalArgumentException.class, () -> view(tooMany));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewSchema(
                        2,
                        "view",
                        "页面",
                        List.of(),
                        List.of(new ViewSchema.Card("graph", "卡片", "不是图谱", List.of())),
                        Map.of("graph", browsing())));
    }

    @Test
    void 邻居操作与字段均不能携带路径脚本或过长标识() {
        assertEquals("neighbors/query", browsing().neighborsQuery());
        assertThrows(IllegalArgumentException.class, () -> declaration("x".repeat(129), "window"));
        assertThrows(IllegalArgumentException.class, () -> declaration("https://example.com", "window"));
        assertThrows(IllegalArgumentException.class, () -> declaration("neighbors/query", "../path"));
        assertThrows(IllegalArgumentException.class, () -> declaration("neighbors/query", "x".repeat(65)));
        assertEquals(
                64,
                declaration("neighbors/query", "x".repeat(64)).windowParameter().length());
    }

    @Test
    void 查询窗口三个值字段不得重名覆盖彼此() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GraphBrowsing("neighbors/query", "window", "same", "same", "ids"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GraphBrowsing("neighbors/query", "window", "same", "inactive", "same"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GraphBrowsing("neighbors/query", "window", "query", "same", "same"));
    }

    private static ViewSchema view(Map<String, GraphBrowsing> declarations) {
        return new ViewSchema(2, "view", "图谱", List.of(), List.of(graph()), declarations);
    }

    private static ViewSchema.Graph graph() {
        return new ViewSchema.Graph("graph", "图谱", "nodes", "edges", "id", "label", "kind", "from", "to");
    }

    private static GraphBrowsing browsing() {
        return declaration("neighbors/query", "window");
    }

    private static GraphBrowsing declaration(String operation, String window) {
        return new GraphBrowsing(operation, window, "query", "inactive", "admitted");
    }
}
