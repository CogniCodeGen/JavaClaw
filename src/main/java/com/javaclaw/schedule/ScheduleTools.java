package com.javaclaw.schedule;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.application.schedule.ScheduleApplicationService;
import com.javaclaw.application.schedule.ScheduleApplicationService.DisablePolicy;
import com.javaclaw.application.schedule.ScheduleApplicationService.SaveCommand;
import com.javaclaw.application.schedule.ScheduleApplicationService.Task;
import com.javaclaw.application.schedule.ScheduleCommands;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 定时工作管理工具集 —— 让编排器在对话中自主创建与管理定时/周期任务。
 *
 * <p>所有操作委派给工作区 {@link ScheduleApplicationService}。任务策略是独立的结构化字段；
 * UNTIL_CONDITION 只在任务验收结果经系统验证完成后自动停用。</p>
 *
 * <p>创建类受人工确认（会反复自主消耗 token）；查询/停用/删除类直接执行——其中停用/删除必须
 * 非阻塞，以便定时任务自身的后台执行线程能调用它们完成自停。</p>
 */
@com.javaclaw.framework.spi.ToolContract(group = "schedule", permissions = {"tool.execute"}, idempotent = false)
public final class ScheduleTools {

    private static final Logger log = LoggerFactory.getLogger(ScheduleTools.class);

    /** 调用来源令牌（装配期绑定），高风险确认随调用传给 ToolConfirmationManager。 */
    private final ToolCallOrigin origin;
    private final ScheduleApplicationService schedules;

    public ScheduleTools(ToolCallOrigin origin, ScheduleApplicationService schedules) {
        this.origin = origin == null ? ToolCallOrigin.UNKNOWN : origin;
        this.schedules = Objects.requireNonNull(schedules, "schedules");
    }

    @Tool(name = "schedule_create",
            description = "创建并启用一个定时/周期任务，到点时把 prompt 当对话发给智能体执行。"
                    + "triggerType 取 interval（每 N 分钟）/daily（每天 HH:mm）/cron（Cron 表达式）；"
                    + "triggerValue 按类型分别填：分钟数 / HH:mm / Cron 表达式。"
                    + "executionPolicy 取 RECURRING（默认，持续按规则执行）或 UNTIL_CONDITION"
                    + "（系统在结构化任务验收确认目标完成后停用）。需要用户确认后才会创建。")
    public String scheduleCreate(
            @ToolParam( description = "定时任务名称") String name,
            @ToolParam( description = "触发类型：interval / daily / cron") String triggerType,
            @ToolParam( description = "interval 填分钟数；daily 填 HH:mm；cron 填 Cron 表达式") String triggerValue,
            @ToolParam( description = "到点发给智能体执行的提示词指令") String prompt,
            @ToolParam(required = false, description = "RECURRING 或 UNTIL_CONDITION；默认 RECURRING")
                    String executionPolicy) {
        String nm = name == null ? "" : name.trim();
        String type = triggerType == null ? "" : triggerType.trim().toLowerCase();
        String val = triggerValue == null ? "" : triggerValue.trim();
        String pr = prompt == null ? "" : prompt.trim();
        if (nm.isEmpty() || pr.isEmpty()) {
            return ToolResponse.error("schedule_create", "name 与 prompt 不能为空");
        }
        if (!List.of("interval", "daily", "cron").contains(type)) {
            return ToolResponse.error("schedule_create", "triggerType 必须是 interval / daily / cron 之一");
        }
        ExecutionPolicy policy;
        try {
            policy = executionPolicy == null || executionPolicy.isBlank()
                    ? ExecutionPolicy.RECURRING
                    : ExecutionPolicy.valueOf(executionPolicy.strip());
        } catch (IllegalArgumentException invalid) {
            return ToolResponse.error("schedule_create",
                    "executionPolicy 必须是 RECURRING 或 UNTIL_CONDITION");
        }
        if (!ToolConfirmationManager.requestConfirmation(origin, "schedule_create",
                "创建定时任务「" + nm + "」（" + type + "：" + val + "）：" + pr)) {
            return ToolResponse.error("schedule_create", "用户取消了创建");
        }
        try {
            Task draft = schedules.createDraft(nm);
            int intervalValue = 1;
            String dailyTime = "";
            String cronExpression = "";
            switch (type) {
                case "interval" -> {
                    try { intervalValue = Math.max(1, Integer.parseInt(val)); }
                    catch (NumberFormatException ex) { return ToolResponse.error("schedule_create", "interval 需要分钟数，如 5"); }
                }
                case "daily" -> {
                    if (!val.matches("\\d{1,2}:\\d{2}")) return ToolResponse.error("schedule_create", "daily 需要 HH:mm，如 09:00");
                    dailyTime = val;
                }
                case "cron" -> {
                    if (val.isEmpty()) return ToolResponse.error("schedule_create", "cron 需要 Cron 表达式");
                    cronExpression = val;
                }
                default -> { }
            }
            ScheduleApplicationService.OperationResult result = schedules.save(new SaveCommand(
                    draft.id(), nm, "", type, intervalValue, "minute", dailyTime,
                    cronExpression, "", pr, true, draft.version(), false, "none", false, true,
                    policy));
            Task saved = result.snapshot().require(draft.id());
            return ToolResponse.success("schedule_create",
                    "已创建并启用定时任务「" + nm + "」（id=" + saved.id() + "，" + type + "：" + val
                            + "，策略=" + saved.executionPolicy() + "）");
        } catch (Exception e) {
            log.error("schedule_create 异常", e);
            return ToolResponse.fromException("schedule_create", e);
        }
    }

    /** Java callers predating the explicit policy keep the recurring default. */
    public String scheduleCreate(String name, String triggerType, String triggerValue, String prompt) {
        return scheduleCreate(name, triggerType, triggerValue, prompt, null);
    }

    @com.javaclaw.framework.spi.ToolContract(group = "schedule", permissions = {"tool.read"}, idempotent = true)
    @Tool(name = "schedule_list", description = "列出所有定时任务及其触发规则、启用状态、上次执行结果。")
    public String scheduleList() {
        List<Task> all = schedules.snapshot().tasks();
        if (all.isEmpty()) return ToolResponse.success("schedule_list", "当前没有定时任务");
        StringBuilder sb = new StringBuilder("共 ").append(all.size()).append(" 个定时任务：\n");
        for (Task t : all) {
            sb.append("· [").append(t.id()).append("] ").append(t.name())
                    .append(t.builtin() ? "（系统内置·只读）" : "")
                    .append(" — ").append(triggerDesc(t))
                    .append(t.enabled() ? "，启用" : "，停用")
                    .append("，策略=").append(t.executionPolicy());
            if (!t.lastRunTime().isBlank()) {
                sb.append("，上次 ").append(t.lastRunTime()).append(" ").append(t.lastRunStatus());
            }
            sb.append("\n");
        }
        return ToolResponse.success("schedule_list", sb.toString().trim());
    }

    @com.javaclaw.framework.spi.ToolContract(group = "schedule", permissions = {"tool.read"}, idempotent = true)
    @Tool(name = "schedule_get", description = "查询某个定时任务的详情与近期执行历史。")
    public String scheduleGet(@ToolParam( description = "定时任务 id") String id) {
        Task t = find(id);
        if (t == null) return ToolResponse.error("schedule_get", "未找到定时任务: " + id);
        StringBuilder sb = new StringBuilder();
        sb.append("「").append(t.name()).append("」").append(triggerDesc(t))
                .append(t.enabled() ? "，启用" : "，停用")
                .append("，策略=").append(t.executionPolicy())
                .append("\nprompt：").append(t.prompt());
        var history = t.history();
        if (!history.isEmpty()) {
            sb.append("\n近期执行：");
            for (int i = 0; i < Math.min(5, history.size()); i++) {
                var item = history.get(i);
                sb.append("\n  · ").append(item.time()).append(" [").append(item.status())
                        .append("] ").append(item.note());
            }
        }
        return ToolResponse.success("schedule_get", sb.toString());
    }

    @Tool(name = "schedule_disable", description = "按用户指令停用一个定时任务（停止后续触发，保留记录）。")
    public String scheduleDisable(@ToolParam( description = "定时任务 id") String id) {
        try {
            Task t = find(id);
            if (t == null) return ToolResponse.error("schedule_disable", "未找到定时任务: " + id);
            if (t.builtin()) return ToolResponse.error("schedule_disable", "系统内置任务不可停用: " + id);
            boolean selfDisable = origin.kind() == ToolCallOrigin.Kind.SCHEDULED
                    && Objects.equals(origin.taskId(), id);
            if (selfDisable) {
                return ToolResponse.error("schedule_disable",
                        "定时任务不能在本次执行中自行停用；UNTIL_CONDITION 策略由系统依据结构化验收结果处理");
            }
            schedules.setEnabled(ScheduleCommands.copyOf(t), false, DisablePolicy.CANCEL_ACTIVE);
            return ToolResponse.success("schedule_disable", "已停用定时任务: " + id);
        } catch (Exception e) {
            log.error("schedule_disable 异常", e);
            return ToolResponse.fromException("schedule_disable", e);
        }
    }

    @Tool(name = "schedule_delete", description = "删除一个定时任务（不可恢复）。")
    public String scheduleDelete(@ToolParam( description = "定时任务 id") String id) {
        try {
            Task t = find(id);
            if (t == null) return ToolResponse.error("schedule_delete", "未找到定时任务: " + id);
            if (t.builtin()) return ToolResponse.error("schedule_delete", "系统内置任务不可删除: " + id);
            schedules.delete(id);
            return ToolResponse.success("schedule_delete", "已删除定时任务: " + id);
        } catch (Exception e) {
            log.error("schedule_delete 异常", e);
            return ToolResponse.fromException("schedule_delete", e);
        }
    }

    @Tool(name = "schedule_run_now", description = "立即手动执行一次某个定时任务（不影响其后续调度）。")
    public String scheduleRunNow(@ToolParam( description = "定时任务 id") String id) {
        try {
            Task t = find(id);
            if (t == null) return ToolResponse.error("schedule_run_now", "未找到定时任务: " + id);
            if (t.builtin()) return ToolResponse.error("schedule_run_now", "系统内置任务由系统自动运行，不可手动触发: " + id);
            if (!t.enabled() && !ToolConfirmationManager.requestConfirmation(origin,
                    "schedule_run_now", "定时任务「" + t.name()
                            + "」已暂停。仅立即执行一次，不重新启用？")) {
                return ToolResponse.error("schedule_run_now", "用户取消了本次执行");
            }
            ScheduleApplicationService.RunResult result = schedules.runNow(id, !t.enabled()).runResult();
            return switch (result) {
                case STARTED -> ToolResponse.success("schedule_run_now", "已触发立即执行: " + id);
                case ALREADY_ACTIVE -> ToolResponse.error("schedule_run_now", "任务已在运行或排队: " + id);
                case DISABLED -> ToolResponse.error("schedule_run_now", "任务已暂停: " + id);
                case NOT_FOUND -> ToolResponse.error("schedule_run_now", "未找到定时任务: " + id);
                case UNSUPPORTED -> ToolResponse.error("schedule_run_now", "当前无法执行该任务: " + id);
            };
        } catch (Exception e) {
            log.error("schedule_run_now 异常", e);
            return ToolResponse.fromException("schedule_run_now", e);
        }
    }

    private Task find(String id) {
        return schedules.snapshot().tasks().stream()
                .filter(task -> Objects.equals(task.id(), id)).findFirst().orElse(null);
    }

    /** Post-save check used only by the trusted receipt adapter. */
    public boolean receiptHasEnabledTask(String name) {
        return schedules.snapshot().tasks().stream()
                .anyMatch(task -> !task.builtin() && task.enabled() && task.name().equals(name));
    }

    public boolean receiptTaskDisabled(String id) {
        Task task = find(id);
        return task != null && !task.enabled();
    }

    public boolean receiptTaskDeleted(String id) {
        return find(id) == null;
    }

    private static String triggerDesc(Task t) {
        if (t.builtin()) return t.describeTrigger();
        return switch (t.triggerType()) {
            case "interval" -> "每 " + t.intervalMinutes() + " 分钟";
            case "daily" -> "每天 " + t.dailyTime();
            case "cron" -> "cron(" + t.cronExpression() + ")";
            default -> t.triggerType();
        };
    }
}
