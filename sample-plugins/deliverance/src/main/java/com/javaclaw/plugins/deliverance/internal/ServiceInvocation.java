package com.javaclaw.plugins.deliverance.internal;

import java.time.Instant;
import java.util.Map;


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
