package com.javaclaw.plugins.deliverance.internal;


@FunctionalInterface
public interface Registration extends AutoCloseable {
    @Override
    void close();
}
