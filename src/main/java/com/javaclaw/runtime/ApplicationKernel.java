package com.javaclaw.runtime;

import com.javaclaw.agent.ScheduledTaskAgent;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.agent.risk.LlmToolScopeAssessor;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.browser.PlaywrightBrowserManager;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.config.DataManager;
import com.javaclaw.config.EmailConfig;
import com.javaclaw.config.NotificationConfig;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.diagnostics.TraceRecorder;
import com.javaclaw.plugin.PluginManager;
import com.javaclaw.schedule.ScheduleManager;
import com.javaclaw.site.SiteCredentialManager;
import com.javaclaw.skill.SkillManager;
import com.javaclaw.skill.SkillUsageTracker;
import com.javaclaw.skill.curation.SkillProposalQueue;
import com.javaclaw.task.sdd.run.SddTaskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 应用级组合根与生命周期控制器。
 *
 * <p>它是创建、重建、切换和关闭 {@link WorkspaceRuntime} 的唯一入口。JavaFX 控制器只负责
 * 停流、展示遮罩和在完成后采用新的运行时，不再编排基础设施生命周期。</p>
 */
public final class ApplicationKernel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ApplicationKernel.class);

    private final PlaywrightBrowserManager browserManager;
    private final UserInteractionPort interactionPort;
    private final RuntimeFactory runtimeFactory;
    private final AtomicBoolean transitioning = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile WorkspaceRuntime current;
    private boolean externalServicesInitialized;

    public ApplicationKernel(PlaywrightBrowserManager browserManager,
                             UserInteractionPort interactionPort,
                             Runnable openTaskView,
                             Runnable openWorkflowView,
                             Runnable closeWorkflowView) {
        this.browserManager = Objects.requireNonNull(browserManager, "browserManager");
        this.interactionPort = Objects.requireNonNull(interactionPort, "interactionPort");
        this.runtimeFactory = new RuntimeFactory(browserManager, openTaskView,
                openWorkflowView, closeWorkflowView, java.util.Set.of());
    }

    public ApplicationKernel(PlaywrightBrowserManager browserManager,
                             UserInteractionPort interactionPort,
                             Runnable openTaskView,
                             Runnable openWorkflowView) {
        this(browserManager, interactionPort, openTaskView, openWorkflowView, () -> {});
    }

    /** 兼容无工作流 UI 的无头/截图驱动。 */
    public ApplicationKernel(PlaywrightBrowserManager browserManager,
                             UserInteractionPort interactionPort,
                             Runnable openTaskView) {
        this(browserManager, interactionPort, openTaskView, () -> {}, () -> {});
    }

    /** 创建首个工作区运行时并装配依赖它的全局子系统。 */
    public synchronized WorkspaceRuntime initialize() {
        ensureOpen();
        if (current != null) return current;
        WorkspaceRuntime created = runtimeFactory.create(WorkspaceContext.captureCurrent());
        externalServicesInitialized = true;
        try {
            activate(created, true);
            current = created;
            return created;
        } catch (RuntimeException | Error e) {
            created.close();
            throw e;
        }
    }

    public WorkspaceRuntime current() {
        WorkspaceRuntime snapshot = current;
        if (snapshot == null) {
            throw new IllegalStateException("ApplicationKernel 尚未初始化或运行时不可用");
        }
        return snapshot;
    }

    public PlaywrightBrowserManager browserManager() {
        return browserManager;
    }

    public boolean isTransitioning() {
        return transitioning.get();
    }

    /** 按当前工作区配置重建完整运行时。失败时自动再尝试一次恢复。 */
    public WorkspaceRuntime rebuildCurrent() {
        beginTransition("运行时重建");
        try {
            return rebuildCurrentInternal();
        } finally {
            transitioning.set(false);
        }
    }

    private synchronized WorkspaceRuntime rebuildCurrentInternal() {
        ensureOpen();
        WorkspaceContext context = WorkspaceContext.captureCurrent();
        WorkspaceRuntime old = current;
        current = null;
        if (old != null) {
            quiesceRuntimeDependents();
            old.close();
        }

        RuntimeException firstFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                WorkspaceRuntime replacement = createAndActivate(context);
                current = replacement;
                return replacement;
            } catch (RuntimeException e) {
                if (firstFailure == null) firstFailure = e;
                else firstFailure.addSuppressed(e);
                log.error("重建工作区运行时失败（第 {} 次）", attempt, e);
            }
        }
        throw new IllegalStateException("无法恢复工作区运行时: " + context.workspaceId(), firstFailure);
    }

    /**
     * 原子式切换工作区；目标工作区任一步骤失败都会切回原工作区并重建其运行时。
     */
    public WorkspaceRuntime switchWorkspace(String targetWorkspaceId) {
        Objects.requireNonNull(targetWorkspaceId, "targetWorkspaceId");
        beginTransition("工作区切换");
        try {
            return switchWorkspaceInternal(targetWorkspaceId);
        } finally {
            transitioning.set(false);
        }
    }

    private synchronized WorkspaceRuntime switchWorkspaceInternal(String targetWorkspaceId) {
        ensureOpen();
        WorkspaceManager workspaces = WorkspaceManager.getInstance();
        String previousId = workspaces.getCurrentWorkspaceId();
        if (targetWorkspaceId.equals(previousId)) return current();

        WorkspaceRuntime old = current;
        browserManager.saveCookies();
        current = null;
        if (old != null) {
            quiesceRuntimeDependents();
            old.close();
        }

        try {
            if (!workspaces.switchWorkspace(targetWorkspaceId)) {
                throw new IllegalStateException("WorkspaceManager 拒绝切换到 " + targetWorkspaceId);
            }
            reloadWorkspaceState();
            WorkspaceContext targetContext = WorkspaceContext.captureCurrent();
            browserManager.rebindWorkspace(targetContext.browserDir(), targetContext.screenshotsDir());

            WorkspaceRuntime replacement = createAndActivate(targetContext);
            current = replacement;
            return replacement;
        } catch (RuntimeException | Error switchFailure) {
            log.error("切换到工作区 {} 失败，开始回滚到 {}", targetWorkspaceId, previousId,
                    switchFailure);
            try {
                recoverWorkspace(previousId);
            } catch (RuntimeException | Error rollbackFailure) {
                switchFailure.addSuppressed(rollbackFailure);
                log.error("工作区回滚失败，应用运行时不可用", rollbackFailure);
            }
            throw new IllegalStateException("工作区切换失败，已尝试回滚: " + targetWorkspaceId,
                    switchFailure);
        }
    }

    private void recoverWorkspace(String workspaceId) {
        WorkspaceRuntime partial = current;
        current = null;
        if (partial != null) partial.close();

        WorkspaceManager workspaces = WorkspaceManager.getInstance();
        if (!workspaceId.equals(workspaces.getCurrentWorkspaceId())
                && !workspaces.switchWorkspace(workspaceId)) {
            throw new IllegalStateException("无法切回原工作区: " + workspaceId);
        }
        reloadWorkspaceState();
        WorkspaceContext restoredContext = WorkspaceContext.captureCurrent();
        browserManager.rebindWorkspace(restoredContext.browserDir(), restoredContext.screenshotsDir());
        WorkspaceRuntime restored = createAndActivate(restoredContext);
        current = restored;
    }

    /** 创建后激活；激活链失败时必须释放未发布的运行时。 */
    private WorkspaceRuntime createAndActivate(WorkspaceContext context) {
        WorkspaceRuntime created = runtimeFactory.create(context);
        try {
            activate(created, false);
            return created;
        } catch (RuntimeException | Error e) {
            created.close();
            throw e;
        }
    }

    /** 让跨工作区单例重新绑定到 WorkspaceManager 当前指向的数据。 */
    private void reloadWorkspaceState() {
        AgentConfig.getInstance().reload();
        EmailConfig.getInstance().reload();
        NotificationConfig.getInstance().reload();
        DataManager.getInstance().reload();
        SiteCredentialManager.getInstance().reload();
        SkillUsageTracker.getInstance().reload();
        SkillProposalQueue.getInstance().reload();
        TraceRecorder.getInstance().reload();
    }

    /** 把全局订阅方统一切到新运行时，避免任何管理器继续持有旧服务。 */
    private void activate(WorkspaceRuntime workspaceRuntime, boolean initial) {
        var runtime = workspaceRuntime.agentRuntime();
        ToolConfirmationManager.setScopeAssessor(
                new LlmToolScopeAssessor(runtime.getModelFactory(), runtime.getTokenTracker()));

        if (initial) {
            ScheduleManager.getInstance().init(new ScheduledTaskAgent(runtime));
            PluginManager.getInstance().init(runtime, interactionPort);
            SddTaskManager.getInstance().configure(
                    workspaceRuntime.context().dataRoot(),
                    runtime.getModelFactory(), runtime::buildCapabilityTools,
                    SkillManager.getInstance(), interactionPort, workspaceRuntime.workflowService(),
                    workspaceRuntime.databaseAccess(), workspaceRuntime.context().workspaceId());
        } else {
            ScheduleManager.getInstance().reload(new ScheduledTaskAgent(runtime));
            PluginManager.getInstance().reload(runtime);
            SddTaskManager.getInstance().reload(
                    workspaceRuntime.context().dataRoot(),
                    runtime.getModelFactory(), runtime::buildCapabilityTools,
                    workspaceRuntime.workflowService(), workspaceRuntime.databaseAccess(),
                    workspaceRuntime.context().workspaceId());
        }
    }

    /** 先停靠所有持有旧运行时句柄的后台系统，再释放旧基础设施。 */
    private void quiesceRuntimeDependents() {
        if (!externalServicesInitialized) return;
        closeQuietly("定时任务运行时", () -> ScheduleManager.getInstance().suspendForRuntimeTransition());
        closeQuietly("SDD 运行任务", () -> SddTaskManager.getInstance().suspendForRuntimeTransition());
        closeQuietly("插件运行时", () -> PluginManager.getInstance().suspendForRuntimeTransition());
    }

    private void beginTransition(String operation) {
        ensureOpen();
        if (!transitioning.compareAndSet(false, true)) {
            throw new IllegalStateException("已有运行时变更正在执行，无法开始" + operation);
        }
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("ApplicationKernel 已关闭");
    }

    /** 反序关闭全局子系统与当前工作区运行时。 */
    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (externalServicesInitialized) {
            closeQuietly("任务管理器", () -> SddTaskManager.getInstance().shutdown());
            closeQuietly("插件系统", () -> PluginManager.getInstance().shutdown());
            closeQuietly("定时任务调度器", () -> ScheduleManager.getInstance().shutdown());
        }
        WorkspaceRuntime snapshot = current;
        current = null;
        if (snapshot != null) closeQuietly("工作区运行时", snapshot::close);
        closeQuietly("Playwright 浏览器", browserManager::shutdown);
    }

    private void closeQuietly(String name, Runnable closer) {
        try {
            closer.run();
        } catch (Throwable t) {
            log.warn("关闭{}失败（继续释放）: {}", name, t.getMessage(), t);
        }
    }
}
