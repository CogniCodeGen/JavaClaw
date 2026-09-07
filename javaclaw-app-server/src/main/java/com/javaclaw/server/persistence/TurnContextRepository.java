package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.runtime.ContextTokenEstimator;
import com.javaclaw.runtime.ConversationWindow;
import com.javaclaw.runtime.ModelContextPolicy;

/** 容量政策与压缩窗口的完整性边界；缺少旧政策时只绑定精确 Provider 版本。 */
final class TurnContextRepository {
    private final CanonicalJson json;

    TurnContextRepository(CanonicalJson json) {
        this.json = json;
    }

    ModelContextPolicy freeze(Connection connection, TurnId turnId, ProviderRef provider) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT POLICY_JSON, POLICY_DIGEST FROM CORE.TURN_CONTEXT_STATE WHERE TURN_ID = ?")) {
            query.setString(1, turnId.toString());
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    return json.decode(checked(rows.getString(1), rows.getString(2)), ModelContextPolicy.class);
                }
            }
        }
        ModelContextPolicy policy = declared(connection, provider);
        CanonicalPayload payload = json.encode(policy);
        try (var statement = connection.prepareStatement(
                "INSERT INTO CORE.TURN_CONTEXT_STATE (TURN_ID, POLICY_JSON, POLICY_DIGEST) VALUES (?, ?, ?)")) {
            statement.setString(1, turnId.toString());
            statement.setString(2, payload.json());
            statement.setString(3, payload.sha256());
            statement.executeUpdate();
        }
        return policy;
    }

    private ModelContextPolicy declared(Connection connection, ProviderRef provider) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT CONTEXT_WINDOW_TOKENS, MAXIMUM_OUTPUT_TOKENS FROM CORE.PROVIDER_MODEL_CONTEXT
                WHERE PROVIDER_ID = ? AND PROVIDER_REVISION = ? AND MODEL = ?
                """)) {
            query.setString(1, provider.endpointId());
            query.setLong(2, provider.endpointRevision());
            query.setString(3, provider.model());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    return ModelContextPolicy.fallback();
                }
                Long window = rows.getObject(1, Long.class);
                Long output = rows.getObject(2, Long.class);
                long capacity = window == null ? 32_768 : window;
                return new ModelContextPolicy(
                        capacity,
                        Math.min(capacity, output == null ? 4_096 : output),
                        window == null ? "PLATFORM_FALLBACK_V1" : "PROVIDER_REVISION",
                        ContextTokenEstimator.VERSION);
            }
        }
    }

    Optional<TurnContextSnapshot> latest(
            Connection connection, ThreadId threadId, ProviderRef provider, String promptDigest) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT C.WINDOW_JSON, C.WINDOW_DIGEST, C.THROUGH_SEQUENCE FROM CORE.TURN_CONTEXT_STATE C
                JOIN CORE.AGENT_TURN T ON T.ID = C.TURN_ID
                WHERE T.THREAD_ID = ? AND T.PROVIDER_ID = ? AND T.PROVIDER_REVISION = ? AND T.MODEL = ?
                    AND T.PROMPT_MANIFEST_DIGEST = ? AND C.WINDOW_JSON IS NOT NULL
                ORDER BY C.THROUGH_SEQUENCE DESC LIMIT 1
                """)) {
            query.setString(1, threadId.toString());
            query.setString(2, provider.endpointId());
            query.setLong(3, provider.endpointRevision());
            query.setString(4, provider.model());
            query.setString(5, promptDigest);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                ConversationWindow window =
                        json.decode(checked(rows.getString(1), rows.getString(2)), ConversationWindow.class);
                return Optional.of(new TurnContextSnapshot(window, rows.getLong(3)));
            }
        }
    }

    void save(Connection connection, TurnId turnId, ConversationWindow window, long throughSequence)
            throws SQLException {
        CanonicalPayload payload = json.encode(window);
        try (var statement = connection.prepareStatement("""
                UPDATE CORE.TURN_CONTEXT_STATE SET WINDOW_JSON = ?, WINDOW_DIGEST = ?, THROUGH_SEQUENCE = ?,
                    REVISION = REVISION + 1 WHERE TURN_ID = ?
                """)) {
            statement.setString(1, payload.json());
            statement.setString(2, payload.sha256());
            statement.setLong(3, throughSequence);
            statement.setString(4, turnId.toString());
            if (statement.executeUpdate() != 1) {
                throw new PersistenceException("Turn 缺少冻结上下文政策");
            }
        }
    }

    static long sequence(Connection connection, TurnId turnId) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT COALESCE(MAX(I.SEQUENCE), 0) FROM CORE.ITEM I
                JOIN CORE.AGENT_TURN T ON T.THREAD_ID = I.THREAD_ID WHERE T.ID = ?
                """)) {
            query.setString(1, turnId.toString());
            try (var rows = query.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private CanonicalPayload checked(String value, String digest) {
        CanonicalPayload payload = json.parse(value);
        if (!payload.sha256().equals(digest)) {
            throw new PersistenceException("Turn 上下文快照摘要不一致");
        }
        return payload;
    }
}
