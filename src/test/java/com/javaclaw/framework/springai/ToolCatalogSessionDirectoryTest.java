package com.javaclaw.framework.springai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ToolCatalogSessionDirectoryTest {

    @Test
    void 授权目录只返回传入的业务工具并稳定列出组() {
        List<ToolCallback> authorized = List.of(
                callback("web_navigate", "web", "导航到网址", "{}"),
                callback("knowledge_search", "knowledge", "搜索知识库", "{}"),
                callback("web_snapshot", "web", "读取网页结构", "{}"));

        assertEquals(List.of(
                new ToolCatalogSession.ToolGroupSummary("knowledge", 1),
                new ToolCatalogSession.ToolGroupSummary("web", 2)),
                ToolCatalogSession.groupDirectory(authorized));
        assertEquals(List.of("knowledge_search"), ToolCatalogSession.searchDirectory(
                authorized, "search", List.of(), 8).stream()
                .map(ToolCatalogSession.ToolCandidate::name).toList());
        assertFalse(ToolCatalogSession.searchDirectory(
                authorized, "web_search", List.of(), 8).stream()
                .anyMatch(candidate -> candidate.name().equals("web_search")));
    }

    @Test
    void 名称组和描述均可检索且结果上限生效() {
        List<ToolCallback> authorized = List.of(
                callback("web_snapshot", "web", "读取网页结构", "{}"),
                callback("knowledge_search", "knowledge", "搜索知识库", "{}"),
                callback("web_navigate", "web", "导航到网址", "{}"));

        assertEquals(List.of("web_navigate"), ToolCatalogSession.searchDirectory(
                authorized, "navigate", List.of(), 8).stream()
                .map(ToolCatalogSession.ToolCandidate::name).toList());
        assertEquals(List.of("web_snapshot"), ToolCatalogSession.searchDirectory(
                authorized, "网页结构", List.of("web"), 8).stream()
                .map(ToolCatalogSession.ToolCandidate::name).toList());
        assertEquals(1, ToolCatalogSession.searchDirectory(
                authorized, "web", List.of("web"), 1).size());
        assertEquals(List.of("knowledge_search"), ToolCatalogSession.searchDirectory(
                authorized, "web", List.of("knowledge"), 8).stream()
                .map(ToolCatalogSession.ToolCandidate::name).toList());
        assertEquals(List.of(), ToolCatalogSession.searchDirectory(
                authorized, "", List.of(), 8));
    }

    @Test
    void 组内无文本命中时按授权目录顺序回填() {
        List<ToolCallback> original = List.of(
                callback("web_snapshot", "web", "读取网页结构", "{}"),
                callback("web_navigate", "web", "导航到网址", "{}"),
                callback("knowledge_search", "knowledge", "搜索知识库", "{}"));
        List<ToolCallback> reversed = original.reversed();

        assertEquals(List.of("web_snapshot", "web_navigate"), ToolCatalogSession.searchDirectory(original,
                "中秋节旅游景点推荐 中秋出游攻略", List.of("web"), 8).stream()
                .map(ToolCatalogSession.ToolCandidate::name).toList());
        assertEquals(List.of("web_navigate", "web_snapshot"), ToolCatalogSession.searchDirectory(reversed,
                "中秋节旅游景点推荐 中秋出游攻略", List.of("web"), 8).stream()
                .map(ToolCatalogSession.ToolCandidate::name).toList());
    }

    @Test
    void 某组文本命中不遮蔽另一个已选组的回填() {
        List<ToolCallback> authorized = List.of(
                callback("web_navigate", "web", "Open a URL", "{}"),
                callback("web_snapshot", "web", "Read a page", "{}"),
                callback("code_execute", "code", "Run a script", "{}"));

        assertEquals(List.of("web_navigate", "code_execute"),
                ToolCatalogSession.searchDirectory(authorized, "navigate",
                        List.of("web", "code"), 2).stream()
                        .map(ToolCatalogSession.ToolCandidate::name).toList());
    }

    @Test
    void 显式选择的每个组在候选上限内至少出现一次() {
        List<ToolCallback> authorized = List.of(
                callback("code_first", "code", "navigate scripts", "{}"),
                callback("code_second", "code", "navigate files", "{}"),
                callback("web_first", "web", "navigate pages", "{}"));

        assertEquals(List.of("code_first", "web_first"),
                ToolCatalogSession.searchDirectory(authorized, "", List.of("code", "web"), 2)
                        .stream().map(ToolCatalogSession.ToolCandidate::name).toList());
        assertEquals(List.of("code_first", "web_first"),
                ToolCatalogSession.searchDirectory(authorized, "navigate",
                                List.of("code", "web"), 2)
                        .stream().map(ToolCatalogSession.ToolCandidate::name).toList());
    }

    @Test
    void 候选指纹覆盖名称组描述和Schema并可复核() {
        ToolCallback initial = callback("web_navigate", "web", "导航到网址", "{}");
        ToolCatalogSession.ToolCandidate candidate = ToolCatalogSession.searchDirectory(
                List.of(initial), "web", List.of(), 8).getFirst();

        assertEquals(64, candidate.fingerprint().length());
        assertNotEquals(candidate.fingerprint(), ToolCatalogSession.searchDirectory(
                List.of(callback("web_navigate", "web", "导航到另一个网址", "{}")),
                "web", List.of(), 8).getFirst().fingerprint());
        assertNotEquals(candidate.fingerprint(), ToolCatalogSession.searchDirectory(
                List.of(callback("web_navigate", "other", "导航到网址", "{}")),
                "web", List.of(), 8).getFirst().fingerprint());
        ToolCatalogSession.ToolCandidate changedSchema = ToolCatalogSession.searchDirectory(
                List.of(callback("web_navigate", "web", "导航到网址",
                        "{\"type\":\"object\"}")), "web", List.of(), 8).getFirst();
        assertNotEquals(candidate.fingerprint(), changedSchema.fingerprint());
    }

    private static ToolCallback callback(String name, String group,
            String description, String schema) {
        return new GroupedCallback(ToolDefinition.builder()
                .name(name).description(description).inputSchema(schema).build(), group);
    }

    private record GroupedCallback(ToolDefinition definition, String group)
            implements ToolCallback, SpringAiToolCatalog.GroupedCallback {
        @Override public ToolDefinition getToolDefinition() { return definition; }
        @Override public String call(String input) { return "{}"; }
    }
}
