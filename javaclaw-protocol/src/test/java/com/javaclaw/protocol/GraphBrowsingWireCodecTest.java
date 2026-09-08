package com.javaclaw.protocol;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.javaclaw.extension.spi.GraphBrowsing;
import com.javaclaw.extension.spi.ViewSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphBrowsingWireCodecTest {
    private final CanonicalJson json = new CanonicalJson();
    private final ViewSchemaWireCodec codec = new ViewSchemaWireCodec(json);

    @Test
    void 原Graph九参和原页面五参构造保持精确旧wire形状() {
        ViewSchema schema = legacy();
        var wire = codec.encode(schema);
        assertFalse(wire.json().contains("graphBrowsing"));
        assertTrue(schema.graphBrowsing().isEmpty());
        assertEquals(schema, codec.decode(wire));
        assertEquals(
                5,
                json.mapper().valueToTree(json.decode(wire, LegacyShape.class)).size());
    }

    @Test
    void 非空浏览声明roundtrip且不注入Graph节点字段() {
        ViewSchema old = legacy();
        ViewSchema schema = new ViewSchema(
                old.schemaVersion(),
                old.viewId(),
                old.title(),
                old.dataSources(),
                old.nodes(),
                Map.of("graph", browsing()));
        var wire = codec.encode(schema);
        assertEquals(schema, codec.decode(wire));
        assertEquals(browsing(), codec.decode(wire).graphBrowsing().get("graph"));
        assertThrows(ProtocolException.class, () -> json.decode(wire, LegacyShape.class));
        assertThrows(
                ProtocolException.class,
                () -> codec.decode(json.parse(wire.json()
                        .replace(
                                "\"neighborsQuery\":\"neighbors/query\"",
                                "\"neighborsQuery\":\"neighbors/query\",\"script\":\"x\""))));
        assertTrue(StableCapabilities.all().contains(ViewSchemaWireCodec.GRAPH_BROWSING_CAPABILITY));
    }

    @Test
    void 拒绝非Graph关联与任意脚本或路径字段() {
        ViewSchema old = legacy();
        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewSchema(
                        2, old.viewId(), old.title(), old.dataSources(), old.nodes(), Map.of("missing", browsing())));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GraphBrowsing("https://example.com", "window", "query", "includeInactive", "admittedIds"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GraphBrowsing("neighbors/query", "../window", "query", "includeInactive", "admittedIds"));
    }

    private static GraphBrowsing browsing() {
        return new GraphBrowsing("neighbors/query", "window", "query", "includeInactive", "admittedIds");
    }

    private static ViewSchema legacy() {
        return new ViewSchema(
                2,
                "view",
                "图谱",
                List.of(),
                List.of(new ViewSchema.Graph("graph", "节点", "nodes", "edges", "id", "label", "kind", "from", "to")));
    }

    private record LegacyShape(
            int schemaVersion, String viewId, String title, List<Object> dataSources, List<Object> nodes) {}
}
