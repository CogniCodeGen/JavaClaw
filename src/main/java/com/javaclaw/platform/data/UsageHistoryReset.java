package com.javaclaw.platform.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Performs the explicitly requested one-time destructive reset before any Run recovery starts. */
public final class UsageHistoryReset {
    static final String RESET_STATE_KEY = "usage-history-clean-reset-v1";
    private static final Logger log = LoggerFactory.getLogger(UsageHistoryReset.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public UsageHistoryReset(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    /**
     * Clears legacy Run and usage history exactly once. The reset marker is committed in the same
     * transaction, so a failure can neither leave a partial reset nor suppress a later retry.
     *
     * @return true when this invocation performed the reset; false when it was already complete
     */
    public boolean resetOnce() {
        Boolean reset = transactions.execute(status -> {
            if (alreadyReset()) return false;

            LinkedHashMap<String, Integer> affected = new LinkedHashMap<>();
            affected.put("agent_extension_state", jdbc.update("DELETE FROM agent_extension_state"));
            affected.put("agent_run_outbox", jdbc.update("DELETE FROM agent_run_outbox"));
            affected.put("agent_run_events", jdbc.update("DELETE FROM agent_run_events"));
            affected.put("agent_runs", jdbc.update("DELETE FROM agent_runs"));
            affected.put("compiled_execution_plans",
                    jdbc.update("DELETE FROM compiled_execution_plans"));

            affected.put("workflow_checkpoints", jdbc.update("DELETE FROM workflow_checkpoints"));
            affected.put("workflow_runs", jdbc.update("DELETE FROM workflow_runs"));
            affected.put("workflow_threads", jdbc.update("DELETE FROM workflow_threads"));

            affected.put("token_usage_projection_receipts",
                    jdbc.update("DELETE FROM token_usage_projection_receipts"));
            affected.put("token_usage_daily", jdbc.update("DELETE FROM token_usage_daily"));
            affected.put("chat_message_metrics", jdbc.update("""
                    UPDATE chat_messages
                    SET input_tokens = NULL,
                        cache_read_input_tokens = NULL,
                        cache_write_input_tokens = NULL,
                        output_tokens = NULL,
                        reasoning_tokens = NULL,
                        model_calls = NULL,
                        duration_ms = NULL
                    WHERE input_tokens IS NOT NULL
                       OR cache_read_input_tokens IS NOT NULL
                       OR cache_write_input_tokens IS NOT NULL
                       OR output_tokens IS NOT NULL
                       OR reasoning_tokens IS NOT NULL
                       OR model_calls IS NOT NULL
                       OR duration_ms IS NOT NULL
                    """));

            jdbc.update("""
                    INSERT INTO app_state(state_key, state_value, updated_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                    """, RESET_STATE_KEY, "completed");
            logResetCounts(affected);
            return true;
        });
        return Boolean.TRUE.equals(reset);
    }

    private boolean alreadyReset() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_state WHERE state_key = ?",
                Integer.class, RESET_STATE_KEY);
        return count != null && count > 0;
    }

    private static void logResetCounts(Map<String, Integer> affected) {
        String summary = affected.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
        log.warn("已按升级策略不可恢复地清空历史 Run 与用量数据: {}", summary);
    }
}
