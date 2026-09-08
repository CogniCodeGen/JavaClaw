package com.javaclaw.runtime;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.TurnId;

/** 模型事件的背压边界。 */
@FunctionalInterface
public interface ModelEventSink {
    /**
     * 绑定当前持久模型调用编号；适配器无需获知持久化实现。
     *
     * @param invocationNumber 从 1 开始的调用编号
     * @return 当前调用专属的背压 sink
     */
    default ModelEventSink forInvocation(int invocationNumber) {
        return this;
    }

    /**
     * 发布事件；下游没有 demand 时实现必须阻塞，且定期检查取消。
     *
     * @param turnId Turn
     * @param event 事件
     * @param cancellation 取消信号
     * @throws InterruptedException 等待 demand 时线程中断
     */
    void publish(TurnId turnId, ModelStreamEvent event, CancellationToken cancellation) throws InterruptedException;
}
