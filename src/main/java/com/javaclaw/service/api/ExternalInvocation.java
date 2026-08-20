package com.javaclaw.service.api;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface ExternalInvocation {
    String requestId();
    /** Non-secret credential prefix selected by the Runner after authentication. */
    String credentialId();
    String method();
    String path();
    Map<String, List<String>> headers();
    byte[] body();
    InetSocketAddress remoteAddress();
    /** Runner-enforced deadline for this external request. */
    default Instant deadline() { return Instant.MAX; }
    CancellationToken cancellation();
    ExternalResponse response();
}
