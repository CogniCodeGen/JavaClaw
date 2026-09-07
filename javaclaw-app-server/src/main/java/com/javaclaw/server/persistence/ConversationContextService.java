package com.javaclaw.server.persistence;

import java.util.Optional;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.runtime.ModelContextPolicy;

/** 服务端上下文快照查询；不向客户端暴露 opaque 正文。 */
public final class ConversationContextService {
    private final H2Transactions transactions;
    private final TurnContextRepository contexts;

    /**
     * 创建上下文查询边界。
     *
     * @param database 当前 App Server 数据库
     * @param json 规范 codec
     */
    public ConversationContextService(H2Database database, CanonicalJson json) {
        transactions = new H2Transactions(database);
        contexts = new TurnContextRepository(json);
    }

    /**
     * 读取冻结政策；旧 Turn 第一次执行时原子物化缺失政策。
     *
     * @param turnId 已持久 Turn
     * @return 精确版本政策
     */
    public ModelContextPolicy policy(TurnId turnId) {
        return execute(connection -> {
            // 旧 Turn 首次物化与生命周期使用同一行锁，不能并发插入两个冻结政策。
            try (var lock = connection.prepareStatement("SELECT ID FROM CORE.AGENT_TURN WHERE ID = ? FOR UPDATE")) {
                lock.setString(1, turnId.toString());
                try (var rows = lock.executeQuery()) {
                    if (!rows.next()) {
                        throw new PersistenceException("Turn 不存在");
                    }
                }
            }
            AgentTurn turn = new TurnRepository()
                    .find(connection, turnId)
                    .orElseThrow(() -> new PersistenceException("Turn 不存在"));
            return contexts.freeze(connection, turnId, turn.provider());
        });
    }

    /**
     * 读取匹配指令与路由的最新压缩窗口。
     *
     * @param threadId Thread
     * @param provider 精确模型
     * @param promptDigest 冻结指令摘要
     * @return 已提交窗口；没有匹配记录时为空
     */
    public Optional<TurnContextSnapshot> latest(ThreadId threadId, ProviderRef provider, String promptDigest) {
        return execute(connection -> contexts.latest(connection, threadId, provider, promptDigest));
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Turn 上下文查询失败", failure);
        }
    }
}
