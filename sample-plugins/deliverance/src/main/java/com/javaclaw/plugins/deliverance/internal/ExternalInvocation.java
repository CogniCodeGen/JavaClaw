package com.javaclaw.plugins.deliverance.internal;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface ExternalInvocation {
    String requestId();
    
    String credentialId();
    String method();
    String path();
    Map<String, List<String>> headers();
    byte[] body();
    InetSocketAddress remoteAddress();
    
    default Instant deadline() { return Instant.MAX; }
    CancellationToken cancellation();
    ExternalResponse response();
}
