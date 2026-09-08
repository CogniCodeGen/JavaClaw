package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemHistoryEntry;
import com.javaclaw.api.ItemHistoryResult;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.TurnStreamRpcContracts;

/** 使用数据库 CLOB 前缀生成有界摘要；全文只能通过来源引用读取，绝不截断权威 ItemEnvelope。 */
final class ItemHistoryRepository {
    private final CanonicalJson json = new CanonicalJson();

    ItemHistoryResult history(Connection connection, TurnStreamRpcContracts.ItemHistoryRequest request)
            throws SQLException {
        long latest = latest(connection, request);
        List<ItemHistoryEntry> entries = new ArrayList<>();
        boolean earlier = false;
        int bytes = 0;
        try (var statement = connection.prepareStatement("""
                SELECT I.ID,I.TURN_ID,I.SEQUENCE,I.KIND,I.SCHEMA_ID,I.PRODUCER_ID,I.CREATED_AT,T.WORKSPACE_ID,
                       SUBSTRING(I.PAYLOAD,1,65536) AS PREFIX, RIGHT(I.PAYLOAD,4096) AS SUFFIX,
                       CHAR_LENGTH(I.PAYLOAD) AS PAYLOAD_LENGTH
                FROM CORE.ITEM I JOIN CORE.AGENT_THREAD T ON I.THREAD_ID=T.ID
                WHERE I.THREAD_ID=? AND I.SEQUENCE<? ORDER BY I.SEQUENCE DESC LIMIT ?
                """)) {
            statement.setString(1, request.threadId().toString());
            statement.setLong(2, request.beforeSequence() == 0 ? Long.MAX_VALUE : request.beforeSequence());
            statement.setInt(3, request.limit() + 1);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    ItemHistoryEntry entry = project(connection, rows);
                    bytes += json.encode(entry).json().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    if (entries.size() == request.limit() || bytes > 500 * 1024) {
                        earlier = true;
                        break;
                    }
                    entries.add(entry);
                }
            }
        }
        return new ItemHistoryResult(entries.reversed(), latest, earlier);
    }

    private long latest(Connection connection, TurnStreamRpcContracts.ItemHistoryRequest request) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT NEXT_SEQUENCE-1 FROM CORE.AGENT_THREAD WHERE ID=?")) {
            statement.setString(1, request.threadId().toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw PersistenceException.invalidRequest("Thread 不存在");
                }
                return rows.getLong(1);
            }
        }
    }

    private ItemHistoryEntry project(Connection connection, ResultSet row) throws SQLException {
        ItemId id = ItemId.parse(row.getString("ID"));
        TurnId turnId = TurnId.parse(row.getString("TURN_ID"));
        WorkspaceId workspace = WorkspaceId.parse(row.getString("WORKSPACE_ID"));
        String prefix = row.getString("PREFIX");
        String kind = row.getString("KIND");
        boolean message = CoreSchemas.MESSAGE.equals(row.getString("SCHEMA_ID"));
        var text = message
                ? HistoryMessagePrefix.text(prefix, "text", 4096)
                : HistoryStructuredSummary.project(
                        row.getString("SCHEMA_ID"),
                        kind,
                        prefix,
                        row.getString("SUFFIX"),
                        row.getLong("PAYLOAD_LENGTH"));
        var references = HistoryFileReferences.project(connection, row, workspace, id, turnId);
        Optional<MessageRole> role = message ? HistoryMessagePrefix.role(prefix) : Optional.empty();
        boolean publicMessage = role.filter(value -> value == MessageRole.USER || value == MessageRole.ASSISTANT)
                .isPresent();
        return new ItemHistoryEntry(
                id,
                turnId,
                row.getLong("SEQUENCE"),
                kind,
                role,
                text.value(),
                publicMessage ? Optional.of(DocumentReference.message(workspace, id, "body")) : Optional.empty(),
                text.truncated(),
                row.getObject("CREATED_AT", OffsetDateTime.class).toInstant(),
                message ? HistoryMessagePrefix.attachments(prefix, json) : List.of(),
                references);
    }
}
