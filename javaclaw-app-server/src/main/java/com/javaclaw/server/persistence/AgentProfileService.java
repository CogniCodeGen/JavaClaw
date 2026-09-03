package com.javaclaw.server.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.ProfileLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.protocol.CanonicalJson;

/** Agent Profile 不可变版本、引用校验与归档服务。 */
public final class AgentProfileService {
    private final H2Transactions transactions;
    private final VersionedSettingsRepository versions = new VersionedSettingsRepository();
    private final IdempotencyRepository idempotency = new IdempotencyRepository();
    private final ProviderService providers;
    private final PermissionProfileService permissions;
    private final CanonicalJson json;
    private final Clock clock;

    /**
     * 创建 Profile 服务。
     *
     * @param database data-v5 数据库
     * @param providers Provider 精确版本服务
     * @param permissions PermissionProfile 精确版本服务
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     */
    public AgentProfileService(
            H2Database database,
            ProviderService providers,
            PermissionProfileService permissions,
            CanonicalJson json,
            Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.providers = Objects.requireNonNull(providers, "providers");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @return 每个 Profile 的最新版本 */
    public List<AgentProfile> listLatest() {
        return execute(connection -> versions.listLatest(connection, VersionedSettingsRepository.Table.PROFILE).stream()
                .map(this::decode)
                .toList());
    }

    /**
     * 读取精确 Profile 版本。
     *
     * @param id Profile 标识
     * @param revision 精确版本
     * @return Profile
     */
    public AgentProfile require(String id, long revision) {
        return execute(connection -> versions.find(
                        connection, VersionedSettingsRepository.Table.PROFILE, identifier(id), revision)
                .map(this::decode)
                .orElseThrow(() -> PersistenceException.invalidRequest("Agent Profile 不存在")));
    }

    /**
     * 读取可用于新绑定或新 Turn 的精确 Profile。
     *
     * <p>精确版本决定冻结配置，最新版本生命周期决定该 Profile 是否仍允许开始新工作。
     *
     * @param id Profile 标识
     * @param revision 精确版本
     * @return 当前可用的精确 Profile
     */
    public AgentProfile requireAvailable(String id, long revision) {
        AgentProfile profile = require(id, revision);
        AgentProfile latest = requireLatest(id);
        if (profile.lifecycle() != ProfileLifecycle.ACTIVE || latest.lifecycle() != ProfileLifecycle.ACTIVE) {
            throw PersistenceException.invalidRequest("Agent Profile 当前不可用于新绑定或新 Turn");
        }
        providers.requireAvailable(profile.spec().provider(), ProviderModelPurpose.CHAT);
        return profile;
    }

    /**
     * 创建 Agent Profile。
     *
     * @param identity expected revision 必须为 0
     * @param id 稳定标识
     * @param spec 完整配置
     * @return 首个版本
     */
    public AgentProfile create(CommandIdentity identity, String id, AgentProfileSpec spec) {
        return write(identity, identifier(id), Objects.requireNonNull(spec, "spec"), ProfileLifecycle.ACTIVE);
    }

    /**
     * 更新 Agent Profile 并创建新版本。
     *
     * @param identity expected revision 必须匹配当前最新版本
     * @param id 稳定标识
     * @param spec 完整配置
     * @param lifecycle 新生命周期
     * @return 新版本
     */
    public AgentProfile update(CommandIdentity identity, String id, AgentProfileSpec spec, ProfileLifecycle lifecycle) {
        if (Objects.requireNonNull(lifecycle, "lifecycle") == ProfileLifecycle.ARCHIVED) {
            throw PersistenceException.invalidRequest("请使用 profile/archive 归档 Agent Profile");
        }
        return write(identity, identifier(id), Objects.requireNonNull(spec, "spec"), lifecycle);
    }

    /**
     * 归档 Agent Profile；历史 Turn 仍可读取旧版本。
     *
     * @param identity expected revision 必须匹配当前最新版本
     * @param id Profile 标识
     * @return 归档版本
     */
    public AgentProfile archive(CommandIdentity identity, String id) {
        AgentProfile current = requireLatest(id);
        return write(identity, current.id(), current.spec(), ProfileLifecycle.ARCHIVED);
    }

    private AgentProfile write(CommandIdentity identity, String id, AgentProfileSpec spec, ProfileLifecycle lifecycle) {
        validateReferences(spec);
        Objects.requireNonNull(identity, "identity");
        synchronized (CommandLocks.forKey(identity.idempotencyKey())) {
            return execute(connection -> {
                Optional<IdempotencyRepository.StoredCommand> stored =
                        idempotency.find(connection, identity.idempotencyKey());
                if (stored.isPresent()) {
                    return recover(identity, stored.orElseThrow());
                }
                Optional<VersionedSettingsRepository.StoredVersion> current =
                        versions.latest(connection, VersionedSettingsRepository.Table.PROFILE, id, true);
                requireRevision(current, identity.expectedRevision());
                Instant now = Instant.now(clock);
                Instant createdAt = current.map(VersionedSettingsRepository.StoredVersion::createdAt)
                        .orElse(now);
                AgentProfile profile = new AgentProfile(
                        id, Math.addExact(identity.expectedRevision(), 1), lifecycle, spec, createdAt, now);
                versions.insert(connection, VersionedSettingsRepository.Table.PROFILE, stored(profile));
                idempotency.insert(connection, identity, json.encode(profile), now);
                return profile;
            });
        }
    }

    private void validateReferences(AgentProfileSpec spec) {
        providers.requireAvailable(spec.provider(), ProviderModelPurpose.CHAT);
        permissions.require(
                spec.permissionProfile().id(), spec.permissionProfile().version());
    }

    private AgentProfile requireLatest(String id) {
        return execute(connection -> versions.latest(
                        connection, VersionedSettingsRepository.Table.PROFILE, identifier(id), false)
                .map(this::decode)
                .orElseThrow(() -> PersistenceException.invalidRequest("Agent Profile 不存在")));
    }

    private VersionedSettingsRepository.StoredVersion stored(AgentProfile profile) {
        return new VersionedSettingsRepository.StoredVersion(
                profile.id(),
                profile.revision(),
                profile.lifecycle().name(),
                json.encode(profile.spec()),
                profile.createdAt(),
                profile.updatedAt());
    }

    private AgentProfile decode(VersionedSettingsRepository.StoredVersion stored) {
        return new AgentProfile(
                stored.id(),
                stored.revision(),
                ProfileLifecycle.valueOf(stored.lifecycle()),
                json.decode(stored.payload(), AgentProfileSpec.class),
                stored.createdAt(),
                stored.updatedAt());
    }

    private AgentProfile recover(CommandIdentity identity, IdempotencyRepository.StoredCommand stored) {
        if (!stored.method().equals(identity.method())
                || !stored.requestDigest().equals(identity.requestDigest())) {
            throw PersistenceException.idempotencyConflict("幂等键已被不同命令使用");
        }
        return json.decode(stored.response(), AgentProfile.class);
    }

    private static void requireRevision(
            Optional<VersionedSettingsRepository.StoredVersion> current, long expectedRevision) {
        if (current.isEmpty() && expectedRevision == 0) {
            return;
        }
        if (current.isEmpty() || current.orElseThrow().revision() != expectedRevision) {
            throw PersistenceException.revisionConflict("Agent Profile revision 已改变");
        }
    }

    private static String identifier(String value) {
        String id = Objects.requireNonNull(value, "id").strip();
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException("profile id contains unsupported characters");
        }
        return id;
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Agent Profile 事务失败", failure);
        }
    }
}
