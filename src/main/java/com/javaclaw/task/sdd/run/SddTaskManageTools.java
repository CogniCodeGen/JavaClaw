package com.javaclaw.task.sdd.run;

import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.agent.model.ToolResponse;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 长任务（SDD 托管任务）管理工具集 —— 让编排器在对话中自主创建与管理长时托管任务。
 *
 * <p>委派 {@link SddTaskManager} 单例。创建类受 {@link ToolConfirmationManager} 人工确认
 * （会反复自主消耗 token）；查询/控制类直接执行。管理的是用户创建的任务实例。</p>
 */
public final class SddTaskManageTools {

    private static final Logger log = LoggerFactory.getLogger(SddTaskManageTools.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private SddTaskManager mgr() {
        return SddTaskManager.getInstance();
    }

    @Tool(name = "task_create",
            description = "创建并启动一个长时托管任务（SDD：按提案→规格→实现→验收自主推进，可耗时数分钟到更久）。"
                    + "适用于需要持续、多步骤自主完成的复杂目标（写代码/批量处理/调研产出物等）。"
                    + "标题留空会自动生成；工作目录留空默认在程序目录 task/ 下新建；预算 0 表示不限。"
                    + "需要用户确认后才会创建。")
    public String taskCreate(
            @ToolParam(name = "description", description = "任务需求的详细描述") String description,
            @ToolParam(name = "title", description = "任务标题，可留空（自动从描述生成）") String title,
            @ToolParam(name = "workDir", description = "工作目录绝对路径，可留空（默认 task/ 下新建独立目录）") String workDir,
            @ToolParam(name = "tokenBudget", description = "token 预算，0 或留空表示不限，例如 120000") String tokenBudget) {
        String desc = description == null ? "" : description.trim();
        if (desc.isEmpty()) {
            return ToolResponse.error("task_create", "任务描述不能为空");
        }
        if (!ToolConfirmationManager.requestConfirmation("task_create", "创建长任务：" + desc)) {
            return ToolResponse.error("task_create", "用户取消了创建");
        }
        long budget = 0L;
        try { if (tokenBudget != null && !tokenBudget.isBlank()) budget = Math.max(0, Long.parseLong(tokenBudget.trim())); }
        catch (NumberFormatException ignore) { /* 非法预算视为不限 */ }
        try {
            String resolvedTitle = (title == null || title.isBlank()) ? mgr().generateTitle(desc) : title.trim();
            String stamp = LocalDateTime.now().format(TS);
            SddManagedTask t = mgr().create(resolvedTitle, desc, "auto",
                    (workDir == null || workDir.isBlank()) ? null : workDir.trim(),
                    budget, "none", stamp);
            if (t == null) return ToolResponse.error("task_create", "创建失败");
            mgr().start(t.id, stamp);
            return ToolResponse.success("task_create",
                    "已创建并启动长任务「" + resolvedTitle + "」（id=" + t.id + "），可用 task_status 查询进度");
        } catch (Exception e) {
            log.error("task_create 异常", e);
            return ToolResponse.fromException("task_create", e);
        }
    }

    @Tool(name = "task_list", description = "列出所有托管任务及其状态、进度。")
    public String taskList() {
        List<SddManagedTask> all = mgr().list();
        if (all.isEmpty()) return ToolResponse.success("task_list", "当前没有托管任务");
        StringBuilder sb = new StringBuilder("共 ").append(all.size()).append(" 个托管任务：\n");
        for (SddManagedTask t : all) {
            sb.append("· [").append(t.id).append("] ").append(t.title)
                    .append(" — ").append(t.state).append("，进度 ").append(t.progress).append("%\n");
        }
        return ToolResponse.success("task_list", sb.toString().trim());
    }

    @Tool(name = "task_status", description = "查询某个托管任务的状态、进度与结果说明。")
    public String taskStatus(@ToolParam(name = "id", description = "任务 id") String id) {
        SddManagedTask t = mgr().get(id);
        if (t == null) return ToolResponse.error("task_status", "未找到任务: " + id);
        StringBuilder sb = new StringBuilder();
        sb.append("「").append(t.title).append("」状态=").append(t.state)
                .append("，进度=").append(t.progress).append("%");
        if (t.result != null && !t.result.isBlank()) sb.append("，说明：").append(t.result);
        sb.append("，累计 token=").append(t.totalInputTokens + t.totalOutputTokens);
        return ToolResponse.success("task_status", sb.toString());
    }

    @Tool(name = "task_pause", description = "暂停一个运行中的托管任务（可后续 task_resume 续跑）。")
    public String taskPause(@ToolParam(name = "id", description = "任务 id") String id) {
        if (mgr().get(id) == null) return ToolResponse.error("task_pause", "未找到任务: " + id);
        mgr().pause(id);
        return ToolResponse.success("task_pause", "已暂停任务: " + id);
    }

    @Tool(name = "task_resume", description = "续跑一个已暂停或待人工的托管任务（从首个未完成步骤继续）。")
    public String taskResume(@ToolParam(name = "id", description = "任务 id") String id) {
        if (mgr().get(id) == null) return ToolResponse.error("task_resume", "未找到任务: " + id);
        mgr().resume(id, LocalDateTime.now().format(TS));
        return ToolResponse.success("task_resume", "已续跑任务: " + id);
    }

    @Tool(name = "task_cancel", description = "取消一个托管任务（终止运行，不可续跑）。")
    public String taskCancel(@ToolParam(name = "id", description = "任务 id") String id) {
        if (mgr().get(id) == null) return ToolResponse.error("task_cancel", "未找到任务: " + id);
        mgr().cancel(id);
        return ToolResponse.success("task_cancel", "已取消任务: " + id);
    }
}
