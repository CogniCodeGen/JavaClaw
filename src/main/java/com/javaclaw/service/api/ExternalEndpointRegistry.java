package com.javaclaw.service.api;

public interface ExternalEndpointRegistry {
    Registration register(ExternalEndpointDescriptor descriptor, ExternalRequestHandler handler);
}
