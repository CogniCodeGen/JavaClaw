package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CoreTools;
import com.javaclaw.api.EffectivePermissionPreview;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileDiff;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.PermissionSection;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.CanonicalJson;

/** 版本化 PermissionProfile 的模板、历史、预览与实时收窄服务。 */
public final class PermissionProfileService {
    /** 首次启动安装的只读内置模板标识。 */
    public static final String STANDARD_PROFILE_ID = "standard";

    private static final String UPDATE_METHOD = "permissionProfile/update";
    private static final String CLONE_METHOD = "permissionProfile/clone";
    private static final String PRESET_INSTANTIATE_METHOD = "permissionProfile/preset/instantiate";

    private final H2Transactions transactions;
    private final PermissionProfileRepository profiles = new PermissionProfileRepository();
    private final IdempotencyRepository idempotency = new IdempotencyRepository();
    private final CanonicalJson json;
    private final Clock clock;

    /**
     * 创建权限配置服务。
     *
     * @param database data-v6 数据库
     * @param json 共享 JSON codec
     * @param clock 平台时钟
     */
    public PermissionProfileService(H2Database database, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 安装内置只读模板；重复启动只校验内容，不生成新版本。 */
    public void installStandardProfile() {
        installBuiltin(standardProfile());
    }

    /**
     * 读取指定不可变版本。
     *
     * @param id 配置标识
     * @param version 精确版本
     * @return 配置
     */
    public PermissionProfile require(String id, long version) {
        return execute(connection -> require(connection, id, version));
    }

    /**
     * 读取一个配置的全部不可变历史。
     *
     * @param id 配置标识
     * @return revision 升序历史
     */
    public List<PermissionProfile> history(String id) {
        return execute(connection -> {
            List<PermissionProfile> result =
                    profiles.history(connection, id).stream().map(this::decode).toList();
            if (result.isEmpty()) {
                throw PersistenceException.invalidRequest("PermissionProfile 不存在");
            }
            return result;
        });
    }

    /**
     * 列出每个配置的最新版本。
     *
     * @return 按标识排序的不可变列表
     */
    public List<PermissionProfile> listLatest() {
        return execute(connection ->
                profiles.listLatest(connection).stream().map(this::decode).toList());
    }

    /**
     * 从一个精确版本克隆用户配置。
     *
     * <p>配置只能通过克隆创建，确保客户端无法伪造内置模板或绕过平台初始化规则。
     *
     * @param identity 幂等身份，expected revision 必须为 0
     * @param source 精确源版本
     * @param newId 新配置标识
     * @return 新配置的 revision 1
     */
    public PermissionProfile cloneProfile(CommandIdentity identity, PermissionProfileRef source, String newId) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(source, "source");
        PermissionProfileRef destination = new PermissionProfileRef(newId, 1);
        requireCommand(identity, CLONE_METHOD, 0);
        if (STANDARD_PROFILE_ID.equals(destination.id())) {
            throw PersistenceException.invalidRequest("standard 是只读内置模板");
        }
        synchronized (CommandLocks.forKey(identity.idempotencyKey())) {
            return execute(connection -> cloneProfile(connection, identity, source, destination.id()));
        }
    }

    /**
     * 持久化经平台 {@code PermissionPresetCatalog} 为固定 Workspace 构造的首个版本。
     *
     * <p>该边界不接受客户端自由构造的创建方法；RPC handler 必须先通过只读预设目录重建完整配置。
     *
     * @param identity 幂等身份，expected revision 必须为 0
     * @param profile 由内置预设生成的 revision 1 配置
     * @return 已持久化的普通 PermissionProfile
     */
    public PermissionProfile instantiatePreset(CommandIdentity identity, PermissionProfile profile) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(profile, "profile");
        requireCommand(identity, PRESET_INSTANTIATE_METHOD, 0);
        if (profile.version() != 1) {
            throw PersistenceException.invalidRequest("Permission preset 只能创建 revision 1");
        }
        validateUserProfile(profile);
        synchronized (CommandLocks.forKey(identity.idempotencyKey())) {
            return execute(connection -> instantiatePreset(connection, identity, profile));
        }
    }

    /**
     * 写入用户配置的新不可变版本。
     *
     * @param identity 幂等身份；expected revision 必须等于当前最新版本
     * @param profile 完整新版本，version 必须为 expected revision 加一
     * @return 已提交版本
     */
    public PermissionProfile update(CommandIdentity identity, PermissionProfile profile) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(profile, "profile");
        requireCommand(identity, UPDATE_METHOD, identity.expectedRevision());
        validateUserProfile(profile);
        if (profile.version() != Math.addExact(identity.expectedRevision(), 1)) {
            throw PersistenceException.invalidRequest("PermissionProfile version 必须为 expected revision 加一");
        }
        synchronized (CommandLocks.forKey(identity.idempotencyKey())) {
            return execute(connection -> update(connection, identity, profile));
        }
    }

    /**
     * 比较同一配置的两个不可变版本。
     *
     * @param id 配置标识
     * @param beforeVersion 基准版本
     * @param afterVersion 比较版本
     * @return 强类型分区差异
     */
    public PermissionProfileDiff diff(String id, long beforeVersion, long afterVersion) {
        PermissionProfile before = require(id, beforeVersion);
        PermissionProfile after = require(id, afterVersion);
        EnumSet<PermissionSection> changed = EnumSet.noneOf(PermissionSection.class);
        addChangedSections(changed, before, after);
        return new PermissionProfileDiff(before, after, changed);
    }

    /**
     * 预览系统、Workspace、Profile、Turn grant 与工具声明的逐层交集。
     *
     * <p>Profile 层同时加入冻结 revision 与当前最新 revision，因此后续扩权不会扩大活动 Turn，撤权会即时生效。
     *
     * @param profile Turn 冻结的配置引用
     * @param workspace 所属 Workspace
     * @param turnGrant 可选 Turn grant
     * @param toolDeclaration 可选工具声明
     * @return 五层有效权限与通俗拒绝原因
     */
    public EffectivePermissionPreview preview(
            PermissionProfileRef profile,
            Workspace workspace,
            Optional<PermissionProfile> turnGrant,
            Optional<PermissionProfile> toolDeclaration) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(turnGrant, "turnGrant");
        Objects.requireNonNull(toolDeclaration, "toolDeclaration");
        return execute(connection -> {
            PermissionProfile frozen = require(connection, profile.id(), profile.version());
            PermissionProfile current = profiles.latest(connection, profile.id())
                    .map(this::decode)
                    .orElseThrow(() -> PersistenceException.invalidRequest("PermissionProfile 不存在"));
            return PermissionPreviewEvaluator.evaluate(frozen, current, workspace, turnGrant, toolDeclaration);
        });
    }

    /**
     * 解析 Turn 的即时有效权限。
     *
     * @param id Turn 冻结的配置标识
     * @param version Turn 冻结版本
     * @param workspace 所属 Workspace
     * @return 只能更窄的即时权限
     */
    public PermissionProfile resolve(String id, long version, Workspace workspace) {
        return preview(new PermissionProfileRef(id, version), workspace, Optional.empty(), Optional.empty())
                .effective();
    }

    /**
     * 将 Workspace 语义文件范围映射到服务端冻结的执行根。
     *
     * @param id Turn 冻结配置标识
     * @param version Turn 冻结版本
     * @param workspace 所属 Workspace
     * @param executionRoot Workspace 根或平台分配的 Managed Worktree 根
     * @param writable 是否允许保留写权限
     * @return 不会越过 executionRoot 的即时权限
     */
    public PermissionProfile resolveForExecution(
            String id, long version, Workspace workspace, Path executionRoot, boolean writable) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(executionRoot, "executionRoot");
        return execute(connection -> {
            PermissionProfile frozen = require(connection, id, version);
            PermissionProfile current = profiles.latest(connection, id)
                    .map(this::decode)
                    .orElseThrow(() -> PersistenceException.invalidRequest("PermissionProfile 不存在"));
            return PermissionPreviewEvaluator.evaluateExecution(frozen, current, workspace, executionRoot, writable)
                    .effective();
        });
    }

    private PermissionProfile cloneProfile(
            Connection connection, CommandIdentity identity, PermissionProfileRef source, String newId)
            throws SQLException {
        Optional<IdempotencyRepository.StoredCommand> stored = idempotency.find(connection, identity.idempotencyKey());
        if (stored.isPresent()) {
            return recover(identity, stored.orElseThrow());
        }
        if (profiles.latest(connection, newId).isPresent()) {
            throw PersistenceException.revisionConflict("PermissionProfile 标识已存在");
        }
        PermissionProfile origin = require(connection, source.id(), source.version());
        PermissionProfile clone = copy(origin, newId, 1);
        validateUserProfile(clone);
        persist(connection, identity, clone);
        return clone;
    }

    private PermissionProfile update(Connection connection, CommandIdentity identity, PermissionProfile profile)
            throws SQLException {
        Optional<IdempotencyRepository.StoredCommand> stored = idempotency.find(connection, identity.idempotencyKey());
        if (stored.isPresent()) {
            return recover(identity, stored.orElseThrow());
        }
        requireLatest(connection, profile.id(), identity.expectedRevision());
        persist(connection, identity, profile);
        return profile;
    }

    private PermissionProfile instantiatePreset(
            Connection connection, CommandIdentity identity, PermissionProfile profile) throws SQLException {
        Optional<IdempotencyRepository.StoredCommand> stored = idempotency.find(connection, identity.idempotencyKey());
        if (stored.isPresent()) {
            return recover(identity, stored.orElseThrow());
        }
        if (profiles.latest(connection, profile.id()).isPresent()) {
            throw PersistenceException.revisionConflict("PermissionProfile 标识已存在");
        }
        persist(connection, identity, profile);
        return profile;
    }

    private void persist(Connection connection, CommandIdentity identity, PermissionProfile profile)
            throws SQLException {
        profiles.insert(connection, profile.id(), profile.version(), json.encode(profile), now());
        idempotency.insert(connection, identity, json.encode(profile), now());
    }

    private void installBuiltin(PermissionProfile profile) {
        execute(connection -> {
            Optional<PermissionProfileRepository.StoredProfile> stored =
                    profiles.find(connection, profile.id(), profile.version());
            if (stored.isEmpty()) {
                profiles.insert(connection, profile.id(), profile.version(), json.encode(profile), now());
                return null;
            }
            if (!decode(stored.orElseThrow()).equals(profile)) {
                throw new PersistenceException("内置 PermissionProfile 内容冲突");
            }
            return null;
        });
    }

    private PermissionProfile require(Connection connection, String id, long version) throws SQLException {
        return profiles.find(connection, id, version)
                .map(this::decode)
                .orElseThrow(() -> PersistenceException.invalidRequest("PermissionProfile 不存在"));
    }

    private PermissionProfile decode(PermissionProfileRepository.StoredProfile stored) {
        PermissionProfile profile = json.decode(stored.payload(), PermissionProfile.class);
        if (!stored.id().equals(profile.id()) || stored.version() != profile.version()) {
            throw new PersistenceException("PermissionProfile 行身份与 payload 不一致");
        }
        return profile;
    }

    private PermissionProfile recover(CommandIdentity identity, IdempotencyRepository.StoredCommand stored) {
        if (!stored.method().equals(identity.method())
                || !stored.requestDigest().equals(identity.requestDigest())) {
            throw PersistenceException.idempotencyConflict("幂等键已被不同命令使用");
        }
        return json.decode(stored.response(), PermissionProfile.class);
    }

    private void requireLatest(Connection connection, String id, long expectedRevision) throws SQLException {
        Optional<PermissionProfileRepository.StoredProfile> latest = profiles.latest(connection, id);
        if (latest.isEmpty()) {
            throw PersistenceException.invalidRequest("PermissionProfile 必须先从模板克隆");
        }
        if (latest.orElseThrow().version() != expectedRevision) {
            throw PersistenceException.revisionConflict("PermissionProfile revision 已改变");
        }
    }

    private static void requireCommand(CommandIdentity identity, String method, long expectedRevision) {
        if (!method.equals(identity.method())) {
            throw PersistenceException.invalidRequest("PermissionProfile 命令方法不匹配");
        }
        if (identity.expectedRevision() != expectedRevision) {
            throw PersistenceException.invalidRequest("PermissionProfile expected revision 不合法");
        }
    }

    private static void validateUserProfile(PermissionProfile profile) {
        if (STANDARD_PROFILE_ID.equals(profile.id())) {
            throw PersistenceException.invalidRequest("standard 是只读内置模板");
        }
        boolean hostRoot = java.util.stream.Stream.concat(
                        profile.files().readRoots().stream(), profile.files().writeRoots().stream())
                .anyMatch(PermissionProfileService::isHostRoot);
        if (hostRoot
                || profile.network().hosts().contains(NetworkPermission.ANY_HOST)
                || profile.network().ports().contains(NetworkPermission.ANY_PORT)
                || profile.processes().executables().contains("*")
                || profile.tools().allowedTools().contains("*")) {
            throw PersistenceException.invalidRequest("禁止保存 HOST_FULL_ACCESS 权限配置");
        }
    }

    private static boolean isHostRoot(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.getParent() == null;
    }

    private static void addChangedSections(
            EnumSet<PermissionSection> changed, PermissionProfile before, PermissionProfile after) {
        if (!before.files().equals(after.files())) {
            changed.add(PermissionSection.FILE);
        }
        if (!before.network().equals(after.network())) {
            changed.add(PermissionSection.NETWORK);
        }
        if (!before.processes().equals(after.processes())) {
            changed.add(PermissionSection.PROCESS);
        }
        if (!before.tools().equals(after.tools())) {
            changed.add(PermissionSection.TOOL);
        }
        if (!before.resources().equals(after.resources())) {
            changed.add(PermissionSection.RESOURCE);
        }
    }

    private static PermissionProfile copy(PermissionProfile source, String id, long version) {
        return new PermissionProfile(
                id, version, source.files(), source.network(), source.processes(), source.tools(), source.resources());
    }

    private static PermissionProfile standardProfile() {
        return new PermissionProfile(
                STANDARD_PROFILE_ID,
                1,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(30)),
                new ToolPermission(Set.of(CoreTools.SEARCH_NAME), ToolRisk.READ_ONLY, ApprovalRequirement.RISKY),
                new ResourceLimits(256L * 1024 * 1024, 16L * 1024 * 1024, 1, 32));
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("PermissionProfile 事务失败", failure);
        }
    }
}
