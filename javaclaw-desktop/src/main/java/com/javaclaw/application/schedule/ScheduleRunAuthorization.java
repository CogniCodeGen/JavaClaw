package com.javaclaw.application.schedule;

import com.javaclaw.framework.api.PermissionSet;

/** 调度开始前核验来源权限，授权租约随本次执行释放。 */
@FunctionalInterface
public interface ScheduleRunAuthorization {
    Grant authorize(String workspaceId, String taskId);

    record Grant(PermissionSet permissions, AutoCloseable lease) implements AutoCloseable {
        @Override public void close() throws Exception { lease.close(); }
    }

    ScheduleRunAuthorization USER_TASKS = (workspace, task) ->
            new Grant(PermissionSet.UNRESTRICTED, () -> { });
}
