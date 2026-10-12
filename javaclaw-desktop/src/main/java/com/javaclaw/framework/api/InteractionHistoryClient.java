package com.javaclaw.framework.api;

/** Scoped interaction audit for a logical conversation and its host-authorized descendants. */
public interface InteractionHistoryClient {
    InteractionHistory history(RunScope logicalMainScope, int limit);
    InteractionHistory history(RunId runId, int limit);
}
