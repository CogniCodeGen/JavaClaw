package com.javaclaw.server.extension;

import java.net.URI;
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
import com.javaclaw.server.security.PinnedHttpNetworkBroker;
import com.javaclaw.server.security.grant.PrivateNetworkGrantService;

/** Worker 只能经此单跳 HTTPS Broker 联网；Cookie 与认证头永远留在私有交换中。 */
final class BrowserSessionNetwork {
    private static final Set<String> CONTROLLED = Set.of(
            "host",
            "connection",
            "content-length",
            "proxy-authorization",
            "proxy-connection",
            "transfer-encoding",
            "upgrade");
    private final SiteBrowserHostContext host;
    private final BrowserSessionAuthority authority;
    private final PrivateNetworkGrantService privateGrants;
    private final PinnedHttpNetworkBroker broker = new PinnedHttpNetworkBroker();

    BrowserSessionNetwork(
            SiteBrowserHostContext host, BrowserSessionAuthority authority, PrivateNetworkGrantService privateGrants) {
        this.host = host;
        this.authority = authority;
        this.privateGrants = privateGrants;
    }

    BrowserNetworkResult exchange(
            BrowserSessionState session,
            BrowserContracts.NetworkRequest request,
            byte[] body,
            CancellationToken cancellation)
            throws Exception {
        var access = session.access;
        var permission = authority.authorize(session, access, request.uri());
        var activeCancellation = authority.cancellation(session, access, cancellation);
        var input = new BrokerRequest(
                request.uri(),
                request.method(),
                headers(request.headers()),
                body,
                BrowserContracts.MAXIMUM_ARTIFACT_BYTES,
                Duration.ofSeconds(30));
        var response = broker.exchangeBrowserSingleHop(
                input,
                permission,
                activeCancellation,
                (origin, addresses) -> privateAuthorization(session, origin, addresses),
                () -> {
                    access.cancelled().throwIfCancelled();
                    if (session.access != access) {
                        throw new SecurityException("浏览器网络请求的控制代次已失效");
                    }
                    authority.authorize(session, access, request.uri());
                });
        authority.authorize(session, access, request.uri());
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

    InteractiveBrowserNetworkExchange callback(BrowserSessionState session) {
        return new InteractiveBrowserNetworkExchange() {
            @Override
            public BrowserNetworkResult exchange(
                    BrowserContracts.NetworkRequest request, byte[] body, CancellationToken cancellation)
                    throws Exception {
                return BrowserSessionNetwork.this.exchange(session, request, body, cancellation);
            }

            @Override
            public void deniedOrigin(URI origin, long generation) {
                synchronized (session) {
                    var access = session.access;
                    if (session.closed
                            || session.closing
                            || access.cancelled().isCancelled()
                            || !access.lease().active(host.clock().instant())
                            || access.lease().generation() != generation
                            || access.lease().allowedOrigins().contains(origin)
                            || session.pendingOrigins.size() >= 128 && !session.pendingOrigins.containsKey(origin)) {
                        return;
                    }
                    session.pendingOrigins.put(SiteContracts.originOf(origin), generation);
                }
            }
        };
    }

    private void privateAuthorization(BrowserSessionState session, URI origin, Set<String> addresses) {
        var grant = privateGrants.listLatest(session.owner.workspaceId()).stream()
                .filter(candidate -> candidate.purpose() == PrivateNetworkPurpose.SITE
                        && candidate.origin().equals(origin))
                .findFirst()
                .orElseThrow(() -> new SecurityException("私网站点仍需要独立的精确私网授权"));
        privateGrants.requireAuthorized(
                grant.id(),
                grant.revision(),
                session.owner.workspaceId(),
                PrivateNetworkPurpose.SITE,
                origin,
                addresses);
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
