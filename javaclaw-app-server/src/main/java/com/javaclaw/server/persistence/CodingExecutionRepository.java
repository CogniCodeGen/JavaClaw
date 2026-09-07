package com.javaclaw.server.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.protocol.CanonicalJson;

/** 只读执行发现索引；固定最多 100 条，不加载请求、项目正文或启动任何恢复进程。 */
public final class CodingExecutionRepository {
    private final H2Transactions transactions;
    private final CanonicalJson json;

    /**
     * 绑定当前已迁移数据库。
     *
     * @param database data-v6 数据库
     * @param json 规范 JSON
     */
    public CodingExecutionRepository(H2Database database, CanonicalJson json) {
        transactions = new H2Transactions(database);
        this.json = json;
    }

    /**
     * 按 Workspace 和可选 Thread/Turn 过滤后返回最近执行，调用者仍须逐条检查当前证据读取权限。
     *
     * @param workspaceId 权威 Workspace
     * @param threadId 可选 Thread 范围
     * @param turnId 可选 Turn 范围
     * @return 最近最多 100 条操作摘要，时间相同时以服务端标识稳定排序
     */
    public List<CodingResults.ExecutionSummary> list(
            WorkspaceId workspaceId, Optional<ThreadId> threadId, Optional<TurnId> turnId) {
        try {
            return transactions.execute(connection -> {
                String sql = """
                        SELECT o.ID,o.TURN_ID,o.OPERATION_NAME,o.STATE,o.RESULT_JSON,o.OUTPUT_JSON,o.SUCCESS,a.STATUS AS TURN_STATUS,
                        s.STATE AS STREAM_STATE,s.EXIT_CODE AS STREAM_EXIT,s.OUTPUT_BYTES AS STREAM_BYTES,
                        t.STATE AS TERMINAL_STATE,t.EXIT_CODE AS TERMINAL_EXIT,t.OUTPUT_BYTES AS TERMINAL_BYTES
                        FROM CORE.CODING_OPERATION o JOIN CORE.AGENT_TURN a ON a.ID=o.TURN_ID
                        LEFT JOIN CORE.CODING_COMMAND_STREAM s ON s.OPERATION_ID=o.ID
                        LEFT JOIN CORE.CODING_TERMINAL t ON t.OPERATION_ID=o.ID
                        WHERE o.WORKSPACE_ID=? AND o.OPERATION_NAME IN ('command_run','dependencies_prepare','terminal_open')
                        """ + (threadId.isPresent() ? " AND a.THREAD_ID=?" : "")
                        + (turnId.isPresent() ? " AND o.TURN_ID=?" : "")
                        + " ORDER BY o.CREATED_AT DESC,o.ID DESC FETCH FIRST 100 ROWS ONLY";
                try (var query = connection.prepareStatement(sql)) {
                    int index = 1;
                    query.setString(index++, workspaceId.toString());
                    if (threadId.isPresent()) {
                        query.setString(index++, threadId.orElseThrow().toString());
                    }
                    if (turnId.isPresent()) {
                        query.setString(index, turnId.orElseThrow().toString());
                    }
                    List<CodingResults.ExecutionSummary> result = new ArrayList<>();
                    try (var rows = query.executeQuery()) {
                        while (rows.next()) {
                            result.add(summary(rows));
                        }
                    }
                    return List.copyOf(result);
                }
            });
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Coding 执行列表读取失败", failure);
        }
    }

    private CodingResults.ExecutionSummary summary(ResultSet rows) throws SQLException {
        Observed observed = observed(rows);
        return new CodingResults.ExecutionSummary(
                rows.getString("ID"),
                rows.getString("OPERATION_NAME"),
                TurnId.parse(rows.getString("TURN_ID")),
                observed.state(),
                bytes(rows),
                observed.exitCode());
    }

    private Observed observed(ResultSet rows) throws SQLException {
        String operation = rows.getString("OPERATION_NAME");
        String state = rows.getString("STATE");
        if (state.equals("UNKNOWN_OUTCOME")) {
            return new Observed("UNKNOWN_OUTCOME", Optional.empty());
        }
        boolean activeTurn = java.util.Set.of("RUNNING", "WAITING").contains(rows.getString("TURN_STATUS"));
        String terminal = rows.getString("TERMINAL_STATE");
        if (terminal != null) {
            return terminalState(rows, terminal, activeTurn);
        }
        // 活跃依赖操作可能仍在收集执行后证据；Turn 终结后只保留已确认退出，否则显示未知。
        boolean pending = state.equals("PREPARED") || state.equals("STARTED");
        if (pending && activeTurn) {
            return new Observed("RUNNING", Optional.empty());
        }
        if (rows.getString("STREAM_STATE") != null
                && !rows.getString("STREAM_STATE").equals("RUNNING")) {
            return new Observed(
                    rows.getString("STREAM_STATE"), Optional.ofNullable(rows.getObject("STREAM_EXIT", Integer.class)));
        }
        if (pending) {
            return new Observed("UNKNOWN_OUTCOME", Optional.empty());
        }
        return legacy(operation, rows.getString("RESULT_JSON"), rows.getBoolean("SUCCESS"));
    }

    private static Observed terminalState(ResultSet rows, String state, boolean activeTurn) throws SQLException {
        if (state.equals("STARTING") || state.equals("RUNNING")) {
            return new Observed(activeTurn ? "RUNNING" : "UNKNOWN_OUTCOME", Optional.empty());
        }
        return new Observed(state, Optional.ofNullable(rows.getObject("TERMINAL_EXIT", Integer.class)));
    }

    private long bytes(ResultSet rows) throws SQLException {
        if (rows.getString("TERMINAL_STATE") != null) {
            return rows.getLong("TERMINAL_BYTES");
        }
        if (rows.getString("STREAM_STATE") != null) {
            return rows.getLong("STREAM_BYTES");
        }
        String legacy = rows.getString("OUTPUT_JSON");
        if (legacy == null) {
            return 0;
        }
        var output = json.decode(new CanonicalPayload(legacy), CodingCommandOutputRepository.Output.class);
        return output.stdoutBytes() + output.stderrBytes();
    }

    private Observed legacy(String operation, String result, boolean success) {
        if (result == null) {
            return new Observed(success ? "COMPLETED" : "FAILED", Optional.empty());
        }
        var payload = new CanonicalPayload(result);
        if (json.fieldNames(payload).contains("errorCode")) {
            return new Observed("FAILED", Optional.empty());
        }
        Optional<CanonicalPayload> command = json.objectField(payload, "command");
        if (operation.equals("dependencies_prepare")) {
            command = command.flatMap(value -> json.objectField(value, "command"));
        }
        if (command.isPresent()) {
            var summary = json.decode(command.orElseThrow(), CodingResults.CommandSummary.class);
            return new Observed(summary.state().name(), summary.exitCode());
        }
        return new Observed(success ? "COMPLETED" : "FAILED", Optional.empty());
    }

    private record Observed(String state, Optional<Integer> exitCode) {}
}
