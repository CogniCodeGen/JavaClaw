package com.javaclaw.schedule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** H2 定时任务按行存储；配置版本与执行结果使用互不覆盖的更新语句。 */
final class ScheduledTaskStore {

    @FunctionalInterface
    interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    enum ExecutionStatus {
        SUCCESS("成功"), FAILURE("失败"), CANCELLED("已取消");

        private final String label;

        ExecutionStatus(String label) {
            this.label = label;
        }

        String label() { return label; }
    }

    record ExecutionResult(ExecutionStatus status, String duration, String note) {}

    private static final String COLUMNS = """
            id, name, description, trigger_type, interval_minutes, interval_value,
            interval_unit, daily_time, cron_expression, once_date_time, prompt,
            enabled, version, last_run_time, last_run_status, last_duration, run_count,
            fail_count, notify_enabled, notify_channel, execution_history_json,
            exec_records_json, unattended_authorized
            """;

    private final ConnectionFactory connections;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    ScheduledTaskStore(ConnectionFactory connections) {
        this.connections = connections;
    }

    List<ScheduledTask> loadAll(String workspaceId) {
        String sql = "SELECT " + COLUMNS + " FROM scheduled_tasks "
                + "WHERE workspace_id = ? ORDER BY name, id";
        try (Connection c = connections.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, workspaceId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ScheduledTask> result = new ArrayList<>();
                while (rs.next()) result.add(readTask(rs));
                return result;
            }
        } catch (SQLException e) {
            throw new SchedulePersistenceException("加载定时任务失败", e);
        }
    }

    ScheduledTask find(String workspaceId, String id) {
        try (Connection c = connections.open()) {
            return find(c, workspaceId, id, false);
        } catch (SQLException e) {
            throw new SchedulePersistenceException("读取定时任务失败：" + id, e);
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
        try (Connection c = connections.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            bindInsert(ps, workspaceId, task);
            ps.executeUpdate();
            return task.copy();
        } catch (SQLException | IOException e) {
            throw new SchedulePersistenceException("创建定时任务失败：" + task.getId(), e);
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
        try (Connection c = connections.open()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = 1;
                ps.setString(i++, task.getName());
                ps.setString(i++, task.getDescription());
                ps.setString(i++, task.getTriggerType());
                ps.setInt(i++, task.getIntervalMinutes());
                ps.setInt(i++, task.getIntervalValue());
                ps.setString(i++, task.getIntervalUnit());
                ps.setString(i++, task.getDailyTime());
                ps.setString(i++, task.getCronExpression());
                ps.setString(i++, task.getOnceDateTime());
                ps.setString(i++, task.getPrompt());
                ps.setBoolean(i++, task.isEnabled());
                ps.setBoolean(i++, task.isNotifyEnabled());
                ps.setString(i++, task.getNotifyChannel());
                ps.setBoolean(i++, task.isUnattendedToolsAuthorized());
                ps.setString(i++, workspaceId);
                ps.setString(i++, task.getId());
                ps.setLong(i, task.getVersion());
                if (ps.executeUpdate() != 1) {
                    c.rollback();
                    throw new ScheduleConflictException(task.getId());
                }
            }
            ScheduledTask updated = find(c, workspaceId, task.getId(), false);
            c.commit();
            if (updated == null) throw new ScheduleConflictException(task.getId());
            return updated;
        } catch (ScheduleConflictException e) {
            throw e;
        } catch (SQLException e) {
            throw new SchedulePersistenceException("更新定时任务失败：" + task.getId(), e);
        }
    }

    void delete(String workspaceId, String id, long version) {
        String sql = "DELETE FROM scheduled_tasks WHERE workspace_id = ? AND id = ? AND version = ?";
        try (Connection c = connections.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, workspaceId);
            ps.setString(2, id);
            ps.setLong(3, version);
            if (ps.executeUpdate() != 1) throw new ScheduleConflictException(id);
        } catch (ScheduleConflictException e) {
            throw e;
        } catch (SQLException e) {
            throw new SchedulePersistenceException("删除定时任务失败：" + id, e);
        }
    }

    /**
     * 在行锁内合并执行记录。该语句刻意不修改配置列与 version，因此旧执行回调不能复活已停用任务。
     */
    ScheduledTask recordExecution(String workspaceId, String id, ExecutionResult result) {
        String update = """
                UPDATE scheduled_tasks SET
                    last_run_time = ?, last_run_status = ?, last_duration = ?, run_count = ?, fail_count = ?,
                    execution_history_json = ?, exec_records_json = ?, updated_at = CURRENT_TIMESTAMP
                WHERE workspace_id = ? AND id = ?
                """;
        try (Connection c = connections.open()) {
            c.setAutoCommit(false);
            ScheduledTask task = find(c, workspaceId, id, true);
            if (task == null) {
                c.rollback();
                return null;
            }
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
            try (PreparedStatement ps = c.prepareStatement(update)) {
                int i = 1;
                ps.setString(i++, task.getLastRunTime());
                ps.setString(i++, task.getLastRunStatus());
                ps.setString(i++, task.getLastDuration());
                ps.setInt(i++, task.getRunCount());
                ps.setInt(i++, task.getFailCount());
                ps.setString(i++, mapper.writeValueAsString(task.getExecutionHistory()));
                ps.setString(i++, mapper.writeValueAsString(task.getExecRecords()));
                ps.setString(i++, workspaceId);
                ps.setString(i, id);
                if (ps.executeUpdate() != 1) {
                    c.rollback();
                    return null;
                }
            }
            c.commit();
            return task.copy();
        } catch (SQLException | IOException e) {
            throw new SchedulePersistenceException("保存任务执行结果失败：" + id, e);
        }
    }

    private ScheduledTask find(Connection c, String workspaceId, String id, boolean forUpdate)
            throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM scheduled_tasks "
                + "WHERE workspace_id = ? AND id = ?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, workspaceId);
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readTask(rs) : null;
            }
        }
    }

    private ScheduledTask readTask(ResultSet rs) throws SQLException {
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

    private void bindInsert(PreparedStatement ps, String workspaceId, ScheduledTask task)
            throws SQLException, IOException {
        int i = 1;
        ps.setString(i++, workspaceId);
        ps.setString(i++, task.getId());
        ps.setString(i++, task.getName());
        ps.setString(i++, task.getDescription());
        ps.setString(i++, task.getTriggerType());
        ps.setInt(i++, task.getIntervalMinutes());
        ps.setInt(i++, task.getIntervalValue());
        ps.setString(i++, task.getIntervalUnit());
        ps.setString(i++, task.getDailyTime());
        ps.setString(i++, task.getCronExpression());
        ps.setString(i++, task.getOnceDateTime());
        ps.setString(i++, task.getPrompt());
        ps.setBoolean(i++, task.isEnabled());
        ps.setLong(i++, task.getVersion());
        ps.setString(i++, task.getLastRunTime());
        ps.setString(i++, task.getLastRunStatus());
        ps.setString(i++, task.getLastDuration());
        ps.setInt(i++, task.getRunCount());
        ps.setInt(i++, task.getFailCount());
        ps.setBoolean(i++, task.isNotifyEnabled());
        ps.setString(i++, task.getNotifyChannel());
        ps.setString(i++, mapper.writeValueAsString(task.getExecutionHistory()));
        ps.setString(i++, mapper.writeValueAsString(task.getExecRecords()));
        ps.setBoolean(i, task.isUnattendedToolsAuthorized());
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<String> out = mapper.readValue(json, new TypeReference<>() {});
            return out == null ? new ArrayList<>() : out;
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private List<ScheduledTask.ExecRecord> readExecRecords(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<ScheduledTask.ExecRecord> out = mapper.readValue(json, new TypeReference<>() {});
            return out == null ? new ArrayList<>() : out;
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private static String safeNote(String note) {
        if (note == null || note.isBlank()) return "—";
        return note.length() > 60 ? note.substring(0, 60) + "…" : note;
    }
}
