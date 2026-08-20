package com.javaclaw.service.api;

@FunctionalInterface
public interface ExternalRequestHandler {
    void handle(ExternalInvocation invocation) throws Exception;
}
