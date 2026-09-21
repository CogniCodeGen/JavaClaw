package com.javaclaw.server.extension;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.javaclaw.api.BrokerRequest;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.browser.client.BrowserNetworkResult;
import com.javaclaw.browser.client.InteractiveBrowserNetworkExchange;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.server.security.grant.PrivateNetworkGrantService;

/** 登记浏览器的单跳 Broker；明确的人工来源租约与既有精确私网授权分别复核。 */
final class SiteRegistrationNetwork {
    private static final Set<String> CONTROLLED = Set.of(
            "host",
            "connection",
            "content-length",
            "proxy-authorization",
            "proxy-connection",
            "transfer-encoding",
            "upgrade");
    private final PrivateNetworkGrantService grants;
    private final SiteNetworkBroker broker;
    private final Clock clock;

    SiteRegistrationNetwork(PrivateNetworkGrantService grants, SiteNetworkBroker broker, Clock clock) {
        this.grants = grants;
        this.broker = broker;
        this.clock = clock;
    }

    InteractiveBrowserNetworkExchange callback(SiteRegistrationSession session) {
        return new InteractiveBrowserNetworkExchange() {
            @Override
            public BrowserNetworkResult exchange(
                    BrowserContracts.NetworkRequest request, byte[] body, CancellationToken cancellation)
                    throws Exception {
                return SiteRegistrationNetwork.this.exchange(session, request, body, cancellation);
            }

            @Override
            public void deniedOrigin(URI origin, long generation) {
                var lease = session.lease;
                if (session.cancelled.isCancelled()
                        || !lease.active(clock.instant())
                        || generation != lease.generation()
                        || lease.allowedOrigins().contains(origin)) {
                    return;
                }
                URI checked = SiteContracts.originOf(origin);
                if (!"https".equalsIgnoreCase(checked.getScheme()) || !checked.equals(origin)) {
                    return;
                }
                synchronized (session.pending) {
                    if (session.pending.size() < 128) {
                        session.pending.add(checked);
                    }
                }
            }
        };
    }

    private BrowserNetworkResult exchange(
            SiteRegistrationSession session,
            BrowserContracts.NetworkRequest request,
            byte[] body,
            CancellationToken cancellation)
            throws Exception {
        var lease = session.lease;
        var permissions = session.authorize(lease, request.uri(), clock);
        var active = session.cancellation(lease, clock, cancellation);
        var input = new BrokerRequest(
                request.uri(),
                request.method(),
                headers(request.headers()),
                body,
                BrowserContracts.MAXIMUM_ARTIFACT_BYTES,
                Duration.ofSeconds(30));
        var response = broker.exchange(
                input,
                permissions,
                active,
                (origin, addresses) -> authorizePrivate(session, origin, addresses),
                () -> session.authorize(lease, request.uri(), clock));
        active.throwIfCancelled();
        session.authorize(lease, request.uri(), clock);
        byte[] bytes = response.body();
        try {
            return new BrowserNetworkResult(
                    new BrowserWorkerProtocol.NetworkResponse(
                            response.statusCode(), response.headers(), response.truncated()),
                    bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private void authorizePrivate(SiteRegistrationSession session, URI origin, Set<String> addresses) {
        var grant = grants.available(session.workspace, PrivateNetworkPurpose.SITE).stream()
                .filter(value ->
                        value.origin().equals(origin) && value.dnsAddresses().equals(addresses))
                .findFirst()
                .orElseThrow(() -> new SecurityException("私网站点仍需要独立的精确私网授权"));
        grants.requireAuthorized(
                grant.id(), grant.revision(), session.workspace, PrivateNetworkPurpose.SITE, origin, addresses);
    }

    private static Map<String, List<String>> headers(Map<String, List<String>> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        source.forEach((name, values) -> {
            String normalized = name.toLowerCase(Locale.ROOT);
            if (!CONTROLLED.contains(normalized)) {
                result.put(normalized, List.copyOf(values));
            }
        });
        return result;
    }
}
