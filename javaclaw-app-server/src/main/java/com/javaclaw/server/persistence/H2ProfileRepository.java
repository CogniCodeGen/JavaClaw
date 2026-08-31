package com.javaclaw.server.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import com.javaclaw.agent.conversation.ProfileRepository;
import com.javaclaw.core.api.ExecutionProfile;
import com.javaclaw.core.api.ProfileKind;
import com.javaclaw.sandbox.api.SandboxMode;

/** H2 implementation of the Conversation Feature's Profile repository. */
public final class H2ProfileRepository implements ProfileRepository {
    private final H2Database database;
    private final H2IdempotencyStore idempotency = new H2IdempotencyStore();
    private final ObjectMapper json =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** 绑定 App Server 持有的共享 H2Database；不另建连接工厂，数据库生命周期由装配层统一管理。 */
    public H2ProfileRepository(H2Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public List<ExecutionProfile> list() {
        return database.query(connection -> {
            ArrayList<ExecutionProfile> result = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT p.*, r.provider, r.model, r.config_json
                    FROM profiles p JOIN profile_revisions r
                      ON r.profile_id = p.profile_id AND r.revision = p.current_revision
                    WHERE p.enabled = TRUE ORDER BY p.name, p.profile_id
                    """);
                    ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    result.add(read(rows));
                }
            }
            return List.copyOf(result);
        });
    }

    @Override
    public Optional<ExecutionProfile> find(String id) {
        String profileId = required(id, "id");
        return database.query(connection -> {
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT p.*, r.provider, r.model, r.config_json
                    FROM profiles p JOIN profile_revisions r
                      ON r.profile_id = p.profile_id AND r.revision = p.current_revision
                    WHERE p.profile_id = ? AND p.enabled = TRUE
                    """)) {
                query.setString(1, profileId);
                try (ResultSet row = query.executeQuery()) {
                    return row.next() ? Optional.of(read(row)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public ExecutionProfile put(ProfileDraft draft, long expectedRevision, String idempotencyKey) {
        Objects.requireNonNull(draft, "draft");
        String config = config(draft);
        return database.transaction(connection -> {
            String requestedId = draft.id() == null ? "" : draft.id().strip();
            String requestHash = H2IdempotencyStore.requestHash(
                    requestedId, draft.name(), draft.kind(), draft.provider(), draft.model(), config, expectedRevision);
            Optional<ExecutionProfile> replay =
                    idempotency.replay(connection, "profile/put", idempotencyKey, requestHash, ExecutionProfile.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            String id = requestedId.isBlank()
                    ? "profile_" + UUID.randomUUID().toString().replace("-", "")
                    : required(requestedId, "id");
            Long current = null;
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT current_revision FROM profiles WHERE profile_id = ? FOR UPDATE
                    """)) {
                query.setString(1, id);
                try (ResultSet row = query.executeQuery()) {
                    if (row.next()) {
                        current = row.getLong(1);
                    }
                }
            }
            if (current == null && expectedRevision > 0) {
                throw new NoSuchElementException("profile not found: " + id);
            }
            if (current != null && current != expectedRevision) {
                throw new IllegalStateException("profile revision conflict: " + id);
            }
            long revision = current == null ? 1 : current + 1;
            long now = System.currentTimeMillis();
            if (current == null) {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO profiles(profile_id, name, kind, current_revision,
                            enabled, created_at, updated_at)
                        VALUES (?, ?, ?, ?, TRUE, ?, ?)
                        """)) {
                    insert.setString(1, id);
                    insert.setString(2, required(draft.name(), "name"));
                    insert.setString(
                            3, Objects.requireNonNull(draft.kind(), "kind").name());
                    insert.setLong(4, revision);
                    insert.setLong(5, now);
                    insert.setLong(6, now);
                    insert.executeUpdate();
                }
            } else {
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE profiles SET name = ?, kind = ?, current_revision = ?,
                            updated_at = ? WHERE profile_id = ? AND current_revision = ?
                        """)) {
                    update.setString(1, required(draft.name(), "name"));
                    update.setString(
                            2, Objects.requireNonNull(draft.kind(), "kind").name());
                    update.setLong(3, revision);
                    update.setLong(4, now);
                    update.setString(5, id);
                    update.setLong(6, current);
                    if (update.executeUpdate() != 1) {
                        throw new IllegalStateException("profile revision conflict: " + id);
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO profile_revisions(profile_id, revision, provider, model,
                        config_json, fingerprint, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, id);
                insert.setLong(2, revision);
                insert.setString(3, required(draft.provider(), "provider"));
                insert.setString(4, required(draft.model(), "model"));
                insert.setString(5, config);
                insert.setString(6, sha256(config));
                insert.setLong(7, now);
                insert.executeUpdate();
            }
            ExecutionProfile result = new ExecutionProfile(
                    id,
                    draft.name(),
                    draft.kind(),
                    draft.provider(),
                    draft.model(),
                    draft.systemPrompt(),
                    draft.enabledTools(),
                    draft.requestedSandboxMode(),
                    draft.maxIterations(),
                    draft.maxModelCalls(),
                    draft.attributes(),
                    revision,
                    Instant.ofEpochMilli(now));
            idempotency.record(connection, "profile/put", idempotencyKey, requestHash, result, now);
            return result;
        });
    }

    @Override
    public boolean delete(String id, long expectedRevision, String idempotencyKey) {
        String profileId = required(id, "id");
        if (expectedRevision < 1) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }
        return database.transaction(connection -> {
            String requestHash = H2IdempotencyStore.requestHash(profileId, expectedRevision);
            Optional<Boolean> replay =
                    idempotency.replay(connection, "profile/delete", idempotencyKey, requestHash, Boolean.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            long now = System.currentTimeMillis();
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE profiles SET enabled = FALSE, current_revision = current_revision + 1,
                        updated_at = ? WHERE profile_id = ? AND current_revision = ?
                    """)) {
                update.setLong(1, now);
                update.setString(2, profileId);
                update.setLong(3, expectedRevision);
                int count = update.executeUpdate();
                if (count == 0 && exists(connection, profileId)) {
                    throw new IllegalStateException("profile revision conflict: " + profileId);
                }
                boolean deleted = count == 1;
                idempotency.record(connection, "profile/delete", idempotencyKey, requestHash, deleted, now);
                return deleted;
            }
        });
    }

    private boolean exists(java.sql.Connection connection, String id) throws java.sql.SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM profiles WHERE profile_id = ?")) {
            query.setString(1, id);
            try (ResultSet row = query.executeQuery()) {
                return row.next();
            }
        }
    }

    private ExecutionProfile read(ResultSet row) throws java.sql.SQLException {
        try {
            Config config = json.readValue(row.getString("config_json"), Config.class);
            return new ExecutionProfile(
                    row.getString("profile_id"),
                    row.getString("name"),
                    ProfileKind.valueOf(row.getString("kind")),
                    row.getString("provider"),
                    row.getString("model"),
                    config.systemPrompt(),
                    config.enabledTools(),
                    config.requestedSandboxMode(),
                    config.maxIterations(),
                    config.maxModelCalls(),
                    config.attributes(),
                    row.getLong("current_revision"),
                    Instant.ofEpochMilli(row.getLong("updated_at")));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("invalid persisted profile", failure);
        }
    }

    private String config(ProfileDraft draft) {
        if (draft.maxIterations() < 0
                || draft.maxIterations() > 1_000
                || draft.maxModelCalls() < 0
                || draft.maxModelCalls() > 1_000) {
            throw new IllegalArgumentException(
                    "profile budgets must be between 0 and 1000; zero inherits finite limits");
        }
        Objects.requireNonNull(draft.requestedSandboxMode(), "requestedSandboxMode");
        try {
            return json.writeValueAsString(new Config(
                    draft.systemPrompt(),
                    draft.enabledTools(),
                    draft.requestedSandboxMode(),
                    draft.maxIterations(),
                    draft.maxModelCalls(),
                    draft.attributes()));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("cannot serialize profile", failure);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }

    private record Config(
            String systemPrompt,
            Set<String> enabledTools,
            SandboxMode requestedSandboxMode,
            int maxIterations,
            int maxModelCalls,
            Map<String, String> attributes) {
        private Config {
            systemPrompt = systemPrompt == null ? "" : systemPrompt;
            enabledTools = enabledTools == null ? Set.of() : Set.copyOf(enabledTools);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
