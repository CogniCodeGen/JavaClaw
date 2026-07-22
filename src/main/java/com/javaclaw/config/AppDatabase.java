package com.javaclaw.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 全局 H2 数据库入口。
 *
 * <p>全应用只使用一个数据库文件：{@code data/javaclaw.mv.db}。
 * 工作区隔离由各业务表的 {@code workspace_id} 字段完成，调用方按当前工作区读写。</p>
 */
public final class AppDatabase {

    private static final Logger log = LoggerFactory.getLogger(AppDatabase.class);
    private static final String DB_BASENAME = "javaclaw";
    private static volatile boolean autoServerUnavailable;

    private AppDatabase() {}

    public static Connection getConnection() throws SQLException {
        Path dbBase = databaseBasePath();
        try {
            Files.createDirectories(dbBase.getParent());
        } catch (IOException e) {
            throw new SQLException("创建数据库目录失败: " + dbBase.getParent(), e);
        }
        Connection c = openConnection(dbBase);
        try {
            initSchema(c);
            return c;
        } catch (SQLException | RuntimeException e) {
            try {
                c.close();
            } catch (SQLException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    public static String currentWorkspaceId() {
        return WorkspaceManager.getInstance().getCurrentWorkspaceId();
    }

    public static Path databaseFilePath() {
        return databaseBasePath().resolveSibling(DB_BASENAME + ".mv.db");
    }

    public static String databaseDisplayPath() {
        return databaseFilePath().toString();
    }

    private static Path databaseBasePath() {
        return Path.of(System.getProperty("user.dir"), "data", DB_BASENAME)
                .toAbsolutePath().normalize();
    }

    private static Connection openConnection(Path dbBase) throws SQLException {
        if (!autoServerUnavailable) {
            try {
                return DriverManager.getConnection(jdbcUrl(dbBase, true), "sa", "");
            } catch (SQLException e) {
                if (!canFallbackToEmbedded(e)) {
                    throw e;
                }
                autoServerUnavailable = true;
                log.warn("H2 mixed mode 不可用，回退 embedded 模式: {}", e.getMessage());
            }
        }
        return DriverManager.getConnection(jdbcUrl(dbBase, false), "sa", "");
    }

    private static boolean canFallbackToEmbedded(SQLException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        return e.getErrorCode() == 50100
                || e.getErrorCode() == 90031
                || message.contains("AUTO_SERVER")
                || message.contains("SocketException")
                || message.contains("Operation not permitted");
    }

    private static String jdbcUrl(Path dbBase, boolean autoServer) {
        String path = dbBase.toString().replace('\\', '/');
        String url = "jdbc:h2:file:" + path
                + ";DATABASE_TO_UPPER=false"
                + ";LOCK_TIMEOUT=10000"
                + ";TRACE_LEVEL_FILE=0";
        return autoServer ? url + ";AUTO_SERVER=TRUE" : url;
    }

    private static void initSchema(Connection c) throws SQLException {
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
                        role VARCHAR(32) NOT NULL,
                        content CLOB,
                        timestamp VARCHAR(64) NOT NULL,
                        image_paths_json CLOB,
                        adopted BOOLEAN NOT NULL,
                        PRIMARY KEY (workspace_id, session_id, position)
                    )
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS token_usage_daily (
                        workspace_id VARCHAR(128) NOT NULL,
                        usage_date VARCHAR(32) NOT NULL,
                        input_tokens BIGINT NOT NULL,
                        output_tokens BIGINT NOT NULL,
                        metered_input BIGINT NOT NULL,
                        cached_input BIGINT NOT NULL,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (workspace_id, usage_date)
                    )
                    """);

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
                        status VARCHAR(32) NOT NULL,
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
        }
        log.debug("全局 H2 数据库已就绪: {}", databaseFilePath());
    }
}
