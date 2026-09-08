package com.javaclaw.server.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.ConversationEvidencePort;
import com.javaclaw.protocol.CanonicalJson;

/** 仅公开完成对话文本的有界证据适配器；学习游标和时间窗口由调用扩展拥有。 */
public final class ServerConversationEvidencePort implements ConversationEvidencePort {
    private final H2Transactions transactions;
    private final CanonicalJson json;

    /**
     * 创建证据端口，并幂等回填迁移前已经成功完成的 Turn。
     *
     * @param database 已初始化到 V006 的数据库
     * @param json Core payload codec
     */
    public ServerConversationEvidencePort(H2Database database, CanonicalJson json) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.json = Objects.requireNonNull(json, "json");
        backfill();
    }

    @Override
    public long committedUpperBound(WorkspaceId workspaceId) {
        return execute(connection -> {
            try (var query = connection.prepareStatement(
                    "SELECT COMMITTED_SEQUENCE FROM CORE.CONVERSATION_COMPLETION_HEAD WHERE WORKSPACE_ID = ?")) {
                query.setString(1, workspaceId.toString());
                try (var rows = query.executeQuery()) {
                    return rows.next() ? rows.getLong(1) : 0L;
                }
            }
        });
    }

    @Override
    public Page scan(WorkspaceId workspaceId, Cursor after, long upperSequence, Instant completedAfter, int limit) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(completedAfter, "completedAfter");
        if (limit < 1 || limit > 200 || upperSequence < after.completionSequence()) {
            throw new IllegalArgumentException("evidence page bounds are invalid");
        }
        return execute(connection -> {
            List<Evidence> evidence = new ArrayList<>();
            Cursor next = after;
            boolean more = false;
            try (var query = connection.prepareStatement("""
                    SELECT C.COMPLETION_SEQUENCE, C.THREAD_ID, C.TURN_ID, I.ID, I.SEQUENCE, I.PAYLOAD
                    FROM CORE.CONVERSATION_COMPLETION C JOIN CORE.ITEM I ON I.TURN_ID = C.TURN_ID
                    WHERE C.WORKSPACE_ID = ? AND C.COMPLETION_SEQUENCE <= ? AND C.COMPLETED_AT >= ?
                    AND (C.COMPLETION_SEQUENCE > ? OR (C.COMPLETION_SEQUENCE = ? AND I.SEQUENCE > ?))
                    AND I.SEQUENCE <= C.LAST_ITEM_SEQUENCE AND I.SCHEMA_ID = ? AND I.STATUS = 'COMPLETED'
                    AND NOT EXISTS (SELECT 1 FROM CORE.CONVERSATION_EVIDENCE_EXCLUSION X WHERE X.TURN_ID = C.TURN_ID)
                    ORDER BY C.COMPLETION_SEQUENCE, I.SEQUENCE LIMIT ?
                    """)) {
                query.setString(1, workspaceId.toString());
                query.setLong(2, upperSequence);
                query.setObject(3, completedAfter.atOffset(ZoneOffset.UTC));
                query.setLong(4, after.completionSequence());
                query.setLong(5, after.completionSequence());
                query.setLong(6, after.itemSequence());
                query.setString(7, CoreSchemas.MESSAGE);
                query.setInt(8, limit + 1);
                try (var rows = query.executeQuery()) {
                    int consumed = 0;
                    while (rows.next()) {
                        if (consumed++ == limit) {
                            more = true;
                            break;
                        }
                        next = new Cursor(rows.getLong(1), rows.getLong(5));
                        CorePayloads.Message message =
                                json.decode(new CanonicalPayload(rows.getString(6)), CorePayloads.Message.class);
                        if ((message.role() != MessageRole.USER && message.role() != MessageRole.ASSISTANT)
                                || message.text().isBlank()) {
                            continue;
                        }
                        boolean oversized = message.text().length() > 48000;
                        evidence.add(new Evidence(
                                next,
                                ThreadId.parse(rows.getString(2)),
                                TurnId.parse(rows.getString(3)),
                                ItemId.parse(rows.getString(4)),
                                message.role() == MessageRole.USER ? SourceKind.USER_TEXT : SourceKind.ASSISTANT_TEXT,
                                oversized ? "" : message.text(),
                                digest(message.text()),
                                oversized));
                    }
                }
            }
            return new Page(evidence, next, more);
        });
    }

    private void backfill() {
        while (true) {
            int filled = execute(connection -> {
                List<Completion> pending = new ArrayList<>();
                try (var query = connection.prepareStatement("""
                        SELECT R.ID, R.UPDATED_AT FROM CORE.AGENT_TURN R
                        WHERE R.STATUS = 'COMPLETED' AND NOT EXISTS
                        (SELECT 1 FROM CORE.CONVERSATION_COMPLETION C WHERE C.TURN_ID = R.ID)
                        ORDER BY R.UPDATED_AT, R.ID LIMIT 200
                        """);
                        var rows = query.executeQuery()) {
                    while (rows.next()) {
                        pending.add(new Completion(
                                TurnId.parse(rows.getString(1)),
                                rows.getObject(2, OffsetDateTime.class).toInstant()));
                    }
                }
                for (Completion completion : pending) {
                    ConversationCompletionIndex.record(connection, completion.turnId(), completion.completedAt());
                }
                return pending.size();
            });
            if (filled < 200) {
                return;
            }
        }
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (Exception failure) {
            throw new PersistenceException("无法读取已完成对话证据", failure);
        }
    }

    private static String digest(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record Completion(TurnId turnId, Instant completedAt) {}
}
