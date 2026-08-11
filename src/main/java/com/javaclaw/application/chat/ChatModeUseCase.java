package com.javaclaw.application.chat;

import com.javaclaw.api.conversation.ConversationMode;
import com.javaclaw.api.conversation.Placement;
import com.javaclaw.config.ToolReviewMode;
import com.javaclaw.mode.WorkflowMode;
import com.javaclaw.runtime.ApplicationKernel;
import com.javaclaw.runtime.WorkspaceRuntime;

import java.util.List;
import java.util.Objects;

/**
 * 以工作区快照为边界实现聊天模式、工作流选择和审核策略用例。
 *
 * <p>本对象无可变页面状态，生命周期归根 Spring Context；审核策略的持久化与关闭协议由
 * 注入的端口实现负责。</p>
 */
public final class ChatModeUseCase implements ChatModeApplicationService {

    private final ApplicationKernel kernel;
    private final ToolReviewSettingsPort reviewSettings;

    public ChatModeUseCase(
            ApplicationKernel kernel, ToolReviewSettingsPort reviewSettings) {
        this.kernel = Objects.requireNonNull(kernel, "kernel");
        this.reviewSettings = Objects.requireNonNull(reviewSettings, "reviewSettings");
    }

    @Override
    public List<ModeOption> availableConversationModes() {
        return kernel.current().modeRegistry().listByPlacement(Placement.TOP_SEGMENT).stream()
                .filter(ConversationMode.class::isInstance)
                .map(mode -> new ModeOption(mode.id(), mode.displayName(), mode.tooltip()))
                .toList();
    }

    @Override
    public WorkflowSnapshot publishedWorkflows() {
        WorkspaceRuntime runtime = kernel.current();
        List<WorkflowOption> workflows = runtime.workflowService().definitions().list(false).stream()
                .filter(com.javaclaw.workflow.store.WorkflowDefinitionRecord::isPublished)
                .map(record -> new WorkflowOption(record.id(), record.name()))
                .toList();
        return new WorkflowSnapshot(runtime.context().workspaceId(), workflows);
    }

    @Override
    public boolean isCurrent(WorkflowSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (kernel.isTransitioning()) return false;
        try {
            return snapshot.workspaceId().equals(kernel.current().context().workspaceId());
        } catch (IllegalStateException unavailable) {
            return false;
        }
    }

    @Override
    public boolean isTransitioning() {
        return kernel.isTransitioning();
    }

    @Override
    public void selectWorkflow(String workflowId) {
        if (kernel.isTransitioning()) return;
        try {
            kernel.current().modeRegistry().getById("workflow")
                    .filter(WorkflowMode.class::isInstance)
                    .map(WorkflowMode.class::cast)
                    .ifPresent(mode -> mode.selectWorkflow(workflowId));
        } catch (IllegalStateException unavailable) {
            // 切换窗口没有可选择的工作流，后续刷新会对新运行时重新同步。
        }
    }

    @Override
    public ToolReviewMode currentReviewMode() {
        return reviewSettings.current();
    }

    @Override
    public void changeReviewMode(ToolReviewMode mode) {
        reviewSettings.update(mode);
    }
}
