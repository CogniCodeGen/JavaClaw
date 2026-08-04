package com.javaclaw.schedule;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.api.conversation.ConversationCallbacks;

/** 可取消的定时任务执行器；生产实现为 ScheduledTaskAgent，测试可注入可控 runner。 */
public interface ScheduledTaskRunner {
    void run(ScheduledRunControl control, ToolCallOrigin origin, String prompt,
             ConversationCallbacks callbacks);

    void shutdown();
}
