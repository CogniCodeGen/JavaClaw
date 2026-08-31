package com.javaclaw.sdk.model;

import java.util.List;

/**
 * Authority may only be narrowed here; provider/model/path/sandbox policy stay server-owned.
 *
 * @param threadId 目标 Thread 标识，必须非空白
 * @param profileId 服务端 Profile 标识；不允许客户端替换最终权限策略
 * @param input 非空的文本/附件输入列表，构造时复制；空列表拒绝
 * @param approvalMode 审批收窄选项；null 使用 PROFILE_DEFAULT
 * @param reasoningMode 推理展示收窄选项；null 使用 PROFILE_DEFAULT
 * @param idempotencyKey 可选请求幂等键；只有携带键的变更请求才可被 SDK 自动重放
 */
public record TurnStartRequest(
        String threadId,
        String profileId,
        List<TurnInput> input,
        ApprovalMode approvalMode,
        ReasoningMode reasoningMode,
        String idempotencyKey) {
    /** 校验 Thread/Profile 标识和非空输入，填充默认收窄选项；不接受 Provider、路径或沙箱策略覆盖。 */
    public TurnStartRequest {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId is blank");
        }
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("profileId is blank");
        }
        input = List.copyOf(input == null ? List.of() : input);
        if (input.isEmpty()) {
            throw new IllegalArgumentException("input is empty");
        }
        approvalMode = approvalMode == null ? ApprovalMode.PROFILE_DEFAULT : approvalMode;
        reasoningMode = reasoningMode == null ? ReasoningMode.PROFILE_DEFAULT : reasoningMode;
    }

    /** 仅可维持 Profile 默认、要求全部审批或拒绝全部的客户端选项。 */
    public enum ApprovalMode {
        PROFILE_DEFAULT,
        REQUIRE_ALL,
        DENY_ALL
    }

    /** 仅控制默认、摘要或禁用推理展示的客户端选项，不暴露原始思维链。 */
    public enum ReasoningMode {
        PROFILE_DEFAULT,
        SUMMARY_ONLY,
        DISABLED
    }
}
