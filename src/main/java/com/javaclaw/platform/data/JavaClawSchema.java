package com.javaclaw.platform.data;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * JavaClaw 3 初始数据库结构。
 *
 * <p>所有 DDL 都可重复执行；对象没有运行时状态，只由根 Context 的
 * {@link SchemaInitializer} 在启动时调用。这里不负责连接、目录解析或旧版本迁移。</p>
 */
final class JavaClawSchema {

    /**
     * 创建 JavaClaw 3 初始 schema。DDL 全部幂等；生产启动链只由根 Spring Context
     * 的 schema 初始化器调用一次，静态连接门面保留到旧调用方完成迁移为止。
     */
    void initialize(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS workspaces (
                        id VARCHAR(128) PRIMARY KEY,
                        name VARCHAR(512) NOT NULL,
                        created_at VARCHAR(64) NOT NULL,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS app_state (
                        state_key VARCHAR(128) PRIMARY KEY,
                        state_value CLOB,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS app_properties (
                        workspace_id VARCHAR(128) NOT NULL,
                        namespace VARCHAR(128) NOT NULL,
                        prop_key VARCHAR(256) NOT NULL,
                        prop_value CLOB,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (workspace_id, namespace, prop_key)
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_app_properties_ns ON app_properties(workspace_id, namespace)");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS mcp_servers (
                        workspace_id VARCHAR(128) NOT NULL,
                        name VARCHAR(256) NOT NULL,
                        command CLOB,
                        args_json CLOB,
                        env_json CLOB,
                        url CLOB,
                        headers_json CLOB,
                        enabled BOOLEAN NOT NULL,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (workspace_id, name)
                    )
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS site_credentials (
                        workspace_id VARCHAR(128) NOT NULL,
                        id VARCHAR(128) NOT NULL,
                        name VARCHAR(512),
                        host_pattern VARCHAR(512),
                        login_url CLOB,
                        username VARCHAR(512),
                        password_enc CLOB,
                        notes CLOB,
                        created_at BIGINT NOT NULL,
                        last_used_at BIGINT NOT NULL,
                        has_session BOOLEAN NOT NULL,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (workspace_id, id)
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_site_credentials_host ON site_credentials(workspace_id, host_pattern)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS site_sessions (
                        workspace_id VARCHAR(128) NOT NULL,
                        credential_id VARCHAR(128) NOT NULL,
                        storage_state_json CLOB NOT NULL,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (workspace_id, credential_id)
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS site_account_bindings (
                        workspace_id VARCHAR(128) NOT NULL,
                        scope_id VARCHAR(512) NOT NULL,
                        site_host VARCHAR(512) NOT NULL,
                        credential_id VARCHAR(128) NOT NULL,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (workspace_id, scope_id, site_host)
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_site_account_bindings_credential "
                    + "ON site_account_bindings(workspace_id, credential_id)");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS scheduled_tasks (
                        workspace_id VARCHAR(128) NOT NULL,
                        id VARCHAR(128) NOT NULL,
                        name VARCHAR(512),
                        description CLOB,
                        trigger_type VARCHAR(32),
                        interval_minutes INT NOT NULL,
                        interval_value INT NOT NULL,
                        interval_unit VARCHAR(32),
                        daily_time VARCHAR(32),
                        cron_expression CLOB,
                        once_date_time VARCHAR(64),
                        prompt CLOB,
                        enabled BOOLEAN NOT NULL,
                        version BIGINT NOT NULL DEFAULT 0,
                        last_run_time VARCHAR(64),
                        last_run_status VARCHAR(64),
                        last_duration VARCHAR(64),
                        run_count INT NOT NULL,
                        fail_count INT NOT NULL,
                        notify_enabled BOOLEAN NOT NULL,
                        notify_channel VARCHAR(64),
                        execution_history_json CLOB,
                        exec_records_json CLOB,
                        unattended_authorized BOOLEAN DEFAULT FALSE,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (workspace_id, id)
                    )
                    """);
            // 既有库迁移：为早于本列的 scheduled_tasks 补列（IF NOT EXISTS 幂等，新库已由上面 CREATE 带列）
            st.execute("ALTER TABLE scheduled_tasks "
                    + "ADD COLUMN IF NOT EXISTS unattended_authorized BOOLEAN DEFAULT FALSE");
            st.execute("ALTER TABLE scheduled_tasks "
                    + "ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0");
            // 旧工具创建 interval 任务时只更新 interval_minutes，遗留的 UI 字段仍可能是默认 60。
            // Quartz 一直以 interval_minutes 为准，迁移时同步展示字段以保持实际运行语义不变。
            st.execute("""
                    UPDATE scheduled_tasks
                    SET interval_value = CASE WHEN interval_minutes > 0 THEN interval_minutes ELSE 60 END,
                        interval_unit = 'minute'
                    WHERE trigger_type = 'interval'
                      AND (interval_value IS NULL OR interval_value <= 0
                           OR interval_minutes <> interval_value *
                              CASE interval_unit WHEN 'hour' THEN 60 WHEN 'day' THEN 1440 ELSE 1 END)
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS custom_agents (
                        workspace_id VARCHAR(128) NOT NULL,
                        id VARCHAR(128) NOT NULL,
                        name VARCHAR(512),
                        tool_name VARCHAR(256),
                        description CLOB,
                        sys_prompt CLOB,
                        max_iters INT NOT NULL,
                        enabled BOOLEAN NOT NULL,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (workspace_id, id)
                    )
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS plugin_state (
                        workspace_id VARCHAR(128) NOT NULL,
                        plugin_id VARCHAR(256) NOT NULL,
                        enabled BOOLEAN NOT NULL,
                        granted_json CLOB,
                        config_json CLOB,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (workspace_id, plugin_id)
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS plugin_storage (
                        workspace_id VARCHAR(128) NOT NULL,
                        plugin_id VARCHAR(256) NOT NULL,
                        store_key VARCHAR(512) NOT NULL,
                        store_value CLOB,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (workspace_id, plugin_id, store_key)
                    )
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS command_whitelist (
                        workspace_id VARCHAR(128) NOT NULL,
                        id VARCHAR(128) NOT NULL,
                        command_prefix CLOB NOT NULL,
                        work_dir CLOB,
                        added_at VARCHAR(64),
                        use_count INT NOT NULL,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (workspace_id, id)
                    )
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS chat_sessions (
                        workspace_id VARCHAR(128) NOT NULL,
                        id VARCHAR(128) NOT NULL,
                        title VARCHAR(512),
                        created_at VARCHAR(64) NOT NULL,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (workspace_id, id)
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_chat_sessions_created_at ON chat_sessions(workspace_id, created_at)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS chat_messages (
                        workspace_id VARCHAR(128) NOT NULL,
                        session_id VARCHAR(128) NOT NULL,
                        position INT NOT NULL,
                        message_id VARCHAR(96),
                        role VARCHAR(32) NOT NULL,
                        content CLOB,
                        timestamp VARCHAR(64) NOT NULL,
                        image_paths_json CLOB,
                        adopted BOOLEAN NOT NULL,
                        delivery_state VARCHAR(32),
                        input_tokens BIGINT,
                        cache_read_input_tokens BIGINT,
                        cache_write_input_tokens BIGINT,
                        output_tokens BIGINT,
                        reasoning_tokens BIGINT,
                        model_calls BIGINT,
                        duration_ms BIGINT,
                        PRIMARY KEY (workspace_id, session_id, position)
                    )
                    """);
            st.execute("ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS delivery_state VARCHAR(32)");
            st.execute("ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS message_id VARCHAR(96)");
            st.execute("ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS input_tokens BIGINT");
            st.execute("ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS cache_read_input_tokens BIGINT");
            st.execute("ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS cache_write_input_tokens BIGINT");
            st.execute("ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS output_tokens BIGINT");
            st.execute("ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS reasoning_tokens BIGINT");
            st.execute("ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS model_calls BIGINT");
            st.execute("ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS duration_ms BIGINT");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_chat_messages_message_id "
                    + "ON chat_messages(workspace_id, session_id, message_id)");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS conversation_context_summary (
                        workspace_id VARCHAR(128) NOT NULL,
                        session_id VARCHAR(128) NOT NULL,
                        summarized_messages INT NOT NULL,
                        cursor_message_id VARCHAR(96),
                        source_hash VARCHAR(128) NOT NULL,
                        summary_json CLOB NOT NULL,
                        rendered_summary CLOB NOT NULL,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (workspace_id, session_id)
                    )
                    """);
            st.execute("ALTER TABLE conversation_context_summary "
                    + "ADD COLUMN IF NOT EXISTS cursor_message_id VARCHAR(96)");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS token_usage_daily (
                        workspace_id VARCHAR(128) NOT NULL,
                        usage_date VARCHAR(32) NOT NULL,
                        input_tokens BIGINT NOT NULL,
                        pricing_input_tokens BIGINT NOT NULL DEFAULT 0,
                        output_tokens BIGINT NOT NULL,
                        metered_input BIGINT NOT NULL,
                        cached_input BIGINT NOT NULL,
                        cache_write_input BIGINT NOT NULL DEFAULT 0,
                        reasoning_tokens BIGINT NOT NULL DEFAULT 0,
                        model_calls BIGINT NOT NULL DEFAULT 0,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (workspace_id, usage_date)
                    )
                    """);
            st.execute("ALTER TABLE token_usage_daily ADD COLUMN IF NOT EXISTS "
                    + "pricing_input_tokens BIGINT NOT NULL DEFAULT 0");
            st.execute("ALTER TABLE token_usage_daily ADD COLUMN IF NOT EXISTS cache_write_input BIGINT NOT NULL DEFAULT 0");
            st.execute("ALTER TABLE token_usage_daily ADD COLUMN IF NOT EXISTS reasoning_tokens BIGINT NOT NULL DEFAULT 0");
            st.execute("ALTER TABLE token_usage_daily ADD COLUMN IF NOT EXISTS model_calls BIGINT NOT NULL DEFAULT 0");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS token_usage_projection_receipts (
                        workspace_id VARCHAR(128) NOT NULL,
                        model_call_id VARCHAR(512) NOT NULL,
                        run_id VARCHAR(128) NOT NULL,
                        event_timestamp_ms BIGINT NOT NULL,
                        usage_date VARCHAR(32) NOT NULL,
                        projected_at BIGINT NOT NULL,
                        PRIMARY KEY (workspace_id, model_call_id)
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_token_usage_receipts_run "
                    + "ON token_usage_projection_receipts(run_id)");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS skill_usage (
                        workspace_id VARCHAR(128) NOT NULL,
                        skill_name VARCHAR(512) NOT NULL,
                        route_hits BIGINT NOT NULL,
                        reads BIGINT NOT NULL,
                        turn_success BIGINT NOT NULL,
                        turn_fail BIGINT NOT NULL,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (workspace_id, skill_name)
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS skill_proposals (
                        workspace_id VARCHAR(128) NOT NULL,
                        id VARCHAR(128) NOT NULL,
                        request_json CLOB,
                        created_at BIGINT NOT NULL,
                        status VARCHAR(64) NOT NULL,
                        resolved_at BIGINT NOT NULL,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (workspace_id, id)
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_skill_proposals_status ON skill_proposals(workspace_id, status)");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS sdd_tasks (
                        workspace_id VARCHAR(128) NOT NULL,
                        id VARCHAR(128) NOT NULL,
                        task_json CLOB NOT NULL,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (workspace_id, id)
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS sdd_spec_docs (
                        workspace_id VARCHAR(128) NOT NULL,
                        work_dir VARCHAR(2048) NOT NULL,
                        slug VARCHAR(256) NOT NULL,
                        doc_path VARCHAR(1024) NOT NULL,
                        doc_text CLOB,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (workspace_id, work_dir, slug, doc_path)
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_sdd_spec_docs_slug ON sdd_spec_docs(workspace_id, slug)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS sdd_verify_cache (
                        workspace_id VARCHAR(128) NOT NULL,
                        work_dir VARCHAR(2048) NOT NULL,
                        slug VARCHAR(256) NOT NULL,
                        fingerprint VARCHAR(128),
                        passes_json CLOB,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (workspace_id, work_dir, slug)
                    )
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS knowledge_doc_prefs (
                        workspace_id VARCHAR(128) NOT NULL,
                        scope VARCHAR(32) NOT NULL,
                        doc_name VARCHAR(1024) NOT NULL,
                        excluded BOOLEAN NOT NULL,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (workspace_id, scope, doc_name)
                    )
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS browser_state (
                        workspace_id VARCHAR(128) NOT NULL,
                        state_key VARCHAR(128) NOT NULL,
                        state_json CLOB,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (workspace_id, state_key)
                    )
                    """);
            // 旧版把整个工作区所有网站的 Cookie/localStorage 存在单行中，任一聊天都会继承，
            // 会造成跨会话串号。认证态现已迁移为 site_sessions（按账号）+ 会话 Context（内存）。
            st.execute("DELETE FROM browser_state WHERE state_key = 'playwright-storage-state'");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS workflow_definitions (
                        workspace_id VARCHAR(128) NOT NULL,
                        id VARCHAR(128) NOT NULL,
                        name VARCHAR(512) NOT NULL,
                        description CLOB,
                        draft_json CLOB NOT NULL,
                        published_json CLOB,
                        draft_revision INT NOT NULL,
                        published_version INT NOT NULL,
                        archived BOOLEAN NOT NULL,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (workspace_id, id)
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_workflow_defs_archived "
                    + "ON workflow_definitions(workspace_id, archived, updated_at)");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS workflow_threads (
                        workspace_id VARCHAR(128) NOT NULL,
                        workflow_id VARCHAR(128) NOT NULL,
                        thread_id VARCHAR(512) NOT NULL,
                        state_json CLOB NOT NULL,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (workspace_id, workflow_id, thread_id)
                    )
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS workflow_runs (
                        workspace_id VARCHAR(128) NOT NULL,
                        id VARCHAR(128) NOT NULL,
                        workflow_id VARCHAR(128) NOT NULL,
                        workflow_version INT NOT NULL,
                        thread_id VARCHAR(512) NOT NULL,
                        definition_json CLOB NOT NULL,
                        state_json CLOB NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        current_node_id VARCHAR(256),
                        next_node_id VARCHAR(256),
                        step_count INT NOT NULL,
                        output_text CLOB,
                        error_text CLOB,
                        interrupt_json CLOB,
                        extension_locks_json CLOB,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (workspace_id, id)
                    )
                    """);
            st.execute("ALTER TABLE workflow_runs ADD COLUMN IF NOT EXISTS extension_locks_json CLOB");
            st.execute("ALTER TABLE workflow_runs ALTER COLUMN status VARCHAR(64) NOT NULL");
            st.execute("CREATE INDEX IF NOT EXISTS idx_workflow_runs_thread "
                    + "ON workflow_runs(workspace_id, workflow_id, thread_id, updated_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_workflow_runs_status "
                    + "ON workflow_runs(workspace_id, status, updated_at)");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS workflow_checkpoints (
                        workspace_id VARCHAR(128) NOT NULL,
                        run_id VARCHAR(128) NOT NULL,
                        seq INT NOT NULL,
                        node_id VARCHAR(256),
                        phase VARCHAR(32) NOT NULL,
                        state_json CLOB NOT NULL,
                        created_at BIGINT NOT NULL,
                        PRIMARY KEY (workspace_id, run_id, seq)
                    )
                    """);

            // Unified Agent Framework definitions and immutable published versions.
            st.execute("""
                    CREATE TABLE IF NOT EXISTS agent_definitions (
                        workspace_id VARCHAR(128) NOT NULL,
                        id VARCHAR(256) NOT NULL,
                        name VARCHAR(512) NOT NULL,
                        builtin BOOLEAN NOT NULL DEFAULT FALSE,
                        archived BOOLEAN NOT NULL DEFAULT FALSE,
                        draft_json CLOB,
                        draft_revision BIGINT NOT NULL DEFAULT 0,
                        published_version BIGINT NOT NULL DEFAULT 0,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (workspace_id, id)
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS agent_definition_versions (
                        workspace_id VARCHAR(128) NOT NULL,
                        definition_id VARCHAR(256) NOT NULL,
                        version BIGINT NOT NULL,
                        definition_json CLOB NOT NULL,
                        checksum VARCHAR(64) NOT NULL,
                        published_at BIGINT NOT NULL,
                        PRIMARY KEY (workspace_id, definition_id, version)
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_agent_definition_versions_latest "
                    + "ON agent_definition_versions(workspace_id, definition_id, version DESC)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS run_profiles (
                        workspace_id VARCHAR(128) NOT NULL,
                        id VARCHAR(256) NOT NULL,
                        name VARCHAR(512) NOT NULL,
                        builtin BOOLEAN NOT NULL DEFAULT FALSE,
                        archived BOOLEAN NOT NULL DEFAULT FALSE,
                        draft_json CLOB,
                        draft_revision BIGINT NOT NULL DEFAULT 0,
                        published_version BIGINT NOT NULL DEFAULT 0,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (workspace_id, id)
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS run_profile_versions (
                        workspace_id VARCHAR(128) NOT NULL,
                        profile_id VARCHAR(256) NOT NULL,
                        version BIGINT NOT NULL,
                        profile_json CLOB NOT NULL,
                        checksum VARCHAR(64) NOT NULL,
                        published_at BIGINT NOT NULL,
                        PRIMARY KEY (workspace_id, profile_id, version)
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_run_profile_versions_latest "
                    + "ON run_profile_versions(workspace_id, profile_id, version DESC)");

            // Exact plans are retained for paused/non-terminal run recovery.
            st.execute("""
                    CREATE TABLE IF NOT EXISTS compiled_execution_plans (
                        plan_id VARCHAR(128) PRIMARY KEY,
                        extension_generation BIGINT NOT NULL,
                        plan_json CLOB NOT NULL,
                        checksum VARCHAR(64) NOT NULL,
                        created_at BIGINT NOT NULL
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS agent_runs (
                        run_id VARCHAR(128) PRIMARY KEY,
                        workspace_id VARCHAR(128) NOT NULL,
                        user_id VARCHAR(256) NOT NULL,
                        session_id VARCHAR(256) NOT NULL,
                        idempotency_key VARCHAR(512),
                        request_json CLOB NOT NULL,
                        execution_plan_id VARCHAR(128) NOT NULL,
                        state VARCHAR(64) NOT NULL,
                        last_sequence BIGINT NOT NULL,
                        output_json CLOB,
                        error_text CLOB,
                        version BIGINT NOT NULL,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """);
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_runs_idempotency "
                    + "ON agent_runs(workspace_id, idempotency_key)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_agent_runs_state "
                    + "ON agent_runs(workspace_id, state, updated_at)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS agent_run_events (
                        run_id VARCHAR(128) NOT NULL,
                        event_sequence BIGINT NOT NULL,
                        timestamp_ms BIGINT NOT NULL,
                        type VARCHAR(256) NOT NULL,
                        schema_version INT NOT NULL,
                        producer VARCHAR(256) NOT NULL,
                        correlation_id VARCHAR(256),
                        causation_id VARCHAR(256),
                        payload_json CLOB NOT NULL,
                        PRIMARY KEY (run_id, event_sequence)
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS agent_run_outbox (
                        run_id VARCHAR(128) NOT NULL,
                        event_sequence BIGINT NOT NULL,
                        event_type VARCHAR(256) NOT NULL,
                        envelope_json CLOB NOT NULL,
                        created_at BIGINT NOT NULL,
                        published_at BIGINT,
                        PRIMARY KEY (run_id, event_sequence)
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_agent_run_outbox_pending "
                    + "ON agent_run_outbox(published_at, created_at)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS agent_extension_state (
                        run_id VARCHAR(128) NOT NULL,
                        extension_id VARCHAR(256) NOT NULL,
                        state_key VARCHAR(256) NOT NULL,
                        schema_version INT NOT NULL,
                        state_json CLOB NOT NULL,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (run_id, extension_id, state_key)
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS extension_artifact_cache (
                        extension_id VARCHAR(256) NOT NULL,
                        extension_version VARCHAR(64) NOT NULL,
                        artifact_sha256 VARCHAR(64) NOT NULL,
                        artifact_path CLOB NOT NULL,
                        authorized BOOLEAN NOT NULL,
                        enabled_for_new_runs BOOLEAN NOT NULL DEFAULT TRUE,
                        cached_at BIGINT NOT NULL,
                        PRIMARY KEY (extension_id, extension_version, artifact_sha256)
                    )
                    """);
            st.execute("ALTER TABLE extension_artifact_cache ADD COLUMN IF NOT EXISTS "
                    + "enabled_for_new_runs BOOLEAN NOT NULL DEFAULT TRUE");

            // 本地推理资产与运行时属于进程根 Context；工作区仅保存档位绑定。
            st.execute("""
                    CREATE TABLE IF NOT EXISTS inference_runtimes (
                        runtime_id VARCHAR(256) PRIMARY KEY,
                        engine VARCHAR(128) NOT NULL,
                        engine_version VARCHAR(64) NOT NULL,
                        adapter_version VARCHAR(64) NOT NULL,
                        protocol_major INT NOT NULL,
                        protocol_minor INT NOT NULL,
                        platform VARCHAR(64) NOT NULL,
                        architecture VARCHAR(64) NOT NULL,
                        manifest_json CLOB NOT NULL,
                        install_path CLOB NOT NULL,
                        runtime_state VARCHAR(32) NOT NULL,
                        active BOOLEAN NOT NULL,
                        installed_at BIGINT NOT NULL
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_inference_runtimes_active "
                    + "ON inference_runtimes(engine, active)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS inference_runtime_activation_history (
                        activation_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        engine VARCHAR(128) NOT NULL,
                        runtime_id VARCHAR(256) NOT NULL,
                        activated_at BIGINT NOT NULL
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_inference_runtime_history "
                    + "ON inference_runtime_activation_history(engine, activation_id DESC)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS inference_model_assets (
                        asset_id VARCHAR(128) PRIMARY KEY,
                        source_type VARCHAR(32) NOT NULL,
                        display_name VARCHAR(512),
                        model_type VARCHAR(128),
                        content_sha256 VARCHAR(64) NOT NULL UNIQUE,
                        asset_path CLOB NOT NULL,
                        hf_repository VARCHAR(512),
                        hf_commit VARCHAR(128),
                        files_json CLOB NOT NULL,
                        size_bytes BIGINT NOT NULL,
                        artifact_format VARCHAR(32) NOT NULL DEFAULT 'SAFETENSORS',
                        quantization_type VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
                        source_size_bytes BIGINT NOT NULL DEFAULT 0,
                        quantized_size_bytes BIGINT NOT NULL DEFAULT 0,
                        source_updated_at BIGINT NOT NULL DEFAULT 0,
                        asset_state VARCHAR(32) NOT NULL,
                        failure CLOB,
                        created_at BIGINT NOT NULL
                    )
                    """);
            st.execute("ALTER TABLE inference_model_assets ADD COLUMN IF NOT EXISTS "
                    + "display_name VARCHAR(512)");
            st.execute("ALTER TABLE inference_model_assets ADD COLUMN IF NOT EXISTS "
                    + "model_type VARCHAR(128)");
            st.execute("ALTER TABLE inference_model_assets ADD COLUMN IF NOT EXISTS "
                    + "artifact_format VARCHAR(32) NOT NULL DEFAULT 'SAFETENSORS'");
            st.execute("ALTER TABLE inference_model_assets ADD COLUMN IF NOT EXISTS "
                    + "quantization_type VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN'");
            st.execute("ALTER TABLE inference_model_assets ADD COLUMN IF NOT EXISTS "
                    + "source_size_bytes BIGINT NOT NULL DEFAULT 0");
            st.execute("ALTER TABLE inference_model_assets ADD COLUMN IF NOT EXISTS "
                    + "quantized_size_bytes BIGINT NOT NULL DEFAULT 0");
            st.execute("ALTER TABLE inference_model_assets ADD COLUMN IF NOT EXISTS "
                    + "source_updated_at BIGINT NOT NULL DEFAULT 0");
            st.execute("UPDATE inference_model_assets SET quantized_size_bytes = size_bytes "
                    + "WHERE quantized_size_bytes = 0 AND size_bytes > 0");
            st.execute("""
                    UPDATE inference_model_assets
                    SET display_name = CASE
                      WHEN hf_repository IS NOT NULL AND TRIM(hf_repository) <> ''
                        THEN REGEXP_REPLACE(hf_repository, '^.*/', '')
                      ELSE SUBSTRING(content_sha256, 1, 12)
                    END
                    WHERE display_name IS NULL OR TRIM(display_name) = ''
                    """);
            st.execute("""
                    UPDATE inference_model_assets SET model_type = 'unknown'
                    WHERE model_type IS NULL OR TRIM(model_type) = ''
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS inference_model_profiles (
                        profile_id VARCHAR(128) PRIMARY KEY,
                        profile_name VARCHAR(512) NOT NULL,
                        model_kind VARCHAR(32) NOT NULL,
                        asset_id VARCHAR(128) NOT NULL,
                        runtime_id VARCHAR(256) NOT NULL,
                        load_parameters_json CLOB NOT NULL,
                        default_parameters_json CLOB NOT NULL,
                        context_length INT NOT NULL,
                        embedding_dimensions INT NOT NULL,
                        profile_state VARCHAR(32) NOT NULL,
                        failure CLOB,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_inference_profiles_asset "
                    + "ON inference_model_profiles(asset_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_inference_profiles_runtime "
                    + "ON inference_model_profiles(runtime_id)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS inference_workspace_bindings (
                        workspace_id VARCHAR(128) NOT NULL,
                        model_tier VARCHAR(32) NOT NULL,
                        profile_id VARCHAR(128) NOT NULL,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (workspace_id, model_tier)
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_inference_bindings_profile "
                    + "ON inference_workspace_bindings(profile_id)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS inference_public_models (
                        model_alias VARCHAR(256) PRIMARY KEY,
                        profile_id VARCHAR(128) NOT NULL,
                        enabled BOOLEAN NOT NULL,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_inference_public_profile "
                    + "ON inference_public_models(profile_id)");
            st.execute("ALTER TABLE inference_model_profiles ADD CONSTRAINT IF NOT EXISTS "
                    + "fk_inference_profile_asset FOREIGN KEY (asset_id) "
                    + "REFERENCES inference_model_assets(asset_id)");
            st.execute("ALTER TABLE inference_model_profiles ADD CONSTRAINT IF NOT EXISTS "
                    + "fk_inference_profile_runtime FOREIGN KEY (runtime_id) "
                    + "REFERENCES inference_runtimes(runtime_id)");
            st.execute("ALTER TABLE inference_workspace_bindings ADD CONSTRAINT IF NOT EXISTS "
                    + "fk_inference_binding_profile FOREIGN KEY (profile_id) "
                    + "REFERENCES inference_model_profiles(profile_id)");
            st.execute("ALTER TABLE inference_public_models ADD CONSTRAINT IF NOT EXISTS "
                    + "fk_inference_public_profile FOREIGN KEY (profile_id) "
                    + "REFERENCES inference_model_profiles(profile_id)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS inference_gateway_config (
                        config_id VARCHAR(32) PRIMARY KEY,
                        enabled BOOLEAN NOT NULL,
                        bind_address VARCHAR(128) NOT NULL,
                        port INT NOT NULL,
                        tls_enabled BOOLEAN NOT NULL,
                        allow_insecure_lan BOOLEAN NOT NULL DEFAULT FALSE,
                        key_store_path CLOB,
                        key_store_password_enc CLOB,
                        max_request_bytes BIGINT NOT NULL,
                        request_timeout_seconds INT NOT NULL,
                        invocation_logging_enabled BOOLEAN NOT NULL DEFAULT FALSE,
                        updated_at BIGINT NOT NULL
                    )
                    """);
            st.execute("ALTER TABLE inference_gateway_config ADD COLUMN IF NOT EXISTS "
                    + "allow_insecure_lan BOOLEAN NOT NULL DEFAULT FALSE");
            st.execute("ALTER TABLE inference_gateway_config ADD COLUMN IF NOT EXISTS "
                    + "invocation_logging_enabled BOOLEAN NOT NULL DEFAULT FALSE");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS inference_api_keys (
                        key_id VARCHAR(128) PRIMARY KEY,
                        key_name VARCHAR(512) NOT NULL,
                        key_prefix VARCHAR(64) NOT NULL UNIQUE,
                        key_salt VARCHAR(128) NOT NULL,
                        key_digest VARCHAR(256) NOT NULL,
                        scopes_json CLOB NOT NULL,
                        models_json CLOB NOT NULL,
                        requests_per_minute INT NOT NULL,
                        tokens_per_minute BIGINT NOT NULL,
                        max_concurrent INT NOT NULL,
                        revoked BOOLEAN NOT NULL,
                        created_at BIGINT NOT NULL,
                        last_used_at BIGINT
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS inference_api_usage (
                        key_id VARCHAR(128) NOT NULL,
                        minute_bucket BIGINT NOT NULL,
                        request_count BIGINT NOT NULL,
                        prompt_tokens BIGINT NOT NULL,
                        completion_tokens BIGINT NOT NULL,
                        failed_count BIGINT NOT NULL,
                        PRIMARY KEY (key_id, minute_bucket)
                    )
                    """);
            st.execute("ALTER TABLE inference_api_usage ADD CONSTRAINT IF NOT EXISTS "
                    + "fk_inference_usage_key FOREIGN KEY (key_id) "
                    + "REFERENCES inference_api_keys(key_id)");

            // 独立服务插件属于根 Context；插件进程永远不能直接访问该表或 H2 连接。
            st.execute("""
                    CREATE TABLE IF NOT EXISTS service_plugin_config (
                        plugin_id VARCHAR(256) PRIMARY KEY,
                        plugin_version VARCHAR(64) NOT NULL,
                        artifact_sha256 VARCHAR(64) NOT NULL,
                        startup_policy VARCHAR(32) NOT NULL,
                        resources_json CLOB NOT NULL,
                        endpoints_json CLOB NOT NULL,
                        plugin_config_json CLOB DEFAULT '{}' NOT NULL,
                        quarantined BOOLEAN NOT NULL,
                        crash_history_json CLOB NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """);
            st.execute("ALTER TABLE service_plugin_config ADD COLUMN IF NOT EXISTS "
                    + "plugin_config_json CLOB DEFAULT '{}' NOT NULL");
        }
    }
}
