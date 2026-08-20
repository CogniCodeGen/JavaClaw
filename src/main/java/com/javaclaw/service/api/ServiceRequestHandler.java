package com.javaclaw.service.api;

@FunctionalInterface
public interface ServiceRequestHandler {
    void handle(ServiceInvocation invocation) throws Exception;
}
