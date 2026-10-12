package com.javaclaw.framework.spi;

/** Provider slots defined now; remote implementations can be selected in a later deployment. */
public enum InfrastructureKind {
    RUN_STORE,
    EVENT_STORE,
    WORKSPACE,
    FILE_SYSTEM,
    SANDBOX,
    MEMORY_GRAPH,
    PROTOCOL_ADAPTER
}
