package com.javaclaw.infrastructure.onboarding;

import com.javaclaw.application.onboarding.ConnectionProbePort;
import com.javaclaw.platform.http.HttpGateway;
import com.javaclaw.platform.http.HttpRetryPolicy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Objects;

/** 使用共享 HTTP 网关执行无重试的幂等可达性探测。 */
public final class HttpConnectionProbeAdapter implements ConnectionProbePort {

    private final HttpGateway gateway;

    public HttpConnectionProbeAdapter(HttpGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    @Override
    public int status(URI uri) throws IOException, InterruptedException {
        return gateway.sendAndWait(
                "onboarding-connection-probe",
                () -> HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(8))
                        .GET()
                        .build(),
                HttpRetryPolicy.none()).statusCode();
    }
}
