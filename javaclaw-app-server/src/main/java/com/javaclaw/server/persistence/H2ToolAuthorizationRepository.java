package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.agent.tool.ToolAuthorizationGateway;
import com.javaclaw.server.extension.ToolAuthorization;
import com.javaclaw.server.extension.ToolAuthorizationRepository;

/** H2 预授权实现；行锁串行化剩余额度和执行凭据，不在数据库事务中发起外部请求。 */
public final class H2ToolAuthorizationRepository implements ToolAuthorizationRepository {
    private final H2Database database;
    private final H2IdempotencyStore idempotency = new H2IdempotencyStore();
    private final ObjectMapper json = new ObjectMapper();

    /** 复用 App Server 唯一事务边界。 */
    public H2ToolAuthorizationRepository(H2Database database) {
        this.database = java.util.Objects.requireNonNull(database);
    }

    @Override
    public List<ToolAuthorization> list(String workspaceId) {
        return database.query(connection -> {
            var result = new ArrayList<ToolAuthorization>();
            try (var query = connection.prepareStatement(
                    "SELECT * FROM tool_preauthorizations WHERE workspace_id=? ORDER BY authorization_id")) {
                query.setString(1, workspaceId);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        result.add(read(rows));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    @Override
    public ToolAuthorization put(ToolAuthorization request, long expectedRevision, String key) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be nonnegative");
        }
        return database.transaction(connection -> {
            String constraints = constraints(request);
            String hash = H2IdempotencyStore.requestHash(
                    request.id(),
                    request.workspaceId(),
                    request.toolName(),
                    request.sourceRevision(),
                    request.schemaSha256(),
                    constraints,
                    request.maximumUses(),
                    request.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                    request.enabled(),
                    expectedRevision);
            var replay = idempotency.replay(connection, "tool/authorization/put", key, hash, ToolAuthorization.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            String id = request.id() == null || request.id().isBlank() ? "toolauth_" + UUID.randomUUID() : request.id();
            ToolAuthorization current = find(connection, id, true).orElse(null);
            long revision = current == null ? 0 : current.revision();
            int consumed = current == null ? 0 : current.consumedUses();
            if (revision != expectedRevision || request.maximumUses() < consumed) {
                throw new IllegalStateException("authorization revision or remaining quota conflict");
            }
            if (current != null
                    && (!current.workspaceId().equals(request.workspaceId())
                            || !current.sourceId().equals(request.sourceId())
                            || !current.toolName().equals(request.toolName()))) {
                throw new IllegalArgumentException("authorization source and workspace are immutable");
            }
            var result = new ToolAuthorization(
                    id,
                    request.workspaceId(),
                    request.sourceId(),
                    request.toolName(),
                    request.sourceRevision(),
                    request.schemaSha256(),
                    request.argumentTemplate(),
                    request.recipientField(),
                    request.variableFields(),
                    request.maximumUses(),
                    consumed,
                    request.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                    request.enabled(),
                    revision + 1,
                    Instant.ofEpochMilli(System.currentTimeMillis()));
            try (var update = connection.prepareStatement("""
                    MERGE INTO tool_preauthorizations(authorization_id,workspace_id,tool_name,source_revision,schema_sha256,
                    constraints_json,maximum_uses,consumed_uses,expires_at,enabled,revision,updated_at)
                    KEY(authorization_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                update.setString(1, id);
                update.setString(2, result.workspaceId());
                update.setString(3, result.toolName());
                update.setLong(4, result.sourceRevision());
                update.setString(5, result.schemaSha256());
                update.setString(6, constraints);
                update.setInt(7, result.maximumUses());
                update.setInt(8, consumed);
                update.setLong(9, result.expiresAt().toEpochMilli());
                update.setBoolean(10, result.enabled());
                update.setLong(11, result.revision());
                update.setLong(12, result.updatedAt().toEpochMilli());
                update.executeUpdate();
            }
            idempotency.record(
                    connection,
                    "tool/authorization/put",
                    key,
                    hash,
                    result,
                    result.updatedAt().toEpochMilli());
            return result;
        });
    }

    @Override
    public boolean disable(String id, long expectedRevision, String key) {
        return database.transaction(connection -> {
            String hash = H2IdempotencyStore.requestHash(id, expectedRevision);
            var replay = idempotency.replay(connection, "tool/authorization/delete", key, hash, Boolean.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            try (var update = connection.prepareStatement("""
                    UPDATE tool_preauthorizations SET enabled=FALSE,revision=revision+1,updated_at=?
                    WHERE authorization_id=? AND revision=?
                    """)) {
                update.setLong(1, System.currentTimeMillis());
                update.setString(2, id);
                update.setLong(3, expectedRevision);
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException("authorization revision conflict");
                }
            }
            idempotency.record(connection, "tool/authorization/delete", key, hash, true, System.currentTimeMillis());
            return true;
        });
    }

    @Override
    public Optional<ToolAuthorizationGateway.Receipt> consume(
            String id, long revision, String invocationId, String argumentsSha256, Instant now) {
        return database.transaction(connection -> {
            ToolAuthorization grant = find(connection, id, true).orElse(null);
            if (grant == null
                    || !grant.enabled()
                    || grant.revision() != revision
                    || !grant.expiresAt().isAfter(now)) {
                return Optional.empty();
            }
            try (var query = connection.prepareStatement("""
                    SELECT arguments_sha256 FROM tool_authorization_receipts
                    WHERE authorization_id=? AND invocation_id=?
                    """)) {
                query.setString(1, id);
                query.setString(2, invocationId);
                try (var row = query.executeQuery()) {
                    if (row.next()) {
                        if (!row.getString(1).equals(argumentsSha256)) {
                            throw new IllegalStateException("authorization invocation payload changed");
                        }
                        return Optional.of(new ToolAuthorizationGateway.Receipt(
                                id, revision, grant.maximumUses() - grant.consumedUses()));
                    }
                }
            }
            if (grant.consumedUses() >= grant.maximumUses()) {
                return Optional.empty();
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO tool_authorization_receipts(authorization_id,invocation_id,arguments_sha256,consumed_at)
                    VALUES(?,?,?,?)
                    """);
                    var update = connection.prepareStatement("""
                    UPDATE tool_preauthorizations SET consumed_uses=consumed_uses+1 WHERE authorization_id=?
                    """)) {
                insert.setString(1, id);
                insert.setString(2, invocationId);
                insert.setString(3, argumentsSha256);
                insert.setLong(4, now.toEpochMilli());
                insert.executeUpdate();
                update.setString(1, id);
                update.executeUpdate();
            }
            return Optional.of(
                    new ToolAuthorizationGateway.Receipt(id, revision, grant.maximumUses() - grant.consumedUses() - 1));
        });
    }

    private Optional<ToolAuthorization> find(Connection connection, String id, boolean lock) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT * FROM tool_preauthorizations WHERE authorization_id=?" + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, id);
            try (var row = query.executeQuery()) {
                return row.next() ? Optional.of(read(row)) : Optional.empty();
            }
        }
    }

    private String constraints(ToolAuthorization request) throws SQLException {
        try {
            var result = json.createObjectNode()
                    .put("sourceId", request.sourceId())
                    .put("recipientField", request.recipientField());
            result.set("argumentTemplate", json.readTree(request.argumentTemplate()));
            var fields = result.putArray("variableFields");
            request.variableFields().stream().sorted().forEach(fields::add);
            return result.toString();
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new SQLException("invalid authorization template");
        }
    }

    private ToolAuthorization read(ResultSet row) throws SQLException {
        try {
            var constraints = json.readTree(row.getString("constraints_json"));
            var variables = new java.util.LinkedHashSet<String>();
            constraints.path("variableFields").forEach(value -> variables.add(value.asText()));
            return new ToolAuthorization(
                    row.getString("authorization_id"),
                    row.getString("workspace_id"),
                    constraints.path("sourceId").asText(),
                    row.getString("tool_name"),
                    row.getLong("source_revision"),
                    row.getString("schema_sha256"),
                    constraints.path("argumentTemplate").toString(),
                    constraints.path("recipientField").asText(),
                    variables,
                    row.getInt("maximum_uses"),
                    row.getInt("consumed_uses"),
                    Instant.ofEpochMilli(row.getLong("expires_at")),
                    row.getBoolean("enabled"),
                    row.getLong("revision"),
                    Instant.ofEpochMilli(row.getLong("updated_at")));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new SQLException("invalid stored authorization");
        }
    }
}
