package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingContracts;

/** 仅从已知 Core Schema 和 Coding 工具身份生成选择器；真正文件读取仍重放完整权限验证。 */
final class HistoryFileReferences {
    private HistoryFileReferences() {}

    static List<DocumentReference> project(
            Connection connection, ResultSet row, WorkspaceId workspace, ItemId id, TurnId turnId) throws SQLException {
        if (!"core".equals(row.getString("PRODUCER_ID"))) {
            return List.of();
        }
        String schema = row.getString("SCHEMA_ID");
        if (CoreSchemas.FILE_CHANGE.equals(schema)) {
            return List.of(DocumentReference.file(workspace, id, "file:0"));
        }
        // 固定 ToolResult 经 CanonicalJson 按字段名排序，顶层 success 必为末字段。
        // 只认可整个对象的成功尾部，避免 output 中的嵌套 success 或文本冒充权威执行结果。
        if (!CoreSchemas.TOOL_RESULT.equals(schema) || !row.getString("SUFFIX").endsWith(",\"success\":true}")) {
            return List.of();
        }
        String callId = HistoryMessagePrefix.text(row.getString("PREFIX"), "callId", 240)
                .value();
        String tool = tool(connection, turnId, callId);
        if ("file_read".equals(tool)) {
            return List.of(DocumentReference.file(workspace, id, "file:0"));
        }
        if (!"file_list".equals(tool) && !"file_search".equals(tool)) {
            return List.of();
        }
        return paths(row.getString("PREFIX"), workspace, id, tool.equals("file_list"));
    }

    private static String tool(Connection connection, TurnId turnId, String callId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT RIGHT(PAYLOAD,4096) FROM CORE.ITEM WHERE TURN_ID=? AND SCHEMA_ID=? AND PRODUCER_ID='core'
                """)) {
            statement.setString(1, turnId.toString());
            statement.setString(2, CoreSchemas.TOOL_CALL);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String payload = rows.getString(1);
                    if (callId.equals(HistoryMessagePrefix.text(payload, "callId", 240)
                                    .value())
                            && CodingContracts.EXTENSION_ID.equals(HistoryMessagePrefix.text(payload, "producerId", 240)
                                    .value())) {
                        return HistoryMessagePrefix.text(payload, "toolName", 240)
                                .value();
                    }
                }
            }
        }
        return "";
    }

    private static List<DocumentReference> paths(String prefix, WorkspaceId workspace, ItemId id, boolean directories) {
        List<DocumentReference> references = new ArrayList<>();
        int position = 0;
        int index = 0;
        while (references.size() < 32 && (position = prefix.indexOf("\"path\":", position)) >= 0) {
            int start = prefix.lastIndexOf('{', position);
            String entryPrefix = prefix.substring(Math.max(0, start), position);
            if (!directories || entryPrefix.contains("\"kind\":\"FILE\"")) {
                references.add(DocumentReference.file(workspace, id, "file:" + index));
            }
            index++;
            position += 7;
        }
        return List.copyOf(references);
    }
}
