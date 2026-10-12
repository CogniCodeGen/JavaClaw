package com.javaclaw.plugins.deliverance.internal;

@FunctionalInterface
public interface ServiceRequestHandler {
    void handle(ServiceInvocation invocation) throws Exception;
}
