package com.javaclaw.plugins.deliverance.internal;

public interface InternalServiceRegistry {
    Registration register(ServiceDescriptor descriptor, ServiceRequestHandler handler);
}
