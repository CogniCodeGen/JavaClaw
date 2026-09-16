package com.javaclaw.server.persistence;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.runtime.ModelImage;

/** 历史附件只接纳当前 Thread 内成功 Core 结果与先行唯一 Site 调用的组合证据。 */
final class HistoryBrowserAttachments {
    private HistoryBrowserAttachments() {}

    static List<AttachmentRef> project(Connection connection, ResultSet row, CanonicalJson json) throws SQLException {
        if (!CoreSchemas.TOOL_RESULT.equals(row.getString("SCHEMA_ID"))
                || !"core".equals(row.getString("PRODUCER_ID"))
                || !ItemStatus.COMPLETED.name().equals(row.getString("STATUS"))
                || !row.getString("SUFFIX").endsWith(",\"success\":true}")) {
            return List.of();
        }
        String callId = HistoryPayloadPrefix.fields(row.getString("PREFIX")).get("callId");
        if (callId == null || !siteCall(connection, row, callId, json)) {
            return List.of();
        }
        // Worker 结果有界。超出 8 MiB 的异常 Item 不扫描全文，也不从不完整片段猜测所有权。
        if (row.getLong("PAYLOAD_LENGTH") > 8L * 1024 * 1024) {
            return List.of();
        }
        try (var statement = connection.prepareStatement("SELECT PAYLOAD FROM CORE.ITEM WHERE ID=?")) {
            statement.setString(1, row.getString("ID"));
            try (var source = statement.executeQuery()) {
                if (!source.next()) {
                    return List.of();
                }
                try (var reader = source.getCharacterStream(1)) {
                    Map<String, String> fields = HistoryBrowserFields.read(reader);
                    return attachment(fields, row, callId);
                }
            }
        } catch (IOException | IllegalArgumentException invalid) {
            return List.of();
        }
    }

    private static boolean siteCall(Connection connection, ResultSet result, String callId, CanonicalJson json)
            throws SQLException {
        int matching = 0;
        boolean site = false;
        try (var statement = connection.prepareStatement("""
                SELECT RIGHT(PAYLOAD,4096) FROM CORE.ITEM
                WHERE TURN_ID=? AND SCHEMA_ID=? AND PRODUCER_ID='core' AND SEQUENCE<?
                """)) {
            statement.setString(1, result.getString("TURN_ID"));
            statement.setString(2, CoreSchemas.TOOL_CALL);
            statement.setLong(3, result.getLong("SEQUENCE"));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String suffix = rows.getString(1);
                    int start = suffix.lastIndexOf(",\"callId\":");
                    if (start >= 0) {
                        Call call = json.decode(json.parse("{" + suffix.substring(start + 1)), Call.class);
                        if (call.callId().equals(callId)) {
                            matching++;
                            site = BuiltinExtensionIds.SITE.equals(call.producerId())
                                    && BrowserCommands.TOOL_NAMES.contains(call.toolName())
                                    && call.toolRevision() > 0;
                        }
                    }
                }
            }
        }
        return matching == 1 && site;
    }

    private static List<AttachmentRef> attachment(Map<String, String> fields, ResultSet row, String callId)
            throws SQLException {
        if (!callId.equals(fields.get("callId"))
                || !"true".equals(fields.get("success"))
                || !"1".equals(fields.get("output.version"))
                || !row.getString("WORKSPACE_ID").equals(fields.get("output.observation.session.owner.workspaceId"))
                || !row.getString("THREAD_ID").equals(fields.get("output.observation.session.owner.threadId"))) {
            return List.of();
        }
        AttachmentRef attachment = new AttachmentRef(
                required(fields, "output.attachment.digest"),
                required(fields, "output.attachment.mediaType"),
                required(fields, "output.attachment.fileName"),
                Long.parseLong(required(fields, "output.attachment.sizeBytes")));
        BrowserContracts.FileSpec artifact = new BrowserContracts.FileSpec(
                required(fields, "output.observation.artifact.file.fileName"),
                required(fields, "output.observation.artifact.file.mediaType"));
        if (!artifact.fileName().equals(attachment.fileName())
                || !artifact.mediaType().equals(attachment.mediaType())
                || attachment.sizeBytes() != Long.parseLong(required(fields, "output.observation.artifact.sizeBytes"))
                || attachment.sizeBytes() > BrowserContracts.MAXIMUM_ARTIFACT_BYTES) {
            return List.of();
        }
        if (fields.containsKey("output.observation.frame.frameId")) {
            new ModelImage(
                    attachment,
                    WorkspaceId.parse(row.getString("WORKSPACE_ID")),
                    ThreadId.parse(row.getString("THREAD_ID")),
                    fields.get("output.observation.frame.frameId"),
                    Integer.parseInt(required(fields, "output.observation.frame.imageWidth")),
                    Integer.parseInt(required(fields, "output.observation.frame.imageHeight")));
        }
        return List.of(attachment);
    }

    private static String required(Map<String, String> fields, String path) {
        String value = fields.get(path);
        if (value == null) {
            throw new IllegalArgumentException("浏览器附件证据字段缺失");
        }
        return value;
    }

    private record Call(String callId, String producerId, String toolName, long toolRevision) {}
}
