package com.javaclaw.service.api;

import java.util.Set;

public record ExternalEndpointDescriptor(
        String id, Protocol protocol, String pathPrefix, Set<String> capabilities) {
    public ExternalEndpointDescriptor {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("endpoint id is required");
        protocol = protocol == null ? Protocol.HTTP : protocol;
        pathPrefix = pathPrefix == null || pathPrefix.isBlank() ? "/" : pathPrefix;
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }

    public enum Protocol { HTTP, HTTPS, TCP, SSE, WEBSOCKET }
}
