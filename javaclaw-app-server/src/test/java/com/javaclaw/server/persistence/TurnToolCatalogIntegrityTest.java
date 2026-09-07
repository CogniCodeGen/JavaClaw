package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.TurnId;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertThrows;

class TurnToolCatalogIntegrityTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void 冻结工具目录拒绝非法摘要Turn漂移和Payload篡改() throws Exception {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        CanonicalJson json = new CanonicalJson();
        PermissionProfileService permissions =
                new PermissionProfileService(database, json, Clock.fixed(NOW, ZoneOffset.UTC));
        permissions.installStandardProfile();
        PermissionProfile ceiling = permissions.require(PermissionProfileService.STANDARD_PROFILE_ID, 1);
        TurnToolCatalogRepository repository = new TurnToolCatalogRepository();

        try (var connection = database.open()) {
            connection.createStatement().execute("SET REFERENTIAL_INTEGRITY FALSE");
            TurnId invalidDigest = TurnId.random();
            ToolCatalogSnapshot invalidSnapshot = snapshot(invalidDigest, ceiling);
            insert(connection, invalidDigest, json.encode(invalidSnapshot).json(), "not-a-digest");
            assertThrows(PersistenceException.class, () -> repository.find(connection, invalidDigest, json));

            TurnId changedTurn = TurnId.random();
            ToolCatalogSnapshot otherTurn = snapshot(TurnId.random(), ceiling);
            insert(connection, changedTurn, json.encode(otherTurn).json(), otherTurn.digest());
            assertThrows(PersistenceException.class, () -> repository.find(connection, changedTurn, json));

            TurnId changedPayload = TurnId.random();
            ToolCatalogSnapshot original = snapshot(changedPayload, ceiling);
            insert(connection, changedPayload, json.encode(original).json(), "f".repeat(64));
            assertThrows(PersistenceException.class, () -> repository.find(connection, changedPayload, json));
        }
    }

    private static ToolCatalogSnapshot snapshot(TurnId turnId, PermissionProfile ceiling) {
        return new ToolCatalogSnapshot(turnId, 1, List.of(), ceiling, NOW);
    }

    private static void insert(java.sql.Connection connection, TurnId turnId, String payload, String digest)
            throws Exception {
        try (var statement = connection.prepareStatement(
                "INSERT INTO CORE.TURN_TOOL_CATALOG_SNAPSHOT (TURN_ID, PAYLOAD, DIGEST) VALUES (?, ?, ?)")) {
            statement.setString(1, turnId.toString());
            statement.setString(2, payload);
            statement.setString(3, digest);
            statement.executeUpdate();
        }
    }
}
