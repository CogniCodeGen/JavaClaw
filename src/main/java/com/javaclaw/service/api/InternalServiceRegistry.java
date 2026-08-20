package com.javaclaw.service.api;

public interface InternalServiceRegistry {
    Registration register(ServiceDescriptor descriptor, ServiceRequestHandler handler);
}
