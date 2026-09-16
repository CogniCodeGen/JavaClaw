package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.protocol.CanonicalJson;

/** 新 Turn 与已结束前序的同事务连接；原始输入只保留一个 Item，续接链不可分叉。 */
final class TurnContinuationRepository {
    private TurnContinuationRepository() {}

    static Optional<TurnId> validate(Connection connection, TurnStartRequest request, CanonicalJson json)
            throws SQLException {
        if (request.continuedFrom().isEmpty()) {
            return Optional.empty();
        }
        TurnId previous = request.continuedFrom().orElseThrow();
        var parent = new TurnRepository()
                .find(connection, previous)
                .orElseThrow(() -> PersistenceException.invalidRequest("续接的前序 Turn 不存在"));
        if (!parent.threadId().equals(request.threadId())
                || !Set.of(TurnStatus.COMPLETED, TurnStatus.CANCELLED, TurnStatus.FAILED)
                        .contains(parent.status())) {
            throw PersistenceException.invalidRequest("续接前序必须已结束且属于同一 Thread");
        }
        requireNotContinued(connection, previous);
        TurnId original = originalInputTurn(connection, previous);
        if (!TurnInputMessages.user(connection, original, json).equals(request.message())) {
            throw PersistenceException.invalidRequest("续接消息必须与原始用户消息完全一致");
        }
        return Optional.of(original);
    }

    static void insert(Connection connection, TurnId created, TurnStartRequest request, TurnId original, Instant now)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO CORE.TURN_CONTINUATION(TURN_ID,PREVIOUS_TURN_ID,ORIGINAL_INPUT_TURN_ID,THREAD_ID,CREATED_AT)
                VALUES(?,?,?,?,?)
                """)) {
            statement.setString(1, created.toString());
            statement.setString(2, request.continuedFrom().orElseThrow().toString());
            statement.setString(3, original.toString());
            statement.setString(4, request.threadId().toString());
            statement.setObject(5, now.atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    static TurnId originalInputTurn(Connection connection, TurnId turnId) throws SQLException {
        var turns = new TurnRepository();
        var turn = turns.find(connection, turnId).orElseThrow(() -> PersistenceException.invalidRequest("Turn 不存在"));
        Optional<Link> link = link(connection, turnId);
        if (link.isEmpty()) {
            return turnId;
        }
        Link value = link.orElseThrow();
        var original =
                turns.find(connection, value.original()).orElseThrow(() -> new PersistenceException("续接原始输入 Turn 缺失"));
        var previous =
                turns.find(connection, value.previous()).orElseThrow(() -> new PersistenceException("续接前序 Turn 缺失"));
        if (!value.thread().equals(turn.threadId())
                || !original.threadId().equals(turn.threadId())
                || !previous.threadId().equals(turn.threadId())
                || value.original().equals(turnId)
                || link(connection, value.original()).isPresent()) {
            throw new PersistenceException("续接身份或原始输入归属不一致");
        }
        return value.original();
    }

    private static Optional<Link> link(Connection connection, TurnId turnId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT PREVIOUS_TURN_ID,ORIGINAL_INPUT_TURN_ID,THREAD_ID FROM CORE.TURN_CONTINUATION WHERE TURN_ID=?
                """)) {
            statement.setString(1, turnId.toString());
            try (var result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(new Link(
                                TurnId.parse(result.getString(1)),
                                TurnId.parse(result.getString(2)),
                                ThreadId.parse(result.getString(3))))
                        : Optional.empty();
            }
        }
    }

    private static void requireNotContinued(Connection connection, TurnId previous) throws SQLException {
        try (var statement =
                connection.prepareStatement("SELECT TURN_ID FROM CORE.TURN_CONTINUATION WHERE PREVIOUS_TURN_ID=?")) {
            statement.setString(1, previous.toString());
            try (var result = statement.executeQuery()) {
                if (result.next()) {
                    throw PersistenceException.revisionConflict("前序 Turn 已经续接，不能创建第二条分支");
                }
            }
        }
    }

    private record Link(TurnId previous, TurnId original, ThreadId thread) {}
}
