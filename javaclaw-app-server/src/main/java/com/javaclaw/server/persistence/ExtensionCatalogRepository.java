package com.javaclaw.server.persistence;

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
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionAccessDeniedException;
import com.javaclaw.extension.spi.ExtensionAvailability;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionState;
import com.javaclaw.extension.spi.ExtensionTrust;
import com.javaclaw.protocol.BuiltinExtensionRpcContracts;
import com.javaclaw.protocol.CanonicalJson;

/** CORE.EXTENSION 的内置安装快照与所有扩展实时状态检查。 */
public final class ExtensionCatalogRepository {
    private final H2Transactions transactions;
    private final IdempotentCommandStore commands = new IdempotentCommandStore();
    private final CanonicalJson json;
    private final Clock clock;

    /**
     * 创建目录仓储。
     *
     * @param database data-v6 数据库
     * @param json 共享 JSON codec
     * @param clock 平台时钟
     */
    public ExtensionCatalogRepository(H2Database database, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 安装或校验随发行版提供的 Bundle；已有禁用状态不会被启动过程覆盖。
     *
     * @param descriptor 已启动并验证的内置描述
     */
    public void installBuiltIn(ExtensionDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (descriptor.requirements().trust() != ExtensionTrust.BUILT_IN) {
            throw new IllegalArgumentException("only built-in descriptors may be installed in-process");
        }
        execute(connection -> {
            Optional<StoredExtension> stored = find(connection, descriptor.id());
            if (stored.isEmpty()) {
                insert(connection, descriptor);
            } else {
                requireSameBundle(descriptor, stored.orElseThrow());
            }
            return null;
        });
    }

    /**
     * 读取扩展实时状态。
     *
     * @param id 扩展标识
     * @return 状态
     */
    public ExtensionState state(ExtensionId id) {
        return execute(connection -> find(connection, id)
                .map(StoredExtension::state)
                .orElseThrow(() -> new ExtensionAccessDeniedException("extension is not installed")));
    }

    /**
     * 在每次调用前检查 enabled 与 revision。
     *
     * @param id 扩展标识
     * @param expectedRevision 冻结版本
     */
    public void requireEnabled(ExtensionId id, long expectedRevision) {
        StoredExtension stored = execute(connection -> find(connection, id)
                .orElseThrow(() -> new ExtensionAccessDeniedException("extension is not installed")));
        if (stored.state() != ExtensionState.ENABLED) {
            throw new ExtensionAccessDeniedException("extension is not enabled: " + id.value());
        }
        if (stored.revision() != expectedRevision) {
            throw new ExtensionAccessDeniedException("extension revision changed: " + id.value());
        }
    }

    /**
     * 在平台能力没有独立调用 revision 时检查实时启停状态。
     *
     * @param id 扩展标识
     */
    public void requireEnabled(ExtensionId id) {
        ExtensionState current = state(Objects.requireNonNull(id, "id"));
        if (current != ExtensionState.ENABLED) {
            throw new ExtensionAccessDeniedException("extension is not enabled: " + id.value());
        }
    }

    /**
     * 列出随发行版提供、由统一目录治理的内置能力。
     *
     * @return 按扩展标识排序的实时状态
     */
    public List<BuiltinExtensionRpcContracts.Status> listBuiltIns() {
        return execute(connection -> {
            List<BuiltinExtensionRpcContracts.Status> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT ID, TRUST_LEVEL, STATE, REVISION, STATE_REVISION, DESCRIPTOR, UPDATED_AT
                    FROM CORE.EXTENSION WHERE TRUST_LEVEL = ? ORDER BY ID
                    """)) {
                statement.setString(1, ExtensionTrust.BUILT_IN.name());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(toStatus(map(rows)));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    /**
     * 读取一个内置能力的实时状态。
     *
     * @param extensionId 内置扩展标识
     * @return 权威状态
     */
    public BuiltinExtensionRpcContracts.Status requireBuiltIn(String extensionId) {
        ExtensionId id = new ExtensionId(extensionId);
        return execute(connection -> toStatus(requireBuiltIn(connection, id)));
    }

    /**
     * 启用可选内置能力。
     *
     * @param identity 幂等身份；expected revision 为当前状态版本
     * @param extensionId 内置扩展标识
     * @return 提交后的权威状态
     */
    public BuiltinExtensionRpcContracts.Status enable(CommandIdentity identity, String extensionId) {
        return transition(identity, new ExtensionId(extensionId), ExtensionState.ENABLED);
    }

    /**
     * 停用可选内置能力。
     *
     * @param identity 幂等身份；expected revision 为当前状态版本
     * @param extensionId 内置扩展标识
     * @return 提交后的权威状态
     */
    public BuiltinExtensionRpcContracts.Status disable(CommandIdentity identity, String extensionId) {
        return transition(identity, new ExtensionId(extensionId), ExtensionState.DISABLED);
    }

    private BuiltinExtensionRpcContracts.Status transition(
            CommandIdentity identity, ExtensionId id, ExtensionState desired) {
        Objects.requireNonNull(identity, "identity");
        String expectedMethod =
                desired == ExtensionState.ENABLED ? "extension/builtin/enable" : "extension/builtin/disable";
        if (!expectedMethod.equals(identity.method()) || identity.expectedRevision() < 1) {
            throw PersistenceException.invalidRequest("内置扩展命令身份无效");
        }
        synchronized (CommandLocks.forKey("builtin-extension:" + id.value())) {
            return execute(connection -> transition(connection, identity, id, desired));
        }
    }

    private BuiltinExtensionRpcContracts.Status transition(
            java.sql.Connection connection, CommandIdentity identity, ExtensionId id, ExtensionState desired)
            throws SQLException {
        Optional<CanonicalPayload> recovered = commands.recover(connection, identity);
        if (recovered.isPresent()) {
            return json.decode(recovered.orElseThrow(), BuiltinExtensionRpcContracts.Status.class);
        }
        StoredExtension stored = requireBuiltIn(connection, id);
        ExtensionDescriptor descriptor = decodeStoredDescriptor(id, stored.descriptor());
        if (descriptor.requirements().availability() != ExtensionAvailability.OPTIONAL) {
            throw PersistenceException.invalidRequest("必需内置扩展不能停用");
        }
        if (stored.stateRevision() != identity.expectedRevision()) {
            throw PersistenceException.revisionConflict("内置扩展状态 revision 已变化");
        }
        Instant updatedAt = Instant.now(clock);
        long nextRevision = Math.addExact(stored.stateRevision(), 1);
        if (stored.state() != desired) {
            updateState(connection, id, desired, nextRevision, updatedAt);
            stored = stored.withState(desired, nextRevision, updatedAt);
        }
        BuiltinExtensionRpcContracts.Status status = toStatus(stored);
        commands.record(connection, identity, json.encode(status), updatedAt);
        return status;
    }

    private void updateState(
            java.sql.Connection connection, ExtensionId id, ExtensionState state, long stateRevision, Instant updatedAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.EXTENSION SET STATE = ?, STATE_REVISION = ?, UPDATED_AT = ?
                WHERE ID = ? AND TRUST_LEVEL = ?
                """)) {
            statement.setString(1, state.name());
            statement.setLong(2, stateRevision);
            statement.setObject(3, updatedAt.atOffset(ZoneOffset.UTC));
            statement.setString(4, id.value());
            statement.setString(5, ExtensionTrust.BUILT_IN.name());
            if (statement.executeUpdate() != 1) {
                throw PersistenceException.revisionConflict("内置扩展状态更新失败");
            }
        }
    }

    private Optional<StoredExtension> find(java.sql.Connection connection, ExtensionId id)
            throws java.sql.SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ID, TRUST_LEVEL, STATE, REVISION, STATE_REVISION, DESCRIPTOR, UPDATED_AT
                FROM CORE.EXTENSION WHERE ID = ?
                """)) {
            statement.setString(1, id.value());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(map(result));
            }
        }
    }

    private StoredExtension requireBuiltIn(java.sql.Connection connection, ExtensionId id) throws SQLException {
        StoredExtension stored = find(connection, id).orElseThrow(() -> PersistenceException.invalidRequest("内置扩展不存在"));
        if (stored.trust() != ExtensionTrust.BUILT_IN) {
            throw PersistenceException.invalidRequest("目标不是内置扩展");
        }
        return stored;
    }

    private void insert(java.sql.Connection connection, ExtensionDescriptor descriptor) throws java.sql.SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.EXTENSION (ID, TRUST_LEVEL, STATE, REVISION, DESCRIPTOR, UPDATED_AT)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, descriptor.id().value());
            statement.setString(2, ExtensionTrust.BUILT_IN.name());
            statement.setString(3, ExtensionState.ENABLED.name());
            statement.setLong(4, descriptor.revision());
            statement.setString(5, json.encode(descriptor).json());
            statement.setObject(6, Instant.now(clock).atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private void requireSameBundle(ExtensionDescriptor descriptor, StoredExtension stored) {
        if (stored.trust() != ExtensionTrust.BUILT_IN || stored.revision() != descriptor.revision()) {
            throw new PersistenceException("内置扩展目录与发行版描述不一致: " + descriptor.id().value());
        }
        ExtensionDescriptor installed = decodeStoredDescriptor(descriptor.id(), stored.descriptor());
        if (!installed.equals(descriptor)) {
            throw new PersistenceException("内置扩展目录与发行版描述不一致: " + descriptor.id().value());
        }
    }

    private ExtensionDescriptor decodeStoredDescriptor(ExtensionId id, String payload) {
        try {
            return json.decode(new CanonicalPayload(payload), ExtensionDescriptor.class);
        } catch (RuntimeException invalid) {
            throw new PersistenceException("内置扩展目录损坏: " + id.value(), invalid);
        }
    }

    private BuiltinExtensionRpcContracts.Status toStatus(StoredExtension stored) {
        ExtensionDescriptor descriptor = decodeStoredDescriptor(stored.id(), stored.descriptor());
        if (!descriptor.id().equals(stored.id()) || descriptor.revision() != stored.revision()) {
            throw new PersistenceException("内置扩展目录行身份与描述不一致: " + stored.id().value());
        }
        BuiltinExtensionRpcContracts.RuntimeKind runtimeKind =
                descriptor.contributionKinds().contains(ContributionKind.MCP)
                        ? BuiltinExtensionRpcContracts.RuntimeKind.PLATFORM
                        : BuiltinExtensionRpcContracts.RuntimeKind.BUNDLE;
        return new BuiltinExtensionRpcContracts.Status(
                stored.id().value(),
                descriptor.displayName(),
                descriptor.version(),
                stored.revision(),
                stored.stateRevision(),
                descriptor.requirements().availability(),
                stored.state(),
                runtimeKind,
                descriptor.contributionKinds(),
                stored.updatedAt());
    }

    private static StoredExtension map(ResultSet result) throws SQLException {
        return new StoredExtension(
                new ExtensionId(result.getString("ID")),
                ExtensionTrust.valueOf(result.getString("TRUST_LEVEL")),
                ExtensionState.valueOf(result.getString("STATE")),
                result.getLong("REVISION"),
                result.getLong("STATE_REVISION"),
                result.getString("DESCRIPTOR"),
                result.getObject("UPDATED_AT", OffsetDateTime.class).toInstant());
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Extension 目录事务失败", failure);
        }
    }

    private record StoredExtension(
            ExtensionId id,
            ExtensionTrust trust,
            ExtensionState state,
            long revision,
            long stateRevision,
            String descriptor,
            Instant updatedAt) {
        private StoredExtension withState(ExtensionState nextState, long nextRevision, Instant nextUpdatedAt) {
            return new StoredExtension(id, trust, nextState, revision, nextRevision, descriptor, nextUpdatedAt);
        }
    }
}
