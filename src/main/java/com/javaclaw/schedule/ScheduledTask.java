package com.javaclaw.schedule;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 定时任务数据模型
 *
 * <p>每个定时任务包含触发规则和要执行的提示词指令，
 * 触发时将提示词发送给编排智能体执行。</p>
 *
 * @author JavaClaw
 */
public class ScheduledTask {

    static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 任务唯一标识 */
    private String id;

    /** 任务名称 */
    private String name;

    /** 任务描述 */
    private String description;

    /**
     * 触发类型：
     * <ul>
     *   <li>{@code once} — 一次性，到点跑一次后自动停用</li>
     *   <li>{@code interval} — 固定间隔（数值 + 单位）</li>
     *   <li>{@code daily} — 每天指定时间</li>
     *   <li>{@code cron} — Cron 表达式</li>
     * </ul>
     */
    private String triggerType;

    /** 间隔的规范分钟数（triggerType=interval 时有效，供 Quartz 直接使用） */
    private int intervalMinutes;

    /** 间隔数值（triggerType=interval，配合 {@link #intervalUnit}，仅供 UI 编辑/展示） */
    private int intervalValue;

    /** 间隔单位：minute / hour / day（triggerType=interval） */
    private String intervalUnit;

    /** 每日触发时间，格式 HH:mm（triggerType=daily 时有效） */
    private String dailyTime;

    /** Cron 表达式（triggerType=cron 时有效） */
    private String cronExpression;

    /** 一次性运行时间，格式 yyyy-MM-dd HH:mm（triggerType=once 时有效） */
    private String onceDateTime;

    /** 要发送给智能体的提示词 */
    private String prompt;

    /** 是否启用 */
    private boolean enabled;

    /** 上次执行时间 */
    private String lastRunTime;

    /** 上次执行结果（成功/失败） */
    private String lastRunStatus;

    /** 上次执行耗时（如 "6.2s"） */
    private String lastDuration;

    /** 累计运行次数 */
    private int runCount;

    /** 累计失败次数 */
    private int failCount;

    /** 完成后是否推送通知 */
    private boolean notifyEnabled;

    /** 通知渠道 key（none/all/dingtalk/wechat/feishu/email/custom） */
    private String notifyChannel;

    /** 执行历史记录（旧：纯文本字符串，保留向后兼容） */
    private List<String> executionHistory;

    /** 结构化执行历史（最新在前，最多保留 20 条） */
    private List<ExecRecord> execRecords;

    /**
     * 一条结构化执行记录（POJO 以兼容 Jackson 无 -parameters 反序列化）。
     */
    public static class ExecRecord {
        private String time = "";
        private String status = "";
        private String duration = "—";
        private String note = "";

        public ExecRecord() {}

        public ExecRecord(String time, String status, String duration, String note) {
            this.time = time;
            this.status = status;
            this.duration = duration;
            this.note = note;
        }

        public String getTime() { return time; }
        public void setTime(String time) { this.time = time; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getDuration() { return duration; }
        public void setDuration(String duration) { this.duration = duration; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
    }

    public ScheduledTask() {
        this.executionHistory = new ArrayList<>();
        this.execRecords = new ArrayList<>();
    }

    public ScheduledTask(String id, String name) {
        this.id = id;
        this.name = name;
        this.description = "";
        this.triggerType = "interval";
        this.intervalMinutes = 60;
        this.intervalValue = 60;
        this.intervalUnit = "minute";
        this.dailyTime = "09:00";
        this.cronExpression = "";
        this.onceDateTime = "";
        this.prompt = "";
        this.enabled = false;
        this.lastRunTime = "";
        this.lastRunStatus = "";
        this.lastDuration = "—";
        this.runCount = 0;
        this.failCount = 0;
        this.notifyEnabled = false;
        this.notifyChannel = "none";
        this.executionHistory = new ArrayList<>();
        this.execRecords = new ArrayList<>();
    }

    /** 记录一次执行结果（更新时间/状态/计数） */
    public void recordExecution(boolean success) {
        this.lastRunTime = LocalDateTime.now().format(FORMATTER);
        this.lastRunStatus = success ? "成功" : "失败";
        this.runCount++;
        if (!success) this.failCount++;
    }

    /**
     * 添加一条结构化执行记录（最新在前，最多保留 20 条）。
     */
    public void addExecRecord(ExecRecord record) {
        if (execRecords == null) execRecords = new ArrayList<>();
        execRecords.addFirst(record);
        while (execRecords.size() > 20) execRecords.removeLast();
    }

    /**
     * 添加一条执行记录（旧接口，最新在前，最多保留 20 条）
     *
     * @param record 执行记录描述（含时间戳）
     */
    public void addExecutionRecord(String record) {
        if (executionHistory == null) {
            executionHistory = new ArrayList<>();
        }
        executionHistory.addFirst(record);
        // 上限 20 条
        while (executionHistory.size() > 20) {
            executionHistory.removeLast();
        }
    }

    /** 把 intervalValue + intervalUnit 折算为规范分钟数写入 intervalMinutes。 */
    public void recomputeIntervalMinutes() {
        int v = Math.max(1, intervalValue);
        int factor = switch (intervalUnit == null ? "minute" : intervalUnit) {
            case "hour" -> 60;
            case "day" -> 1440;
            default -> 1;
        };
        this.intervalMinutes = v * factor;
    }

    /** 人类可读的触发描述（如 "每天 08:30" / "每 30 分钟" / "一次性 06-10 08:30"）。 */
    public String describeTrigger() {
        return switch (triggerType == null ? "interval" : triggerType) {
            case "once" -> "一次性 " + (onceDateTime == null || onceDateTime.isBlank() ? "未设置" : onceDateTime);
            case "daily" -> "每天 " + (dailyTime == null || dailyTime.isBlank() ? "09:00" : dailyTime);
            case "cron" -> "Cron " + (cronExpression == null ? "" : cronExpression);
            default -> {
                int v = intervalValue > 0 ? intervalValue : Math.max(1, intervalMinutes);
                String unit = switch (intervalUnit == null ? "minute" : intervalUnit) {
                    case "hour" -> "小时";
                    case "day" -> "天";
                    default -> "分钟";
                };
                yield "每 " + v + " " + unit;
            }
        };
    }

    // ==================== Getter / Setter ====================

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }

    public int getIntervalMinutes() { return intervalMinutes; }
    public void setIntervalMinutes(int intervalMinutes) { this.intervalMinutes = intervalMinutes; }

    public int getIntervalValue() { return intervalValue; }
    public void setIntervalValue(int intervalValue) { this.intervalValue = intervalValue; }

    public String getIntervalUnit() { return intervalUnit; }
    public void setIntervalUnit(String intervalUnit) { this.intervalUnit = intervalUnit; }

    public String getDailyTime() { return dailyTime; }
    public void setDailyTime(String dailyTime) { this.dailyTime = dailyTime; }

    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }

    public String getOnceDateTime() { return onceDateTime; }
    public void setOnceDateTime(String onceDateTime) { this.onceDateTime = onceDateTime; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getLastRunTime() { return lastRunTime; }
    public void setLastRunTime(String lastRunTime) { this.lastRunTime = lastRunTime; }

    public String getLastRunStatus() { return lastRunStatus; }
    public void setLastRunStatus(String lastRunStatus) { this.lastRunStatus = lastRunStatus; }

    public String getLastDuration() { return lastDuration; }
    public void setLastDuration(String lastDuration) { this.lastDuration = lastDuration; }

    public int getRunCount() { return runCount; }
    public void setRunCount(int runCount) { this.runCount = runCount; }

    public int getFailCount() { return failCount; }
    public void setFailCount(int failCount) { this.failCount = failCount; }

    public boolean isNotifyEnabled() { return notifyEnabled; }
    public void setNotifyEnabled(boolean notifyEnabled) { this.notifyEnabled = notifyEnabled; }

    public String getNotifyChannel() { return notifyChannel; }
    public void setNotifyChannel(String notifyChannel) { this.notifyChannel = notifyChannel; }

    public List<String> getExecutionHistory() { return executionHistory; }
    public void setExecutionHistory(List<String> executionHistory) { this.executionHistory = executionHistory; }

    public List<ExecRecord> getExecRecords() { return execRecords; }
    public void setExecRecords(List<ExecRecord> execRecords) { this.execRecords = execRecords; }
}
