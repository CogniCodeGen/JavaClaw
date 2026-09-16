package com.javaclaw.browser.worker;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Route;

import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.builtin.contracts.BrowserContracts;

/** 页面网络只由当前租约和宿主 Broker fulfill；无租约时连后台请求也拒绝。 */
final class InteractiveBrowserNetwork {
    private final InteractiveWorkerConnection connection;
    private final Supplier<BrowserContracts.AccessLease> lease;
    private int requests;
    private long bytes;
    private long generation;
    private final java.util.Set<URI> denied = new java.util.LinkedHashSet<>();
    private long deniedGeneration;

    InteractiveBrowserNetwork(InteractiveWorkerConnection connection, Supplier<BrowserContracts.AccessLease> lease) {
        this.connection = connection;
        this.lease = lease;
    }

    void configure(BrowserContext context) {
        context.route("**/*", this::route);
        context.routeWebSocket("**/*", socket -> socket.close());
    }

    URI requireTarget(String value) {
        BrowserContracts.AccessLease current = lease.get();
        URI uri = URI.create(value).normalize();
        URI origin;
        try {
            origin = PrivateNetworkGrant.normalizeOrigin(
                    new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null));
        } catch (java.net.URISyntaxException failure) {
            throw new IllegalArgumentException("Browser target has invalid origin");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Browser target contains credentials");
        }
        if (!current.active(Instant.now()) || !current.allowedOrigins().contains(origin)) {
            throw new IllegalStateException("Browser target is outside current lease");
        }
        return uri;
    }

    BrowserNetworkChannel.NetworkResult exchange(BrowserContracts.NetworkRequest request, byte[] body) {
        BrowserContracts.AccessLease before = lease.get();
        requireTarget(request.uri().toString());
        if (generation != before.generation()) {
            generation = before.generation();
            requests = 0;
            bytes = 0;
        }
        if (++requests > 1000 || body.length > BrowserContracts.MAXIMUM_ARTIFACT_BYTES) {
            throw new IllegalStateException("Browser network quota exceeded");
        }
        BrowserNetworkChannel.NetworkResult result = connection.exchange(request, body, before.generation());
        requireTarget(request.uri().toString());
        if (lease.get().generation() != before.generation()) {
            throw new IllegalStateException("Browser lease changed during network exchange");
        }
        bytes += result.body().length;
        if (bytes > 64L * 1024 * 1024) {
            throw new IllegalStateException("Browser network quota exceeded");
        }
        return result;
    }

    java.util.Set<URI> pendingOrigins() {
        return denied.stream().filter(origin -> !lease.get().allowedOrigins().contains(origin))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private void route(Route route) {
        byte[] original = route.request().postDataBuffer();
        byte[] body = original == null ? new byte[0] : original.clone();
        try {
            notifyDeniedOrigin(route.request().url());
            URI target = requireTarget(route.request().url());
            Map<String, List<String>> headers = route.request().allHeaders().entrySet().stream()
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.of(entry.getValue())));
            var result = exchange(
                    new BrowserContracts.NetworkRequest(target, route.request().method(), headers), body);
            fulfill(route, result);
        } catch (RuntimeException denied) {
            route.abort("blockedbyclient");
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }

    private void notifyDeniedOrigin(String value) {
        var current = lease.get();
        if (!current.active(Instant.now())) {
            return;
        }
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null) {
            return;
        }
        URI origin;
        try {
            origin = PrivateNetworkGrant.normalizeOrigin(
                    new URI("https", null, uri.getHost(), uri.getPort(), null, null, null));
        } catch (java.net.URISyntaxException failure) {
            return;
        }
        if (deniedGeneration != current.generation()) {
            deniedGeneration = current.generation();
            denied.clear();
        }
        if (!current.allowedOrigins().contains(origin) && denied.size() < 128 && denied.add(origin)) {
            connection.deniedOrigin(origin, current.generation());
        }
    }

    private static void fulfill(Route route, BrowserNetworkChannel.NetworkResult result) {
        byte[] body = result.body();
        try {
            if (result.truncated()) {
                route.abort("blockedbyresponse");
                return;
            }
            Map<String, String> headers = result.headers().entrySet().stream()
                    .collect(Collectors.toUnmodifiableMap(
                            Map.Entry::getKey, entry -> String.join("\n", entry.getValue())));
            route.fulfill(new Route.FulfillOptions()
                    .setStatus(result.statusCode())
                    .setHeaders(headers)
                    .setBodyBytes(body));
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }
}
