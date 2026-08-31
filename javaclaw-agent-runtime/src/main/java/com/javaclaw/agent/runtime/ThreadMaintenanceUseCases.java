package com.javaclaw.agent.runtime;

import com.javaclaw.core.api.ThreadId;

/** 受控目录维护端口；只冻结已登记 Thread 的目录，不接收客户端提供的文件路径。 */
public interface ThreadMaintenanceUseCases {
    /**
     * 为无活动执行的 Thread 目录获取独占维护租约；同目录及子目录中的 fork/子任务也必须已经退出。 租约期间新 Turn 立即被拒绝，调用方必须关闭租约；文件或 Git 操作不占用 Runtime 调度锁。
     *
     * @throws IllegalStateException 目录仍有活动执行、已有维护操作或 Runtime 已关闭
     */
    Lease acquireInactiveDirectory(ThreadId threadId);

    /** 目录维护权；close 幂等释放启动屏障，不中断其他 Thread。 */
    interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}
