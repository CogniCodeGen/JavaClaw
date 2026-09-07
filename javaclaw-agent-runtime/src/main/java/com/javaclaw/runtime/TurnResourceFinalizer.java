package com.javaclaw.runtime;

import com.javaclaw.api.TurnId;

/**
 * Turn 进入终态前释放平台拥有的活动资源。
 *
 * <p>实现必须幂等，并在无法确定资源已关闭时明确失败。外部资源关闭不在数据库事务中执行； 已发生的副作用必须保留可恢复证据，不能因为终结失败自动重放。
 */
@FunctionalInterface
public interface TurnResourceFinalizer {
    /**
     * 关闭当前 Turn 的资源并保存结束事实。
     *
     * @param turnId 资源所有者
     * @throws Exception 清理或事实持久化失败
     */
    void finish(TurnId turnId) throws Exception;

    /** @return 不持有外部资源的实现 */
    static TurnResourceFinalizer empty() {
        return turnId -> {};
    }
}
