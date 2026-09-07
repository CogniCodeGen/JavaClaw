package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;
import java.util.OptionalLong;

import com.javaclaw.api.ModelContextLimits;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderRef;

/** 模型容量的不可变版本行；旧 Provider 写入只能继承适用元数据，不能清除用户已声明限制。 */
final class ProviderContextRepository {
    ModelContextLimits read(Connection connection, ProviderRef provider) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT CONTEXT_WINDOW_TOKENS, MAXIMUM_OUTPUT_TOKENS FROM CORE.PROVIDER_MODEL_CONTEXT
                WHERE PROVIDER_ID = ? AND PROVIDER_REVISION = ? AND MODEL = ?
                """)) {
            query.setString(1, provider.endpointId());
            query.setLong(2, provider.endpointRevision());
            query.setString(3, provider.model());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    return ModelContextLimits.unknown(provider);
                }
                Long window = rows.getObject(1, Long.class);
                Long output = rows.getObject(2, Long.class);
                return new ModelContextLimits(provider, optional(window), optional(output));
            }
        }
    }

    void insert(Connection connection, ModelContextLimits limits) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO CORE.PROVIDER_MODEL_CONTEXT
                (PROVIDER_ID, PROVIDER_REVISION, MODEL, CONTEXT_WINDOW_TOKENS, MAXIMUM_OUTPUT_TOKENS)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, limits.provider().endpointId());
            statement.setLong(2, limits.provider().endpointRevision());
            statement.setString(3, limits.provider().model());
            if (limits.contextWindowTokens().isPresent()) {
                statement.setLong(4, limits.contextWindowTokens().getAsLong());
            } else {
                statement.setNull(4, Types.BIGINT);
            }
            if (limits.maximumOutputTokens().isPresent()) {
                statement.setLong(5, limits.maximumOutputTokens().getAsLong());
            } else {
                statement.setNull(5, Types.BIGINT);
            }
            statement.executeUpdate();
        }
    }

    void inherit(Connection connection, Optional<ProviderEndpoint> previous, ProviderEndpoint next)
            throws SQLException {
        if (previous.isEmpty()
                || previous.orElseThrow().spec().adapter() != next.spec().adapter()
                || !previous.orElseThrow().spec().baseUri().equals(next.spec().baseUri())) {
            return;
        }
        ProviderEndpoint old = previous.orElseThrow();
        for (var model : next.spec().models()) {
            if (old.spec().models().stream().noneMatch(entry -> entry.modelId().equals(model.modelId()))) {
                continue;
            }
            ModelContextLimits limits = read(connection, new ProviderRef(old.id(), old.revision(), model.modelId()));
            insert(
                    connection,
                    new ModelContextLimits(
                            new ProviderRef(next.id(), next.revision(), model.modelId()),
                            limits.contextWindowTokens(),
                            limits.maximumOutputTokens()));
        }
    }

    private OptionalLong optional(Long value) {
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }
}
