package com.javaclaw.sandbox.api;

import java.io.IOException;

/**
 * Cancellable, bounded broker response stream. Implementations must release the underlying connection when cancelled or
 * closed and must never return more bytes than the request limit.
 */
public interface BrokerExchange extends AutoCloseable {
    /** 返回已通过 Broker 校验的响应头；正文仍需按字节/时间预算读取。 */
    BrokerResponseHead head();

    /** Returns -1 at end of stream. */
    int read(byte[] destination, int offset, int length) throws IOException;

    /** 取消当前 HTTP/SSE 交换并释放底层资源；不得继续消费正文。 */
    void cancel();

    /** 返回交换是否已取消，供上层区分主动取消与正常 EOF。 */
    boolean cancelled();

    @Override
    void close() throws IOException;
}
