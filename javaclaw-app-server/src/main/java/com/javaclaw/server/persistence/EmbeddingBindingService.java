package com.javaclaw.server.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import com.javaclaw.api.EmbeddingBinding;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.protocol.CanonicalJson;

/** 管理本地安装唯一、精确版本的 Embedding 模型绑定。 */
public final class EmbeddingBindingService {
    private final H2Transactions transactions;
    private final EmbeddingBindingRepository bindings = new EmbeddingBindingRepository();
    private final IdempotencyRepository idempotency = new IdempotencyRepository();
    private final ProviderService providers;
    private final CanonicalJson json;
    private final Clock clock;
    private final Object mutationLock = new Object();
    private final List<BindingMutationParticipant> participants = new CopyOnWriteArrayList<>();

    /**
     * 创建安装级 Embedding 绑定服务。
     *
     * @param database data-v5 数据库
     * @param providers Provider 精确版本服务
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     */
    public EmbeddingBindingService(H2Database database, ProviderService providers, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.providers = Objects.requireNonNull(providers, "providers");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @return 当前安装级绑定；尚未配置时为空 */
    public Optional<EmbeddingBinding> find() {
        return execute(connection -> bindings.find(connection, false));
    }

    /**
     * 创建或替换安装级绑定。
     *
     * <p>提交前会同时验证精确 Provider 版本和该 Provider 的最新生命周期，并预构造运行时 Adapter。提交成功后才原子切换运行时引用。
     *
     * @param identity expected revision 为绑定当前 revision；首次配置为 0
     * @param provider 精确 Embedding 模型引用
     * @return 已提交绑定
     */
    public EmbeddingBinding update(CommandIdentity identity, ProviderRef provider) {
        CommandIdentity checkedIdentity = Objects.requireNonNull(identity, "identity");
        ProviderRef checkedProvider = Objects.requireNonNull(provider, "provider");
        synchronized (mutationLock) {
            synchronized (CommandLocks.forKey(checkedIdentity.idempotencyKey())) {
                Optional<EmbeddingBinding> replay = replay(checkedIdentity);
                if (replay.isPresent()) {
                    return replay.orElseThrow();
                }
                providers.requireAvailable(checkedProvider, ProviderModelPurpose.EMBEDDING);
                Optional<EmbeddingBinding> current = execute(connection -> bindings.find(connection, false));
                requireRevision(current, checkedIdentity.expectedRevision());
                EmbeddingBinding candidate = new EmbeddingBinding(
                        checkedProvider, Math.addExact(checkedIdentity.expectedRevision(), 1), Instant.now(clock));
                return commitPrepared(checkedIdentity, current, candidate);
            }
        }
    }

    /**
     * 注册运行时绑定变更参与者。
     *
     * @param participant 在数据库提交前完整预构造候选资源的参与者
     */
    public void participate(BindingMutationParticipant participant) {
        participants.add(Objects.requireNonNull(participant, "participant"));
    }

    private EmbeddingBinding commitPrepared(
            CommandIdentity identity, Optional<EmbeddingBinding> current, EmbeddingBinding candidate) {
        List<PreparedBindingChange> prepared = prepare(candidate);
        try {
            EmbeddingBinding committed = execute(connection -> {
                Optional<EmbeddingBinding> locked = bindings.find(connection, true);
                requireRevision(locked, identity.expectedRevision());
                if (current.isEmpty()) {
                    bindings.insert(connection, candidate);
                } else {
                    bindings.update(connection, candidate, identity.expectedRevision());
                }
                idempotency.insert(connection, identity, json.encode(candidate), candidate.updatedAt());
                return candidate;
            });
            prepared.forEach(PreparedBindingChange::activate);
            return committed;
        } catch (RuntimeException failure) {
            discard(prepared, failure);
            throw failure;
        }
    }

    private List<PreparedBindingChange> prepare(EmbeddingBinding candidate) {
        List<PreparedBindingChange> prepared = new ArrayList<>();
        try {
            for (BindingMutationParticipant participant : participants) {
                prepared.add(Objects.requireNonNull(participant.prepare(candidate), "prepared binding change"));
            }
            return List.copyOf(prepared);
        } catch (RuntimeException failure) {
            discard(prepared, failure);
            throw failure;
        }
    }

    private Optional<EmbeddingBinding> replay(CommandIdentity identity) {
        return execute(connection ->
                idempotency.find(connection, identity.idempotencyKey()).map(stored -> recover(identity, stored)));
    }

    private EmbeddingBinding recover(CommandIdentity identity, IdempotencyRepository.StoredCommand stored) {
        if (!stored.method().equals(identity.method())
                || !stored.requestDigest().equals(identity.requestDigest())) {
            throw PersistenceException.idempotencyConflict("幂等键已被不同命令使用");
        }
        return json.decode(stored.response(), EmbeddingBinding.class);
    }

    private static void requireRevision(Optional<EmbeddingBinding> current, long expectedRevision) {
        if (current.isEmpty() && expectedRevision == 0) {
            return;
        }
        if (current.isEmpty() || current.orElseThrow().revision() != expectedRevision) {
            throw PersistenceException.revisionConflict("Embedding binding revision 已改变");
        }
    }

    private static void discard(List<PreparedBindingChange> prepared, RuntimeException original) {
        for (int index = prepared.size() - 1; index >= 0; index--) {
            try {
                prepared.get(index).close();
            } catch (RuntimeException closeFailure) {
                original.addSuppressed(closeFailure);
            }
        }
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Embedding binding 事务失败", failure);
        }
    }

    /** 安装级绑定提交前的运行时预构造边界。 */
    @FunctionalInterface
    public interface BindingMutationParticipant {
        /**
         * 预构造候选绑定对应的完整运行时资源。
         *
         * @param candidate 即将提交的绑定
         * @return 待激活资源
         */
        PreparedBindingChange prepare(EmbeddingBinding candidate);
    }

    /** 已完成构造、尚未对运行时可见的绑定变更。 */
    public interface PreparedBindingChange extends AutoCloseable {
        /** 数据库提交成功后执行不可失败的引用交换。 */
        void activate();

        /** 数据库未提交时释放候选资源。 */
        @Override
        void close();
    }
}
