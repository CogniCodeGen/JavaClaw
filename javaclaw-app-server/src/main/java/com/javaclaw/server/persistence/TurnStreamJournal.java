package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.TurnStreamEvent;
import com.javaclaw.api.TurnStreamKind;
import com.javaclaw.protocol.CanonicalJson;

/** 与 Harness 日志共享 Connection 的公开事件写入；调用身份直接来自同一事件日志。 */
final class TurnStreamJournal {
    private final TurnStreamRepository events;

    TurnStreamJournal(CanonicalJson json) {
        events = new TurnStreamRepository(json);
    }

    void intent(Connection connection, TurnId turnId, int number, Instant now) throws SQLException {
        events.started(connection, turnId, number, now);
    }

    void lock(Connection connection, TurnId turnId) throws SQLException {
        events.lockJournal(connection, turnId);
    }

    ItemId messageId(Connection connection, TurnId turnId, int number) throws SQLException {
        var data = events.latest(connection, turnId).orElseThrow(() -> new PersistenceException("模型结果缺少持久流意图"));
        var call = data.call().orElseThrow(() -> new PersistenceException("模型结果晚于 Turn 终态"));
        if (call.invocationNumber() != number
                || (data.kind() != TurnStreamKind.STARTED && data.kind() != TurnStreamKind.TEXT_DELTA)) {
            throw new PersistenceException("模型结果与活动流调用身份不匹配");
        }
        return call.messageItemId();
    }

    void committed(Connection connection, TurnId turnId, Optional<ItemEnvelope> item, Instant now) throws SQLException {
        var prior = events.latest(connection, turnId).orElseThrow();
        events.append(
                connection,
                turnId,
                new TurnStreamEvent.Data(
                        item.isPresent() ? TurnStreamKind.COMMITTED : TurnStreamKind.CLOSED,
                        prior.call(),
                        "",
                        0,
                        item.map(ItemEnvelope::sequence),
                        Optional.empty()),
                now);
    }

    void finished(Connection connection, TurnId turnId, TurnStatus status, Instant now) throws SQLException {
        if (status != TurnStatus.COMPLETED && status != TurnStatus.FAILED && status != TurnStatus.CANCELLED) {
            return;
        }
        Optional<TurnStreamEvent.Data> latest = events.latest(connection, turnId);
        if (latest.isPresent()) {
            var prior = latest.orElseThrow();
            if (prior.kind() == TurnStreamKind.TURN_FINISHED) {
                return;
            }
            if (prior.kind() == TurnStreamKind.STARTED || prior.kind() == TurnStreamKind.TEXT_DELTA) {
                events.append(
                        connection,
                        turnId,
                        new TurnStreamEvent.Data(
                                TurnStreamKind.CLOSED, prior.call(), "", 0, Optional.empty(), Optional.empty()),
                        now);
            }
        }
        events.append(
                connection,
                turnId,
                new TurnStreamEvent.Data(
                        TurnStreamKind.TURN_FINISHED,
                        Optional.empty(),
                        "",
                        0,
                        Optional.of(finalItemSequence(connection, turnId)),
                        Optional.of(status)),
                now);
    }

    private long finalItemSequence(Connection connection, TurnId turnId) throws SQLException {
        try (var statement =
                connection.prepareStatement("SELECT COALESCE(MAX(SEQUENCE), 0) FROM CORE.ITEM WHERE TURN_ID = ?")) {
            statement.setString(1, turnId.toString());
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }
}
