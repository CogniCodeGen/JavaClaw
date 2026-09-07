package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionState;
import com.javaclaw.extension.spi.ExtensionTrust;
import com.javaclaw.protocol.CanonicalJson;

/** 第三方 Bundle 的安装元数据、状态、退避与隔离持久化。 */
public final class ThirdPartyExtensionRepository {
    private static final String SELECT = """
            SELECT E.TRUST_LEVEL, E.STATE, E.REVISION, E.DESCRIPTOR,
                   B.MANIFEST_DIGEST, B.SIGNING_KEY_ID, B.INSTALL_DIRECTORY,
                   B.PERMISSION_REVIEW, B.FAILURE_COUNT, B.HEALTH_STATE, B.LAST_HEALTH_AT,
                   B.NEXT_RETRY_AT, B.LAST_FAILURE, B.PENDING_TRASH_NAME
            FROM CORE.EXTENSION E
            JOIN CORE.THIRD_PARTY_BUNDLE B ON B.EXTENSION_ID = E.ID
            """;

    private final H2Transactions transactions;
    private final CanonicalJson json;
    private final Clock clock;

    /**
     * 创建第三方目录仓储。
     *
     * @param database data-v6 数据库
     * @param json 共享 JSON codec
     * @param clock 平台时钟
     */
    public ThirdPartyExtensionRepository(H2Database database, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 原子登记已经完成签名与权限审阅的第三方 Bundle。
     *
     * @param descriptor 平台分配 revision 后的描述
     * @param manifestDigest 已签名 manifest 摘要
     * @param signingKeyId 管理员信任的签名密钥
     * @param installDirectory installed 根下的目录名
     * @return 持久化安装记录；完全相同的重试返回原记录
     */
    public ThirdPartyExtensionRecord install(
            ExtensionDescriptor descriptor,
            String manifestDigest,
            String signingKeyId,
            String installDirectory,
            com.javaclaw.protocol.BundleRpcContracts.PermissionReview permissions) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (descriptor.requirements().trust() != ExtensionTrust.THIRD_PARTY) {
            throw new IllegalArgumentException("third-party descriptor trust is required");
        }
        return execute(connection -> {
            Optional<ThirdPartyExtensionRecord> stored = find(connection, descriptor.id());
            if (stored.isPresent()) {
                requireSame(
                        stored.orElseThrow(), descriptor, manifestDigest, signingKeyId, installDirectory, permissions);
                return stored.orElseThrow();
            }
            requireIdAvailable(connection, descriptor.id());
            insertExtension(connection, descriptor);
            insertMetadata(connection, descriptor.id(), manifestDigest, signingKeyId, installDirectory, permissions);
            return find(connection, descriptor.id()).orElseThrow();
        });
    }

    /**
     * 列出所有第三方安装记录。
     *
     * @return 按扩展标识排序的记录
     */
    public List<ThirdPartyExtensionRecord> list() {
        return execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT + " ORDER BY E.ID")) {
                try (ResultSet result = statement.executeQuery()) {
                    ArrayList<ThirdPartyExtensionRecord> records = new ArrayList<>();
                    while (result.next()) {
                        records.add(map(result));
                    }
                    return List.copyOf(records);
                }
            }
        });
    }

    /**
     * 读取第三方安装记录。
     *
     * @param id 扩展标识
     * @return 记录
     */
    public Optional<ThirdPartyExtensionRecord> find(ExtensionId id) {
        return execute(connection -> find(connection, id));
    }

    /**
     * 分配同一扩展标识的下一个单调 revision。
     *
     * <p>已移除记录也参与计算，确保卸载再安装不会复用旧 revision。
     *
     * @param id 扩展标识
     * @return 尚未占用的正 revision
     */
    public long nextRevision(ExtensionId id) {
        Objects.requireNonNull(id, "id");
        return execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COALESCE(MAX(REVISION), 0)
                    FROM (
                        SELECT REVISION FROM CORE.EXTENSION WHERE ID = ?
                        UNION ALL
                        SELECT REVISION FROM CORE.THIRD_PARTY_REMOVAL WHERE EXTENSION_ID = ?
                    ) HISTORY
                    """)) {
                statement.setString(1, id.value());
                statement.setString(2, id.value());
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return Math.incrementExact(result.getLong(1));
                }
            }
        });
    }

    /**
     * 原子切换到已完成验签和健康检查的新 Bundle revision。
     *
     * @param id 扩展标识
     * @param expectedRevision 当前 revision
     * @param descriptor 新描述
     * @param manifestDigest 新 manifest 摘要
     * @param signingKeyId 新签名密钥
     * @param installDirectory 新受管目录名
     * @param permissions 新权限审阅
     * @param targetState 切换后的生命周期状态
     * @return 新安装记录
     */
    public ThirdPartyExtensionRecord upgrade(ExtensionId id, long expectedRevision, Upgrade replacement) {
        Objects.requireNonNull(replacement, "replacement");
        ExtensionDescriptor descriptor = replacement.descriptor();
        return execute(connection -> {
            ThirdPartyExtensionRecord current = require(connection, id);
            if (current.descriptor().revision() != expectedRevision
                    || descriptor.revision() != Math.incrementExact(expectedRevision)
                    || !descriptor.id().equals(id)) {
                throw PersistenceException.revisionConflict("Bundle 升级 revision 或标识已改变");
            }
            updateExtension(connection, descriptor, replacement.targetState());
            updateBundle(
                    connection,
                    id,
                    replacement.manifestDigest(),
                    replacement.signingKeyId(),
                    replacement.installDirectory(),
                    replacement.permissions(),
                    replacement.targetState());
            return require(connection, id);
        });
    }

    /**
     * 在 revision 与允许源状态同时匹配时切换状态。
     *
     * @param id 扩展标识
     * @param expectedRevision Bundle revision
     * @param allowedStates 允许的当前状态
     * @param target 目标状态
     * @return 更新后的记录
     */
    public ThirdPartyExtensionRecord transition(
            ExtensionId id, long expectedRevision, Set<ExtensionState> allowedStates, ExtensionState target) {
        Objects.requireNonNull(allowedStates, "allowedStates");
        Objects.requireNonNull(target, "target");
        return execute(connection -> {
            ThirdPartyExtensionRecord current = require(connection, id);
            if (current.descriptor().revision() != expectedRevision) {
                throw PersistenceException.revisionConflict("第三方扩展 revision 已改变");
            }
            if (!allowedStates.contains(current.state())) {
                throw PersistenceException.invalidRequest("第三方扩展状态不允许该操作: " + current.state());
            }
            updateState(connection, id, target);
            return require(connection, id);
        });
    }

    /**
     * 记录确定的 Trash 目标后进入 REMOVING，保证进程中断后可继续同一次移动。
     *
     * @param id 扩展标识
     * @param expectedRevision Bundle revision
     * @param trashName 预先分配的 Trash 目录名
     * @return REMOVING 记录
     */
    public ThirdPartyExtensionRecord beginRemoval(ExtensionId id, long expectedRevision, String trashName) {
        if (trashName == null || !trashName.matches("[A-Za-z0-9._-]{1,160}")) {
            throw new IllegalArgumentException("invalid Trash directory name");
        }
        return execute(connection -> {
            ThirdPartyExtensionRecord current = require(connection, id);
            if (current.descriptor().revision() != expectedRevision) {
                throw PersistenceException.revisionConflict("第三方扩展 revision 已改变");
            }
            if (!Set.of(ExtensionState.INSTALLED, ExtensionState.DISABLED, ExtensionState.QUARANTINED)
                    .contains(current.state())) {
                throw PersistenceException.invalidRequest("第三方扩展必须先禁用再移除");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE CORE.THIRD_PARTY_BUNDLE SET PENDING_TRASH_NAME = ? WHERE EXTENSION_ID = ?
                    """)) {
                statement.setString(1, trashName);
                statement.setString(2, id.value());
                statement.executeUpdate();
            }
            updateState(connection, id, ExtensionState.REMOVING);
            return require(connection, id);
        });
    }

    /**
     * 记录连续失败、最早重试时间，并在达到阈值时隔离。
     *
     * @param id 扩展标识
     * @param failureCount 连续失败次数
     * @param nextRetryAt 最早重试时间
     * @param message 脱敏失败说明
     * @param quarantine 是否隔离
     */
    public void recordFailure(
            ExtensionId id, int failureCount, Instant nextRetryAt, String message, boolean quarantine) {
        if (failureCount < 1) {
            throw new IllegalArgumentException("failureCount must be positive");
        }
        execute(connection -> {
            require(connection, id);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE CORE.THIRD_PARTY_BUNDLE
                    SET FAILURE_COUNT = ?, HEALTH_STATE = ?, LAST_HEALTH_AT = ?,
                        NEXT_RETRY_AT = ?, LAST_FAILURE = ?
                    WHERE EXTENSION_ID = ?
                    """)) {
                statement.setInt(1, failureCount);
                statement.setString(
                        2,
                        (quarantine
                                        ? com.javaclaw.protocol.BundleRpcContracts.HealthState.QUARANTINED
                                        : com.javaclaw.protocol.BundleRpcContracts.HealthState.BACKING_OFF)
                                .name());
                statement.setObject(3, Instant.now(clock).atOffset(ZoneOffset.UTC));
                statement.setObject(4, nextRetryAt.atOffset(ZoneOffset.UTC));
                statement.setString(5, Objects.requireNonNull(message, "message"));
                statement.setString(6, id.value());
                statement.executeUpdate();
            }
            if (quarantine) {
                updateState(connection, id, ExtensionState.QUARANTINED);
            }
            return null;
        });
    }

    /**
     * 清除成功调用前累计的失败与退避。
     *
     * @param id 扩展标识
     */
    public void clearFailures(ExtensionId id) {
        execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE CORE.THIRD_PARTY_BUNDLE
                    SET FAILURE_COUNT = 0, HEALTH_STATE = ?, LAST_HEALTH_AT = ?,
                        NEXT_RETRY_AT = NULL, LAST_FAILURE = NULL
                    WHERE EXTENSION_ID = ?
                    """)) {
                statement.setString(1, com.javaclaw.protocol.BundleRpcContracts.HealthState.HEALTHY.name());
                statement.setObject(2, Instant.now(clock).atOffset(ZoneOffset.UTC));
                statement.setString(3, id.value());
                if (statement.executeUpdate() != 1) {
                    throw PersistenceException.invalidRequest("第三方扩展不存在");
                }
            }
            return null;
        });
    }

    private Optional<ThirdPartyExtensionRecord> find(Connection connection, ExtensionId id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT + " WHERE E.ID = ?")) {
            statement.setString(1, id.value());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    private ThirdPartyExtensionRecord require(Connection connection, ExtensionId id) throws SQLException {
        return find(connection, id).orElseThrow(() -> PersistenceException.invalidRequest("第三方扩展不存在"));
    }

    private ThirdPartyExtensionRecord map(ResultSet result) throws SQLException {
        if (ExtensionTrust.valueOf(result.getString("TRUST_LEVEL")) != ExtensionTrust.THIRD_PARTY) {
            throw new PersistenceException("第三方 Bundle 元数据指向非第三方扩展");
        }
        ExtensionDescriptor descriptor =
                json.decode(new CanonicalPayload(result.getString("DESCRIPTOR")), ExtensionDescriptor.class);
        OffsetDateTime retry = result.getObject("NEXT_RETRY_AT", OffsetDateTime.class);
        OffsetDateTime healthAt = result.getObject("LAST_HEALTH_AT", OffsetDateTime.class);
        return new ThirdPartyExtensionRecord(
                descriptor,
                ExtensionState.valueOf(result.getString("STATE")),
                result.getString("MANIFEST_DIGEST"),
                result.getString("SIGNING_KEY_ID"),
                result.getString("INSTALL_DIRECTORY"),
                json.decode(
                        new CanonicalPayload(result.getString("PERMISSION_REVIEW")),
                        com.javaclaw.protocol.BundleRpcContracts.PermissionReview.class),
                result.getInt("FAILURE_COUNT"),
                com.javaclaw.protocol.BundleRpcContracts.HealthState.valueOf(result.getString("HEALTH_STATE")),
                Optional.ofNullable(healthAt).map(OffsetDateTime::toInstant),
                Optional.ofNullable(retry).map(OffsetDateTime::toInstant),
                Optional.ofNullable(result.getString("LAST_FAILURE")),
                Optional.ofNullable(result.getString("PENDING_TRASH_NAME")));
    }

    private void requireIdAvailable(Connection connection, ExtensionId id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM CORE.EXTENSION WHERE ID = ?")) {
            statement.setString(1, id.value());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw PersistenceException.invalidRequest("扩展标识已被占用");
                }
            }
        }
    }

    private void insertExtension(Connection connection, ExtensionDescriptor descriptor) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.EXTENSION (ID, TRUST_LEVEL, STATE, REVISION, DESCRIPTOR, UPDATED_AT)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, descriptor.id().value());
            statement.setString(2, ExtensionTrust.THIRD_PARTY.name());
            statement.setString(3, ExtensionState.INSTALLED.name());
            statement.setLong(4, descriptor.revision());
            statement.setString(5, json.encode(descriptor).json());
            statement.setObject(6, Instant.now(clock).atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private void insertMetadata(
            Connection connection,
            ExtensionId id,
            String manifestDigest,
            String signingKeyId,
            String installDirectory,
            com.javaclaw.protocol.BundleRpcContracts.PermissionReview permissions)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.THIRD_PARTY_BUNDLE
                    (EXTENSION_ID, MANIFEST_DIGEST, SIGNING_KEY_ID, INSTALL_DIRECTORY, PERMISSION_REVIEW)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, id.value());
            statement.setString(2, manifestDigest);
            statement.setString(3, signingKeyId);
            statement.setString(4, installDirectory);
            statement.setString(5, json.encode(permissions).json());
            statement.executeUpdate();
        }
    }

    private void updateState(Connection connection, ExtensionId id, ExtensionState target) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.EXTENSION SET STATE = ?, UPDATED_AT = ? WHERE ID = ?
                """)) {
            statement.setString(1, target.name());
            statement.setObject(2, Instant.now(clock).atOffset(ZoneOffset.UTC));
            statement.setString(3, id.value());
            statement.executeUpdate();
        }
    }

    private void updateExtension(Connection connection, ExtensionDescriptor descriptor, ExtensionState targetState)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.EXTENSION
                SET STATE = ?, REVISION = ?, DESCRIPTOR = ?, UPDATED_AT = ?
                WHERE ID = ?
                """)) {
            statement.setString(1, targetState.name());
            statement.setLong(2, descriptor.revision());
            statement.setString(3, json.encode(descriptor).json());
            statement.setObject(4, Instant.now(clock).atOffset(ZoneOffset.UTC));
            statement.setString(5, descriptor.id().value());
            statement.executeUpdate();
        }
    }

    private void updateBundle(
            Connection connection,
            ExtensionId id,
            String manifestDigest,
            String signingKeyId,
            String installDirectory,
            com.javaclaw.protocol.BundleRpcContracts.PermissionReview permissions,
            ExtensionState targetState)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.THIRD_PARTY_BUNDLE
                SET MANIFEST_DIGEST = ?, SIGNING_KEY_ID = ?, INSTALL_DIRECTORY = ?,
                    PERMISSION_REVIEW = ?, FAILURE_COUNT = 0, HEALTH_STATE = ?,
                    LAST_HEALTH_AT = ?, NEXT_RETRY_AT = NULL, LAST_FAILURE = NULL,
                    PENDING_TRASH_NAME = NULL
                WHERE EXTENSION_ID = ?
                """)) {
            statement.setString(1, manifestDigest);
            statement.setString(2, signingKeyId);
            statement.setString(3, installDirectory);
            statement.setString(4, json.encode(permissions).json());
            statement.setString(
                    5,
                    (targetState == ExtensionState.ENABLED
                                    ? com.javaclaw.protocol.BundleRpcContracts.HealthState.HEALTHY
                                    : com.javaclaw.protocol.BundleRpcContracts.HealthState.DISABLED)
                            .name());
            statement.setObject(6, Instant.now(clock).atOffset(ZoneOffset.UTC));
            statement.setString(7, id.value());
            statement.executeUpdate();
        }
    }

    private static void requireSame(
            ThirdPartyExtensionRecord existing,
            ExtensionDescriptor descriptor,
            String manifestDigest,
            String signingKeyId,
            String installDirectory,
            com.javaclaw.protocol.BundleRpcContracts.PermissionReview permissions) {
        if (!existing.descriptor().equals(descriptor)
                || !existing.manifestDigest().equals(manifestDigest)
                || !existing.signingKeyId().equals(signingKeyId)
                || !existing.installDirectory().equals(installDirectory)
                || !existing.permissions().equals(permissions)) {
            throw PersistenceException.revisionConflict("第三方扩展标识或 manifest 已被不同 Bundle 使用");
        }
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("第三方 Extension 目录事务失败", failure);
        }
    }

    /**
     * 已验证升级内容。
     *
     * @param descriptor 新 revision 描述
     * @param manifestDigest manifest 摘要
     * @param signingKeyId 签名密钥
     * @param installDirectory 新受管目录
     * @param permissions 新权限审阅
     * @param targetState 切换后状态
     */
    public record Upgrade(
            ExtensionDescriptor descriptor,
            String manifestDigest,
            String signingKeyId,
            String installDirectory,
            com.javaclaw.protocol.BundleRpcContracts.PermissionReview permissions,
            ExtensionState targetState) {
        /** 校验升级内容。 */
        public Upgrade {
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(manifestDigest, "manifestDigest");
            Objects.requireNonNull(signingKeyId, "signingKeyId");
            Objects.requireNonNull(installDirectory, "installDirectory");
            Objects.requireNonNull(permissions, "permissions");
            Objects.requireNonNull(targetState, "targetState");
        }
    }
}
