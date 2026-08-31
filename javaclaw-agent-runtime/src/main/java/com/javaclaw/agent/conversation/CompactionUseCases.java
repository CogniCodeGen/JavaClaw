package com.javaclaw.agent.conversation;

import com.javaclaw.core.api.ThreadId;

/** 用户显式发起的上下文压缩用例；使用正常 Turn、模型预算和 Item Journal，不创建第二套执行器。 */
public interface CompactionUseCases {
    /** 使用最近真实 Turn 的服务端配置异步启动压缩；客户端不能提交 summary 或 Provider 参数。 */
    void start(ThreadId threadId);
}
