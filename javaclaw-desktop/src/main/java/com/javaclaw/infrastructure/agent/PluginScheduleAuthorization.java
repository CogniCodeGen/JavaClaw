package com.javaclaw.infrastructure.agent;

import com.javaclaw.application.plugin.v2.PluginManagementService;
import com.javaclaw.application.schedule.ScheduleApplicationService;
import com.javaclaw.application.schedule.ScheduleCommands;
import com.javaclaw.application.schedule.ScheduleRunAuthorization;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.runtime.WorkspaceRuntime;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.function.Supplier;

/** 插件创建的调度保持来源归属，不能取得用户任务的工具授权。 */
public final class PluginScheduleAuthorization implements ScheduleRunAuthorization {
    private final PluginManagementService plugins;
    private final JdbcTemplate jdbc;
    private final Supplier<WorkspaceRuntime> workspace;

    public PluginScheduleAuthorization(PluginManagementService plugins, JdbcTemplate jdbc,
                                      Supplier<WorkspaceRuntime> workspace) {
        this.plugins = plugins; this.jdbc = jdbc; this.workspace = workspace;
    }

    @Override public Grant authorize(String workspaceId, String taskId) {
        var owners = jdbc.query("SELECT plugin_id FROM plugin_storage WHERE workspace_id=? AND store_key=? AND store_value=?",
                (row, i) -> row.getString(1), workspaceId, "__host:schedule:" + taskId, taskId);
        if (owners.isEmpty()) return USER_TASKS.authorize(workspaceId, taskId);
        if (owners.size() != 1) throw new SecurityException("插件调度来源不唯一");
        var current = workspace.get();
        if (current == null || current.isClosed() || !current.context().workspaceId().equals(workspaceId))
            throw new SecurityException("插件调度工作区已失效");
        var lease = plugins.acquireRun(workspaceId, owners.getFirst());
        if (!lease.grantedPermissions().contains("host.schedule")) {
            lease.close();
            throw new SecurityException("插件调度权限已撤销");
        }
        return new Grant(PermissionSet.NONE, lease);
    }

    /** 撤权与停用同步取消排队或活动任务；下次启动不会自动重新授权。 */
    public void disable(String workspaceId, String pluginId) {
        List<String> owned = owned(workspaceId, pluginId);
        var current = workspace.get();
        if (current != null && !current.isClosed() && current.context().workspaceId().equals(workspaceId)) {
            var schedules = current.schedules();
            for (var task : schedules.snapshot().tasks()) {
                if (owned.contains(task.id()) && (task.enabled() || task.active()))
                    schedules.setEnabled(ScheduleCommands.copyOf(task), false,
                            ScheduleApplicationService.DisablePolicy.CANCEL_ACTIVE);
            }
        } else {
            // 未激活工作区没有调度器；同步持久停用，避免再次打开工作区时启动旧任务。
            for (String id : owned) jdbc.update("UPDATE scheduled_tasks SET enabled=FALSE,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE workspace_id=? AND id=? AND enabled=TRUE", workspaceId, id);
        }
    }

    public void deleteData(String workspaceId, String pluginId) {
        disable(workspaceId, pluginId);
        var current = workspace.get();
        for (String id : owned(workspaceId, pluginId)) {
            if (current != null && !current.isClosed() && current.context().workspaceId().equals(workspaceId)) {
                if (current.schedules().snapshot().tasks().stream().anyMatch(task -> task.id().equals(id)))
                    current.schedules().delete(id);
            } else jdbc.update("DELETE FROM scheduled_tasks WHERE workspace_id=? AND id=?", workspaceId, id);
        }
        jdbc.update("DELETE FROM plugin_storage WHERE workspace_id=? AND plugin_id=?", workspaceId, pluginId);
    }

    private List<String> owned(String workspaceId, String pluginId) {
        return jdbc.query("SELECT store_value FROM plugin_storage WHERE workspace_id=? AND plugin_id=? AND LEFT(store_key,16)='__host:schedule:'",
                (row, i) -> row.getString(1), workspaceId, pluginId);
    }
}
