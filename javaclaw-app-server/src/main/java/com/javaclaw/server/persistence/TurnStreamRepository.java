package com.javaclaw.server.persistence;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.TurnStreamCall;
import com.javaclaw.api.TurnStreamEvent;
import com.javaclaw.api.TurnStreamKind;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.TurnStreamRpcContracts;

/** 单一 CORE.EVENT 公开投影；调用方持有 Turn 锁直至提交，不在此打开嵌套事务。 */
final class TurnStreamRepository {
    private static final String TOPIC = "turn.public-stream.v1";
    private final CanonicalJson json;
    private final TurnRepository turns = new TurnRepository();
    private final EventRepository events = new EventRepository();

    TurnStreamRepository(CanonicalJson json) {
        this.json = json;
    }

    void lockJournal(Connection connection, TurnId turnId) throws SQLException {
        // Item sequence 已使用 Thread 锁。先 Thread 后 Turn，避免与输入/审批事务产生逆序。
        turns.lockForJournal(connection, turnId);
    }

    void started(Connection connection, TurnId turnId, int number, Instant now) throws SQLException {
        // 调用方已持 Turn 锁。终态和 STARTED 同锁串行，禁止迟到意图使终态水位重新活动。
        if (turns.find(connection, turnId).orElseThrow().status() != TurnStatus.RUNNING) {
            throw PersistenceException.revisionConflict("只有运行中的 Turn 可以记录模型流意图");
        }
        TurnStreamCall call = new TurnStreamCall(number, UUID.randomUUID().toString(), ItemId.random());
        append(
                connection,
                turnId,
                new TurnStreamEvent.Data(
                        TurnStreamKind.STARTED, Optional.of(call), "", 0, Optional.empty(), Optional.empty()),
                now);
    }

    Optional<TurnStreamEvent.Data> latest(Connection connection, TurnId turnId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT PAYLOAD FROM CORE.EVENT WHERE TURN_ID = ? AND TOPIC = ?
                ORDER BY SEQUENCE DESC FETCH FIRST 1 ROW ONLY
                """)) {
            statement.setString(1, turnId.toString());
            statement.setString(2, TOPIC);
            try (var result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(
                                json.decode(new CanonicalPayload(result.getString(1)), TurnStreamEvent.Data.class))
                        : Optional.empty();
            }
        }
    }

    void append(Connection connection, TurnId turnId, TurnStreamEvent.Data data, Instant now) throws SQLException {
        events.append(connection, turns.threadId(connection, turnId), turnId, "core", TOPIC, json.encode(data), now);
    }

    void text(Connection connection, TurnId turnId, int number, String text, Instant now) throws SQLException {
        turns.lock(connection, turnId);
        TurnStreamEvent.Data prior =
                latest(connection, turnId).orElseThrow(() -> new PersistenceException("模型流缺少持久调用意图"));
        if (prior.call().isEmpty()
                || prior.call().orElseThrow().invocationNumber() != number
                || (prior.kind() != TurnStreamKind.STARTED && prior.kind() != TurnStreamKind.TEXT_DELTA)) {
            throw new PersistenceException("模型流身份已关闭或与当前调用不符");
        }
        long offset = prior.kind() == TurnStreamKind.TEXT_DELTA
                ? prior.offsetUtf16() + prior.text().length()
                : 0;
        for (int start = 0; start < text.length(); ) {
            int end = Math.min(text.length(), start + 2048);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            String fragment = text.substring(start, end);
            append(
                    connection,
                    turnId,
                    new TurnStreamEvent.Data(
                            TurnStreamKind.TEXT_DELTA,
                            prior.call(),
                            fragment,
                            offset,
                            Optional.empty(),
                            Optional.empty()),
                    now);
            offset += fragment.length();
            start = end;
        }
    }

    TurnStreamRpcContracts.Page page(Connection connection, TurnStreamRpcContracts.ListRequest request)
            throws SQLException {
        turns.find(connection, request.turnId()).orElseThrow(() -> PersistenceException.invalidRequest("Turn 不存在"));
        long after = decode(connection, request.turnId(), request.afterCursor());
        List<TurnStreamEvent> rows = new ArrayList<>();
        String previous = request.afterCursor();
        boolean more = false;
        int bytes = 0;
        try (var statement = connection.prepareStatement("""
                SELECT SEQUENCE, PAYLOAD FROM CORE.EVENT
                WHERE TURN_ID = ? AND TOPIC = ? AND SEQUENCE > ? ORDER BY SEQUENCE LIMIT ?
                """)) {
            statement.setString(1, request.turnId().toString());
            statement.setString(2, TOPIC);
            statement.setLong(3, after);
            statement.setInt(4, request.limit() + 1);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    String cursor = encode(request.turnId(), result.getLong(1));
                    var data = json.decode(new CanonicalPayload(result.getString(2)), TurnStreamEvent.Data.class);
                    var event = new TurnStreamEvent(request.turnId(), cursor, previous, data);
                    bytes += json.encode(event).json().getBytes(StandardCharsets.UTF_8).length;
                    if (rows.size() == request.limit() || bytes > 240 * 1024) {
                        more = true;
                        break;
                    }
                    rows.add(event);
                    previous = cursor;
                }
            }
        }
        return new TurnStreamRpcContracts.Page(rows, previous, more);
    }

    TurnStreamRpcContracts.Watermark watermark(Connection connection, TurnId turnId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT SEQUENCE, PAYLOAD FROM CORE.EVENT WHERE TURN_ID = ? AND TOPIC = ?
                ORDER BY SEQUENCE DESC FETCH FIRST 1 ROW ONLY
                """)) {
            statement.setString(1, turnId.toString());
            statement.setString(2, TOPIC);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return new TurnStreamRpcContracts.Watermark(TurnStreamRpcContracts.START, false, Optional.empty());
                }
                var data = json.decode(new CanonicalPayload(result.getString(2)), TurnStreamEvent.Data.class);
                boolean terminal = data.kind() == TurnStreamKind.TURN_FINISHED;
                return new TurnStreamRpcContracts.Watermark(
                        encode(turnId, result.getLong(1)), terminal, terminal ? data.itemSequence() : Optional.empty());
            }
        }
    }

    private long decode(Connection connection, TurnId turnId, String cursor) throws SQLException {
        if (TurnStreamRpcContracts.START.equals(cursor)) {
            return 0;
        }
        long sequence;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String prefix = "v1:" + turnId + ":";
            if (!decoded.startsWith(prefix)) {
                throw new IllegalArgumentException("wrong stream cursor scope");
            }
            sequence = Long.parseLong(decoded.substring(prefix.length()));
        } catch (IllegalArgumentException invalid) {
            throw PersistenceException.invalidRequest("STREAM_RESYNC_REQUIRED: 游标不适用于当前 Turn 或投影");
        }
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM CORE.EVENT WHERE TURN_ID = ? AND TOPIC = ? AND SEQUENCE = ?")) {
            statement.setString(1, turnId.toString());
            statement.setString(2, TOPIC);
            statement.setLong(3, sequence);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw PersistenceException.invalidRequest("STREAM_RESYNC_REQUIRED: 游标事件不存在");
                }
            }
        }
        return sequence;
    }

    private static String encode(TurnId turnId, long sequence) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(("v1:" + turnId + ":" + sequence).getBytes(StandardCharsets.UTF_8));
    }
}
