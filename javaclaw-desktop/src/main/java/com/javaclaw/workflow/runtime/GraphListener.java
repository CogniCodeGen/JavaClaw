package com.javaclaw.workflow.runtime;

@FunctionalInterface
public interface GraphListener {
    GraphListener NOOP = event -> {};
    void onEvent(GraphEvent event);
}
