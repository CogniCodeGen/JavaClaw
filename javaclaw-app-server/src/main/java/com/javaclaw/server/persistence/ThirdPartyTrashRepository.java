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

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionState;
import com.javaclaw.extension.spi.ExtensionTrust;
import com.javaclaw.protocol.BundleRpcContracts;
import com.javaclaw.protocol.CanonicalJson;

/** Bundle Trash tombstone、恢复与永久清除状态的事务仓储。 */
public final class ThirdPartyTrashRepository {
    private static final String SELECT = """
            SELECT EXTENSION_ID, REVISION, DESCRIPTOR, MANIFEST_DIGEST, SIGNING_KEY_ID,
                   PERMISSION_REVIEW, TRASH_NAME, STATE, REMOVED_AT, RESTORED_REVISION, PURGED_AT
            FROM CORE.THIRD_PARTY_REMOVAL
            """;

    private final H2Transactions transactions;
    private final CanonicalJson json;
    private final Clock clock;

    /**
     * 创建 Trash 仓储。
     *
     * @param database data-v6 数据库
     * @param json 共享 JSON codec
     * @param clock 平台时钟
     */
    public ThirdPartyTrashRepository(H2Database database, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 文件已进入 Trash 后，原子写 tombstone 并移除活动目录记录。
     *
     * @param current REMOVING 快照
     * @param trashName 已完成文件移动的 Trash 标识
     * @return TRASHED 条目
     */
    public ThirdPartyTrashRecord completeRemoval(ThirdPartyExtensionRecord current, String trashName) {
        Objects.requireNonNull(current, "current");
        return execute(connection -> {
            requireRemoving(connection, current, trashName);
            insert(connection, current, trashName);
            deleteExtension(connection, current.descriptor().id().value());
            return find(connection, trashName).orElseThrow();
        });
    }

    /**
     * 登记原子升级后已经移入 Trash 的旧 revision，不影响当前活动 Bundle。
     *
     * @param superseded 被替换快照
     * @param trashName Trash 标识
     * @return TRASHED 条目
     */
    public ThirdPartyTrashRecord recordSuperseded(ThirdPartyExtensionRecord superseded, String trashName) {
        Objects.requireNonNull(superseded, "superseded");
        return execute(connection -> {
            Optional<ThirdPartyTrashRecord> existing = findByRevision(
                    connection,
                    superseded.descriptor().id().value(),
                    superseded.descriptor().revision());
            if (existing.isPresent()) {
                return existing.orElseThrow();
            }
            insert(connection, superseded, trashName);
            return find(connection, trashName).orElseThrow();
        });
    }

    /**
     * 列出全部 Trash 历史，包括已恢复和已清除 tombstone。
     *
     * @return 按移除时间倒序
     */
    public List<ThirdPartyTrashRecord> list() {
        return execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT + " ORDER BY REMOVED_AT DESC");
                    ResultSet result = statement.executeQuery()) {
                ArrayList<ThirdPartyTrashRecord> entries = new ArrayList<>();
                while (result.next()) {
                    entries.add(map(result));
                }
                return List.copyOf(entries);
            }
        });
    }

    /**
     * 按 Trash 标识读取。
     *
     * @param trashId Trash 标识
     * @return 条目
     */
    public Optional<ThirdPartyTrashRecord> find(String trashId) {
        return execute(connection -> find(connection, trashId));
    }

    /**
     * 查找指定 Bundle revision 的 Trash 结果，用于卸载命令幂等重试。
     *
     * @param extensionId 扩展标识
     * @param revision 被卸载 revision
     * @return 条目
     */
    public Optional<ThirdPartyTrashRecord> findByRevision(String extensionId, long revision) {
        return execute(connection -> findByRevision(connection, extensionId, revision));
    }

    /**
     * 以新的单调 revision 恢复已经重新验签的 Bundle。
     *
     * @param trash 当前 TRASHED 记录
     * @param descriptor 新 revision 描述
     * @param installDirectory 新受管安装目录
     * @return RESTORED tombstone
     */
    public ThirdPartyTrashRecord restore(
            ThirdPartyTrashRecord trash, ExtensionDescriptor descriptor, String installDirectory) {
        Objects.requireNonNull(trash, "trash");
        Objects.requireNonNull(descriptor, "descriptor");
        return execute(connection -> {
            ThirdPartyTrashRecord current =
                    requireTrashed(connection, trash.entry().trashId());
            if (!current.equals(trash)
                    || !descriptor.id().equals(current.descriptor().id())) {
                throw PersistenceException.revisionConflict("Trash 内容已改变");
            }
            requireExtensionAbsent(connection, descriptor.id().value());
            insertRestoredExtension(connection, current, descriptor, installDirectory);
            updateRestored(connection, current.entry().trashId(), descriptor.revision());
            return find(connection, current.entry().trashId()).orElseThrow();
        });
    }

    /**
     * 标记 Trash 文件已永久清除；保留最小 tombstone 防止 revision 重用。
     *
     * @param trashId Trash 标识
     * @param expectedRevision 被卸载 Bundle revision
     * @return PURGED tombstone
     */
    public ThirdPartyTrashRecord purge(String trashId, long expectedRevision) {
        return execute(connection -> {
            ThirdPartyTrashRecord current =
                    find(connection, trashId).orElseThrow(() -> PersistenceException.invalidRequest("Trash 条目不存在"));
            if (current.entry().revision() != expectedRevision) {
                throw PersistenceException.revisionConflict("Trash revision 已改变");
            }
            if (current.entry().state() == BundleRpcContracts.TrashState.PURGED) {
                return current;
            }
            if (current.entry().state() != BundleRpcContracts.TrashState.TRASHED) {
                throw PersistenceException.invalidRequest("仅 TRASHED 条目可以永久清除");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE CORE.THIRD_PARTY_REMOVAL SET STATE = ?, PURGED_AT = ? WHERE TRASH_NAME = ?
                    """)) {
                statement.setString(1, BundleRpcContracts.TrashState.PURGED.name());
                statement.setObject(2, Instant.now(clock).atOffset(ZoneOffset.UTC));
                statement.setString(3, trashId);
                statement.executeUpdate();
            }
            return find(connection, trashId).orElseThrow();
        });
    }

    private Optional<ThirdPartyTrashRecord> find(Connection connection, String trashId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT + " WHERE TRASH_NAME = ?")) {
            statement.setString(1, Objects.requireNonNull(trashId, "trashId"));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    private Optional<ThirdPartyTrashRecord> findByRevision(Connection connection, String extensionId, long revision)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(SELECT + " WHERE EXTENSION_ID = ? AND REVISION = ?")) {
            statement.setString(1, Objects.requireNonNull(extensionId, "extensionId"));
            statement.setLong(2, revision);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    private void requireRemoving(Connection connection, ThirdPartyExtensionRecord current, String trashName)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT E.STATE, E.REVISION, B.PENDING_TRASH_NAME
                FROM CORE.EXTENSION E JOIN CORE.THIRD_PARTY_BUNDLE B ON B.EXTENSION_ID = E.ID
                WHERE E.ID = ?
                """)) {
            statement.setString(1, current.descriptor().id().value());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()
                        || result.getLong("REVISION") != current.descriptor().revision()
                        || !ExtensionState.REMOVING.name().equals(result.getString("STATE"))
                        || !trashName.equals(result.getString("PENDING_TRASH_NAME"))) {
                    throw PersistenceException.revisionConflict("Bundle 移除意图已改变");
                }
            }
        }
    }

    private void insert(Connection connection, ThirdPartyExtensionRecord current, String trashName)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.THIRD_PARTY_REMOVAL
                    (EXTENSION_ID, REVISION, DESCRIPTOR, MANIFEST_DIGEST, SIGNING_KEY_ID,
                     PERMISSION_REVIEW, TRASH_NAME, STATE, REMOVED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, current.descriptor().id().value());
            statement.setLong(2, current.descriptor().revision());
            statement.setString(3, json.encode(current.descriptor()).json());
            statement.setString(4, current.manifestDigest());
            statement.setString(5, current.signingKeyId());
            statement.setString(6, json.encode(current.permissions()).json());
            statement.setString(7, trashName);
            statement.setString(8, BundleRpcContracts.TrashState.TRASHED.name());
            statement.setObject(9, Instant.now(clock).atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private static void deleteExtension(Connection connection, String extensionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM CORE.EXTENSION WHERE ID = ?")) {
            statement.setString(1, extensionId);
            statement.executeUpdate();
        }
    }

    private ThirdPartyTrashRecord requireTrashed(Connection connection, String trashId) throws SQLException {
        ThirdPartyTrashRecord record =
                find(connection, trashId).orElseThrow(() -> PersistenceException.invalidRequest("Trash 条目不存在"));
        if (record.entry().state() != BundleRpcContracts.TrashState.TRASHED) {
            throw PersistenceException.invalidRequest(
                    "Trash 条目不可恢复: " + record.entry().state());
        }
        return record;
    }

    private static void requireExtensionAbsent(Connection connection, String extensionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM CORE.EXTENSION WHERE ID = ?")) {
            statement.setString(1, extensionId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw PersistenceException.invalidRequest("同名 Bundle 已安装，不能恢复 Trash");
                }
            }
        }
    }

    private void insertRestoredExtension(
            Connection connection, ThirdPartyTrashRecord trash, ExtensionDescriptor descriptor, String installDirectory)
            throws SQLException {
        try (PreparedStatement extension = connection.prepareStatement("""
                INSERT INTO CORE.EXTENSION (ID, TRUST_LEVEL, STATE, REVISION, DESCRIPTOR, UPDATED_AT)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            extension.setString(1, descriptor.id().value());
            extension.setString(2, ExtensionTrust.THIRD_PARTY.name());
            extension.setString(3, ExtensionState.DISABLED.name());
            extension.setLong(4, descriptor.revision());
            extension.setString(5, json.encode(descriptor).json());
            extension.setObject(6, Instant.now(clock).atOffset(ZoneOffset.UTC));
            extension.executeUpdate();
        }
        try (PreparedStatement bundle = connection.prepareStatement("""
                INSERT INTO CORE.THIRD_PARTY_BUNDLE
                    (EXTENSION_ID, MANIFEST_DIGEST, SIGNING_KEY_ID, INSTALL_DIRECTORY,
                     PERMISSION_REVIEW, HEALTH_STATE)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            bundle.setString(1, descriptor.id().value());
            bundle.setString(2, trash.entry().manifestDigest());
            bundle.setString(3, trash.entry().signingKeyId());
            bundle.setString(4, installDirectory);
            bundle.setString(5, json.encode(trash.permissions()).json());
            bundle.setString(6, BundleRpcContracts.HealthState.DISABLED.name());
            bundle.executeUpdate();
        }
    }

    private static void updateRestored(Connection connection, String trashId, long restoredRevision)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.THIRD_PARTY_REMOVAL SET STATE = ?, RESTORED_REVISION = ? WHERE TRASH_NAME = ?
                """)) {
            statement.setString(1, BundleRpcContracts.TrashState.RESTORED.name());
            statement.setLong(2, restoredRevision);
            statement.setString(3, trashId);
            statement.executeUpdate();
        }
    }

    private ThirdPartyTrashRecord map(ResultSet result) throws SQLException {
        ExtensionDescriptor descriptor =
                json.decode(new CanonicalPayload(result.getString("DESCRIPTOR")), ExtensionDescriptor.class);
        Long restored = result.getObject("RESTORED_REVISION", Long.class);
        OffsetDateTime purged = result.getObject("PURGED_AT", OffsetDateTime.class);
        var entry = new BundleRpcContracts.TrashEntry(
                result.getString("TRASH_NAME"),
                result.getString("EXTENSION_ID"),
                descriptor.version(),
                result.getLong("REVISION"),
                result.getString("MANIFEST_DIGEST"),
                result.getString("SIGNING_KEY_ID"),
                BundleRpcContracts.TrashState.valueOf(result.getString("STATE")),
                result.getObject("REMOVED_AT", OffsetDateTime.class).toInstant(),
                Optional.ofNullable(restored),
                Optional.ofNullable(purged).map(OffsetDateTime::toInstant));
        BundleRpcContracts.PermissionReview permissions = json.decode(
                new CanonicalPayload(result.getString("PERMISSION_REVIEW")), BundleRpcContracts.PermissionReview.class);
        return new ThirdPartyTrashRecord(entry, descriptor, permissions);
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Bundle Trash 事务失败", failure);
        }
    }
}
