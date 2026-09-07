package com.javaclaw.server.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderCapabilities;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderReadiness;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderStatus;
import com.javaclaw.protocol.CanonicalJson;

/** Provider 不可变版本、生命周期与非计费本地检查服务。 */
public final class ProviderService {
    private final H2Transactions transactions;
    private final VersionedSettingsRepository versions = new VersionedSettingsRepository();
    private final IdempotencyRepository idempotency = new IdempotencyRepository();
    private final CredentialAvailabilityPort credentials;
    private final CanonicalJson json;
    private final Clock clock;
    private final Object mutationLock = new Object();
    private final List<ProviderMutationParticipant> mutationParticipants = new CopyOnWriteArrayList<>();

    /**
     * 创建 Provider 服务。
     *
     * @param database data-v6 数据库
     * @param credentials Provider Secret 的实时可用性边界
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     */
    public ProviderService(
            H2Database database, CredentialAvailabilityPort credentials, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @return 每个 Provider 的最新版本 */
    public List<ProviderEndpoint> listLatest() {
        return execute(
                connection -> versions.listLatest(connection, VersionedSettingsRepository.Table.PROVIDER).stream()
                        .map(this::decode)
                        .toList());
    }

    /**
     * 返回全部不可变历史版本，供 Model Registry 重建精确路由。
     *
     * @return 按 ID、revision 排序的历史
     */
    public List<ProviderEndpoint> listAllVersions() {
        return execute(connection -> versions.listAll(connection, VersionedSettingsRepository.Table.PROVIDER).stream()
                .map(this::decode)
                .toList());
    }

    /**
     * 读取精确 Provider 版本。
     *
     * @param id Provider 标识
     * @param revision 精确版本
     * @return Provider
     */
    public ProviderEndpoint require(String id, long revision) {
        return execute(connection -> versions.find(
                        connection, VersionedSettingsRepository.Table.PROVIDER, identifier(id), revision)
                .map(this::decode)
                .orElseThrow(() -> PersistenceException.invalidRequest("Provider 不存在")));
    }

    /**
     * 创建 Provider。
     *
     * @param identity expected revision 必须为 0
     * @param id 稳定标识
     * @param spec 完整配置
     * @param lifecycle 初始生命周期；允许建立尚未配置模型的 DISABLED 连接壳
     * @return 首个版本
     */
    public ProviderEndpoint create(
            CommandIdentity identity, String id, ProviderEndpointSpec spec, ProviderLifecycle lifecycle) {
        ProviderLifecycle checkedLifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        if (checkedLifecycle == ProviderLifecycle.ARCHIVED) {
            throw PersistenceException.invalidRequest("新建 Provider 只能为 ACTIVE 或 DISABLED");
        }
        return write(identity, identifier(id), Objects.requireNonNull(spec, "spec"), checkedLifecycle);
    }

    /**
     * 更新 Provider 并创建新版本。
     *
     * @param identity expected revision 必须匹配当前最新版本
     * @param id 稳定标识
     * @param spec 完整配置
     * @param lifecycle 新生命周期
     * @return 新版本
     */
    public ProviderEndpoint update(
            CommandIdentity identity, String id, ProviderEndpointSpec spec, ProviderLifecycle lifecycle) {
        if (Objects.requireNonNull(lifecycle, "lifecycle") == ProviderLifecycle.ARCHIVED) {
            throw PersistenceException.invalidRequest("请使用 provider/archive 归档 Provider");
        }
        return write(identity, identifier(id), Objects.requireNonNull(spec, "spec"), lifecycle);
    }

    /**
     * 归档 Provider；配置内容保留为新不可变版本。
     *
     * @param identity expected revision 必须匹配当前最新版本
     * @param id Provider 标识
     * @return 归档版本
     */
    public ProviderEndpoint archive(CommandIdentity identity, String id) {
        ProviderEndpoint current = requireLatest(id);
        return write(identity, current.id(), current.spec(), ProviderLifecycle.ARCHIVED);
    }

    /**
     * 仅检查本地配置，不发起任何网络或计费模型调用。
     *
     * @param reference Provider 与模型
     * @return 检查状态
     */
    public ProviderStatus probe(ProviderRef reference) {
        ProviderEndpoint endpoint = require(reference.endpointId(), reference.endpointRevision());
        ProviderEndpoint latest = requireLatest(reference.endpointId());
        ProviderCapabilities capabilities =
                capabilities(endpoint.spec().adapter(), declaredPurposes(endpoint, reference.model()));
        ProviderReadiness readiness = readiness(endpoint, latest, reference.model());
        Optional<String> detail =
                switch (readiness) {
                    case READY -> Optional.of("Provider 已由运行时解析");
                    case CREDENTIAL_UNVERIFIED -> Optional.of("CredentialRef 已配置，等待 Vault 验证");
                    case CREDENTIAL_REQUIRED -> Optional.of("需要先配置 CredentialRef");
                    case DISABLED -> Optional.of("Provider 已停用");
                    case ARCHIVED -> Optional.of("Provider 已归档");
                    case INVALID_CONFIGURATION -> Optional.of("模型不在 Provider 目录中");
                    case CREDENTIAL_UNAVAILABLE -> Optional.of("CredentialRef 当前不可用");
                };
        return new ProviderStatus(reference, readiness, capabilities, detail, Instant.now(clock));
    }

    /**
     * 校验精确 Provider 引用可用于一次新的业务绑定或执行。
     *
     * <p>精确版本决定配置内容，最新版本生命周期提供实时 kill switch；两者任一不是 ACTIVE 都会拒绝。
     *
     * @param reference 精确 Provider 模型引用
     * @param purpose 要求的模型用途
     * @return 精确 Provider 版本
     */
    public ProviderEndpoint requireAvailable(ProviderRef reference, ProviderModelPurpose purpose) {
        ProviderRef checked = Objects.requireNonNull(reference, "reference");
        ProviderModelPurpose checkedPurpose = Objects.requireNonNull(purpose, "purpose");
        ProviderEndpoint endpoint = require(checked.endpointId(), checked.endpointRevision());
        ProviderEndpoint latest = requireLatest(checked.endpointId());
        if (endpoint.lifecycle() != ProviderLifecycle.ACTIVE || latest.lifecycle() != ProviderLifecycle.ACTIVE) {
            throw PersistenceException.invalidRequest("Provider 当前不可用于新绑定或执行");
        }
        ProviderModelSpec model = endpoint.spec().models().stream()
                .filter(candidate -> candidate.modelId().equals(checked.model()))
                .findFirst()
                .orElseThrow(() -> PersistenceException.invalidRequest("模型不在 Provider 精确版本目录中"));
        if (!model.supports(checkedPurpose)) {
            throw PersistenceException.invalidRequest("Provider 模型不支持要求的用途");
        }
        requireCredentialAvailable(endpoint);
        return endpoint;
    }

    /**
     * 校验精确 Provider 版本可用于只读模型目录发现。
     *
     * <p>初次配置流程需要从 DISABLED 连接壳读取目录，因此这里不要求 ACTIVE；精确版本或最新版本已归档时仍立即拒绝。
     *
     * @param id Provider 稳定标识
     * @param revision 精确不可变版本
     * @return 经过生命周期校验的精确版本
     */
    public ProviderEndpoint requireDiscoverable(String id, long revision) {
        ProviderEndpoint endpoint = require(id, revision);
        ProviderEndpoint latest = requireLatest(id);
        if (endpoint.lifecycle() == ProviderLifecycle.ARCHIVED || latest.lifecycle() == ProviderLifecycle.ARCHIVED) {
            throw PersistenceException.invalidRequest("Provider 已归档，不能读取模型目录");
        }
        return endpoint;
    }

    /**
     * 注册 Provider 变更参与者。
     *
     * <p>参与者必须先完整构造候选运行时资源，数据库提交成功后再以不会失败的操作激活。该协议保证多个注册表不会互相覆盖，也不会让无效配置先写入 H2。
     *
     * @param participant 运行时变更参与者
     */
    public void participate(ProviderMutationParticipant participant) {
        mutationParticipants.add(Objects.requireNonNull(participant, "participant"));
    }

    private ProviderEndpoint write(
            CommandIdentity identity, String id, ProviderEndpointSpec spec, ProviderLifecycle lifecycle) {
        requireActiveConfiguration(spec, lifecycle);
        return coordinateSerialCommand(
                Objects.requireNonNull(identity, "identity"), () -> writeSerially(identity, id, spec, lifecycle));
    }

    private static void requireActiveConfiguration(ProviderEndpointSpec spec, ProviderLifecycle lifecycle) {
        if (lifecycle != ProviderLifecycle.ACTIVE) {
            return;
        }
        if (spec.models().isEmpty()) {
            throw PersistenceException.invalidRequest("启用 Provider 前必须至少配置一个模型");
        }
        if (spec.authentication() == ProviderAuthentication.API_KEY
                && spec.credential().isEmpty()) {
            throw PersistenceException.invalidRequest("启用 Provider 前必须先配置凭据");
        }
    }

    /** Provider 资源锁始终先于幂等键锁获取，避免普通配置写与凭据复合写形成锁顺序反转。 */
    <T> T coordinateSerialCommand(CommandIdentity identity, Supplier<T> work) {
        CommandIdentity checked = Objects.requireNonNull(identity, "identity");
        synchronized (mutationLock) {
            synchronized (CommandLocks.forKey(checked.idempotencyKey())) {
                return Objects.requireNonNull(work, "work").get();
            }
        }
    }

    private ProviderEndpoint writeSerially(
            CommandIdentity identity, String id, ProviderEndpointSpec spec, ProviderLifecycle lifecycle) {
        Optional<ProviderEndpoint> replay = replay(identity);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        Optional<VersionedSettingsRepository.StoredVersion> current = execute(
                connection -> versions.latest(connection, VersionedSettingsRepository.Table.PROVIDER, id, false));
        requireRevision(current, identity.expectedRevision());
        requireCredentialUnchanged(current, spec);
        Instant now = Instant.now(clock);
        Instant createdAt = current.map(VersionedSettingsRepository.StoredVersion::createdAt)
                .orElse(now);
        ProviderEndpoint candidate = new ProviderEndpoint(
                id, Math.addExact(identity.expectedRevision(), 1), lifecycle, spec, createdAt, now);
        return coordinatePreparedMutation(candidate, identity.expectedRevision(), () -> commit(identity, candidate));
    }

    /**
     * 在共享 Provider 写锁内完成候选预构造、数据库提交和无失败激活。
     *
     * <p>该窄事务协调点仅供同包复合服务使用；提交工作必须自行再次锁定并核对 H2 revision。
     */
    <T> T coordinatePreparedMutation(ProviderEndpoint candidate, long expectedRevision, Supplier<T> commit) {
        Objects.requireNonNull(candidate, "candidate");
        synchronized (mutationLock) {
            Optional<VersionedSettingsRepository.StoredVersion> current = execute(connection ->
                    versions.latest(connection, VersionedSettingsRepository.Table.PROVIDER, candidate.id(), false));
            requireRevision(current, expectedRevision);
            if (candidate.revision() != expectedRevision + 1) {
                throw PersistenceException.revisionConflict("Provider revision 已改变");
            }
            List<PreparedProviderChange> prepared = prepare(candidate);
            try {
                T committed = Objects.requireNonNull(commit, "commit").get();
                prepared.forEach(PreparedProviderChange::activate);
                return committed;
            } catch (RuntimeException failure) {
                discard(prepared, failure);
                throw failure;
            }
        }
    }

    ProviderEndpoint requireLatestForMutation(String id) {
        return requireLatest(id);
    }

    private Optional<ProviderEndpoint> replay(CommandIdentity identity) {
        return execute(connection ->
                idempotency.find(connection, identity.idempotencyKey()).map(stored -> recover(identity, stored)));
    }

    private ProviderEndpoint commit(CommandIdentity identity, ProviderEndpoint candidate) {
        return execute(connection -> {
            Optional<IdempotencyRepository.StoredCommand> replay =
                    idempotency.find(connection, identity.idempotencyKey());
            if (replay.isPresent()) {
                return recover(identity, replay.orElseThrow());
            }
            Optional<VersionedSettingsRepository.StoredVersion> current =
                    versions.latest(connection, VersionedSettingsRepository.Table.PROVIDER, candidate.id(), true);
            requireRevision(current, identity.expectedRevision());
            versions.insert(connection, VersionedSettingsRepository.Table.PROVIDER, stored(candidate));
            new ProviderContextRepository().inherit(connection, current.map(this::decode), candidate);
            idempotency.insert(connection, identity, json.encode(candidate), candidate.updatedAt());
            return candidate;
        });
    }

    private List<PreparedProviderChange> prepare(ProviderEndpoint candidate) {
        java.util.ArrayList<PreparedProviderChange> prepared = new java.util.ArrayList<>();
        try {
            for (ProviderMutationParticipant participant : mutationParticipants) {
                prepared.add(Objects.requireNonNull(participant.prepare(candidate), "prepared change"));
            }
            return List.copyOf(prepared);
        } catch (RuntimeException failure) {
            discard(prepared, failure);
            throw failure;
        }
    }

    private static void discard(List<PreparedProviderChange> prepared, RuntimeException original) {
        for (int index = prepared.size() - 1; index >= 0; index--) {
            try {
                prepared.get(index).close();
            } catch (RuntimeException closeFailure) {
                original.addSuppressed(closeFailure);
            }
        }
    }

    private ProviderEndpoint requireLatest(String id) {
        return execute(connection -> versions.latest(
                        connection, VersionedSettingsRepository.Table.PROVIDER, identifier(id), false)
                .map(this::decode)
                .orElseThrow(() -> PersistenceException.invalidRequest("Provider 不存在")));
    }

    private VersionedSettingsRepository.StoredVersion stored(ProviderEndpoint endpoint) {
        return new VersionedSettingsRepository.StoredVersion(
                endpoint.id(),
                endpoint.revision(),
                endpoint.lifecycle().name(),
                json.encode(endpoint.spec()),
                endpoint.createdAt(),
                endpoint.updatedAt());
    }

    private ProviderEndpoint decode(VersionedSettingsRepository.StoredVersion stored) {
        return new ProviderEndpoint(
                stored.id(),
                stored.revision(),
                ProviderLifecycle.valueOf(stored.lifecycle()),
                json.decode(stored.payload(), ProviderEndpointSpec.class),
                stored.createdAt(),
                stored.updatedAt());
    }

    private ProviderEndpoint recover(CommandIdentity identity, IdempotencyRepository.StoredCommand stored) {
        if (!stored.method().equals(identity.method())
                || !stored.requestDigest().equals(identity.requestDigest())) {
            throw PersistenceException.idempotencyConflict("幂等键已被不同命令使用");
        }
        return json.decode(stored.response(), ProviderEndpoint.class);
    }

    private static void requireRevision(
            Optional<VersionedSettingsRepository.StoredVersion> current, long expectedRevision) {
        if (current.isEmpty() && expectedRevision == 0) {
            return;
        }
        if (current.isEmpty() || current.orElseThrow().revision() != expectedRevision) {
            throw PersistenceException.revisionConflict("Provider revision 已改变");
        }
    }

    private void requireCredentialUnchanged(
            Optional<VersionedSettingsRepository.StoredVersion> current, ProviderEndpointSpec next) {
        Optional<CredentialRef> before =
                current.map(this::decode).flatMap(endpoint -> endpoint.spec().credential());
        if (!before.equals(next.credential())) {
            throw PersistenceException.invalidRequest("Provider CredentialRef 只能通过 provider/credential 复合命令变更");
        }
    }

    private ProviderReadiness readiness(ProviderEndpoint endpoint, ProviderEndpoint latest, String model) {
        if (endpoint.lifecycle() == ProviderLifecycle.ARCHIVED || latest.lifecycle() == ProviderLifecycle.ARCHIVED) {
            return ProviderReadiness.ARCHIVED;
        }
        if (endpoint.lifecycle() == ProviderLifecycle.DISABLED || latest.lifecycle() == ProviderLifecycle.DISABLED) {
            return ProviderReadiness.DISABLED;
        }
        if (endpoint.spec().models().stream()
                .noneMatch(candidate -> candidate.modelId().equals(model))) {
            return ProviderReadiness.INVALID_CONFIGURATION;
        }
        if (endpoint.spec().authentication() == ProviderAuthentication.NONE) {
            return ProviderReadiness.READY;
        }
        Optional<CredentialRef> credential = endpoint.spec().credential();
        if (credential.isEmpty()) {
            return ProviderReadiness.CREDENTIAL_REQUIRED;
        }
        return credentials.available(credential.orElseThrow())
                ? ProviderReadiness.READY
                : ProviderReadiness.CREDENTIAL_UNAVAILABLE;
    }

    private void requireCredentialAvailable(ProviderEndpoint endpoint) {
        if (endpoint.spec().authentication() == ProviderAuthentication.NONE) {
            return;
        }
        CredentialRef credential = endpoint.spec()
                .credential()
                .orElseThrow(() -> PersistenceException.invalidRequest("Provider 缺少 CredentialRef"));
        if (!credentials.available(credential)) {
            throw PersistenceException.invalidRequest("Provider CredentialRef 当前不可用");
        }
    }

    /**
     * 把适配器类型映射为平台能力；实际 Adapter 创建时必须再次核对。
     *
     * @param adapter 适配器
     * @param purposes 逐模型声明用途的并集
     * @return 能力
     */
    public static ProviderCapabilities capabilities(
            ProviderAdapter adapter, java.util.Set<ProviderModelPurpose> purposes) {
        boolean chat = purposes.contains(ProviderModelPurpose.CHAT);
        return switch (Objects.requireNonNull(adapter, "adapter")) {
            case OPENAI_COMPATIBLE, ANTHROPIC, GOOGLE_GENAI ->
                new ProviderCapabilities(purposes, chat, chat, chat, chat, false, false, false);
            case OPENAI_RESPONSES -> new ProviderCapabilities(purposes, chat, chat, chat, chat, chat, chat, chat);
        };
    }

    private static java.util.Set<ProviderModelPurpose> declaredPurposes(ProviderEndpoint endpoint, String modelId) {
        return endpoint.spec().models().stream()
                .filter(model -> model.modelId().equals(modelId))
                .findFirst()
                .map(ProviderModelSpec::purposes)
                .orElseGet(java.util.Set::of);
    }

    private static String identifier(String value) {
        String id = Objects.requireNonNull(value, "id").strip();
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException("provider id contains unsupported characters");
        }
        return id;
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Provider 事务失败", failure);
        }
    }

    /** 在 Provider 提交前构造候选运行时资源的参与者。 */
    @FunctionalInterface
    public interface ProviderMutationParticipant {
        /**
         * 构造候选资源；失败必须抛出异常并由调用方回滚配置提交。
         *
         * @param candidate 即将提交的完整 Provider 版本
         * @return 待激活变更
         */
        PreparedProviderChange prepare(ProviderEndpoint candidate);
    }

    /** 已完整构造、尚未对运行时可见的 Provider 变更。 */
    public interface PreparedProviderChange extends AutoCloseable {
        /**
         * 在 H2 提交成功后使候选资源可见。
         *
         * <p>实现只能执行引用交换和旧资源退役，不得再做校验、I/O 或其他可能失败的工作。
         */
        void activate();

        /** 未提交时释放候选资源；实现必须允许调用一次。 */
        @Override
        void close();
    }
}
