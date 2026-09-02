package com.javaclaw.protocol;

import java.util.List;
import java.util.Objects;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.PromptOptimizationDraft;
import com.javaclaw.api.PromptOptimizationId;
import com.javaclaw.api.WorkspaceId;

/** Agent Profile Prompt 优化的 Protocol v2 强类型 payload。 */
public final class PromptOptimizationRpcContracts {
    /** 启动命令要求的显式计费确认文本。 */
    public static final String BILLING_CONFIRMATION = "我确认本次 Prompt 优化会调用当前 Provider，并且可能产生费用";

    /** 采纳命令要求的显式人工确认文本。 */
    public static final String ADOPTION_CONFIRMATION = "我确认仅采纳此草稿，并创建新的 Agent Profile revision";

    private PromptOptimizationRpcContracts() {}

    /**
     * 启动一个普通 Harness Turn。
     *
     * @param workspaceId 项目约定和执行根所属 Workspace
     * @param profile 精确源 Agent Profile
     * @param billingConfirmed 必须为 true
     * @param confirmation 必须与 {@link #BILLING_CONFIRMATION} 完全一致
     */
    public record StartPayload(
            WorkspaceId workspaceId, AgentProfileRef profile, boolean billingConfirmed, String confirmation) {
        /** 校验引用和确认文本存在；服务端再校验精确常量。 */
        public StartPayload {
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(profile, "profile");
            confirmation = text(confirmation, "confirmation");
        }
    }

    /**
     * 读取单个草稿。
     *
     * @param draftId 优化任务标识
     */
    public record ReadPayload(PromptOptimizationId draftId) {
        /** 校验任务标识。 */
        public ReadPayload {
            Objects.requireNonNull(draftId, "draftId");
        }
    }

    /**
     * 列出 Workspace 的 Prompt 优化任务。
     *
     * @param workspaceId Workspace
     */
    public record ListPayload(WorkspaceId workspaceId) {
        /** 校验 Workspace。 */
        public ListPayload {
            Objects.requireNonNull(workspaceId, "workspaceId");
        }
    }

    /**
     * 取消尚未终止的优化 Turn。
     *
     * @param draftId 优化任务标识
     * @param reason 面向审计的脱敏原因
     */
    public record CancelPayload(PromptOptimizationId draftId, String reason) {
        /** 校验任务与原因。 */
        public CancelPayload {
            Objects.requireNonNull(draftId, "draftId");
            reason = text(reason, "reason");
        }
    }

    /**
     * 人工采纳 READY 草稿。
     *
     * @param draftId 优化任务标识
     * @param adoptionConfirmed 必须为 true
     * @param confirmation 必须与 {@link #ADOPTION_CONFIRMATION} 完全一致
     */
    public record AdoptPayload(PromptOptimizationId draftId, boolean adoptionConfirmed, String confirmation) {
        /** 校验任务和确认文本存在；服务端再校验精确常量。 */
        public AdoptPayload {
            Objects.requireNonNull(draftId, "draftId");
            confirmation = text(confirmation, "confirmation");
        }
    }

    /**
     * Workspace 草稿目录。
     *
     * @param drafts 创建时间倒序的不可变草稿快照
     */
    public record ListResult(List<PromptOptimizationDraft> drafts) {
        /** 复制目录。 */
        public ListResult {
            drafts = List.copyOf(drafts);
        }
    }

    private static String text(String value, String name) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty() || checked.length() > 500) {
            throw new IllegalArgumentException(name + " length must be between 1 and 500");
        }
        return checked;
    }
}
