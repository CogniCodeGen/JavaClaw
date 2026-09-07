package com.javaclaw.server.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import com.javaclaw.api.ModelContextLimits;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.protocol.CanonicalJson;

/** 模型容量的版本化管理；复用 Provider 资源锁、幂等键锁和候选 Adapter 激活事务。 */
public final class ProviderContextService {
    private final H2Transactions transactions;
    private final ProviderService providers;
    private final CanonicalJson json;
    private final Clock clock;
    private final ProviderContextRepository contexts = new ProviderContextRepository();
    private final VersionedSettingsRepository versions = new VersionedSettingsRepository();
    private final IdempotencyRepository commands = new IdempotencyRepository();

    /**
     * 创建模型容量管理边界。
     *
     * @param database 数据库
     * @param providers 权威 Provider 服务
     * @param json 规范 codec
     * @param clock 平台时钟
     */
    public ProviderContextService(H2Database database, ProviderService providers, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(database);
        this.providers = providers;
        this.json = json;
        this.clock = clock;
    }

    /**
     * 查询精确版本；不存在的模型不是 unknown 容量。
     *
     * @param provider 精确模型
     * @return 声明的容量或 unknown
     */
    public ModelContextLimits read(ProviderRef provider) {
        requireModel(provider);
        return execute(connection -> contexts.read(connection, provider));
    }

    /**
     * 用新 Provider revision 保存容量，旧 revision 与所有冻结 Turn 不变。
     *
     * @param identity expected revision 必须匹配引用及当前最新 Provider
     * @param limits 基于旧 revision 的新容量
     * @return 使用新 revision 的元数据；重试返回首次结果
     */
    public ModelContextLimits update(CommandIdentity identity, ModelContextLimits limits) {
        return providers.coordinateSerialCommand(identity, () -> updateSerially(identity, limits));
    }

    private ModelContextLimits updateSerially(CommandIdentity identity, ModelContextLimits limits) {
        Optional<ModelContextLimits> replay = execute(connection ->
                commands.find(connection, identity.idempotencyKey()).map(stored -> recover(identity, stored)));
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        if (identity.expectedRevision() != limits.provider().endpointRevision()) {
            throw PersistenceException.revisionConflict("容量更新必须引用当前 Provider revision");
        }
        ProviderEndpoint previous = requireModel(limits.provider());
        if (previous.lifecycle() == com.javaclaw.api.ProviderLifecycle.ARCHIVED) {
            throw PersistenceException.invalidRequest("已归档 Provider 不能更新模型容量");
        }
        Instant now = clock.instant();
        ProviderEndpoint next = new ProviderEndpoint(
                previous.id(),
                previous.revision() + 1,
                previous.lifecycle(),
                previous.spec(),
                previous.createdAt(),
                now);
        ModelContextLimits result = new ModelContextLimits(
                new ProviderRef(next.id(), next.revision(), limits.provider().model()),
                limits.contextWindowTokens(),
                limits.maximumOutputTokens());
        return providers.coordinatePreparedMutation(
                next, identity.expectedRevision(), () -> commit(identity, previous, next, result));
    }

    private ModelContextLimits commit(
            CommandIdentity identity, ProviderEndpoint previous, ProviderEndpoint next, ModelContextLimits result) {
        return execute(connection -> {
            var current = versions.latest(connection, VersionedSettingsRepository.Table.PROVIDER, next.id(), true)
                    .orElseThrow(() -> PersistenceException.invalidRequest("Provider 不存在"));
            if (current.revision() != identity.expectedRevision()) {
                throw PersistenceException.revisionConflict("Provider revision 已改变");
            }
            versions.insert(
                    connection,
                    VersionedSettingsRepository.Table.PROVIDER,
                    new VersionedSettingsRepository.StoredVersion(
                            next.id(),
                            next.revision(),
                            next.lifecycle().name(),
                            json.encode(next.spec()),
                            next.createdAt(),
                            next.updatedAt()));
            contexts.inherit(connection, Optional.of(previous), next);
            try (var statement = connection.prepareStatement(
                    "DELETE FROM CORE.PROVIDER_MODEL_CONTEXT WHERE PROVIDER_ID = ? AND PROVIDER_REVISION = ? AND MODEL = ?")) {
                statement.setString(1, next.id());
                statement.setLong(2, next.revision());
                statement.setString(3, result.provider().model());
                statement.executeUpdate();
            }
            contexts.insert(connection, result);
            commands.insert(connection, identity, json.encode(result), next.updatedAt());
            return result;
        });
    }

    private ProviderEndpoint requireModel(ProviderRef provider) {
        ProviderEndpoint endpoint = providers.require(provider.endpointId(), provider.endpointRevision());
        if (endpoint.spec().models().stream().noneMatch(model -> model.modelId().equals(provider.model()))) {
            throw PersistenceException.invalidRequest("模型不在精确 Provider revision 中");
        }
        return endpoint;
    }

    private ModelContextLimits recover(CommandIdentity identity, IdempotencyRepository.StoredCommand stored) {
        if (!stored.method().equals(identity.method())
                || !stored.requestDigest().equals(identity.requestDigest())) {
            throw PersistenceException.idempotencyConflict("幂等键已被其他容量更新使用");
        }
        return json.decode(stored.response(), ModelContextLimits.class);
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Provider 容量事务失败", failure);
        }
    }
}
