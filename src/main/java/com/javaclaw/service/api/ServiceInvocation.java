package com.javaclaw.service.api;

import java.time.Instant;
import java.util.Map;

/** One Desktop request. Payload bytes are interpreted according to contentType. */
public interface ServiceInvocation {
    String requestId();
    String serviceId();
    String operation();
    String contentType();
    byte[] payload();
    Instant deadline();
    Map<String, String> metadata();
    CancellationToken cancellation();
    ServiceResponseChannel responses();
}
