package com.javaclaw.agent.runtime;

/** Resolution boundary for user approvals and structured input. */
public interface InteractionUseCases {
    /** 持久化审批决议并恢复相应等待状态；找不到有效待决请求时返回 false，不直接放宽沙箱。 */
    boolean respondToApproval(String approvalId, boolean approved);

    /** 持久化回答或取消并恢复等待状态；无有效待决请求时返回 false，value 不得包含凭据。 */
    boolean respondToUserInput(String requestId, String value, boolean cancelled);
}
