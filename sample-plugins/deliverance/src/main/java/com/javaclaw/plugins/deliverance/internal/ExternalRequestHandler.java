package com.javaclaw.plugins.deliverance.internal;

@FunctionalInterface
public interface ExternalRequestHandler {
    void handle(ExternalInvocation invocation) throws Exception;
}
