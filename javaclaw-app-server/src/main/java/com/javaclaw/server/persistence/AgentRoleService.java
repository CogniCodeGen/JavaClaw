package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.protocol.CanonicalJson;

/** Agent Role 不可变版本、引用校验与归档服务。 */
public final class AgentRoleService {
    private final H2Transactions transactions;
    private final VersionedSettingsRepository versions = new VersionedSettingsRepository();
    private final IdempotencyRepository idempotency = new IdempotencyRepository();
    private final ProviderService providers;
    private final CanonicalJson json;
    private final Clock clock;

    /**
     * 创建 Role 服务。
     *
     * @param database data-v6 数据库
     * @param providers Provider 精确版本服务
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     */
    public AgentRoleService(H2Database database, ProviderService providers, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.providers = Objects.requireNonNull(providers, "providers");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        initializeBuiltins();
    }

    /** @return 每个 Role 的最新版本 */
    public List<AgentRole> listLatest() {
        return execute(connection -> versions.listLatest(connection, VersionedSettingsRepository.Table.ROLE).stream()
                .map(this::decode)
                .toList());
    }

    /**
     * 读取精确 Role 版本。
     *
     * @param id Role 标识
     * @param revision 精确版本
     * @return Role
     */
    public AgentRole require(String id, long revision) {
        return execute(connection -> versions.find(
                        connection, VersionedSettingsRepository.Table.ROLE, identifier(id), revision)
                .map(this::decode)
                .orElseThrow(() -> PersistenceException.invalidRequest("Agent Role 不存在")));
    }

    /**
     * 读取可用于新绑定或新 Turn 的精确 Role。
     *
     * <p>精确版本决定冻结配置，最新版本生命周期决定该 Role 是否仍允许开始新工作。
     *
     * @param id Role 标识
     * @param revision 精确版本
     * @return 当前可用的精确 Role
     */
    public AgentRole requireAvailable(String id, long revision) {
        AgentRole role = require(id, revision);
        AgentRole latest = requireLatest(id);
        if (role.lifecycle() != RoleLifecycle.ACTIVE || latest.lifecycle() != RoleLifecycle.ACTIVE) {
            throw PersistenceException.invalidRequest("Agent Role 当前不可用于新绑定或新 Turn");
        }
        validateReferences(role.spec());
        return role;
    }

    /**
     * 创建 Agent Role。
     *
     * @param identity expected revision 必须为 0
     * @param id 稳定标识
     * @param spec 完整配置
     * @return 首个版本
     */
    public AgentRole create(CommandIdentity identity, String id, AgentRoleSpec spec) {
        if (identity.expectedRevision() != 0) {
            throw PersistenceException.invalidRequest("Role 创建 expected revision 必须为 0");
        }
        return write(identity, identifier(id), Objects.requireNonNull(spec, "spec"), RoleLifecycle.ACTIVE);
    }

    /**
     * 更新 Agent Role 并创建新版本。
     *
     * @param identity expected revision 必须匹配当前最新版本
     * @param id 稳定标识
     * @param spec 完整配置
     * @param lifecycle 新生命周期
     * @return 新版本
     */
    public AgentRole update(CommandIdentity identity, String id, AgentRoleSpec spec, RoleLifecycle lifecycle) {
        if (identity.expectedRevision() == 0) {
            throw PersistenceException.invalidRequest("Role 更新必须指定当前 revision");
        }
        if (Objects.requireNonNull(lifecycle, "lifecycle") == RoleLifecycle.ARCHIVED) {
            throw PersistenceException.invalidRequest("请使用 agent/role/archive 归档 Agent Role");
        }
        return write(identity, identifier(id), Objects.requireNonNull(spec, "spec"), lifecycle);
    }

    /**
     * 归档 Agent Role；历史 Turn 仍可读取旧版本。
     *
     * @param identity expected revision 必须匹配当前最新版本
     * @param id Role 标识
     * @return 归档版本
     */
    public AgentRole archive(CommandIdentity identity, String id) {
        AgentRole current = requireLatest(id);
        return write(identity, current.id(), current.spec(), RoleLifecycle.ARCHIVED);
    }

    private AgentRole write(CommandIdentity identity, String id, AgentRoleSpec spec, RoleLifecycle lifecycle) {
        Objects.requireNonNull(identity, "identity");
        synchronized (CommandLocks.forKey(identity.idempotencyKey())) {
            return execute(connection -> writeInTransaction(connection, identity, id, spec, lifecycle));
        }
    }

    AgentRole writeInTransaction(
            Connection connection, CommandIdentity identity, String id, AgentRoleSpec spec, RoleLifecycle lifecycle)
            throws SQLException {
        Optional<IdempotencyRepository.StoredCommand> existing =
                idempotency.find(connection, identity.idempotencyKey());
        if (existing.isPresent()) {
            return recover(identity, existing.orElseThrow());
        }
        if (lifecycle == RoleLifecycle.ACTIVE) {
            validateReferences(spec);
        }
        Optional<VersionedSettingsRepository.StoredVersion> current =
                versions.latest(connection, VersionedSettingsRepository.Table.ROLE, identifier(id), true);
        requireRevision(current, identity.expectedRevision());
        if (current.map(this::decode).map(AgentRole::builtin).orElse(false)
                || com.javaclaw.server.role.BuiltinAgentRoles.contains(id)) {
            throw PersistenceException.invalidRequest("内置 Agent Role 只能 clone，不能编辑或归档");
        }
        Instant now = Instant.now(clock);
        Instant createdAt = current.map(VersionedSettingsRepository.StoredVersion::createdAt)
                .orElse(now);
        AgentRole role = new AgentRole(
                id, Math.addExact(identity.expectedRevision(), 1), lifecycle, spec, false, createdAt, now);
        versions.insert(connection, VersionedSettingsRepository.Table.ROLE, stored(role));
        idempotency.insert(connection, identity, json.encode(role), now);
        return role;
    }

    private void validateReferences(AgentRoleSpec spec) {
        spec.model().ifPresent(model -> providers.requireAvailable(model.provider(), ProviderModelPurpose.CHAT));
    }

    /**
     * 读取当前最新版本，包括禁用和归档状态。
     *
     * @param id Role 稳定标识
     * @return 当前最新版本
     */
    public AgentRole requireLatest(String id) {
        return execute(
                connection -> versions.latest(connection, VersionedSettingsRepository.Table.ROLE, identifier(id), false)
                        .map(this::decode)
                        .orElseThrow(() -> PersistenceException.invalidRequest("Agent Role 不存在")));
    }

    private VersionedSettingsRepository.StoredVersion stored(AgentRole role) {
        return new VersionedSettingsRepository.StoredVersion(
                role.id(),
                role.revision(),
                role.lifecycle().name(),
                json.encode(role),
                role.createdAt(),
                role.updatedAt());
    }

    private AgentRole decode(VersionedSettingsRepository.StoredVersion stored) {
        AgentRole role = json.decode(stored.payload(), AgentRole.class);
        if (!role.id().equals(stored.id())
                || role.revision() != stored.revision()
                || !role.lifecycle().name().equals(stored.lifecycle())
                || !role.createdAt().equals(stored.createdAt())
                || !role.updatedAt().equals(stored.updatedAt())) {
            throw new PersistenceException("Role payload 与版本行身份不一致");
        }
        return role;
    }

    /**
     * 从精确历史版本复制用户可编辑的新 Role；执行配置和权限不随角色复制。
     *
     * @param identity 新建命令，expected revision 为 0
     * @param source 源精确 Role
     * @param id 新稳定标识
     * @param name 新名称
     * @return 新的用户 Role
     */
    public AgentRole clone(CommandIdentity identity, com.javaclaw.api.AgentRoleRef source, String id, String name) {
        AgentRoleSpec sourceSpec = require(source.id(), source.revision()).spec();
        AgentRoleSpec copy = new AgentRoleSpec(
                name,
                sourceSpec.description(),
                sourceSpec.developerInstructions(),
                sourceSpec.model(),
                sourceSpec.reasoning(),
                sourceSpec.narrowing(),
                sourceSpec.permissionConstraint(),
                sourceSpec.extensions());
        return create(identity, id, copy);
    }

    private void initializeBuiltins() {
        List<AgentRole> resources = new com.javaclaw.server.role.BuiltinAgentRoles().list();
        execute(connection -> {
            // 内置资源必须在同一事务中全部写入；重启只验证已存在版本，绝不覆盖用户数据。
            for (AgentRole role : resources) {
                Optional<VersionedSettingsRepository.StoredVersion> existing =
                        versions.find(connection, VersionedSettingsRepository.Table.ROLE, role.id(), role.revision());
                if (existing.isEmpty()) {
                    versions.insert(connection, VersionedSettingsRepository.Table.ROLE, stored(role));
                } else if (!decode(existing.orElseThrow()).equals(role)) {
                    throw new PersistenceException("内置 Role 与发行资源不一致: " + role.id());
                }
            }
            return null;
        });
    }

    private AgentRole recover(CommandIdentity identity, IdempotencyRepository.StoredCommand stored) {
        if (!stored.method().equals(identity.method())
                || !stored.requestDigest().equals(identity.requestDigest())) {
            throw PersistenceException.idempotencyConflict("幂等键已被不同命令使用");
        }
        return json.decode(stored.response(), AgentRole.class);
    }

    private static void requireRevision(
            Optional<VersionedSettingsRepository.StoredVersion> current, long expectedRevision) {
        if (current.isEmpty() && expectedRevision == 0) {
            return;
        }
        if (current.isEmpty() || current.orElseThrow().revision() != expectedRevision) {
            throw PersistenceException.revisionConflict("Agent Role revision 已改变");
        }
    }

    private static String identifier(String value) {
        String id = Objects.requireNonNull(value, "id").strip();
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException("role id contains unsupported characters");
        }
        return id;
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Agent Role 事务失败", failure);
        }
    }
}
