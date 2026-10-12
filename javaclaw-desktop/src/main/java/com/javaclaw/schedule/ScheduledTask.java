package com.javaclaw.schedule;

import com.javaclaw.framework.api.TaskResult;

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

    /** 到点执行策略；旧任务和新任务均默认持续执行。 */
    private ExecutionPolicy executionPolicy = ExecutionPolicy.RECURRING;

    /** 是否启用 */
    private boolean enabled;

    /** 配置版本：配置更新使用乐观锁；执行结果更新不递增此版本。 */
    private long version;

    /** 上次执行时间 */
    private String lastRunTime;

    /** 上次执行结果的稳定枚举码（SUCCESS/FAILURE/CANCELLED） */
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

    /**
     * 无人值守高风险工具授权：用户对本定时任务<b>显式授权</b>后，其无人值守执行期间遇到
     * CONFIRM/DOUBLE_CONFIRM 级工具（如 jshell_exec）自动放行，不再弹确认（弹了也无人应答、
     * 只会超时按拒绝而静默失败）。默认 false——保持"定时任务不能无人值守跑高风险工具"的安全默认，
     * 只有用户逐任务打开此开关才放行。必须是<b>人</b>的动作（UI 开关），agent 不能自授权。
     */
    private boolean unattendedToolsAuthorized;

    /** 结构化执行历史（最新在前，最多保留 20 条） */
    private List<ExecRecord> execRecords;

    /**
     * 系统内置任务标记：为 true 时该任务代表「代码内部的周期性机制」（如命令会话清理、习惯回顾、
     * 记忆蒸馏），仅在定时任务模块中<b>只读展示</b>——不参与 Quartz 调度、不写入 scheduled_tasks 表，
     * 且不可被用户或智能体编辑 / 停用 / 删除 / 手动运行。默认 false（普通用户任务），向后兼容。
     */
    private boolean builtin;

    /**
     * 内置任务的触发描述覆盖（仅内置任务使用）；非空时 {@link #describeTrigger()} 直接返回它，
     * 用于表达「每轮对话后」这类无法用 interval/daily/cron 精确表达的事件驱动周期。普通任务为 null。
     */
    private String triggerSummary;

    /** 内置任务的来源模块说明（仅内置任务使用，如 "CommandSessionManager"）。普通任务为 null。 */
    private String sourceModule;

    /**
     * 一条结构化执行记录（POJO 以兼容 Jackson 无 -parameters 反序列化）。
     */
    public static class ExecRecord {
        private String time = "";
        private String status = "";
        private String duration = "—";
        private String note = "";
        /** Null for historical executions recorded before task acceptance existed. */
        private TaskResult taskResult;

        public ExecRecord() {}

        public ExecRecord(String time, String status, String duration, String note) {
            this(time, status, duration, note, null);
        }

        public ExecRecord(String time, String status, String duration, String note,
                          TaskResult taskResult) {
            this.time = time;
            this.status = status;
            this.duration = duration;
            this.note = note;
            this.taskResult = taskResult;
        }

        public String getTime() { return time; }
        public void setTime(String time) { this.time = time; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getDuration() { return duration; }
        public void setDuration(String duration) { this.duration = duration; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
        public TaskResult getTaskResult() { return taskResult; }
        public void setTaskResult(TaskResult taskResult) { this.taskResult = taskResult; }
    }

    public ScheduledTask() {
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
        this.executionPolicy = ExecutionPolicy.RECURRING;
        this.enabled = false;
        this.version = 0L;
        this.lastRunTime = "";
        this.lastRunStatus = "";
        this.lastDuration = "—";
        this.runCount = 0;
        this.failCount = 0;
        this.notifyEnabled = false;
        this.notifyChannel = "none";
        this.execRecords = new ArrayList<>();
    }

    /** 记录一次执行结果（更新时间/状态/计数） */
    public void recordExecution(boolean success) {
        this.lastRunTime = LocalDateTime.now().format(FORMATTER);
        this.lastRunStatus = success ? "SUCCESS" : "FAILURE";
        this.runCount++;
        if (!success) this.failCount++;
    }

    /** 记录一次由用户停用等原因造成的取消；取消计入运行总数，但不计为失败。 */
    public void recordCancellation() {
        this.lastRunTime = LocalDateTime.now().format(FORMATTER);
        this.lastRunStatus = "CANCELLED";
        this.runCount++;
    }

    /**
     * 返回完全脱离原对象的快照。任务管理器只向调用方暴露快照，避免调用方绕过
     * 持久化与调度协调直接修改内部任务。
     */
    public ScheduledTask copy() {
        ScheduledTask out = new ScheduledTask();
        out.id = id;
        out.name = name;
        out.description = description;
        out.triggerType = triggerType;
        out.intervalMinutes = intervalMinutes;
        out.intervalValue = intervalValue;
        out.intervalUnit = intervalUnit;
        out.dailyTime = dailyTime;
        out.cronExpression = cronExpression;
        out.onceDateTime = onceDateTime;
        out.prompt = prompt;
        out.executionPolicy = executionPolicy;
        out.enabled = enabled;
        out.version = version;
        out.lastRunTime = lastRunTime;
        out.lastRunStatus = lastRunStatus;
        out.lastDuration = lastDuration;
        out.runCount = runCount;
        out.failCount = failCount;
        out.notifyEnabled = notifyEnabled;
        out.notifyChannel = notifyChannel;
        out.unattendedToolsAuthorized = unattendedToolsAuthorized;
        out.execRecords = new ArrayList<>();
        if (execRecords != null) {
            for (ExecRecord record : execRecords) {
                out.execRecords.add(new ExecRecord(
                        record.time, record.status, record.duration, record.note,
                        record.taskResult));
            }
        }
        out.builtin = builtin;
        out.triggerSummary = triggerSummary;
        out.sourceModule = sourceModule;
        return out;
    }

    /**
     * 添加一条结构化执行记录（最新在前，最多保留 20 条）。
     */
    public void addExecRecord(ExecRecord record) {
        if (execRecords == null) execRecords = new ArrayList<>();
        execRecords.addFirst(record);
        while (execRecords.size() > 20) execRecords.removeLast();
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

    /** 验证规范分钟数与 UI 间隔字段一致，拒绝损坏的任务状态。 */
    public void validateIntervalFields() {
        if (!"interval".equals(triggerType)) return;
        if (intervalUnit == null) {
            throw new IllegalArgumentException("定时任务间隔单位无效");
        }
        int factor = switch (intervalUnit) {
            case "hour" -> 60;
            case "day" -> 1440;
            case "minute" -> 1;
            default -> throw new IllegalArgumentException("定时任务间隔单位无效");
        };
        if (intervalMinutes < 1 || intervalValue < 1
                || (long) intervalValue * factor != intervalMinutes) {
            throw new IllegalArgumentException("定时任务间隔字段不一致");
        }
    }

    /** 用一个入口同时写入 Quartz 规范分钟数与 UI 展示字段。 */
    public void setIntervalInMinutes(int minutes) {
        int normalized = Math.max(1, minutes);
        this.intervalMinutes = normalized;
        this.intervalValue = normalized;
        this.intervalUnit = "minute";
    }

    /** 人类可读的触发描述（如 "每天 08:30" / "每 30 分钟" / "一次性 06-10 08:30"）。 */
    public String describeTrigger() {
        if (triggerSummary != null && !triggerSummary.isBlank()) return triggerSummary;
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

    public ExecutionPolicy getExecutionPolicy() { return executionPolicy; }
    public void setExecutionPolicy(ExecutionPolicy executionPolicy) {
        this.executionPolicy = java.util.Objects.requireNonNull(executionPolicy, "executionPolicy");
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

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

    public boolean isUnattendedToolsAuthorized() { return unattendedToolsAuthorized; }
    public void setUnattendedToolsAuthorized(boolean v) { this.unattendedToolsAuthorized = v; }

    public String getNotifyChannel() { return notifyChannel; }
    public void setNotifyChannel(String notifyChannel) { this.notifyChannel = notifyChannel; }

    public List<ExecRecord> getExecRecords() { return execRecords; }
    public void setExecRecords(List<ExecRecord> execRecords) { this.execRecords = execRecords; }

    public boolean isBuiltin() { return builtin; }
    public void setBuiltin(boolean builtin) { this.builtin = builtin; }

    public String getTriggerSummary() { return triggerSummary; }
    public void setTriggerSummary(String triggerSummary) { this.triggerSummary = triggerSummary; }

    public String getSourceModule() { return sourceModule; }
    public void setSourceModule(String sourceModule) { this.sourceModule = sourceModule; }
}
