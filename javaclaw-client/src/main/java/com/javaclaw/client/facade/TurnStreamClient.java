package com.javaclaw.client.facade;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.javaclaw.api.TurnId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.NegotiatedCapabilities;
import com.javaclaw.protocol.TurnStreamRpcContracts;

/** 公开聊天流 facade。复用当前 RPC；传输断线由连接 owner 重建 SDK，再用原快照 resume。 不创建第二条连接，也不在 reader 通知回调中执行同步请求。 */
public final class TurnStreamClient implements AutoCloseable {
    private final RpcClientConnection connection;
    private final NegotiatedCapabilities capabilities;
    private final ConcurrentHashMap<String, TurnStreamSubscription> subscriptions = new ConcurrentHashMap<>();
    private final AutoCloseable notifications;
    private final AutoCloseable failures;
    private boolean closed;

    /**
     * 创建复用当前连接的流 facade。
     *
     * @param connection 当前 SDK 连接
     * @param capabilities 本连接已协商能力
     */
    public TurnStreamClient(RpcClientConnection connection, NegotiatedCapabilities capabilities) {
        this.connection = connection;
        this.capabilities = capabilities;
        CanonicalJson json = new CanonicalJson();
        notifications = connection.observe(notification -> {
            if (TurnStreamRpcContracts.EVENT.equals(notification.method())) {
                var event = json.decode(notification.params(), TurnStreamRpcContracts.Notification.class);
                var subscription = subscriptions.get(event.subscriptionId());
                if (subscription != null) {
                    subscription.offer(event, notification.params().json().length() * 3L);
                }
            }
        });
        failures = connection.onFailure(failure -> subscriptions.values().forEach(value -> value.failed(failure)));
    }

    /** @return 双方已协商公开流时为 true */
    public boolean available() {
        return capabilities.allows(TurnStreamRpcContracts.CAPABILITY);
    }

    /**
     * 从头订阅完整公开日志；发送订阅请求前登记接收器，回调允许早于 RPC 受理回执。
     *
     * @param turnId 目标 Turn
     * @param updates 后台顺序快照消费者，UI 应只安排绘制
     * @param onFailure 连接失败或不可恢复的 cursor 错误，持有句柄仍可读取最后快照
     * @return 可关闭订阅；终态最终 Item 归并后由调用者关闭
     */
    public TurnStreamSubscription subscribe(
            TurnId turnId, Consumer<TurnStreamSnapshot> updates, Consumer<Throwable> onFailure) {
        return resume(
                new TurnStreamSnapshot(
                        turnId, TurnStreamRpcContracts.START, List.of(), Optional.empty(), Optional.empty()),
                updates,
                onFailure);
    }

    /**
     * 用旧连接最后成功应用的完整快照恢复；新连接重新协商并产生新订阅 ID。
     *
     * @param snapshot Java 权威展示快照，不是 WebView 绘制位置
     * @param updates 后台顺序快照消费者
     * @param onFailure 失败回调
     * @return 新连接拥有的订阅
     */
    public synchronized TurnStreamSubscription resume(
            TurnStreamSnapshot snapshot, Consumer<TurnStreamSnapshot> updates, Consumer<Throwable> onFailure) {
        requireOpen();
        capabilities.require(TurnStreamRpcContracts.CAPABILITY);
        var subscription = new TurnStreamSubscription(this, snapshot, updates, onFailure);
        subscription.start();
        return subscription;
    }

    /**
     * 显式历史补拉或诊断，不用于周期性 token 查询。
     *
     * @param request cursor 和有界页大小
     * @return 原始有序事件页
     */
    public TurnStreamRpcContracts.Page list(TurnStreamRpcContracts.ListRequest request) {
        capabilities.require(TurnStreamRpcContracts.CAPABILITY);
        return connection.query(TurnStreamRpcContracts.LIST, request, TurnStreamRpcContracts.Page.class);
    }

    void subscribeWire(TurnStreamSubscription subscription, String id) {
        synchronized (this) {
            if (closed) {
                subscription.close();
                throw new IllegalStateException("聊天流 facade 已关闭");
            }
            subscriptions.put(id, subscription);
        }
        var snapshot = subscription.snapshot();
        connection.command(
                TurnStreamRpcContracts.SUBSCRIBE,
                new TurnStreamRpcContracts.Subscribe(id, snapshot.turnId(), snapshot.cursor()),
                new CommandOptions(id + ":subscribe", 0),
                TurnStreamRpcContracts.Receipt.class);
    }

    void unsubscribeWire(String id) {
        subscriptions.remove(id);
        connection.command(
                TurnStreamRpcContracts.UNSUBSCRIBE,
                new TurnStreamRpcContracts.Unsubscribe(id),
                new CommandOptions(id + ":unsubscribe", 0),
                TurnStreamRpcContracts.Released.class);
    }

    void forget(String id) {
        subscriptions.remove(id);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("聊天流 facade 已关闭");
        }
    }

    /** 释放当前连接的全部订阅和监听，保留各句柄的最后快照供连接 owner 恢复。 */
    @Override
    public synchronized void close() {
        closed = true;
        subscriptions.values().forEach(TurnStreamSubscription::close);
        subscriptions.clear();
        try {
            notifications.close();
            failures.close();
        } catch (Exception failure) {
            throw new IllegalStateException("关闭聊天流 facade 失败", failure);
        }
    }
}
