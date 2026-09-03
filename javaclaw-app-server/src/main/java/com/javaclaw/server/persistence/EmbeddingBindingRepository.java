package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import com.javaclaw.api.EmbeddingBinding;
import com.javaclaw.api.ProviderRef;

/** 本地安装唯一 Embedding 绑定的 SQL 实现。 */
final class EmbeddingBindingRepository {
    Optional<EmbeddingBinding> find(Connection connection, boolean lock) throws SQLException {
        String sql = "SELECT PROVIDER_ID, PROVIDER_REVISION, MODEL, REVISION, UPDATED_AT "
                + "FROM CORE.EMBEDDING_BINDING WHERE SINGLETON = TRUE"
                + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            return result.next() ? Optional.of(map(result)) : Optional.empty();
        }
    }

    void insert(Connection connection, EmbeddingBinding binding) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.EMBEDDING_BINDING (
                    SINGLETON, PROVIDER_ID, PROVIDER_REVISION, MODEL, REVISION, UPDATED_AT
                ) VALUES (TRUE, ?, ?, ?, ?, ?)
                """)) {
            bind(statement, binding, 1);
            statement.executeUpdate();
        }
    }

    void update(Connection connection, EmbeddingBinding binding, long expectedRevision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.EMBEDDING_BINDING SET
                    PROVIDER_ID = ?, PROVIDER_REVISION = ?, MODEL = ?, REVISION = ?, UPDATED_AT = ?
                WHERE SINGLETON = TRUE AND REVISION = ?
                """)) {
            int next = bind(statement, binding, 1);
            statement.setLong(next, expectedRevision);
            if (statement.executeUpdate() != 1) {
                throw PersistenceException.revisionConflict("Embedding binding revision 已改变");
            }
        }
    }

    private static int bind(PreparedStatement statement, EmbeddingBinding binding, int start) throws SQLException {
        int index = start;
        statement.setString(index++, binding.provider().endpointId());
        statement.setLong(index++, binding.provider().endpointRevision());
        statement.setString(index++, binding.provider().model());
        statement.setLong(index++, binding.revision());
        statement.setObject(index++, binding.updatedAt().atOffset(ZoneOffset.UTC));
        return index;
    }

    private static EmbeddingBinding map(ResultSet result) throws SQLException {
        return new EmbeddingBinding(
                new ProviderRef(
                        result.getString("PROVIDER_ID"),
                        result.getLong("PROVIDER_REVISION"),
                        result.getString("MODEL")),
                result.getLong("REVISION"),
                result.getObject("UPDATED_AT", OffsetDateTime.class).toInstant());
    }
}
