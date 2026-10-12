package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCatalogPagesTest {
    @Test
    void 长记录在字符预算内分页且不遗漏工具() {
        List<ToolCatalogPages.Entry> entries = IntStream.range(0, 9)
                .mapToObj(index -> new ToolCatalogPages.Entry(
                        "tool_" + index + "n".repeat(58), "g".repeat(50), "d".repeat(120)))
                .toList();
        List<String> names = new ArrayList<>();
        int page = 1;
        boolean hasNext;
        do {
            ObjectNode result = ToolCatalogPages.list(entries, page, 1_000);
            assertTrue(result.toString().length() <= 1_000);
            assertEquals(page, result.path("page").asInt());
            assertEquals(entries.size(), result.path("total").asInt());
            assertEquals(result.path("tools").size(), result.path("pageSize").asInt());
            assertTrue(result.path("pageSize").asInt() > 0);
            for (JsonNode tool : result.path("tools")) {
                names.add(tool.path("name").asText());
                assertEquals(80, tool.path("description").asText().length());
            }
            hasNext = result.path("hasNext").asBoolean();
            page++;
        } while (hasNext && page <= entries.size());

        assertFalse(hasNext);
        assertTrue(page > 2);
        assertEquals(entries.stream().map(ToolCatalogPages.Entry::name).toList(), names);
        ObjectNode beyondLast = ToolCatalogPages.list(entries, page, 1_000);
        assertEquals(0, beyondLast.path("pageSize").asInt());
        assertFalse(beyondLast.path("hasNext").asBoolean());
        assertEquals(entries.size(), beyondLast.path("total").asInt());
    }

    @Test
    void 单条记录无法容纳时返回有界且可识别的错误() {
        ObjectNode result = ToolCatalogPages.list(List.of(new ToolCatalogPages.Entry(
                "oversized_tool_" + "x".repeat(1_500), "workspace", "description")), 1, 1_000);

        assertFalse(result.path("success").asBoolean());
        assertTrue(result.path("error").asText().contains("oversized_tool_"));
        assertTrue(result.path("error").asText().contains("budget"));
        assertTrue(result.toString().length() <= 1_000);
    }
}
