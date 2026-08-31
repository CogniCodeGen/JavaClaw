package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.SQLException;

import com.javaclaw.core.api.ItemId;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.TurnId;

/** 领域执行的查询投影；仅使用 Journal 传入的事务连接，不能另开事务破坏 Item/Event/Outbox 原子性。 */
final class H2ExecutionProjection {
    private H2ExecutionProjection() {}

    static void append(
            Connection connection,
            ThreadId thread,
            TurnId turn,
            ItemId itemId,
            ThreadItem item,
            String payload,
            long now)
            throws SQLException {
        if (item instanceof ThreadItem.Checkpoint checkpoint) {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO execution_checkpoints(item_id, thread_id, turn_id, execution_id,
                        definition_hash, step_id, status, payload_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, itemId.value());
                statement.setString(2, thread.value());
                statement.setString(3, turn.value());
                statement.setString(4, checkpoint.executionId());
                statement.setString(5, checkpoint.definitionHash());
                statement.setString(6, checkpoint.stepId());
                statement.setString(7, checkpoint.status());
                statement.setString(8, payload);
                statement.setLong(9, now);
                statement.executeUpdate();
            }
        } else if (item instanceof ThreadItem.EffectReceipt receipt) {
            // PENDING 不能覆盖既有凭据；即使模型重复提出相同操作，数据库也会阻止第二次启动。
            String sql = "PENDING".equals(receipt.state()) ? """
                    INSERT INTO effect_receipts(thread_id, effect_key, item_id, turn_id,
                        tool_name, state, payload_json, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """ : """
                    MERGE INTO effect_receipts(thread_id, effect_key, item_id, turn_id,
                        tool_name, state, payload_json, updated_at) KEY(thread_id, effect_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, thread.value());
                statement.setString(2, receipt.key());
                statement.setString(3, itemId.value());
                statement.setString(4, turn.value());
                statement.setString(5, receipt.tool());
                statement.setString(6, receipt.state());
                statement.setString(7, payload);
                statement.setLong(8, now);
                statement.executeUpdate();
            }
        } else if (item instanceof ThreadItem.Artifact artifact) {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO domain_artifacts(item_id, thread_id, artifact_id, category,
                        revision, payload_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, itemId.value());
                statement.setString(2, thread.value());
                statement.setString(3, artifact.artifactId());
                statement.setString(4, artifact.category());
                statement.setLong(5, artifact.revision());
                statement.setString(6, payload);
                statement.setLong(7, now);
                statement.executeUpdate();
            }
        }
    }
}
