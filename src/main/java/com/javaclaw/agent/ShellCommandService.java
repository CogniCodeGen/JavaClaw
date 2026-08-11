package com.javaclaw.agent;

import com.javaclaw.application.agent.AgentManagementApplicationService;
import com.javaclaw.application.agent.AgentManagementApplicationService.Agent;
import com.javaclaw.application.schedule.ScheduleApplicationService;
import com.javaclaw.application.schedule.ScheduleApplicationService.SaveCommand;
import com.javaclaw.application.schedule.ScheduleApplicationService.Task;
import com.javaclaw.application.schedule.ScheduleCommands;
import com.javaclaw.application.task.SddTaskApplicationService;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.api.conversation.CancellationReason;
import com.javaclaw.platform.execution.TaskHandle;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

/**
 * Shell 命令服务 —— 在"命令"交互模式下，用确定性命令（不走 LLM）创建与管理长任务、定时任务、智能体。
 *
 * <p>与对话内的三组 @Tool 是同一批底层 Manager 的两条入口：这里供用户手敲命令即时执行。
 * 管理的是用户创建的实例；内置专家不可删除。命令在后台线程执行后经 {@link ConversationCallbacks}
 * 回显一条文本结果。</p>
 */
public final class ShellCommandService {

    private static final Logger log = LoggerFactory.getLogger(ShellCommandService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AgentManagementApplicationService agents;
    private final ScheduleApplicationService schedules;
    private final SddTaskApplicationService sddTasks;
    private final TaskScope tasks;

    public ShellCommandService(
            AgentManagementApplicationService agents,
            ScheduleApplicationService schedules,
            SddTaskApplicationService sddTasks,
            TaskScope tasks) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.schedules = Objects.requireNonNull(schedules, "schedules");
        this.sddTasks = Objects.requireNonNull(sddTasks, "sddTasks");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    /** 入口：解析并执行一条命令，异步回显结果。立即返回（在后台线程执行）。 */
    public TaskHandle<String> handle(
            ConversationRequest request,
            ConversationCallbacks callbacks) {
        String input = request == null ? "" : request.userInput();
        TaskHandle<String> handle = tasks.submit(
                TaskSpec.io("shell-command"),
                context -> dispatch(input == null ? "" : input.trim()));
        handle.completion().whenComplete((output, failure) -> {
            if (failure == null) {
                callbacks.onEvent(new ConversationEvent.Reply(output));
                callbacks.onTerminal(ConversationOutcome.completed());
                return;
            }
            Throwable cause = unwrap(failure);
            if (cause instanceof CancellationException) {
                callbacks.onTerminal(ConversationOutcome.cancelled(
                        CancellationReason.RUNTIME_REBUILD));
                return;
            }
            log.warn("命令执行异常", cause);
            callbacks.onEvent(new ConversationEvent.Reply(
                    "✗ 命令执行异常：" + cause.getMessage()));
            callbacks.onTerminal(ConversationOutcome.failed(cause));
        });
        return handle;
    }

    private String dispatch(String line) {
        if (line.isEmpty() || line.equals("/help") || line.equals("help") || line.equals("/?")) {
            return helpText();
        }
        String body = line.startsWith("/") ? line.substring(1) : line;
        String[] head = body.split("\\s+", 2);
        String domain = head[0].toLowerCase();
        String rest = head.length > 1 ? head[1].trim() : "";
        return switch (domain) {
            case "task", "任务" -> task(rest);
            case "agent", "智能体" -> agent(rest);
            case "schedule", "定时" -> schedule(rest);
            default -> "✗ 未知命令：" + domain + "\n\n" + helpText();
        };
    }

    // ==================== /task ====================

    private String task(String rest) {
        String[] p = rest.split("\\s+", 2);
        String sub = p[0].toLowerCase();
        String arg = p.length > 1 ? p[1].trim() : "";
        switch (sub) {
            case "", "list" -> {
                List<SddTaskApplicationService.Task> all = sddTasks.snapshot().tasks();
                if (all.isEmpty()) return "（无托管任务）";
                StringBuilder sb = new StringBuilder("托管任务：\n");
                for (SddTaskApplicationService.Task task : all) {
                    sb.append("· [").append(task.id()).append("] ").append(task.title())
                            .append(" — ").append(task.state()).append(" ")
                            .append(task.progress()).append("%\n");
                }
                return sb.toString().trim();
            }
            case "status" -> {
                if (arg.isEmpty()) return "用法：/task status <id>";
                try {
                    SddTaskApplicationService.Task task = sddTasks.require(arg);
                    return "「" + task.title() + "」状态=" + task.state()
                            + "，进度=" + task.progress() + "%"
                            + (task.result() != null && !task.result().isBlank()
                            ? "，" + task.result() : "");
                } catch (com.javaclaw.application.error.NotFoundException failure) {
                    return "✗ " + failure.getMessage();
                }
            }
            case "create" -> {
                if (arg.isEmpty()) return "用法：/task create <任务描述>";
                String title = sddTasks.generateTitle(arg);
                String stamp = LocalDateTime.now().format(TS);
                SddTaskApplicationService.Task task = sddTasks.create(
                        new SddTaskApplicationService.CreateCommand(
                                title, arg, "auto", null, 0L, "none", stamp));
                sddTasks.start(task.id(), stamp);
                return "✓ 已创建并启动长任务「" + title + "」（id=" + task.id() + "）";
            }
            case "pause" -> { return ctrlTask(arg, "pause"); }
            case "resume" -> { return ctrlTask(arg, "resume"); }
            case "cancel" -> { return ctrlTask(arg, "cancel"); }
            default -> { return "✗ 未知子命令：task " + sub + "\n用法：/task list|status <id>|create <描述>|pause|resume|cancel <id>"; }
        }
    }

    private String ctrlTask(String id, String op) {
        if (id.isEmpty()) return "用法：/task " + op + " <id>";
        try {
            switch (op) {
                case "pause" -> sddTasks.pause(id);
                case "resume" -> sddTasks.resume(id, LocalDateTime.now().format(TS));
                case "cancel" -> sddTasks.cancel(id);
                default -> { }
            }
        } catch (com.javaclaw.application.error.NotFoundException failure) {
            return "✗ " + failure.getMessage();
        }
        return "✓ 已" + (op.equals("pause") ? "暂停" : op.equals("resume") ? "续跑" : "取消") + "任务：" + id;
    }

    // ==================== /agent ====================

    private String agent(String rest) {
        String[] p = rest.split("\\s+", 2);
        String sub = p[0].toLowerCase();
        switch (sub) {
            case "", "list" -> {
                StringBuilder sb = new StringBuilder("内置专家：\n");
                List<Agent> catalog = agents.catalog().agents();
                catalog.stream().filter(Agent::builtIn)
                        .forEach(agent -> sb.append("· ").append(agent.name()).append("\n"));
                List<Agent> customs = catalog.stream().filter(agent -> !agent.builtIn()).toList();
                sb.append("自定义智能体：").append(customs.isEmpty() ? "无" : "");
                for (Agent custom : customs) {
                    sb.append("\n· [").append(custom.id()).append("] ").append(custom.name())
                            .append(custom.enabled() ? "" : "（停用）");
                }
                return sb.toString().trim();
            }
            case "create", "delete" -> {
                return "✗ 已不在命令中增删智能体。可复用能力请改用技能（在对话中让我 skill_create，或用技能中心）；"
                        + "自定义智能体的增删请到设置面板。/agent 仅支持 list。";
            }
            default -> { return "✗ 未知子命令：agent " + sub + "\n用法：/agent list"; }
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    // ==================== /schedule ====================

    private String schedule(String rest) {
        String[] p = rest.split("\\s+", 2);
        String sub = p[0].toLowerCase();
        String arg = p.length > 1 ? p[1].trim() : "";
        switch (sub) {
            case "", "list" -> {
                List<Task> all = schedules.snapshot().tasks();
                if (all.isEmpty()) return "（无定时任务）";
                StringBuilder sb = new StringBuilder("定时任务：\n");
                for (Task t : all) {
                    sb.append("· [").append(t.id()).append("] ").append(t.name())
                            .append(" — ").append(t.triggerType())
                            .append(t.enabled() ? "，启用" : "，停用").append("\n");
                }
                return sb.toString().trim();
            }
            case "get" -> {
                if (arg.isEmpty()) return "用法：/schedule get <id>";
                Task t = scheduleTask(arg);
                if (t == null) return "✗ 未找到定时任务：" + arg;
                return "「" + t.name() + "」" + t.triggerType()
                        + (t.enabled() ? "，启用" : "，停用") + "\nprompt：" + t.prompt();
            }
            case "create" -> {
                // 用法：/schedule create 名称 | 类型 | 值 | 提示词
                String[] parts = arg.split("\\|", 4);
                if (parts.length < 4) return "用法：/schedule create 名称 | interval|daily|cron | 值 | 提示词";
                String name = parts[0].trim(), type = parts[1].trim().toLowerCase(),
                        val = parts[2].trim(), prompt = parts[3].trim();
                if (!List.of("interval", "daily", "cron").contains(type)) return "✗ 类型须为 interval/daily/cron";
                Task draft = schedules.createDraft(name);
                int intervalValue = 1;
                String dailyTime = "";
                String cronExpression = "";
                switch (type) {
                    case "interval" -> {
                        try { intervalValue = Math.max(1, Integer.parseInt(val)); }
                        catch (NumberFormatException ex) { return "✗ interval 需分钟数"; }
                    }
                    case "daily" -> dailyTime = val;
                    case "cron" -> cronExpression = val;
                    default -> { }
                }
                var saved = schedules.save(new SaveCommand(draft.id(), name, "", type,
                        intervalValue, "minute", dailyTime, cronExpression, "", prompt,
                        true, draft.version(), false, "none", false, true))
                        .snapshot().require(draft.id());
                return "✓ 已创建并启用定时任务「" + name + "」（id=" + saved.id() + "）";
            }
            case "stop", "disable" -> {
                if (arg.isEmpty()) return "用法：/schedule stop <id>";
                Task task = scheduleTask(arg);
                if (task == null) return "✗ 未找到定时任务：" + arg;
                schedules.setEnabled(ScheduleCommands.copyOf(task), false);
                return "✓ 已停用定时任务：" + arg;
            }
            case "delete" -> {
                if (arg.isEmpty()) return "用法：/schedule delete <id>";
                if (scheduleTask(arg) == null) return "✗ 未找到定时任务：" + arg;
                schedules.delete(arg);
                return "✓ 已删除定时任务：" + arg;
            }
            case "run" -> {
                if (arg.isEmpty()) return "用法：/schedule run <id>";
                Task t = scheduleTask(arg);
                if (t == null) return "✗ 未找到定时任务：" + arg;
                ScheduleApplicationService.RunResult result =
                        schedules.runNow(arg, !t.enabled()).runResult();
                return switch (result) {
                    case STARTED -> "✓ 已触发立即执行：" + arg
                            + (t.enabled() ? "" : "（仅本次，仍保持暂停）");
                    case ALREADY_ACTIVE -> "✗ 任务已在运行或排队：" + arg;
                    case DISABLED -> "✗ 任务已暂停：" + arg;
                    case NOT_FOUND -> "✗ 未找到定时任务：" + arg;
                    case UNSUPPORTED -> "✗ 当前无法执行该任务：" + arg;
                };
            }
            default -> { return "✗ 未知子命令：schedule " + sub + "\n用法：/schedule list|get <id>|create ...|stop <id>|delete <id>|run <id>"; }
        }
    }

    private Task scheduleTask(String id) {
        return schedules.snapshot().tasks().stream()
                .filter(task -> Objects.equals(task.id(), id)).findFirst().orElse(null);
    }

    private String helpText() {
        return """
                命令模式 —— 用确定性命令管理长任务 / 智能体 / 定时工作（内置专家不可删除）：

                长任务（SDD 托管任务）
                  /task list                      列出全部任务
                  /task status <id>               查询某任务进度
                  /task create <任务描述>          创建并启动（标题自动生成、目录默认 task/ 下新建）
                  /task pause|resume|cancel <id>  暂停 / 续跑 / 取消

                智能体（只读；新增可复用能力请改用技能 skill_create，或到设置面板增删自定义智能体）
                  /agent list                                 列出内置专家与自定义智能体

                定时工作
                  /schedule list                                   列出全部定时任务
                  /schedule get <id>                               查看详情
                  /schedule create 名称 | interval|daily|cron | 值 | 提示词
                  /schedule stop <id>                              停用
                  /schedule delete <id>                            删除
                  /schedule run <id>                               立即执行一次
                """;
    }
}
