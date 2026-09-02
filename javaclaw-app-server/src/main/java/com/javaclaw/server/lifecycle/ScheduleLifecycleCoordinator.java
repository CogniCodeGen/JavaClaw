package com.javaclaw.server.lifecycle;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.LoginStartupPort;
import com.javaclaw.extension.spi.ScheduleLifecyclePort;

/** 把全部 Workspace 的启用 Schedule 投影为一个持久 lease 和一个用户登录启动项。 */
public final class ScheduleLifecycleCoordinator implements ScheduleLifecyclePort, AutoCloseable {
    private static final String LEASE_OWNER = "schedule-enabled";
    private static final String LEASE_KIND = "SCHEDULE";

    private final LifecycleCoordinator lifecycle;
    private final LoginStartupPort loginStartup;
    private final Map<WorkspaceId, Boolean> workspaces = new HashMap<>();
    private LifecycleCoordinator.Lease lease;
    private boolean required;

    /**
     * 创建 Schedule 后台生命周期协调器。
     *
     * @param lifecycle App Server lease 协调器
     * @param loginStartup 三平台登录启动项端口
     */
    public ScheduleLifecycleCoordinator(LifecycleCoordinator lifecycle, LoginStartupPort loginStartup) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.loginStartup = Objects.requireNonNull(loginStartup, "loginStartup");
    }

    /**
     * {@inheritDoc}
     *
     * @implNote 先准备新 lease 再切换登录启动项；任何一步失败都不发布新的内存投影。
     */
    @Override
    public synchronized void synchronize(WorkspaceId workspaceId, boolean workspaceRequired) {
        WorkspaceId id = Objects.requireNonNull(workspaceId, "workspaceId");
        Map<WorkspaceId, Boolean> candidate = new HashMap<>(workspaces);
        candidate.put(id, workspaceRequired);
        boolean nextRequired = candidate.values().stream().anyMatch(Boolean::booleanValue);
        if (nextRequired == required) {
            workspaces.clear();
            workspaces.putAll(candidate);
            return;
        }
        if (nextRequired) {
            enable(candidate);
        } else {
            disable(candidate);
        }
    }

    /**
     * 重新把当前权威需求写入系统登录启动项。
     *
     * <p>该操作不修改 Definition 或 lease，供诊断页“修复启动项”调用。
     */
    public synchronized void repairLoginStartup() {
        LoginStartupPort.Status status = loginStartup.status(required);
        if (!status.repairAvailable()) {
            throw new IllegalStateException(status.unavailableReason().orElseThrow());
        }
        loginStartup.setRequired(required);
    }

    /**
     * 返回不含 Workspace 身份的当前状态。
     *
     * @return 是否需要登录启动、是否持有 lease 与已同步 Workspace 数
     */
    public synchronized Status status() {
        LoginStartupPort.Status startup = loginStartup.status(required);
        return new Status(
                required,
                lease != null,
                workspaces.size(),
                startup.repairAvailable(),
                startup.installed(),
                startup.unavailableReason());
    }

    private void enable(Map<WorkspaceId, Boolean> candidate) {
        LifecycleCoordinator.Lease acquired = lifecycle.acquirePersistentActivity(LEASE_OWNER, LEASE_KIND);
        try {
            loginStartup.setRequired(true);
        } catch (RuntimeException failure) {
            acquired.close();
            throw failure;
        }
        lease = acquired;
        required = true;
        replaceWorkspaces(candidate);
    }

    private void disable(Map<WorkspaceId, Boolean> candidate) {
        loginStartup.setRequired(false);
        LifecycleCoordinator.Lease current = lease;
        lease = null;
        required = false;
        replaceWorkspaces(candidate);
        if (current != null) {
            current.close();
        }
    }

    private void replaceWorkspaces(Map<WorkspaceId, Boolean> candidate) {
        workspaces.clear();
        workspaces.putAll(candidate);
    }

    /** 释放本进程 lease；登录启动项仍反映持久 Definition，供下次登录启动服务。 */
    @Override
    public synchronized void close() {
        LifecycleCoordinator.Lease current = lease;
        lease = null;
        workspaces.clear();
        if (current != null) {
            current.close();
        }
    }

    /**
     * Schedule 生命周期非敏感状态。
     *
     * @param loginStartupRequired 权威状态是否要求登录启动
     * @param leaseHeld 当前进程是否持有 Schedule lease
     * @param synchronizedWorkspaces 已恢复或更新的 Workspace 数
     * @param loginStartupRepairAvailable 当前运行环境能否修复启动项
     * @param loginStartupInstalled 系统启动项投影是否存在
     * @param loginStartupUnavailableReason 不可修复时的脱敏原因
     */
    public record Status(
            boolean loginStartupRequired,
            boolean leaseHeld,
            int synchronizedWorkspaces,
            boolean loginStartupRepairAvailable,
            boolean loginStartupInstalled,
            java.util.Optional<String> loginStartupUnavailableReason) {
        /** 校验计数。 */
        public Status {
            if (synchronizedWorkspaces < 0) {
                throw new IllegalArgumentException("synchronizedWorkspaces must not be negative");
            }
            loginStartupUnavailableReason =
                    Objects.requireNonNull(loginStartupUnavailableReason, "loginStartupUnavailableReason");
        }
    }
}
