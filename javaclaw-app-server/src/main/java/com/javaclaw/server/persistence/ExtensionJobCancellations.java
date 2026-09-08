package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.extension.spi.ExtensionJobStepResult;

/**
 * 将取消意图与执行 revision 分离，避免取消命令使活动单元的真实结果无法提交。
 *
 * <p>SQL 行是恢复权威，进程内信号只负责及时唤醒；先注册信号再读 SQL，消除取消与启动的竞态。
 */
final class ExtensionJobCancellations {
    private final Map<String, CancellationSource> active = new ConcurrentHashMap<>();

    void request(Connection connection, String jobId, Instant now) throws SQLException {
        if (requested(connection, jobId)) {
            return;
        }
        try (var statement = connection.prepareStatement(
                "INSERT INTO CORE.EXTENSION_JOB_CANCELLATION (JOB_ID, REQUESTED_AT) VALUES (?, ?)")) {
            statement.setString(1, jobId);
            statement.setObject(2, now.atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    boolean requested(Connection connection, String jobId) throws SQLException {
        try (var statement =
                connection.prepareStatement("SELECT JOB_ID FROM CORE.EXTENSION_JOB_CANCELLATION WHERE JOB_ID = ?")) {
            statement.setString(1, jobId);
            try (var result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    void publish(String jobId) {
        CancellationSource cancellation = active.get(jobId);
        if (cancellation != null) {
            cancellation.cancel("用户取消后台任务");
        }
    }

    void register(String jobId, CancellationSource cancellation) {
        if (active.putIfAbsent(jobId, cancellation) != null) {
            throw new IllegalStateException("Job 已有活动取消信号");
        }
    }

    void remove(String jobId, CancellationSource cancellation) {
        active.remove(jobId, cancellation);
    }

    ExtensionJobStepResult afterStep(Connection connection, String jobId, ExtensionJobStepResult step)
            throws SQLException {
        if (!requested(connection, jobId) || step.nextState() != ExecutionState.RUNNING) {
            // 已经完成的结果保留；等待输入或结果未知由扩展的恢复协议处理，不能伪造取消完成。
            return step;
        }
        return new ExtensionJobStepResult(
                step.result(), step.checkpoint(), ExecutionState.CANCELLED, step.turnId(), step.effectReceiptKey());
    }
}
