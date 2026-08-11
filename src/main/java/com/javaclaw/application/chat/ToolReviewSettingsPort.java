package com.javaclaw.application.chat;

import com.javaclaw.config.ToolReviewMode;

/**
 * 工具审核策略的应用端口。
 *
 * <p>实现必须线程安全；{@link #update(ToolReviewMode)} 先更新当前工作区的内存状态，再排队
 * 持久化，因此不会阻塞 FX 线程。重复写入同一策略是幂等的。持久化失败由实现记录，后续
 * 读取仍返回本进程内的最新值。生命周期归根 Spring Context。</p>
 */
public interface ToolReviewSettingsPort {

    ToolReviewMode current();

    void update(ToolReviewMode mode);
}
