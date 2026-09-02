package com.javaclaw.server.persistence;

import com.javaclaw.extension.spi.ExtensionJob;

/** 为正在推进的 Extension Job 工作单元持有 App Server 生命周期。 */
@FunctionalInterface
public interface ExtensionJobActivityPort {
    /**
     * 在执行工作单元前取得持有关系。
     *
     * @param job 已领取的权威 Job 快照
     * @return 工作单元收口后必须释放的句柄
     */
    Lease acquire(ExtensionJob job);

    /** 可幂等释放的工作单元生命周期句柄。 */
    @FunctionalInterface
    interface Lease extends AutoCloseable {
        /** 释放生命周期持有关系。 */
        @Override
        void close();
    }
}
