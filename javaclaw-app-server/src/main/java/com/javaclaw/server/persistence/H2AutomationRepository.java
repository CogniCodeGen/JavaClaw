package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.agent.automation.AutomationKind;
import com.javaclaw.agent.automation.AutomationRepository;

/** H2 authority for automation definitions and schedule configuration/state. */
public final class H2AutomationRepository implements AutomationRepository {
    private final H2Database database;
    private final H2IdempotencyStore idempotency = new H2IdempotencyStore();
    private final ObjectMapper json = new ObjectMapper();

    /** 绑定 App Server 持有的共享 H2Database；不另建连接工厂，数据库生命周期由装配层统一管理。 */
    public H2AutomationRepository(H2Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public List<AutomationDefinition> listAutomations() {
        return database.query(connection -> {
            ArrayList<AutomationDefinition> result = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT * FROM automations ORDER BY name, automation_id
                    """);
                    ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    result.add(readAutomation(connection, rows));
                }
            }
            return List.copyOf(result);
        });
    }

    @Override
    public Optional<AutomationDefinition> findAutomation(String id) {
        String value = bounded(id, "id", 80);
        return database.query(connection -> findAutomation(connection, value, false));
    }

    @Override
    public AutomationDefinition putAutomation(AutomationDraft draft, long expectedRevision, String idempotencyKey) {
        Objects.requireNonNull(draft, "draft");
        String requestedId = draft.id() == null || draft.id().isBlank() ? null : bounded(draft.id(), "id", 80);
        AutomationKind kind = Objects.requireNonNull(draft.kind(), "kind");
        String name = bounded(draft.name(), "name", 500);
        String workspace = bounded(draft.workspaceId(), "workspaceId", 80);
        String profile = bounded(draft.profileId(), "profileId", 80);
        String prompt = bounded(draft.prompt(), "prompt", 2_000_000);
        String definition = json(draft.definitionJson(), "definitionJson");
        String requestHash = H2IdempotencyStore.requestHash(
                requestedId, kind, name, workspace, profile, prompt, definition, expectedRevision);
        return database.transaction(connection -> {
            Optional<AutomationDefinition> replay = idempotency.replay(
                    connection, "automation/put", idempotencyKey, requestHash, AutomationDefinition.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            requireWorkspace(connection, workspace);
            requireProfile(connection, profile);
            String id = requestedId == null ? id("aut_") : requestedId;
            AutomationDefinition current = findAutomation(connection, id, true).orElse(null);
            long now = System.currentTimeMillis();
            AutomationDefinition result;
            if (current == null) {
                if (expectedRevision != 0) {
                    throw new NoSuchElementException("automation not found: " + id);
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO automations(automation_id, kind, name, workspace_id,
                            thread_id, active_turn_id, profile_id, prompt, definition_json,
                            status, revision, created_at, updated_at)
                        VALUES (?, ?, ?, ?, NULL, NULL, ?, ?, ?, 'READY', 1, ?, ?)
                        """)) {
                    insert.setString(1, id);
                    insert.setString(2, kind.name());
                    insert.setString(3, name);
                    insert.setString(4, workspace);
                    insert.setString(5, profile);
                    insert.setString(6, prompt);
                    insert.setString(7, definition);
                    insert.setLong(8, now);
                    insert.setLong(9, now);
                    insert.executeUpdate();
                }
                result = new AutomationDefinition(
                        id,
                        kind,
                        name,
                        workspace,
                        profile,
                        prompt,
                        definition,
                        "READY",
                        null,
                        null,
                        1,
                        instant(now),
                        instant(now));
            } else {
                requireRevision("automation", id, current.revision(), expectedRevision);
                if ("RUNNING".equals(current.status())) {
                    throw new IllegalStateException("running automation cannot be edited");
                }
                long revision = current.revision() + 1;
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE automations SET kind = ?, name = ?, workspace_id = ?,
                            profile_id = ?, prompt = ?, definition_json = ?,
                            revision = ?, updated_at = ?
                        WHERE automation_id = ? AND revision = ?
                        """)) {
                    update.setString(1, kind.name());
                    update.setString(2, name);
                    update.setString(3, workspace);
                    update.setString(4, profile);
                    update.setString(5, prompt);
                    update.setString(6, definition);
                    update.setLong(7, revision);
                    update.setLong(8, now);
                    update.setString(9, id);
                    update.setLong(10, current.revision());
                    if (update.executeUpdate() != 1) {
                        conflict("automation", id);
                    }
                }
                result = new AutomationDefinition(
                        id,
                        kind,
                        name,
                        workspace,
                        profile,
                        prompt,
                        definition,
                        current.status(),
                        current.threadId(),
                        current.activeTurnId(),
                        revision,
                        current.createdAt(),
                        instant(now));
            }
            idempotency.record(connection, "automation/put", idempotencyKey, requestHash, result, now);
            return result;
        });
    }

    @Override
    public boolean deleteAutomation(String id, long expectedRevision, String idempotencyKey) {
        return delete(
                "automation/delete",
                "automations",
                "automation_id",
                "automation",
                bounded(id, "id", 80),
                expectedRevision,
                idempotencyKey);
    }

    @Override
    public AutomationDefinition bindAutomationRun(
            String id, long expectedRevision, String threadId, String turnId, String status) {
        String automationId = bounded(id, "id", 80);
        String thread = bounded(threadId, "threadId", 80);
        String turn = turnId == null ? null : bounded(turnId, "turnId", 80);
        String state = bounded(status, "status", 40);
        return database.transaction(connection -> {
            AutomationDefinition current = findAutomation(connection, automationId, true)
                    .orElseThrow(() -> new NoSuchElementException("automation not found: " + automationId));
            requireRevision("automation", automationId, current.revision(), expectedRevision);
            long revision = current.revision() + 1;
            long now = System.currentTimeMillis();
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE automations SET thread_id = ?, active_turn_id = ?, status = ?,
                        revision = ?, updated_at = ?
                    WHERE automation_id = ? AND revision = ?
                    """)) {
                update.setString(1, thread);
                update.setString(2, turn);
                update.setString(3, state);
                update.setLong(4, revision);
                update.setLong(5, now);
                update.setString(6, automationId);
                update.setLong(7, current.revision());
                if (update.executeUpdate() != 1) {
                    conflict("automation", automationId);
                }
            }
            return new AutomationDefinition(
                    current.id(),
                    current.kind(),
                    current.name(),
                    current.workspaceId(),
                    current.profileId(),
                    current.prompt(),
                    current.definitionJson(),
                    state,
                    thread,
                    turn,
                    revision,
                    current.createdAt(),
                    instant(now));
        });
    }

    @Override
    public List<ScheduleDefinition> listSchedules() {
        return database.query(connection -> {
            ArrayList<ScheduleDefinition> result = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT * FROM schedules ORDER BY name, schedule_id
                    """);
                    ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    result.add(readSchedule(rows));
                }
            }
            return List.copyOf(result);
        });
    }

    @Override
    public Optional<ScheduleDefinition> findSchedule(String id) {
        String value = bounded(id, "id", 80);
        return database.query(connection -> findSchedule(connection, value, false));
    }

    @Override
    public ScheduleDefinition putSchedule(ScheduleDraft draft, long expectedRevision, String idempotencyKey) {
        Objects.requireNonNull(draft, "draft");
        String requestedId = draft.id() == null || draft.id().isBlank() ? null : bounded(draft.id(), "id", 80);
        String name = bounded(draft.name(), "name", 500);
        String workspace = bounded(draft.workspaceId(), "workspaceId", 80);
        String profile = bounded(draft.profileId(), "profileId", 80);
        String prompt = bounded(draft.prompt(), "prompt", 2_000_000);
        String cron = bounded(draft.cronExpression(), "cronExpression", 500);
        String zone = bounded(draft.zoneId(), "zoneId", 100);
        String requestHash = H2IdempotencyStore.requestHash(
                requestedId, name, workspace, profile, prompt, cron, zone, draft.enabled(), expectedRevision);
        return database.transaction(connection -> {
            Optional<ScheduleDefinition> replay = idempotency.replay(
                    connection, "schedule/put", idempotencyKey, requestHash, ScheduleDefinition.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            requireWorkspace(connection, workspace);
            requireProfile(connection, profile);
            String id = requestedId == null ? id("sch_") : requestedId;
            ScheduleDefinition current = findSchedule(connection, id, true).orElse(null);
            long now = System.currentTimeMillis();
            ScheduleDefinition result;
            if (current == null) {
                if (expectedRevision != 0) {
                    throw new NoSuchElementException("schedule not found: " + id);
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO schedules(schedule_id, name, workspace_id, thread_id,
                            profile_id, prompt, cron_expression, zone_id, enabled,
                            next_fire_at, last_fire_at, last_result, revision,
                            created_at, updated_at)
                        VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, NULL, NULL, NULL, 1, ?, ?)
                        """)) {
                    insert.setString(1, id);
                    insert.setString(2, name);
                    insert.setString(3, workspace);
                    insert.setString(4, profile);
                    insert.setString(5, prompt);
                    insert.setString(6, cron);
                    insert.setString(7, zone);
                    insert.setBoolean(8, draft.enabled());
                    insert.setLong(9, now);
                    insert.setLong(10, now);
                    insert.executeUpdate();
                }
                result = new ScheduleDefinition(
                        id,
                        name,
                        workspace,
                        null,
                        profile,
                        prompt,
                        cron,
                        zone,
                        draft.enabled(),
                        null,
                        null,
                        null,
                        1,
                        instant(now),
                        instant(now));
            } else {
                requireRevision("schedule", id, current.revision(), expectedRevision);
                long revision = current.revision() + 1;
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE schedules SET name = ?, workspace_id = ?, profile_id = ?,
                            prompt = ?, cron_expression = ?, zone_id = ?, enabled = ?,
                            revision = ?, updated_at = ?
                        WHERE schedule_id = ? AND revision = ?
                        """)) {
                    update.setString(1, name);
                    update.setString(2, workspace);
                    update.setString(3, profile);
                    update.setString(4, prompt);
                    update.setString(5, cron);
                    update.setString(6, zone);
                    update.setBoolean(7, draft.enabled());
                    update.setLong(8, revision);
                    update.setLong(9, now);
                    update.setString(10, id);
                    update.setLong(11, current.revision());
                    if (update.executeUpdate() != 1) {
                        conflict("schedule", id);
                    }
                }
                result = new ScheduleDefinition(
                        id,
                        name,
                        workspace,
                        current.threadId(),
                        profile,
                        prompt,
                        cron,
                        zone,
                        draft.enabled(),
                        current.nextFireAt(),
                        current.lastFireAt(),
                        current.lastResult(),
                        revision,
                        current.createdAt(),
                        instant(now));
            }
            idempotency.record(connection, "schedule/put", idempotencyKey, requestHash, result, now);
            return result;
        });
    }

    @Override
    public ScheduleDefinition setScheduleEnabled(
            String id, boolean enabled, long expectedRevision, String idempotencyKey) {
        String scheduleId = bounded(id, "id", 80);
        String method = enabled ? "schedule/enable" : "schedule/disable";
        String requestHash = H2IdempotencyStore.requestHash(scheduleId, enabled, expectedRevision);
        return database.transaction(connection -> {
            Optional<ScheduleDefinition> replay =
                    idempotency.replay(connection, method, idempotencyKey, requestHash, ScheduleDefinition.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            ScheduleDefinition current = findSchedule(connection, scheduleId, true)
                    .orElseThrow(() -> new NoSuchElementException("schedule not found: " + scheduleId));
            requireRevision("schedule", scheduleId, current.revision(), expectedRevision);
            long revision = current.revision() + 1;
            long now = System.currentTimeMillis();
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE schedules SET enabled = ?, revision = ?, updated_at = ?
                    WHERE schedule_id = ? AND revision = ?
                    """)) {
                update.setBoolean(1, enabled);
                update.setLong(2, revision);
                update.setLong(3, now);
                update.setString(4, scheduleId);
                update.setLong(5, current.revision());
                if (update.executeUpdate() != 1) {
                    conflict("schedule", scheduleId);
                }
            }
            ScheduleDefinition result = new ScheduleDefinition(
                    current.id(),
                    current.name(),
                    current.workspaceId(),
                    current.threadId(),
                    current.profileId(),
                    current.prompt(),
                    current.cronExpression(),
                    current.zoneId(),
                    enabled,
                    current.nextFireAt(),
                    current.lastFireAt(),
                    current.lastResult(),
                    revision,
                    current.createdAt(),
                    instant(now));
            idempotency.record(connection, method, idempotencyKey, requestHash, result, now);
            return result;
        });
    }

    @Override
    public boolean deleteSchedule(String id, long expectedRevision, String idempotencyKey) {
        return delete(
                "schedule/delete",
                "schedules",
                "schedule_id",
                "schedule",
                bounded(id, "id", 80),
                expectedRevision,
                idempotencyKey);
    }

    @Override
    public ScheduleDefinition bindScheduleThread(String id, long expectedRevision, String threadId) {
        String scheduleId = bounded(id, "id", 80);
        String thread = bounded(threadId, "threadId", 80);
        return database.transaction(connection -> {
            ScheduleDefinition current = findSchedule(connection, scheduleId, true)
                    .orElseThrow(() -> new NoSuchElementException("schedule not found: " + scheduleId));
            requireRevision("schedule", scheduleId, current.revision(), expectedRevision);
            long revision = current.revision() + 1;
            long now = System.currentTimeMillis();
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE schedules SET thread_id = ?, revision = ?, updated_at = ?
                    WHERE schedule_id = ? AND revision = ?
                    """)) {
                update.setString(1, thread);
                update.setLong(2, revision);
                update.setLong(3, now);
                update.setString(4, scheduleId);
                update.setLong(5, current.revision());
                if (update.executeUpdate() != 1) {
                    conflict("schedule", scheduleId);
                }
            }
            return new ScheduleDefinition(
                    current.id(),
                    current.name(),
                    current.workspaceId(),
                    thread,
                    current.profileId(),
                    current.prompt(),
                    current.cronExpression(),
                    current.zoneId(),
                    current.enabled(),
                    current.nextFireAt(),
                    current.lastFireAt(),
                    current.lastResult(),
                    revision,
                    current.createdAt(),
                    instant(now));
        });
    }

    @Override
    public void recordScheduleFire(String id, Instant fireTime, Instant nextFireTime, String result) {
        String scheduleId = bounded(id, "id", 80);
        database.transaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE schedules SET last_fire_at = COALESCE(?, last_fire_at),
                        next_fire_at = ?, last_result = ?, updated_at = ?
                    WHERE schedule_id = ?
                    """)) {
                if (fireTime == null) {
                    update.setNull(1, java.sql.Types.BIGINT);
                } else {
                    update.setLong(1, fireTime.toEpochMilli());
                }
                if (nextFireTime == null) {
                    update.setNull(2, java.sql.Types.BIGINT);
                } else {
                    update.setLong(2, nextFireTime.toEpochMilli());
                }
                update.setString(3, result == null ? null : bounded(result, "result", 500));
                update.setLong(4, System.currentTimeMillis());
                update.setString(5, scheduleId);
                if (update.executeUpdate() != 1) {
                    throw new NoSuchElementException("schedule not found: " + scheduleId);
                }
            }
            return null;
        });
    }

    private boolean delete(
            String method, String table, String column, String kind, String id, long expectedRevision, String key) {
        String requestHash = H2IdempotencyStore.requestHash(id, expectedRevision);
        return database.transaction(connection -> {
            Optional<Boolean> replay = idempotency.replay(connection, method, key, requestHash, Boolean.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            Long revision = null;
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT revision FROM " + table + " WHERE " + column + " = ? FOR UPDATE")) {
                query.setString(1, id);
                try (ResultSet row = query.executeQuery()) {
                    if (row.next()) {
                        revision = row.getLong(1);
                    }
                }
            }
            boolean deleted = revision != null;
            if (deleted) {
                requireRevision(kind, id, revision, expectedRevision);
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE " + column + " = ? AND revision = ?")) {
                    statement.setString(1, id);
                    statement.setLong(2, revision);
                    if (statement.executeUpdate() != 1) {
                        conflict(kind, id);
                    }
                }
            }
            idempotency.record(connection, method, key, requestHash, deleted, System.currentTimeMillis());
            return deleted;
        });
    }

    private Optional<AutomationDefinition> findAutomation(Connection connection, String id, boolean lock)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM automations WHERE automation_id = ?" + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, id);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? Optional.of(readAutomation(connection, row)) : Optional.empty();
            }
        }
    }

    private Optional<ScheduleDefinition> findSchedule(Connection connection, String id, boolean lock)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM schedules WHERE schedule_id = ?" + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, id);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? Optional.of(readSchedule(row)) : Optional.empty();
            }
        }
    }

    private static AutomationDefinition readAutomation(Connection connection, ResultSet row) throws SQLException {
        String threadId = row.getString("thread_id");
        String turnId = row.getString("active_turn_id");
        String status = row.getString("status");
        if (threadId != null) {
            // Turn Journal 是执行状态权威。读取派生状态，不因查询改写定义版本，也不让旧 RUNNING 锁死编辑。
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT t.turn_id,t.status FROM turns t JOIN thread_events e ON e.turn_id=t.turn_id AND e.thread_id=t.thread_id "
                            + "WHERE t.thread_id=? AND e.event_type='turn/queued' ORDER BY e.event_sequence DESC FETCH FIRST ROW ONLY")) {
                query.setString(1, threadId);
                try (ResultSet turn = query.executeQuery()) {
                    if (turn.next()) {
                        var turnStatus = com.javaclaw.core.api.TurnStatus.valueOf(turn.getString("status"));
                        status = turnStatus.terminal() ? turnStatus.name() : "RUNNING";
                        turnId = turnStatus.terminal() ? null : turn.getString("turn_id");
                    }
                }
            }
        }
        return new AutomationDefinition(
                row.getString("automation_id"),
                AutomationKind.valueOf(row.getString("kind")),
                row.getString("name"),
                row.getString("workspace_id"),
                row.getString("profile_id"),
                row.getString("prompt"),
                row.getString("definition_json"),
                status,
                threadId,
                turnId,
                row.getLong("revision"),
                instant(row.getLong("created_at")),
                instant(row.getLong("updated_at")));
    }

    private static ScheduleDefinition readSchedule(ResultSet row) throws SQLException {
        return new ScheduleDefinition(
                row.getString("schedule_id"),
                row.getString("name"),
                row.getString("workspace_id"),
                row.getString("thread_id"),
                row.getString("profile_id"),
                row.getString("prompt"),
                row.getString("cron_expression"),
                row.getString("zone_id"),
                row.getBoolean("enabled"),
                nullableInstant(row, "next_fire_at"),
                nullableInstant(row, "last_fire_at"),
                row.getString("last_result"),
                row.getLong("revision"),
                instant(row.getLong("created_at")),
                instant(row.getLong("updated_at")));
    }

    private static void requireWorkspace(Connection connection, String id) throws SQLException {
        requireExists(connection, "workspaces", "workspace_id", id, "workspace");
    }

    private static void requireProfile(Connection connection, String id) throws SQLException {
        requireExists(connection, "profiles", "profile_id", id, "profile");
    }

    private static void requireExists(Connection connection, String table, String column, String id, String kind)
            throws SQLException {
        try (PreparedStatement query =
                connection.prepareStatement("SELECT 1 FROM " + table + " WHERE " + column + " = ?")) {
            query.setString(1, id);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    throw new NoSuchElementException(kind + " not found: " + id);
                }
            }
        }
    }

    private String json(String value, String name) {
        String result = bounded(value, name, 2_000_000);
        try {
            json.readTree(result);
        } catch (Exception failure) {
            throw new IllegalArgumentException(name + " must be valid JSON", failure);
        }
        return result;
    }

    private static String bounded(String value, String name, int maximum) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        String result = value.strip();
        if (result.length() > maximum) {
            throw new IllegalArgumentException(name + " exceeds " + maximum + " characters");
        }
        return result;
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private static void requireRevision(String kind, String id, long current, long expected) {
        if (expected < 1 || current != expected) {
            conflict(kind, id);
        }
    }

    private static void conflict(String kind, String id) {
        throw new IllegalStateException(kind + " revision conflict: " + id);
    }

    private static Instant instant(long value) {
        return Instant.ofEpochMilli(value);
    }

    private static Instant nullableInstant(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : instant(value);
    }
}
