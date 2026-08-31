package com.javaclaw.agent.conversation;

import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.ItemId;
import com.javaclaw.core.api.ThreadId;

/** 已完成计划的显式采用边界；聊天中的“继续”不是计划执行授权。 */
public interface PlanAdoptionUseCases {
    /** 采用不可变 Plan Item 与已确认决策，用指定 CHAT Profile 创建新 Turn；幂等重放不重复执行。 */
    AgentTurn adopt(
            ThreadId threadId,
            ItemId planItemId,
            String profileId,
            long expectedProfileRevision,
            String decisions,
            String idempotencyKey);
}
