package com.javaclaw.framework.spi;

/** Typed candidate provider. Switching a selected provider always requires kernel restart. */
public interface InfrastructureProvider<T> {
    InfrastructureKind kind();

    String id();

    Class<T> contract();

    T instance();
}
