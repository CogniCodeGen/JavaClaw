package com.javaclaw.server.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelStreamEvent;

/** 将模型增量同步写入 Core Event 的有界背压 sink。 */
public final class H2ModelEventSink implements ModelEventSink {
    private final H2Transactions transactions;
    private final TurnRepository turns = new TurnRepository();
    private final EventRepository events = new EventRepository();
    private final CanonicalJson json;
    private final Clock clock;

    /**
     * 创建事件 sink。
     *
     * <p>每次 publish 在返回前完成一次本地事务，因此 Provider 产生速度不会超过持久消费者；不使用无界内存队列。
     *
     * @param database data-v6 数据库
     * @param json 共享 JSON codec
     * @param clock 平台时钟
     */
    public H2ModelEventSink(H2Database database, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void publish(TurnId turnId, ModelStreamEvent event, CancellationToken cancellation) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(cancellation, "cancellation").throwIfCancelled();
        execute(connection -> {
            ThreadId threadId = turns.threadId(connection, turnId);
            events.append(connection, threadId, turnId, "model", topic(event), json.encode(event), now());
            return null;
        });
        cancellation.throwIfCancelled();
    }

    private static String topic(ModelStreamEvent event) {
        return switch (event) {
            case ModelStreamEvent.TextDelta ignored -> "turn.stream.text";
            case ModelStreamEvent.ReasoningSummaryDelta ignored -> "turn.stream.reasoning-summary";
            case ModelStreamEvent.ToolCallReady ignored -> "turn.stream.tool-call";
            case ModelStreamEvent.Usage ignored -> "turn.stream.usage";
        };
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("模型事件持久化失败", failure);
        }
    }
}
