package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ResolvedTurnConfig;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.runtime.ReservedChildBudget;

/** 子 Thread 与父 Turn 预算和冻结配置的持久关联；创建事务提交后不返还额度。 */
final class ChildTurnReservationRepository {
    private final CanonicalJson json;

    ChildTurnReservationRepository(CanonicalJson json) {
        this.json = json;
    }

    void insert(
            Connection connection,
            TurnId parent,
            ThreadId child,
            ResolvedTurnConfig configuration,
            com.javaclaw.api.ToolCatalogSnapshot catalog)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.CHILD_TURN_RESERVATION
                    (CHILD_THREAD_ID, PARENT_TURN_ID, INPUT_TOKENS, OUTPUT_TOKENS, TOOL_CALLS,
                     WALL_TIME_MILLIS, CONFIGURATION_JSON, CATALOG_JSON) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, child.toString());
            statement.setString(2, parent.toString());
            statement.setLong(3, configuration.budget().inputTokens());
            statement.setLong(4, configuration.budget().outputTokens());
            statement.setInt(5, configuration.budget().toolCalls());
            statement.setLong(6, configuration.budget().wallTime().toMillis());
            statement.setString(7, json.encode(configuration).json());
            statement.setString(8, json.encode(catalog).json());
            statement.executeUpdate();
        }
    }

    void requireReservedStart(Connection connection, TurnStartRequest request) throws SQLException {
        var reserved = snapshot(connection, request.threadId());
        if (reserved.isEmpty()) {
            return;
        }
        if (!reserved.orElseThrow().configuration().equals(request.configuration())) {
            throw PersistenceException.invalidRequest("子 Turn 必须使用从父预算预留的冻结配置");
        }
        if (new TurnRepository().findByThread(connection, request.threadId()).isPresent()) {
            throw PersistenceException.invalidRequest("子任务预算预留只能创建一次 Turn");
        }
    }

    List<ReservedChildBudget> reservations(Connection connection, TurnId parent) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT INPUT_TOKENS, OUTPUT_TOKENS, TOOL_CALLS FROM CORE.CHILD_TURN_RESERVATION WHERE PARENT_TURN_ID = ?")) {
            statement.setString(1, parent.toString());
            try (ResultSet result = statement.executeQuery()) {
                List<ReservedChildBudget> reservations = new ArrayList<>();
                while (result.next()) {
                    reservations.add(new ReservedChildBudget(result.getLong(1), result.getLong(2), result.getInt(3)));
                }
                return List.copyOf(reservations);
            }
        }
    }

    Optional<TurnId> parent(Connection connection, ThreadId child) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT PARENT_TURN_ID FROM CORE.CHILD_TURN_RESERVATION WHERE CHILD_THREAD_ID = ?")) {
            statement.setString(1, child.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(TurnId.parse(result.getString(1))) : Optional.empty();
            }
        }
    }

    Optional<com.javaclaw.api.AutomationExecutionSnapshot> snapshot(Connection connection, ThreadId child)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT CONFIGURATION_JSON, CATALOG_JSON FROM CORE.CHILD_TURN_RESERVATION WHERE CHILD_THREAD_ID = ?")) {
            statement.setString(1, child.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(new com.javaclaw.api.AutomationExecutionSnapshot(
                                json.decode(new CanonicalPayload(result.getString(1)), ResolvedTurnConfig.class),
                                json.decode(
                                        new CanonicalPayload(result.getString(2)),
                                        com.javaclaw.api.ToolCatalogSnapshot.class),
                                Optional.empty()))
                        : Optional.empty();
            }
        }
    }
}
