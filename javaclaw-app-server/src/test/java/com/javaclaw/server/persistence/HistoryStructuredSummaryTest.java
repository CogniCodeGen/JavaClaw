package com.javaclaw.server.persistence;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CoreSchemas;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryStructuredSummaryTest {
    private final CanonicalJson json = new CanonicalJson();

    @Test
    void 根字段不被嵌套工具参数或执行输出冒充() {
        String payload = json.encode(Map.of(
                        "arguments",
                        Map.of("toolName", "fake", "producerId", "fake"),
                        "callId",
                        "call",
                        "producerId",
                        "coding",
                        "toolName",
                        "file_read",
                        "toolRevision",
                        1))
                .json();
        var summary = project(CoreSchemas.TOOL_CALL, payload);
        assertTrue(summary.value().contains("工具 · file_read"));
        assertFalse(summary.value().contains("fake"));
        String failure = json.encode(Map.of("callId", "call", "output", Map.of("success", true), "success", false))
                .json();
        assertTrue(project(CoreSchemas.TOOL_RESULT, failure).value().contains("失败或未完成"));
    }

    @Test
    void 大参数和大输出仍保留权威工具身份与成功状态且不会拆分Unicode() {
        String payload = json.encode(Map.of(
                        "arguments",
                        Map.of("text", "😀".repeat(50_000)),
                        "callId",
                        "call",
                        "producerId",
                        "coding",
                        "toolName",
                        "file_read",
                        "toolRevision",
                        1))
                .json();
        assertTrue(project(CoreSchemas.TOOL_CALL, payload).value().contains("file_read"));
        String result = json.encode(
                        Map.of("callId", "call", "output", Map.of("text", "😀".repeat(50_000)), "success", true))
                .json();
        var summary = project(CoreSchemas.TOOL_RESULT, result);
        assertTrue(summary.value().contains("工具结果 · 成功"));
        assertTrue(summary.truncated());
        assertTrue(summary.value().length() <= 4096);
        assertFalse(
                Character.isHighSurrogate(summary.value().charAt(summary.value().length() - 1)));
    }

    @Test
    void 已知Coding及Core记录保留原因而未知Schema明确受限() {
        assertTrue(project(
                        CodingResults.FAILURE_SCHEMA,
                        "{\"errorCode\":\"DENIED\",\"message\":\"拒绝\",\"operationId\":\"read\"}")
                .value()
                .contains("DENIED\n拒绝"));
        assertTrue(project(
                        CoreSchemas.COMMAND,
                        "{\"argv\":[\"echo\",\"ok\"],\"commandId\":\"x\",\"exitCode\":2,\"workingDirectory\":\"/tmp\"}")
                .value()
                .contains("退出码：2"));
        for (String schema : new String[] {
            CoreSchemas.FILE_CHANGE,
            CoreSchemas.INPUT,
            CoreSchemas.SUBAGENT,
            CoreSchemas.COMPACTION,
            CodingResults.COMMAND_SCHEMA,
            CodingResults.PREPARATION_SCHEMA,
            CodingResults.TERMINAL_SCHEMA,
            CodingResults.PATCH_SCHEMA
        }) {
            assertFalse(project(schema, "{}").value().isBlank());
        }
        assertTrue(project("unknown@2", "{\"token\":\"opaque\"}").value().contains("仅提供受限摘要"));
    }

    @Test
    void 根字段扫描处理标量数组不完整字符串和无效前缀() {
        assertEquals("true", HistoryPayloadPrefix.fields("{\"complete\":true}").get("complete"));
        assertEquals("[1,2]", HistoryPayloadPrefix.fields("{\"argv\":[1,2]}").get("argv"));
        assertEquals(
                "partial", HistoryPayloadPrefix.fields("{\"message\":\"partial").get("message"));
        assertTrue(HistoryPayloadPrefix.fields("[]").isEmpty());
        assertTrue(HistoryPayloadPrefix.fields("{\"message\":null}").isEmpty());
    }

    private HistoryMessagePrefix.Text project(String schema, String payload) {
        return HistoryStructuredSummary.project(
                schema,
                "execution",
                payload.substring(0, Math.min(65536, payload.length())),
                payload.substring(Math.max(0, payload.length() - 4096)),
                payload.length());
    }
}
