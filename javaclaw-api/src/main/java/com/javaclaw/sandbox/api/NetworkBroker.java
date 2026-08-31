package com.javaclaw.sandbox.api;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Capability boundary for network-enabled tools; sandbox children receive no raw sockets. */
@FunctionalInterface
public interface NetworkBroker {
    /**
     * 在 grantedPolicy 范围内执行有界请求并返回完整响应；每次连接和重定向都必须重新检查目标。
     *
     * @throws Exception 策略拒绝、网络故障、取消或资源预算耗尽
     */
    BrokerResponse execute(BrokerRequest request, NetworkPolicy grantedPolicy) throws IOException;

    /**
     * Opens a cancellable response stream. Implementations with native streaming should override this method; the
     * compatibility implementation remains bounded by {@link BrokerRequest}.
     */
    default BrokerExchange openExchange(BrokerRequest request, NetworkPolicy grantedPolicy) throws IOException {
        BrokerResponse response = execute(request, grantedPolicy);
        ByteArrayInputStream input = new ByteArrayInputStream(response.body());
        AtomicBoolean cancelled = new AtomicBoolean();
        return new BrokerExchange() {
            @Override
            public BrokerResponseHead head() {
                return new BrokerResponseHead(
                        response.statusCode(), response.finalUri(), response.headers(), response.redirectCount());
            }

            @Override
            public int read(byte[] destination, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, destination.length);
                if (cancelled.get()) {
                    throw new IOException("network exchange was cancelled");
                }
                return input.read(destination, offset, length);
            }

            @Override
            public void cancel() {
                cancelled.set(true);
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }

            @Override
            public boolean cancelled() {
                return cancelled.get();
            }

            @Override
            public void close() throws IOException {
                cancelled.set(true);
                input.close();
            }
        };
    }
}
