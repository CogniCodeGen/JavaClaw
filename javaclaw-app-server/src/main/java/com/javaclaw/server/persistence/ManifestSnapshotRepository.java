package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;

/** 不可变、按内容寻址的 Prompt 正文；自动化恢复与 Turn 冻结共享同一份快照。 */
final class ManifestSnapshotRepository {
    void insert(Connection connection, CanonicalPayload payload) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "MERGE INTO CORE.PROMPT_MANIFEST_SNAPSHOT (DIGEST, PAYLOAD) KEY(DIGEST) VALUES (?, ?)")) {
            statement.setString(1, payload.sha256());
            statement.setString(2, payload.json());
            statement.executeUpdate();
        }
    }

    Optional<CanonicalPayload> find(Connection connection, String digest) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT PAYLOAD FROM CORE.PROMPT_MANIFEST_SNAPSHOT WHERE DIGEST = ?")) {
            statement.setString(1, digest);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                CanonicalPayload payload = new CanonicalPayload(result.getString(1));
                if (!payload.sha256().equals(digest)) {
                    throw new PersistenceException("Prompt manifest 内容摘要不一致");
                }
                return Optional.of(payload);
            }
        }
    }
}
