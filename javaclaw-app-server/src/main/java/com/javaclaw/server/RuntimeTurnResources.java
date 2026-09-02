package com.javaclaw.server;

import java.util.Objects;

/** 统一关闭普通 Turn 调度器与独立 Provider 验证执行器。 */
record RuntimeTurnResources(AutoCloseable turns, AutoCloseable providerVerification) implements AutoCloseable {
    RuntimeTurnResources {
        Objects.requireNonNull(turns, "turns");
        Objects.requireNonNull(providerVerification, "providerVerification");
    }

    @Override
    public void close() throws Exception {
        Exception failure = close(turns, null);
        failure = close(providerVerification, failure);
        if (failure != null) {
            throw failure;
        }
    }

    private static Exception close(AutoCloseable resource, Exception previous) {
        try {
            resource.close();
            return previous;
        } catch (Exception failure) {
            if (previous == null) {
                return failure;
            }
            previous.addSuppressed(failure);
            return previous;
        }
    }
}
