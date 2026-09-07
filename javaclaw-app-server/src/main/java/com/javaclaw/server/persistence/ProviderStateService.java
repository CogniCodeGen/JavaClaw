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
     * @param database data-v6 数据库
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

    /**
     * 只复用与本 Turn Prompt 完全相同的模型状态；Role 或项目约定变化时从完整 Item 重建窗口。
     *
     * @param threadId Thread
     * @param modelId 精确模型路由
     * @param promptDigest 当前冻结 Prompt 摘要
     * @return 可安全续接的状态；指令变化时为空
     */
    public Optional<StateSnapshot> latest(ThreadId threadId, String modelId, String promptDigest) {
        return execute(connection -> {
            var stored = states.latest(connection, threadId, modelId);
            if (stored.isEmpty()) {
                return Optional.empty();
            }
            var turn =
                    new TurnRepository().find(connection, stored.orElseThrow().turnId());
            return turn.filter(value -> value.promptManifestDigest().equals(promptDigest))
                    .map(ignored -> StateSnapshot.from(stored.orElseThrow()));
        });
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
