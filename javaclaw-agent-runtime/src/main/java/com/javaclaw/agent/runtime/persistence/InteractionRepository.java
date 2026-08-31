package com.javaclaw.agent.runtime.persistence;

import java.util.Optional;

import com.javaclaw.core.api.ApprovalResolution;
import com.javaclaw.core.api.UserInputResolution;

/** Durable resolution boundary for approval and user-input pauses. */
public interface InteractionRepository {
    /** 原子保存尚未决议的审批及关联事件；已决议或不存在时返回 Optional.empty。 */
    Optional<ApprovalResolution> resolveApproval(String approvalId, boolean approved);

    /** 原子保存回答或取消及关联事件；已决议或不存在时返回 Optional.empty。 */
    Optional<UserInputResolution> resolveUserInput(String requestId, String value, boolean cancelled);
}
