package com.javaclaw.server.rpc;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.RpcConnection;
import com.javaclaw.protocol.TurnStreamRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.persistence.TurnStreamService;

/** 仅当前连接拥有的订阅控制，不写领域 revision/outbox；关闭连接销毁全部幂等回执。 */
final class TurnStreamSession implements AutoCloseable {
    private static final int MAXIMUM_SUBSCRIPTIONS = 16;
    private static final int MAXIMUM_MEMOS = 1024;
    private final TurnStreamService streams;
    private final RpcConnection connection;
    private final CanonicalJson json;
    private final Map<String, Active> active = new HashMap<>();
    private final Set<String> closedIds = new HashSet<>();
    private final Map<Key, Memo> memos = new HashMap<>();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "turn-stream-deadlines");
        thread.setDaemon(true);
        return thread;
    });

    TurnStreamSession(TurnStreamService streams, RpcConnection connection, CanonicalJson json) {
        this.streams = streams;
        this.connection = connection;
        this.json = json;
    }

    CanonicalPayload handle(String method, CanonicalPayload params) {
        if (TurnStreamRpcContracts.ITEM_HISTORY.equals(method)) {
            return json.encode(streams.history(json.decode(params, TurnStreamRpcContracts.ItemHistoryRequest.class)));
        }
        if (TurnStreamRpcContracts.LIST.equals(method)) {
            return json.encode(streams.list(json.decode(params, TurnStreamRpcContracts.ListRequest.class)));
        }
        WriteCommand command = json.decode(params, WriteCommand.class);
        if (command.expectedRevision() != 0) {
            throw PersistenceException.invalidRequest("连接控制 expectedRevision 必须为 0");
        }
        Key key = new Key(method, command.idempotencyKey());
        String digest = json.encode(command).sha256();
        Memo prior = memos.get(key);
        if (prior != null) {
            if (!prior.digest().equals(digest)) {
                throw PersistenceException.idempotencyConflict("连接幂等键已绑定不同请求");
            }
            return prior.receipt();
        }
        if (memos.size() >= MAXIMUM_MEMOS || closedIds.size() >= MAXIMUM_MEMOS) {
            throw PersistenceException.invalidRequest("STREAM_RECONNECT_REQUIRED: 连接幂等容量已满");
        }
        CanonicalPayload receipt = TurnStreamRpcContracts.SUBSCRIBE.equals(method)
                ? subscribe(json.decode(command.payload(), TurnStreamRpcContracts.Subscribe.class))
                : unsubscribe(json.decode(command.payload(), TurnStreamRpcContracts.Unsubscribe.class));
        memos.put(key, new Memo(digest, receipt));
        return receipt;
    }

    private CanonicalPayload subscribe(TurnStreamRpcContracts.Subscribe request) {
        if (closedIds.contains(request.subscriptionId())) {
            throw PersistenceException.invalidRequest("已关闭的 subscriptionId 不得重新激活");
        }
        Active prior = active.get(request.subscriptionId());
        if (prior != null) {
            if (!prior.request().equals(request)) {
                throw PersistenceException.idempotencyConflict("subscriptionId 已绑定不同参数");
            }
            return prior.receipt();
        }
        if (active.size() >= MAXIMUM_SUBSCRIPTIONS) {
            throw PersistenceException.invalidRequest("连接最多允许 16 个活动聊天订阅");
        }
        streams.list(new TurnStreamRpcContracts.ListRequest(request.turnId(), request.afterCursor(), 1));
        var subscription = new TurnStreamSubscription(request, streams, connection, json, deadlines);
        var receipt = json.encode(
                new TurnStreamRpcContracts.Receipt(request.subscriptionId(), request.turnId(), request.afterCursor()));
        active.put(request.subscriptionId(), new Active(request, receipt, subscription));
        subscription.start();
        return receipt;
    }

    private CanonicalPayload unsubscribe(TurnStreamRpcContracts.Unsubscribe request) {
        Active subscription = active.remove(request.subscriptionId());
        closedIds.add(request.subscriptionId());
        if (subscription != null) {
            subscription.subscription().close();
        }
        return json.encode(new TurnStreamRpcContracts.Released(request.subscriptionId()));
    }

    @Override
    public void close() {
        active.values().forEach(value -> value.subscription().close());
        active.clear();
        closedIds.clear();
        memos.clear();
        deadlines.shutdownNow();
    }

    private record Active(
            TurnStreamRpcContracts.Subscribe request, CanonicalPayload receipt, TurnStreamSubscription subscription) {}

    private record Key(String method, String idempotencyKey) {}

    private record Memo(String digest, CanonicalPayload receipt) {}
}
