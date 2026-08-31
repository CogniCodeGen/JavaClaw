package com.javaclaw.agent.context;

import java.util.List;

import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.StoredItem;
import com.javaclaw.core.api.ThreadId;

/** Adds bounded, auditable context without introducing another conversation history. */
@FunctionalInterface
public interface ContextContributor {
    /**
     * 返回本 Turn 应使用的版本化上下文；每条记录应可审计，不得读取第二套聊天历史。
     *
     * @throws Exception 来源不可读或上下文构建失败
     */
    List<ContextContribution> contribute(ContextRequest request) throws Exception;

    /**
     * 上下文来源读取请求；只接收当前 Turn 和统一历史，不持有 Runtime 或数据库实现。
     *
     * @param thread 当前 Thread 快照，非空
     * @param turn 当前 Turn 快照，非空
     * @param transcript 本轮之前的非空持久 Item 列表，构造时复制
     */
    record ContextRequest(AgentThread thread, AgentTurn turn, List<StoredItem> transcript) {
        /** 固定 Thread、Turn 和 transcript，避免贡献者观察到正在改变的历史集合。 */
        public ContextRequest {
            transcript = List.copyOf(transcript);
        }
    }

    /**
     * 被纳入模型上下文的一条版本化来源，便于生成 ContextUsage 审计。
     *
     * @param source 非空白来源类别，如 memory、knowledge 或 skill
     * @param sourceId 非空白来源记录标识
     * @param revision 来源版本，必须为正数
     * @param content 非空白上下文文本；不应含明文凭据
     */
    record ContextContribution(String source, String sourceId, long revision, String content) {
        /** 校验来源标识、版本和内容，确保每条贡献都可追溯到持久记录。 */
        public ContextContribution {
            source = ThreadId.required(source, "source");
            sourceId = ThreadId.required(sourceId, "sourceId");
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
            content = ThreadId.required(content, "content");
        }
    }
}
