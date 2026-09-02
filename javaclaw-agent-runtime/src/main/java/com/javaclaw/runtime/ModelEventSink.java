package com.javaclaw.runtime;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.TurnId;

/** 模型事件的背压边界。 */
@FunctionalInterface
public interface ModelEventSink {
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
