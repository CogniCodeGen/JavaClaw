package com.javaclaw.plugins.deliverance.internal;

public interface ExternalEndpointRegistry {
    Registration register(ExternalEndpointDescriptor descriptor, ExternalRequestHandler handler);
}
