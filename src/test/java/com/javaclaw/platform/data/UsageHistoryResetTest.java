package com.javaclaw.platform.data;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageHistoryResetTest {

    @Test
    void clearsOnlyRunAndUsageHistoryAndNeverRepeatsAfterTheMarkerCommits() throws Exception {
        Database database = database("scope");
        seed(database.jdbc());
        UsageHistoryReset reset = new UsageHistoryReset(
                database.jdbc(), database.transactions());

        assertTrue(reset.resetOnce());

        for (String table : new String[] {
                "agent_extension_state", "agent_run_outbox", "agent_run_events",
                "agent_runs", "compiled_execution_plans", "workflow_checkpoints",
                "workflow_runs", "workflow_threads", "token_usage_projection_receipts",
                "token_usage_daily"}) {
            assertEquals(0, count(database.jdbc(), table), table);
        }
        assertEquals(1, count(database.jdbc(), "workflow_definitions"));
        assertEquals(1, count(database.jdbc(), "agent_definitions"));
        assertEquals(1, count(database.jdbc(), "run_profiles"));
        assertEquals(1, count(database.jdbc(), "app_properties"));
        assertEquals(1, count(database.jdbc(), "chat_sessions"));
        assertEquals(1, count(database.jdbc(), "chat_messages"));
        assertEquals("保留的正文", database.jdbc().queryForObject(
                "SELECT content FROM chat_messages", String.class));
        assertEquals("[\"attachment.png\"]", database.jdbc().queryForObject(
                "SELECT image_paths_json FROM chat_messages", String.class));
        for (String metric : new String[] {
                "input_tokens", "cache_read_input_tokens", "cache_write_input_tokens",
                "output_tokens", "reasoning_tokens", "model_calls", "duration_ms"}) {
            assertNull(database.jdbc().queryForObject(
                    "SELECT " + metric + " FROM chat_messages", Long.class), metric);
        }
        assertEquals("completed", database.jdbc().queryForObject(
                "SELECT state_value FROM app_state WHERE state_key = ?",
                String.class, UsageHistoryReset.RESET_STATE_KEY));

        // Rows written by the new version must survive every later startup.
        database.jdbc().update("""
                INSERT INTO agent_runs(run_id, workspace_id, user_id, session_id, request_json,
                    execution_plan_id, state, last_sequence, version, created_at, updated_at)
                VALUES ('new-run', 'ws', 'user', 'session', '{}', 'new-plan',
                    'RUNNING', 0, 0, 2, 2)
                """);
        database.jdbc().update("""
                INSERT INTO token_usage_daily(workspace_id, usage_date, input_tokens,
                    pricing_input_tokens, output_tokens, metered_input, cached_input,
                    cache_write_input, reasoning_tokens, model_calls)
                VALUES ('ws', '2026-08-25', 11, 7, 3, 11, 2, 1, 1, 1)
                """);
        database.jdbc().update("UPDATE chat_messages SET input_tokens = 9, model_calls = 1");

        assertFalse(reset.resetOnce());
        assertEquals(1, count(database.jdbc(), "agent_runs"));
        assertEquals(1, count(database.jdbc(), "token_usage_daily"));
        assertEquals(9L, database.jdbc().queryForObject(
                "SELECT input_tokens FROM chat_messages", Long.class));
    }

    @Test
    void failureRollsBackEveryEarlierDeleteAndDoesNotWriteTheMarker() throws Exception {
        Database database = database("rollback");
        seed(database.jdbc());
        database.jdbc().execute("DROP TABLE workflow_threads");
        UsageHistoryReset reset = new UsageHistoryReset(
                database.jdbc(), database.transactions());

        assertThrows(DataAccessException.class, reset::resetOnce);

        assertEquals(1, count(database.jdbc(), "agent_extension_state"));
        assertEquals(1, count(database.jdbc(), "agent_run_outbox"));
        assertEquals(1, count(database.jdbc(), "agent_run_events"));
        assertEquals(1, count(database.jdbc(), "agent_runs"));
        assertEquals(1, count(database.jdbc(), "compiled_execution_plans"));
        assertEquals(1, count(database.jdbc(), "workflow_checkpoints"));
        assertEquals(1, count(database.jdbc(), "workflow_runs"));
        assertEquals(0, database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM app_state WHERE state_key = ?",
                Integer.class, UsageHistoryReset.RESET_STATE_KEY));
    }

    private static Database database(String suffix) throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:usage-reset-" + suffix + '-' + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            new JavaClawSchema().initialize(connection);
        }
        return new Database(new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource));
    }

    private static void seed(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO workspaces(id, name, created_at) VALUES ('ws', '工作区', 'now')");
        jdbc.update("""
                INSERT INTO app_properties(workspace_id, namespace, prop_key, prop_value)
                VALUES ('ws', 'config', 'kept', 'value')
                """);
        jdbc.update("""
                INSERT INTO chat_sessions(workspace_id, id, title, created_at)
                VALUES ('ws', 'session', '保留的会话', 'now')
                """);
        jdbc.update("""
                INSERT INTO chat_messages(workspace_id, session_id, position, message_id, role,
                    content, timestamp, image_paths_json, adopted, delivery_state,
                    input_tokens, cache_read_input_tokens, cache_write_input_tokens,
                    output_tokens, reasoning_tokens, model_calls, duration_ms)
                VALUES ('ws', 'session', 0, 'message', 'ASSISTANT', '保留的正文', 'now',
                    '["attachment.png"]', TRUE, 'DELIVERED', 10, 3, 2, 4, 1, 1, 99)
                """);
        jdbc.update("""
                INSERT INTO workflow_definitions(workspace_id, id, name, description,
                    draft_json, published_json, draft_revision, published_version,
                    archived, created_at, updated_at)
                VALUES ('ws', 'workflow', '保留的工作流', '', '{}', '{}', 1, 1, FALSE, 1, 1)
                """);
        jdbc.update("""
                INSERT INTO workflow_threads(workspace_id, workflow_id, thread_id,
                    state_json, updated_at)
                VALUES ('ws', 'workflow', 'thread', '{}', 1)
                """);
        jdbc.update("""
                INSERT INTO workflow_runs(workspace_id, id, workflow_id, workflow_version,
                    thread_id, definition_json, state_json, status, step_count,
                    created_at, updated_at)
                VALUES ('ws', 'workflow-run', 'workflow', 1, 'thread', '{}', '{}',
                    'RUNNING', 1, 1, 1)
                """);
        jdbc.update("""
                INSERT INTO workflow_checkpoints(workspace_id, run_id, seq, phase,
                    state_json, created_at)
                VALUES ('ws', 'workflow-run', 1, 'AFTER', '{}', 1)
                """);
        jdbc.update("""
                INSERT INTO agent_definitions(workspace_id, id, name, draft_json,
                    created_at, updated_at)
                VALUES ('ws', 'agent', '保留的 Agent', '{}', 1, 1)
                """);
        jdbc.update("""
                INSERT INTO run_profiles(workspace_id, id, name, draft_json,
                    created_at, updated_at)
                VALUES ('ws', 'profile', '保留的 Profile', '{}', 1, 1)
                """);
        jdbc.update("""
                INSERT INTO compiled_execution_plans(plan_id, extension_generation,
                    plan_json, checksum, created_at)
                VALUES ('plan', 1, '{}', 'checksum', 1)
                """);
        jdbc.update("""
                INSERT INTO agent_runs(run_id, workspace_id, user_id, session_id,
                    request_json, execution_plan_id, state, last_sequence, version,
                    created_at, updated_at)
                VALUES ('run', 'ws', 'user', 'session', '{}', 'plan', 'RUNNING', 1, 0, 1, 1)
                """);
        jdbc.update("""
                INSERT INTO agent_run_events(run_id, event_sequence, timestamp_ms, type,
                    schema_version, producer, payload_json)
                VALUES ('run', 1, 1, 'core.model.usage', 3, 'test', '{}')
                """);
        jdbc.update("""
                INSERT INTO agent_run_outbox(run_id, event_sequence, event_type,
                    envelope_json, created_at)
                VALUES ('run', 1, 'core.model.usage', '{}', 1)
                """);
        jdbc.update("""
                INSERT INTO agent_extension_state(run_id, extension_id, state_key,
                    schema_version, state_json, updated_at)
                VALUES ('run', 'extension', 'state', 1, '{}', 1)
                """);
        jdbc.update("""
                INSERT INTO token_usage_daily(workspace_id, usage_date, input_tokens,
                    pricing_input_tokens, output_tokens, metered_input, cached_input,
                    cache_write_input, reasoning_tokens, model_calls)
                VALUES ('ws', '2026-08-24', 10, 7, 4, 10, 3, 2, 1, 1)
                """);
        jdbc.update("""
                INSERT INTO token_usage_projection_receipts(workspace_id, model_call_id,
                    run_id, event_timestamp_ms, usage_date, projected_at)
                VALUES ('ws', 'call', 'run', 1, '2026-08-24', 1)
                """);
    }

    private static int count(JdbcTemplate jdbc, String table) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private record Database(
            JdbcTemplate jdbc, DataSourceTransactionManager transactions) { }
}
