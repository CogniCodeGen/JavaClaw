package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.TurnId;
import com.javaclaw.protocol.CanonicalJson;

/** Turn 创建时冻结工具目录的不可变持久化。 */
final class TurnToolCatalogRepository {
    void insert(Connection connection, TurnId turnId, ToolCatalogSnapshot source, CanonicalJson json)
            throws SQLException {
        ToolCatalogSnapshot snapshot = new ToolCatalogSnapshot(
                turnId, source.catalogRevision(), source.tools(), source.permissionCeiling(), source.capturedAt());
        CanonicalPayload payload = json.encode(snapshot);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.TURN_TOOL_CATALOG_SNAPSHOT (TURN_ID, PAYLOAD, DIGEST) VALUES (?, ?, ?)
                """)) {
            statement.setString(1, turnId.toString());
            statement.setString(2, payload.json());
            statement.setString(3, snapshot.digest());
            statement.executeUpdate();
        }
    }

    Optional<CanonicalPayload> find(Connection connection, TurnId turnId, CanonicalJson json) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT PAYLOAD, DIGEST FROM CORE.TURN_TOOL_CATALOG_SNAPSHOT WHERE TURN_ID = ?
                """)) {
            statement.setString(1, turnId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                CanonicalPayload payload = new CanonicalPayload(result.getString("PAYLOAD"));
                return Optional.of(validate(turnId, payload, result.getString("DIGEST"), json));
            }
        }
    }

    private static CanonicalPayload validate(
            TurnId turnId, CanonicalPayload payload, String digest, CanonicalJson json) {
        ToolCatalogSnapshot snapshot = json.decode(payload, ToolCatalogSnapshot.class);
        boolean valid = digest.matches("[0-9a-f]{64}")
                && snapshot.turnId().equals(turnId)
                && snapshot.digest().equals(digest);
        if (!valid) {
            throw new PersistenceException("冻结工具目录身份或摘要校验失败");
        }
        return payload;
    }
}
