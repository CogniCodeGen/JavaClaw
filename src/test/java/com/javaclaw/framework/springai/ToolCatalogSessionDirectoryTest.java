package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void 桌面会话自然语言查询列出授权组内的探测和目标工具() {
        List<ToolCallback> authorized = List.of(
                callback("web_tab_list", "web", "列出所有打开的网页", "{}"),
                callback("desktop_session_click", "desktop-session", "点击窗口控件", "{}"),
                callback("desktop_session_open", "desktop-session", "打开桌面会话目标", "{}"),
                callback("desktop_session_targets", "desktop-session", "列出桌面会话应用窗口", "{}"),
                callback("desktop_session_probe", "desktop-session", "探测桌面会话能力", "{}"),
                callback("view_image", "media", "在桌面弹窗中打开图片", "{}"));

        List<ToolCatalogPages.Entry> matches = ToolCatalogSession.listDirectory(
                authorized, "桌面会话 探测 打开 文档管理器 文件", "");
        assertEquals(List.of("desktop_session_open", "desktop_session_probe",
                        "desktop_session_targets", "desktop_session_click"),
                matches.stream().map(ToolCatalogPages.Entry::name).toList());
        assertEquals(4, ToolCatalogPages.list(matches, 1, 4_096).path("tools").size());
        assertFalse(matches.stream().anyMatch(entry -> entry.name().equals("desktop_session_type")));
        assertEquals(List.of("web_tab_list", "desktop_session_open", "view_image"),
                ToolCatalogSession.listDirectory(authorized, "打开", "").stream()
                        .map(ToolCatalogPages.Entry::name).toList());
        assertEquals(List.of("desktop_session_open", "desktop_session_click",
                        "desktop_session_targets", "desktop_session_probe"),
                ToolCatalogSession.listDirectory(authorized.subList(1, 5), "打开 文档管理器 文件", "")
                        .stream().map(ToolCatalogPages.Entry::name).toList());
    }

    @Test
    void 未授权分组返回仅限本轮授权的可用分组且结果有界() {
        List<ToolCallback> authorized = List.of(
                callback("desktop_session_open", "desktop-session", "打开桌面会话", "{}"),
                callback("web_snapshot", "web", "查看网页", "{}"));

        var failure = ToolCatalogSession.invalidGroupResult(authorized, "desktop", 1_000);

        assertFalse(failure.path("success").asBoolean());
        assertEquals("list", failure.path("action").asText());
        assertTrue(failure.path("error").asText().contains("desktop"));
        assertEquals(List.of("desktop-session", "web"),
                java.util.stream.StreamSupport.stream(
                        failure.path("availableGroups").spliterator(), false)
                        .map(node -> node.asText()).toList());
        assertTrue(failure.toString().length() <= 1_000);

        var noAuthorizedGroups = ToolCatalogSession.invalidGroupResult(List.of(), "desktop", 1_000);
        assertFalse(noAuthorizedGroups.has("availableGroups"));
    }

    @Test
    void 激活未知名称数量超限和Schema超限均整批拒绝并给出明确限制() {
        Map<String, ToolCallback> authorized = Map.of(
                "first", callback("first", "desktop-session", "a".repeat(2_400), "{}"),
                "second", callback("second", "desktop-session", "b".repeat(2_400), "{}"),
                "third", callback("third", "desktop-session", "c", "{}"));

        var unknown = ToolCatalogSession.activateSelection(
                names("first", "desktop_session_control"), authorized, 8, 4_000, 1_000);
        assertFalse(unknown.path("success").asBoolean());
        assertTrue(unknown.path("error").asText().contains("unknown or unauthorized"));
        assertTrue(unknown.path("hint").asText().contains("action=list"));
        var longUnknown = ToolCatalogSession.activateSelection(
                names("x\\\"".repeat(2_000)), authorized, 8, 4_000, 1_000);
        assertFalse(longUnknown.path("success").asBoolean());
        assertTrue(longUnknown.toString().length() <= 1_000);

        var count = ToolCatalogSession.activateSelection(
                names("first", "second", "third"), authorized, 2, 10_000, 1_000);
        assertFalse(count.path("success").asBoolean());
        assertEquals(3, count.path("requestedTools").asInt());
        assertEquals(2, count.path("maxTools").asInt());

        Map<String, ToolCallback> nineAuthorized = new java.util.LinkedHashMap<>();
        for (int index = 0; index < 9; index++) {
            String name = "desktop_tool_" + index;
            nineAuthorized.put(name, callback(name, "desktop-session", "short", "{}"));
        }
        var eightToolLimit = ToolCatalogSession.activateSelection(
                names(nineAuthorized.keySet().toArray(String[]::new)),
                nineAuthorized, 8, 4_000, 1_000);
        assertFalse(eightToolLimit.path("success").asBoolean());
        assertEquals(9, eightToolLimit.path("requestedTools").asInt());
        assertEquals(8, eightToolLimit.path("maxTools").asInt());

        var schemas = ToolCatalogSession.activateSelection(
                names("first", "second"), authorized, 8, 4_000, 1_000);
        assertFalse(schemas.path("success").asBoolean());
        assertTrue(schemas.path("requestedSchemaCharacters").asInt() > 4_000);
        assertEquals(4_000, schemas.path("maxSchemaCharacters").asInt());

        var valid = ToolCatalogSession.activateSelection(
                names("first", "third"), authorized, 8, 4_000, 1_000);
        assertTrue(valid.path("success").asBoolean());
        assertEquals(List.of("first", "third"),
                java.util.stream.StreamSupport.stream(valid.path("activated").spliterator(), false)
                        .map(node -> node.asText()).toList());
        for (var result : List.of(unknown, longUnknown, count, eightToolLimit, schemas, valid)) {
            assertTrue(result.toString().length() <= 1_000);
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode names(String... names) {
        var result = JsonNodeFactory.instance.objectNode();
        var values = result.putArray("names");
        for (String name : names) values.add(name);
        return result;
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
