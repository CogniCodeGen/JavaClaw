package com.javaclaw.platform.data;

import java.sql.SQLException;
import java.sql.Statement;

/** Additive, restartable schema for task identities, ordered journals and projections. */
final class ThreadSchema {
    private ThreadSchema() { }
    static void initialize(Statement st) throws SQLException {
        st.execute("""
                CREATE TABLE IF NOT EXISTS agent_threads (
                    workspace_id VARCHAR(128) NOT NULL, user_id VARCHAR(256) NOT NULL,
                    thread_id VARCHAR(256) NOT NULL, title VARCHAR(512) NOT NULL,
                    status VARCHAR(32) NOT NULL, configuration_json CLOB NOT NULL,
                    parent_thread_id VARCHAR(256), parent_turn_id VARCHAR(128),
                    fork_source_thread_id VARCHAR(256), fork_sequence BIGINT NOT NULL DEFAULT 0,
                    generation BIGINT NOT NULL DEFAULT 1, last_sequence BIGINT NOT NULL DEFAULT 0,
                    active_turn_id VARCHAR(128), created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
                    PRIMARY KEY(workspace_id, user_id, thread_id))
                """);
        st.execute("""
                CREATE TABLE IF NOT EXISTS agent_thread_events (
                    workspace_id VARCHAR(128) NOT NULL, user_id VARCHAR(256) NOT NULL,
                    thread_id VARCHAR(256) NOT NULL, event_sequence BIGINT NOT NULL,
                    timestamp_ms BIGINT NOT NULL, type VARCHAR(256) NOT NULL,
                    turn_id VARCHAR(128), payload_json CLOB NOT NULL,
                    PRIMARY KEY(workspace_id, user_id, thread_id, event_sequence))
                """);
        st.execute("""
                CREATE TABLE IF NOT EXISTS agent_thread_outbox (
                    workspace_id VARCHAR(128) NOT NULL, user_id VARCHAR(256) NOT NULL,
                    thread_id VARCHAR(256) NOT NULL, event_sequence BIGINT NOT NULL,
                    generation BIGINT NOT NULL, published_at BIGINT,
                    PRIMARY KEY(workspace_id, user_id, thread_id, event_sequence))
                """);
        st.execute("CREATE INDEX IF NOT EXISTS idx_thread_children ON agent_threads"
                + "(workspace_id,user_id,parent_thread_id)");
        st.execute("""
                CREATE TABLE IF NOT EXISTS agent_thread_mutations (
                    workspace_id VARCHAR(128) NOT NULL,user_id VARCHAR(256) NOT NULL,thread_id VARCHAR(256) NOT NULL,
                    mutation_id VARCHAR(256) NOT NULL,event_sequence BIGINT NOT NULL,
                    PRIMARY KEY(workspace_id,user_id,thread_id,mutation_id))
                """);
        st.execute("DROP INDEX IF EXISTS idx_agent_runs_idempotency");
        st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_turns_idempotency ON agent_runs"
                + "(workspace_id,user_id,session_id,idempotency_key)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_agent_turns_thread ON agent_runs"
                + "(workspace_id,user_id,session_id,created_at)");
        st.execute("""
                CREATE TABLE IF NOT EXISTS workflow_thread_lifecycle (
                    workspace_id VARCHAR(128) NOT NULL, thread_id VARCHAR(512) NOT NULL,
                    deleted BOOLEAN NOT NULL DEFAULT FALSE, PRIMARY KEY(workspace_id,thread_id))
                """);
    }
}
