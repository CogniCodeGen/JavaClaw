package com.javaclaw.api;

import java.util.Optional;

/** 可跨线程读取的协作式取消信号。 */
public interface CancellationToken {
    /**
     * 返回是否已经取消。
     *
     * @return 已取消时为 true
     */
    boolean isCancelled();

    /**
     * 返回首个取消原因。
     *
     * @return 未取消时为空
     */
    Optional<String> reason();

    /**
     * 在取消后抛出异常，便于在安全检查点停止。
     *
     * @throws TurnCancelledException 已取消时抛出
     */
    default void throwIfCancelled() {
        if (isCancelled()) {
            throw new TurnCancelledException(reason().orElse("cancelled"));
        }
    }
}
