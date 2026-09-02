package com.javaclaw.desktop.view;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 页面异步请求 epoch；取消会推进 epoch，旧响应即使稍后到达也不得更新当前页面。
 *
 * <p>底层本地 RPC 目前是串行阻塞读取，因此取消语义是丢弃旧结果，而不是中断共享连接。
 */
public final class ViewRequestEpoch {
    private final AtomicLong current = new AtomicLong();

    /**
     * 开始新请求并使更早请求失效。
     *
     * @return 新 epoch
     */
    public long begin() {
        return current.incrementAndGet();
    }

    /** 取消当前请求并使其结果失效。 */
    public void cancel() {
        current.incrementAndGet();
    }

    /**
     * 判断响应是否仍属于当前请求。
     *
     * @param epoch 请求 epoch
     * @return 只有最新请求返回 true
     */
    public boolean isCurrent(long epoch) {
        return current.get() == epoch;
    }
}
