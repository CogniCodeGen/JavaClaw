package com.javaclaw.agent;

import com.javaclaw.agent.expert.CustomAgentConfig;
import com.javaclaw.agent.expert.CustomAgentConfig.CustomAgentDef;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.schedule.ScheduleManager;
import com.javaclaw.schedule.ScheduledTask;
import com.javaclaw.task.sdd.run.SddManagedTask;
import com.javaclaw.task.sdd.run.SddTaskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

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

    private final ChatService chatService;

    public ShellCommandService(ChatService chatService) {
        this.chatService = chatService;
    }

    /** 入口：解析并执行一条命令，异步回显结果。立即返回（在后台线程执行）。 */
    public void handle(ConversationRequest request, ConversationCallbacks callbacks) {
        String input = request == null ? "" : request.userInput();
        Thread t = new Thread(() -> {
            String out;
            try {
                out = dispatch(input == null ? "" : input.trim());
            } catch (Exception e) {
                log.warn("命令执行异常", e);
                out = "✗ 命令执行异常：" + e.getMessage();
            }
            callbacks.onEvent(new ConversationEvent.Reply(out));
            callbacks.onComplete();
        }, "shell-cmd");
        t.setDaemon(true);
        t.start();
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
        SddTaskManager mgr = SddTaskManager.getInstance();
        switch (sub) {
            case "", "list" -> {
                List<SddManagedTask> all = mgr.list();
                if (all.isEmpty()) return "（无托管任务）";
                StringBuilder sb = new StringBuilder("托管任务：\n");
                for (SddManagedTask t : all) {
                    sb.append("· [").append(t.id).append("] ").append(t.title)
                            .append(" — ").append(t.state).append(" ").append(t.progress).append("%\n");
                }
                return sb.toString().trim();
            }
            case "status" -> {
                if (arg.isEmpty()) return "用法：/task status <id>";
                SddManagedTask t = mgr.get(arg);
                if (t == null) return "✗ 未找到任务：" + arg;
                return "「" + t.title + "」状态=" + t.state + "，进度=" + t.progress + "%"
                        + (t.result != null && !t.result.isBlank() ? "，" + t.result : "");
            }
            case "create" -> {
                if (arg.isEmpty()) return "用法：/task create <任务描述>";
                String title = mgr.generateTitle(arg);
                String stamp = LocalDateTime.now().format(TS);
                SddManagedTask t = mgr.create(title, arg, "auto", null, 0L, "none", stamp);
                if (t == null) return "✗ 创建失败";
                mgr.start(t.id, stamp);
                return "✓ 已创建并启动长任务「" + title + "」（id=" + t.id + "）";
            }
            case "pause" -> { return ctrlTask(mgr, arg, "pause"); }
            case "resume" -> { return ctrlTask(mgr, arg, "resume"); }
            case "cancel" -> { return ctrlTask(mgr, arg, "cancel"); }
            default -> { return "✗ 未知子命令：task " + sub + "\n用法：/task list|status <id>|create <描述>|pause|resume|cancel <id>"; }
        }
    }

    private String ctrlTask(SddTaskManager mgr, String id, String op) {
        if (id.isEmpty()) return "用法：/task " + op + " <id>";
        if (mgr.get(id) == null) return "✗ 未找到任务：" + id;
        switch (op) {
            case "pause" -> mgr.pause(id);
            case "resume" -> mgr.resume(id, LocalDateTime.now().format(TS));
            case "cancel" -> mgr.cancel(id);
            default -> { }
        }
        return "✓ 已" + (op.equals("pause") ? "暂停" : op.equals("resume") ? "续跑" : "取消") + "任务：" + id;
    }

    // ==================== /agent ====================

    private String agent(String rest) {
        String[] p = rest.split("\\s+", 2);
        String sub = p[0].toLowerCase();
        CustomAgentConfig cfg = CustomAgentConfig.getInstance();
        switch (sub) {
            case "", "list" -> {
                StringBuilder sb = new StringBuilder("内置专家：\n");
                for (String d : builtinNames()) {
                    sb.append("· ").append(d).append("\n");
                }
                List<CustomAgentDef> customs = cfg.getAll();
                sb.append("自定义智能体：").append(customs.isEmpty() ? "无" : "");
                for (CustomAgentDef c : customs) {
                    sb.append("\n· [").append(c.id).append("] ").append(c.name)
                            .append(c.enabled ? "" : "（停用）");
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

    private List<String> builtinNames() {
        return chatService.builtinAgentNames();
    }

    // ==================== /schedule ====================

    private String schedule(String rest) {
        String[] p = rest.split("\\s+", 2);
        String sub = p[0].toLowerCase();
        String arg = p.length > 1 ? p[1].trim() : "";
        ScheduleManager mgr = ScheduleManager.getInstance();
        switch (sub) {
            case "", "list" -> {
                List<ScheduledTask> all = mgr.getAllTasks();
                if (all.isEmpty()) return "（无定时任务）";
                StringBuilder sb = new StringBuilder("定时任务：\n");
                for (ScheduledTask t : all) {
                    sb.append("· [").append(t.getId()).append("] ").append(t.getName())
                            .append(" — ").append(t.getTriggerType())
                            .append(t.isEnabled() ? "，启用" : "，停用").append("\n");
                }
                return sb.toString().trim();
            }
            case "get" -> {
                if (arg.isEmpty()) return "用法：/schedule get <id>";
                ScheduledTask t = mgr.getTask(arg);
                if (t == null) return "✗ 未找到定时任务：" + arg;
                return "「" + t.getName() + "」" + t.getTriggerType()
                        + (t.isEnabled() ? "，启用" : "，停用") + "\nprompt：" + t.getPrompt();
            }
            case "create" -> {
                // 用法：/schedule create 名称 | 类型 | 值 | 提示词
                String[] parts = arg.split("\\|", 4);
                if (parts.length < 4) return "用法：/schedule create 名称 | interval|daily|cron | 值 | 提示词";
                String name = parts[0].trim(), type = parts[1].trim().toLowerCase(),
                        val = parts[2].trim(), prompt = parts[3].trim();
                if (!List.of("interval", "daily", "cron").contains(type)) return "✗ 类型须为 interval/daily/cron";
                ScheduledTask t = mgr.createTask(name);
                t.setTriggerType(type);
                switch (type) {
                    case "interval" -> {
                        try { t.setIntervalMinutes(Math.max(1, Integer.parseInt(val))); }
                        catch (NumberFormatException ex) { return "✗ interval 需分钟数"; }
                    }
                    case "daily" -> t.setDailyTime(val);
                    case "cron" -> t.setCronExpression(val);
                    default -> { }
                }
                t.setPrompt(prompt);
                t.setEnabled(true);
                mgr.updateTask(t);
                return "✓ 已创建并启用定时任务「" + name + "」（id=" + t.getId() + "）";
            }
            case "stop", "disable" -> {
                if (arg.isEmpty()) return "用法：/schedule stop <id>";
                ScheduledTask t = mgr.getTask(arg);
                if (t == null) return "✗ 未找到定时任务：" + arg;
                t.setEnabled(false);
                mgr.updateTask(t);
                return "✓ 已停用定时任务：" + arg;
            }
            case "delete" -> {
                if (arg.isEmpty()) return "用法：/schedule delete <id>";
                if (mgr.getTask(arg) == null) return "✗ 未找到定时任务：" + arg;
                mgr.deleteTask(arg);
                return "✓ 已删除定时任务：" + arg;
            }
            case "run" -> {
                if (arg.isEmpty()) return "用法：/schedule run <id>";
                if (mgr.getTask(arg) == null) return "✗ 未找到定时任务：" + arg;
                mgr.runNow(arg);
                return "✓ 已触发立即执行：" + arg;
            }
            default -> { return "✗ 未知子命令：schedule " + sub + "\n用法：/schedule list|get <id>|create ...|stop <id>|delete <id>|run <id>"; }
        }
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
