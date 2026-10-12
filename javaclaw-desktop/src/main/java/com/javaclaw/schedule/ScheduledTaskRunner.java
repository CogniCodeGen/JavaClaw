package com.javaclaw.schedule;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.api.conversation.ConversationCallbacks;

/** 可取消的定时任务执行端口；生产实现提交统一 Agent Run，测试可注入可控 runner。 */
public interface ScheduledTaskRunner {
    void run(ScheduledRunControl control, ToolCallOrigin origin, String prompt,
             ConversationCallbacks callbacks);

    /** Original task wording is distinct from the execution-only scheduler context prefix. */
    default void run(ScheduledRunControl control, ToolCallOrigin origin, String executionPrompt,
                     String originalPrompt, ConversationCallbacks callbacks) {
        run(control, origin, executionPrompt, callbacks);
    }

    void shutdown();
}
