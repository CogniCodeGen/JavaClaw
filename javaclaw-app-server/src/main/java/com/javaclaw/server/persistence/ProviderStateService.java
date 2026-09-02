package com.javaclaw.server.persistence;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ThreadId;
import com.javaclaw.runtime.ProviderState;

/** 为上下文组装提供只读的最新 Provider opaque state。 */
public final class ProviderStateService {
    private final H2Transactions transactions;
    private final ProviderStateRepository states = new ProviderStateRepository();

    /**
     * 创建查询服务。
     *
     * @param database data-v5 数据库
     */
    public ProviderStateService(H2Database database) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
    }

    /**
     * 读取 Thread 对指定模型端点的最新状态。
     *
     * @param threadId Thread
     * @param modelId 模型端点
     * @return 完整性已校验的状态与覆盖位置
     */
    public Optional<StateSnapshot> latest(ThreadId threadId, String modelId) {
        return execute(
                connection -> states.latest(connection, threadId, modelId).map(StateSnapshot::from));
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Provider state 查询失败", failure);
        }
    }

    /**
     * Provider state 及其覆盖的 Thread 位置。
     *
     * @param state opaque state
     * @param throughSequence state 已包含的最后 Item sequence
     * @param estimatedInputTokens Provider 上次报告的输入 token
     */
    public record StateSnapshot(ProviderState state, long throughSequence, long estimatedInputTokens) {
        /** 校验快照。 */
        public StateSnapshot {
            Objects.requireNonNull(state, "state");
            if (throughSequence < 1 || estimatedInputTokens < 0) {
                throw new IllegalArgumentException("invalid provider state position");
            }
        }

        private static StateSnapshot from(ProviderStateRepository.StoredProviderState stored) {
            return new StateSnapshot(stored.state(), stored.throughSequence(), stored.estimatedInputTokens());
        }
    }
}
