package com.javaclaw.server.persistence;

import java.util.Map;

import com.javaclaw.api.CoreSchemas;
import com.javaclaw.builtin.contracts.CodingResults;

/** 已知执行事实的纯文本摘要，保留 4096 UTF-16 和分页预算，不读取完整 CLOB 或签发消息正文引用。 */
final class HistoryStructuredSummary {
    private HistoryStructuredSummary() {}

    static HistoryMessagePrefix.Text project(String schema, String kind, String prefix, String suffix, long length) {
        Map<String, String> fields = HistoryPayloadPrefix.fields(prefix);
        if (CoreSchemas.TOOL_CALL.equals(schema) && length > prefix.length()) {
            // CanonicalJson 固定将 arguments 排在 callId 前。只解析能闭合根对象的完整元数据尾部，
            // 不把 arguments 内同名字段或转义文本解释成工具身份。
            int start = suffix.lastIndexOf(",\"callId\":");
            if (start >= 0) {
                fields = HistoryPayloadPrefix.fields("{" + suffix.substring(start + 1));
            }
        }
        String body = describe(schema, kind, fields, suffix);
        boolean truncated = length > prefix.length() || body.length() > 4096;
        int end = Math.min(body.length(), 4096);
        if (end > 0 && Character.isHighSurrogate(body.charAt(end - 1))) {
            end--;
        }
        return new HistoryMessagePrefix.Text(body.substring(0, end), truncated);
    }

    private static String describe(String schema, String kind, Map<String, String> fields, String suffix) {
        return switch (schema) {
            case CoreSchemas.TOOL_CALL ->
                "工具 · " + value(fields, "toolName") + "\n来源 " + value(fields, "producerId") + " · revision "
                        + value(fields, "toolRevision");
            case CoreSchemas.TOOL_RESULT -> toolResult(fields, suffix);
            case CoreSchemas.APPROVAL ->
                "审批 · " + value(fields, "toolName") + "\n" + value(fields, "state") + " · " + value(fields, "reason");
            case CoreSchemas.ERROR -> value(fields, "code") + "\n" + value(fields, "message");
            case CoreSchemas.COMMAND ->
                "命令 · " + value(fields, "commandId") + "\n"
                        + value(fields, "argv") + "\n目录：" + value(fields, "workingDirectory")
                        + "\n退出码：" + fields.getOrDefault("exitCode", "运行中");
            case CoreSchemas.FILE_CHANGE -> "文件 · " + value(fields, "operation") + "\n" + value(fields, "relativePath");
            case CoreSchemas.INPUT -> "输入请求\n" + value(fields, "prompt");
            case CoreSchemas.SUBAGENT -> "子会话 · " + value(fields, "state") + "\n" + value(fields, "childThreadId");
            case CoreSchemas.COMPACTION -> "上下文压缩 · " + value(fields, "strategy") + "\n" + value(fields, "summary");
            default -> coding(schema, kind, fields);
        };
    }

    private static String coding(String schema, String kind, Map<String, String> fields) {
        return switch (schema) {
            case CodingResults.FAILURE_SCHEMA ->
                "执行失败 · " + value(fields, "errorCode") + "\n" + value(fields, "message") + "\n操作："
                        + value(fields, "operationId");
            case CodingResults.COMMAND_SCHEMA, CodingResults.PREPARATION_SCHEMA ->
                "命令执行结果\n" + value(fields, "command") + "\n" + value(fields, "output");
            case CodingResults.TERMINAL_SCHEMA ->
                "终端 · " + value(fields, "state") + "\n" + value(fields, "output") + "\n退出码："
                        + fields.getOrDefault("exitCode", "运行中");
            case CodingResults.PATCH_SCHEMA ->
                "文件修改 · " + value(fields, "complete") + "\n" + value(fields, "changes") + "\n"
                        + fields.getOrDefault("failureCode", "");
            default -> "执行记录 · " + kind + "\n类型：" + schema + "\n当前类型仅提供受限摘要";
        };
    }

    private static String toolResult(Map<String, String> fields, String suffix) {
        String state = suffix.endsWith(",\"success\":true}") ? "成功" : "失败或未完成";
        return "工具结果 · " + state + "\n调用：" + value(fields, "callId") + "\n" + value(fields, "output");
    }

    private static String value(Map<String, String> fields, String key) {
        return fields.getOrDefault(key, "[摘要范围内无此字段]").replaceAll("[\\p{Cc}&&[^\\n\\t]]", "�");
    }
}
