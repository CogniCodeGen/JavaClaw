package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.CanonicalJson;

/** Workspace 创建 SQL 与根目录冲突分类；原写入事务回滚后才独立核验竞争者，不能改变幂等提交边界。 */
final class WorkspaceCreation {
    private final WorkspaceRepository workspaces = new WorkspaceRepository();
    private final WorkspaceInstructionSettingsRepository instructions = new WorkspaceInstructionSettingsRepository();
    private final ExecutionConfigurationRepository configurations;
    private final H2Transactions transactions;

    WorkspaceCreation(H2Database database, CanonicalJson json) {
        transactions = new H2Transactions(database);
        configurations = new ExecutionConfigurationRepository(json);
    }

    Workspace execute(Path root, Supplier<Workspace> operation) {
        try {
            return operation.get();
        } catch (PersistenceException failure) {
            if (!workspaceInsertConflict(failure)) {
                throw failure;
            }
            throw classify(root, failure);
        }
    }

    Workspace insert(
            Connection connection, String name, Path root, Optional<ExecutionOverrides> execution, Instant createdAt)
            throws SQLException {
        Workspace workspace = workspaces.insert(connection, name, root, createdAt);
        instructions.insert(connection, workspace.id(), createdAt);
        if (execution.isPresent()) {
            configurations.insert(
                    connection,
                    new ExecutionConfiguration(
                            Optional.of(workspace.id()), Optional.empty(), execution.orElseThrow(), 1, createdAt));
        }
        return workspace;
    }

    private PersistenceException classify(Path root, PersistenceException failure) {
        try {
            // SERIALIZABLE 的失败事务看不到并发提交的新行。仅对 WORKSPACE 插入冲突独立核验 root，副写入故障不进入此处。
            return transactions
                    .execute(connection -> workspaces.findByRoot(connection, root))
                    .map(workspaces::rootConflict)
                    .orElse(failure);
        } catch (Exception verificationFailure) {
            failure.addSuppressed(verificationFailure);
            return failure;
        }
    }

    private boolean workspaceInsertConflict(PersistenceException failure) {
        if (failure.kind() != PersistenceException.Kind.INTERNAL) {
            return false;
        }
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof WorkspaceRepository.RootInsertConflict) {
                return true;
            }
        }
        return false;
    }
}
