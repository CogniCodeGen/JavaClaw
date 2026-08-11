package com.javaclaw.application.chat;

import com.javaclaw.config.ToolReviewMode;

import java.util.List;

/**
 * 聊天模式栏共享的应用入口。
 *
 * <p>实例由根 Spring Context 管理并可被多个页面调用。模式与审核状态查询是内存操作，
 * 可在 FX 线程调用；{@link #publishedWorkflows()} 会访问 H2，必须在托管 I/O 任务调用。
 * 工作区切换可能使查询快照过期，调用方必须用 {@link #isCurrent(WorkflowSnapshot)}
 * 校验后再展示。重复选择同一模式或审核策略是幂等的。</p>
 *
 * <p>该接口不吞掉意外失败；调用方负责交给统一错误映射。工作流查询的取消由外层托管任务
 * 通过中断和迟到结果丢弃实现。Context 关闭后不得继续调用。</p>
 */
public interface ChatModeApplicationService {

    List<ModeOption> availableConversationModes();

    WorkflowSnapshot publishedWorkflows();

    boolean isCurrent(WorkflowSnapshot snapshot);

    boolean isTransitioning();

    /** 选择工作流；{@code null} 表示清除当前选择。 */
    void selectWorkflow(String workflowId);

    ToolReviewMode currentReviewMode();

    /** 立即更新内存策略并异步持久化；{@code null} 按智能审核处理。 */
    void changeReviewMode(ToolReviewMode mode);

    record ModeOption(String id, String displayName, String tooltip) {
        public ModeOption {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("模式 id 不能为空");
            displayName = displayName == null || displayName.isBlank() ? id : displayName;
            tooltip = tooltip == null ? "" : tooltip;
        }
    }

    record WorkflowOption(String id, String name) {
        public WorkflowOption {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("工作流 id 不能为空");
            name = name == null || name.isBlank() ? id : name;
        }
    }

    record WorkflowSnapshot(String workspaceId, List<WorkflowOption> workflows) {
        public WorkflowSnapshot {
            if (workspaceId == null || workspaceId.isBlank()) {
                throw new IllegalArgumentException("工作区 id 不能为空");
            }
            workflows = List.copyOf(workflows);
        }
    }
}
