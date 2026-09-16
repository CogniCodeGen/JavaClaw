package com.javaclaw.extension.spi;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;

/**
 * 隔离调用的身份与幂等边界；宿主执行前仍须从权威 Core 状态核验归属与活动 Turn。
 *
 * @param threadId 可选 Thread；交互浏览器必须提供
 * @param turnId 可选执行 Turn；显式人工操作没有 Turn
 * @param idempotencyKey 写操作身份；只读查询为空
 * @param expectedRevision 用户操作针对的代次，创建时为零
 */
public record IsolatedServiceCallScope(
        Optional<ThreadId> threadId, Optional<TurnId> turnId, Optional<String> idempotencyKey, long expectedRevision) {
    /** 校验身份组合与非负版本；这些字段不能从工具参数中提取。 */
    public IsolatedServiceCallScope {
        threadId = Objects.requireNonNull(threadId, "threadId");
        turnId = Objects.requireNonNull(turnId, "turnId");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if ((turnId.isPresent() && threadId.isEmpty()) || expectedRevision < 0) {
            throw new IllegalArgumentException("隔离调用身份或版本无效");
        }
    }

    /**
     * @param request 平台构造的扩展请求
     * @return 保留调用身份但不携带业务参数的上下文
     */
    public static IsolatedServiceCallScope from(ExtensionRequest request) {
        return new IsolatedServiceCallScope(
                request.threadId(), request.turnId(), request.idempotencyKey(), request.expectedRevision());
    }

    /** @return 不支持常驻交互调用的兼容上下文 */
    public static IsolatedServiceCallScope empty() {
        return new IsolatedServiceCallScope(Optional.empty(), Optional.empty(), Optional.empty(), 0);
    }
}
