package com.javaclaw.server.persistence;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.javaclaw.api.TurnId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.TurnStreamRpcContracts;

/** 持久流的有界读取与提交唤醒；监听器只安排 drain，不携带正文或第二份事件日志。 */
public final class TurnStreamService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TurnStreamService.class);
    private final H2Transactions transactions;
    private final TurnStreamRepository repository;
    private final ConcurrentHashMap<TurnId, CopyOnWriteArrayList<Runnable>> listeners = new ConcurrentHashMap<>();

    /**
     * 创建组合根共享的流读取入口。
     *
     * @param database 唯一 H2 数据库
     * @param json 共享 codec
     */
    public TurnStreamService(H2Database database, CanonicalJson json) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        repository = new TurnStreamRepository(Objects.requireNonNull(json, "json"));
    }

    /**
     * 读取已提交事件，校验 cursor 所属 Turn 和公开投影。
     *
     * @param request 有界查询
     * @return 保留每条身份的事件页
     */
    public TurnStreamRpcContracts.Page list(TurnStreamRpcContracts.ListRequest request) {
        return execute(connection -> repository.page(connection, request));
    }

    /**
     * 从 Thread 末端读取有界历史窗口。
     *
     * @param request 排他位置和页大小
     * @return 升序展示页与权威末端水位
     */
    public com.javaclaw.api.ItemHistoryResult history(TurnStreamRpcContracts.ItemHistoryRequest request) {
        return execute(connection -> new ItemRepository(new TurnRepository()).history(connection, request));
    }

    /**
     * 捕获持久尾水位；发送方必须先排完截至该位置的事件。
     *
     * @param turnId Turn
     * @return 捕获水位
     */
    public TurnStreamRpcContracts.Watermark watermark(TurnId turnId) {
        return execute(connection -> repository.watermark(connection, turnId));
    }

    /**
     * 在首次读取日志前登记唤醒，关闭句柄释放监听器。
     *
     * @param turnId Turn
     * @param wakeup 只做非阻塞唤醒的回调
     * @return 连接拥有的释放句柄
     */
    public AutoCloseable listen(TurnId turnId, Runnable wakeup) {
        Objects.requireNonNull(wakeup, "wakeup");
        listeners.compute(turnId, (ignored, current) -> {
            var values = current == null ? new CopyOnWriteArrayList<Runnable>() : current;
            values.add(wakeup);
            return values;
        });
        return () -> listeners.computeIfPresent(turnId, (ignored, values) -> {
            values.remove(wakeup);
            return values.isEmpty() ? null : values;
        });
    }

    /**
     * 事务返回后标记有新记录；通知失败不能逆转已经提交的业务事务。
     *
     * @param turnId 已提交 Turn
     */
    public void committed(TurnId turnId) {
        var callbacks = listeners.get(turnId);
        if (callbacks != null) {
            for (Runnable callback : callbacks) {
                try {
                    callback.run();
                } catch (RuntimeException failure) {
                    LOGGER.warn("聊天流提交唤醒失败，订阅将通过持久水位恢复", failure);
                }
            }
        }
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("读取聊天流失败", failure);
        }
    }
}
