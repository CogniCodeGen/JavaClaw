package com.javaclaw.runtime;

import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.agent.ChatService;
import com.javaclaw.agent.PlanModeService;
import com.javaclaw.api.conversation.ModeRegistry;
import com.javaclaw.config.DatabaseAccess;
import com.javaclaw.workflow.service.WorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工作区级运行时聚合根，统一拥有模型基础设施、编排服务与模式注册表。
 *
 * <p>创建和销毁必须整体进行。UI 只能替换这个聚合根，不能逐个重接内部服务引用。</p>
 */
public final class WorkspaceRuntime implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceRuntime.class);

    private final WorkspaceContext context;
    private final DatabaseAccess databaseAccess;
    private final AgentRuntime agentRuntime;
    private final ChatService chatService;
    private final PlanModeService planModeService;
    private final WorkflowService workflowService;
    private final ModeRegistry modeRegistry;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    WorkspaceRuntime(WorkspaceContext context,
                     DatabaseAccess databaseAccess,
                     AgentRuntime agentRuntime,
                     ChatService chatService,
                     PlanModeService planModeService,
                     WorkflowService workflowService,
                     ModeRegistry modeRegistry) {
        this.context = Objects.requireNonNull(context, "context");
        this.databaseAccess = Objects.requireNonNull(databaseAccess, "databaseAccess");
        this.agentRuntime = Objects.requireNonNull(agentRuntime, "agentRuntime");
        this.chatService = Objects.requireNonNull(chatService, "chatService");
        this.planModeService = Objects.requireNonNull(planModeService, "planModeService");
        this.workflowService = Objects.requireNonNull(workflowService, "workflowService");
        this.modeRegistry = Objects.requireNonNull(modeRegistry, "modeRegistry");
    }

    public WorkspaceContext context() {
        return context;
    }

    public DatabaseAccess databaseAccess() {
        return databaseAccess;
    }

    public AgentRuntime agentRuntime() {
        return agentRuntime;
    }

    public ChatService chatService() {
        return chatService;
    }

    public PlanModeService planModeService() {
        return planModeService;
    }

    public WorkflowService workflowService() { return workflowService; }

    public ModeRegistry modeRegistry() {
        return modeRegistry;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** 按依赖反序幂等关闭；单项失败不阻断后续资源释放。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        closeQuietly("模式注册表", modeRegistry::shutdownAll);
        closeQuietly("工作流运行时", workflowService::close);
        closeQuietly("普通聊天服务", chatService::shutdown);
        closeQuietly("规划模式服务", planModeService::shutdown);
        closeQuietly("AgentRuntime", agentRuntime::shutdown);
    }

    private void closeQuietly(String name, Runnable closer) {
        try {
            closer.run();
        } catch (Throwable t) {
            log.warn("关闭工作区[{}]的{}失败（继续释放）: {}",
                    context.workspaceId(), name, t.getMessage(), t);
        }
    }
}
