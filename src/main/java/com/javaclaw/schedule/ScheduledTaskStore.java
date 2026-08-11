package com.javaclaw.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.javaclaw.platform.json.JsonCodec;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * H2 定时任务仓储。
 *
 * <p>实例线程安全；定义写入使用乐观版本，执行结果在行锁内合并且不修改定义版本，
 * 因而迟到的执行回调不会恢复已停用配置。所有事务由 Spring 统一管理。</p>
 */
public final class ScheduledTaskStore {

    enum ExecutionStatus {
        SUCCESS("成功"), FAILURE("失败"), CANCELLED("已取消");

        private final String label;

        ExecutionStatus(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    record ExecutionResult(ExecutionStatus status, String duration, String note) { }

    private static final String COLUMNS = """
            id, name, description, trigger_type, interval_minutes, interval_value,
            interval_unit, daily_time, cron_expression, once_date_time, prompt,
            enabled, version, last_run_time, last_run_status, last_duration, run_count,
            fail_count, notify_enabled, notify_channel, execution_history_json,
            exec_records_json, unattended_authorized
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JsonCodec json;

    public ScheduledTaskStore(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            JsonCodec json) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.json = Objects.requireNonNull(json, "json");
    }

    List<ScheduledTask> loadAll(String workspaceId) {
        String sql = "SELECT " + COLUMNS + " FROM scheduled_tasks "
                + "WHERE workspace_id = ? ORDER BY name, id";
        try {
            return jdbc.query(sql, this::readTask, workspaceId);
        } catch (DataAccessException failure) {
            throw persistence("加载定时任务失败", failure);
        }
    }

    ScheduledTask find(String workspaceId, String id) {
        try {
            return find(workspaceId, id, false);
        } catch (DataAccessException failure) {
            throw persistence("读取定时任务失败：" + id, failure);
        }
    }

    ScheduledTask insert(String workspaceId, ScheduledTask source) {
        ScheduledTask task = source.copy();
        task.normalizeIntervalFields();
        task.setVersion(0L);
        String sql = """
                INSERT INTO scheduled_tasks(
                    workspace_id, id, name, description, trigger_type, interval_minutes, interval_value,
                    interval_unit, daily_time, cron_expression, once_date_time, prompt,
                    enabled, version, last_run_time, last_run_status, last_duration, run_count,
                    fail_count, notify_enabled, notify_channel, execution_history_json,
                    exec_records_json, unattended_authorized, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """;
        try {
            jdbc.update(sql, workspaceId, task.getId(), task.getName(), task.getDescription(),
                    task.getTriggerType(), task.getIntervalMinutes(), task.getIntervalValue(),
                    task.getIntervalUnit(), task.getDailyTime(), task.getCronExpression(),
                    task.getOnceDateTime(), task.getPrompt(), task.isEnabled(), task.getVersion(),
                    task.getLastRunTime(), task.getLastRunStatus(), task.getLastDuration(),
                    task.getRunCount(), task.getFailCount(), task.isNotifyEnabled(),
                    task.getNotifyChannel(), encode(task.getExecutionHistory()),
                    encode(task.getExecRecords()), task.isUnattendedToolsAuthorized());
            return task.copy();
        } catch (DataAccessException failure) {
            throw persistence("创建定时任务失败：" + task.getId(), failure);
        }
    }

    ScheduledTask updateDefinition(String workspaceId, ScheduledTask source) {
        ScheduledTask task = source.copy();
        task.normalizeIntervalFields();
        String sql = """
                UPDATE scheduled_tasks SET
                    name = ?, description = ?, trigger_type = ?, interval_minutes = ?, interval_value = ?,
                    interval_unit = ?, daily_time = ?, cron_expression = ?, once_date_time = ?, prompt = ?,
                    enabled = ?, notify_enabled = ?, notify_channel = ?, unattended_authorized = ?,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE workspace_id = ? AND id = ? AND version = ?
                """;
        try {
            return transactions.execute(status -> {
                int changed = jdbc.update(sql, task.getName(), task.getDescription(),
                        task.getTriggerType(), task.getIntervalMinutes(), task.getIntervalValue(),
                        task.getIntervalUnit(), task.getDailyTime(), task.getCronExpression(),
                        task.getOnceDateTime(), task.getPrompt(), task.isEnabled(),
                        task.isNotifyEnabled(), task.getNotifyChannel(),
                        task.isUnattendedToolsAuthorized(), workspaceId, task.getId(), task.getVersion());
                if (changed != 1) throw new ScheduleConflictException(task.getId());
                ScheduledTask updated = find(workspaceId, task.getId(), false);
                if (updated == null) throw new ScheduleConflictException(task.getId());
                return updated;
            });
        } catch (ScheduleConflictException conflict) {
            throw conflict;
        } catch (DataAccessException failure) {
            throw persistence("更新定时任务失败：" + task.getId(), failure);
        }
    }

    void delete(String workspaceId, String id, long version) {
        try {
            int changed = jdbc.update(
                    "DELETE FROM scheduled_tasks WHERE workspace_id = ? AND id = ? AND version = ?",
                    workspaceId, id, version);
            if (changed != 1) throw new ScheduleConflictException(id);
        } catch (ScheduleConflictException conflict) {
            throw conflict;
        } catch (DataAccessException failure) {
            throw persistence("删除定时任务失败：" + id, failure);
        }
    }

    /** 在行锁内合并执行记录，不修改配置列或定义版本。 */
    ScheduledTask recordExecution(String workspaceId, String id, ExecutionResult result) {
        String update = """
                UPDATE scheduled_tasks SET
                    last_run_time = ?, last_run_status = ?, last_duration = ?, run_count = ?, fail_count = ?,
                    execution_history_json = ?, exec_records_json = ?, updated_at = CURRENT_TIMESTAMP
                WHERE workspace_id = ? AND id = ?
                """;
        try {
            return transactions.execute(status -> {
                ScheduledTask task = find(workspaceId, id, true);
                if (task == null) return null;
                switch (result.status()) {
                    case SUCCESS -> task.recordExecution(true);
                    case FAILURE -> task.recordExecution(false);
                    case CANCELLED -> task.recordCancellation();
                }
                task.setLastDuration(result.duration());
                String note = safeNote(result.note());
                String now = LocalDateTime.now().format(ScheduledTask.FORMATTER);
                task.addExecRecord(new ScheduledTask.ExecRecord(
                        now, result.status().label(), result.duration(), note));
                task.addExecutionRecord(now + " [" + result.status().label() + "] " + note);
                int changed = jdbc.update(update, task.getLastRunTime(), task.getLastRunStatus(),
                        task.getLastDuration(), task.getRunCount(), task.getFailCount(),
                        encode(task.getExecutionHistory()), encode(task.getExecRecords()),
                        workspaceId, id);
                return changed == 1 ? task.copy() : null;
            });
        } catch (DataAccessException failure) {
            throw persistence("保存任务执行结果失败：" + id, failure);
        }
    }

    private ScheduledTask find(String workspaceId, String id, boolean forUpdate) {
        String sql = "SELECT " + COLUMNS + " FROM scheduled_tasks "
                + "WHERE workspace_id = ? AND id = ?" + (forUpdate ? " FOR UPDATE" : "");
        List<ScheduledTask> rows = jdbc.query(sql, this::readTask, workspaceId, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private ScheduledTask readTask(ResultSet rs, int rowNumber) throws SQLException {
        ScheduledTask task = new ScheduledTask();
        task.setId(rs.getString("id"));
        task.setName(rs.getString("name"));
        task.setDescription(rs.getString("description"));
        task.setTriggerType(rs.getString("trigger_type"));
        task.setIntervalMinutes(rs.getInt("interval_minutes"));
        task.setIntervalValue(rs.getInt("interval_value"));
        task.setIntervalUnit(rs.getString("interval_unit"));
        task.setDailyTime(rs.getString("daily_time"));
        task.setCronExpression(rs.getString("cron_expression"));
        task.setOnceDateTime(rs.getString("once_date_time"));
        task.setPrompt(rs.getString("prompt"));
        task.setEnabled(rs.getBoolean("enabled"));
        task.setVersion(rs.getLong("version"));
        task.setLastRunTime(rs.getString("last_run_time"));
        task.setLastRunStatus(rs.getString("last_run_status"));
        task.setLastDuration(rs.getString("last_duration"));
        task.setRunCount(rs.getInt("run_count"));
        task.setFailCount(rs.getInt("fail_count"));
        task.setNotifyEnabled(rs.getBoolean("notify_enabled"));
        task.setNotifyChannel(rs.getString("notify_channel"));
        task.setExecutionHistory(readStringList(rs.getString("execution_history_json")));
        task.setExecRecords(readExecRecords(rs.getString("exec_records_json")));
        task.setUnattendedToolsAuthorized(rs.getBoolean("unattended_authorized"));
        task.normalizeIntervalFields();
        return task;
    }

    private String encode(Object value) {
        try {
            return json.encodePretty(value);
        } catch (JsonProcessingException failure) {
            throw persistence("序列化定时任务历史失败", failure);
        }
    }

    private List<String> readStringList(String source) {
        if (source == null || source.isBlank()) return new ArrayList<>();
        try {
            List<String> result = json.decode(source, new TypeReference<>() { });
            return result == null ? new ArrayList<>() : result;
        } catch (JsonProcessingException ignored) {
            return new ArrayList<>();
        }
    }

    private List<ScheduledTask.ExecRecord> readExecRecords(String source) {
        if (source == null || source.isBlank()) return new ArrayList<>();
        try {
            List<ScheduledTask.ExecRecord> result = json.decode(source, new TypeReference<>() { });
            return result == null ? new ArrayList<>() : result;
        } catch (JsonProcessingException ignored) {
            return new ArrayList<>();
        }
    }

    private static SchedulePersistenceException persistence(String message, Throwable failure) {
        return new SchedulePersistenceException(message, failure);
    }

    private static String safeNote(String note) {
        if (note == null || note.isBlank()) return "—";
        return note.length() > 60 ? note.substring(0, 60) + "…" : note;
    }
}
