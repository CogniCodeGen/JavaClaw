package com.javaclaw.browser.protocol;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserWorkerProtocolTest {
    private static final CanonicalPayload PAYLOAD = new CanonicalPayload("{}");

    @Test
    void commandFixesVersionOperationAndSensitiveFrameLength() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.Command(1, 1, BrowserWorkerProtocol.SNAPSHOT, PAYLOAD, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.Command(2, 0, BrowserWorkerProtocol.SNAPSHOT, PAYLOAD, 0));
        assertThrows(
                IllegalArgumentException.class, () -> new BrowserWorkerProtocol.Command(2, 1, "unknown", PAYLOAD, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.Command(
                        2, 1, BrowserWorkerProtocol.SNAPSHOT, PAYLOAD, BrowserWorkerProtocol.MAXIMUM_STATE_BYTES + 1));

        BrowserWorkerProtocol.Command command = new BrowserWorkerProtocol.Command(2, 1, " snapshot ", PAYLOAD, 9);
        assertEquals(BrowserWorkerProtocol.SNAPSHOT, command.operation());
        assertEquals(9, command.sensitiveStateBytes());
    }

    @Test
    void directionalMessagesRequireOneStableOutcomeAndBoundedBinary() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.WorkerMessage(
                        2,
                        1,
                        0,
                        BrowserWorkerProtocol.WorkerMessageKind.NETWORK_REQUEST,
                        Optional.of(PAYLOAD),
                        Optional.empty(),
                        0));
        assertThrows(
                IllegalArgumentException.class, () -> BrowserWorkerProtocol.WorkerMessage.failure(1, "secret detail"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.HostMessage(2, 1, 1, Optional.empty(), Optional.of("DENIED"), 1));

        BrowserWorkerProtocol.WorkerMessage request = BrowserWorkerProtocol.WorkerMessage.network(1, 1, PAYLOAD, 3);
        BrowserWorkerProtocol.WorkerMessage failure = BrowserWorkerProtocol.WorkerMessage.failure(1, "NETWORK_DENIED");
        assertEquals(3, request.binaryBytes());
        assertTrue(failure.payload().isEmpty());
        assertEquals("NETWORK_DENIED", failure.error().orElseThrow());
    }

    @Test
    void snapshotAndNetworkMetadataRequireExactHttpsOrigins() {
        BrowserWorkerProtocol.SnapshotTask task = new BrowserWorkerProtocol.SnapshotTask(
                URI.create("https://docs.example.com:8443/start"),
                Set.of(URI.create("https://docs.example.com:8443")),
                100,
                Duration.ofSeconds(5));
        BrowserWorkerProtocol.NetworkRequest request =
                new BrowserWorkerProtocol.NetworkRequest(task.uri(), "get", Map.of("x-test", List.of("one")));

        assertEquals("GET", request.method());
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.NetworkRequest(URI.create("http://docs.example.com"), "GET", Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.SnapshotTask(
                        URI.create("https://other.example.com"), task.allowedOrigins(), 100, Duration.ofSeconds(5)));
    }

    @Test
    void loginMetadataCarriesOnlyAuthorityAndAllowsOnePrivateResultFrame() {
        String sessionId = java.util.UUID.randomUUID().toString();
        BrowserWorkerProtocol.LoginTask task = new BrowserWorkerProtocol.LoginTask(
                sessionId,
                URI.create("https://docs.example.com/login"),
                Set.of(URI.create("https://docs.example.com")),
                Duration.ofMinutes(10));
        BrowserWorkerProtocol.WorkerMessage ready = BrowserWorkerProtocol.WorkerMessage.ready(1, PAYLOAD);
        BrowserWorkerProtocol.WorkerMessage saved =
                BrowserWorkerProtocol.WorkerMessage.sensitiveSuccess(1, PAYLOAD, 128);

        assertEquals(sessionId, task.sessionId());
        assertEquals(BrowserWorkerProtocol.WorkerMessageKind.SESSION_READY, ready.kind());
        assertEquals(128, saved.binaryBytes());
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.LoginTask(
                        sessionId,
                        URI.create("https://docs.example.com"),
                        task.allowedOrigins(),
                        Duration.ofMinutes(10).plusMillis(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.WorkerMessage(
                        2,
                        1,
                        0,
                        BrowserWorkerProtocol.WorkerMessageKind.RESULT,
                        Optional.empty(),
                        Optional.of("DENIED"),
                        1));
    }

    @Test
    void directionalEnvelopeRejectsEveryAmbiguousFrameShape() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.Command(2, 1, BrowserWorkerProtocol.OAUTH, PAYLOAD, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> BrowserWorkerProtocol.WorkerMessage.network(
                        1, 1, PAYLOAD, BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES + 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.WorkerMessage(
                        2,
                        1,
                        0,
                        BrowserWorkerProtocol.WorkerMessageKind.SESSION_READY,
                        Optional.of(PAYLOAD),
                        Optional.empty(),
                        1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.WorkerMessage(
                        2,
                        1,
                        0,
                        BrowserWorkerProtocol.WorkerMessageKind.SESSION_READY,
                        Optional.empty(),
                        Optional.empty(),
                        0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.WorkerMessage(
                        2,
                        1,
                        0,
                        BrowserWorkerProtocol.WorkerMessageKind.SESSION_READY,
                        Optional.of(PAYLOAD),
                        Optional.of("FAILED"),
                        0));
    }

    @Test
    void resultAndHostFramesRequireOneBoundedOutcome() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.WorkerMessage(
                        2,
                        1,
                        1,
                        BrowserWorkerProtocol.WorkerMessageKind.RESULT,
                        Optional.of(PAYLOAD),
                        Optional.empty(),
                        0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.WorkerMessage(
                        2,
                        1,
                        0,
                        BrowserWorkerProtocol.WorkerMessageKind.RESULT,
                        Optional.of(PAYLOAD),
                        Optional.of("FAILED"),
                        0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.HostMessage(2, 1, 1, Optional.of(PAYLOAD), Optional.of("FAILED"), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> BrowserWorkerProtocol.HostMessage.success(
                        1, 1, PAYLOAD, BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES + 1));

        BrowserWorkerProtocol.HostMessage failure = BrowserWorkerProtocol.HostMessage.failure(1, 1, "NETWORK_DENIED");
        assertEquals("NETWORK_DENIED", failure.error().orElseThrow());
        assertEquals(0, failure.binaryBytes());
    }

    @Test
    void taskBoundsRejectPathsPortsTimeoutsAndInvalidIdentifiers() {
        Set<URI> origin = Set.of(URI.create("https://docs.example.com"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.SnapshotTask(
                        URI.create("https://docs.example.com"),
                        Set.of(URI.create("https://docs.example.com/path")),
                        1,
                        Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.SnapshotTask(
                        URI.create("https://docs.example.com"), origin, 0, Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.SnapshotTask(
                        URI.create("https://docs.example.com"), origin, 200_001, Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.SnapshotTask(
                        URI.create("https://docs.example.com"), origin, 1, Duration.ofMillis(999)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.SnapshotTask(
                        URI.create("https://docs.example.com"), origin, 1, Duration.ofSeconds(61)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.LoginTask(
                        "not-a-uuid", URI.create("https://docs.example.com"), origin, Duration.ofSeconds(1)));
        String sessionId = java.util.UUID.randomUUID().toString();
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.LoginTask(
                        sessionId,
                        URI.create("https://docs.example.com"),
                        Set.of(URI.create("https://other.example.com")),
                        Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.LoginTask(
                        sessionId, URI.create("https://docs.example.com"), origin, Duration.ofMillis(999)));
        assertThrows(IllegalArgumentException.class, () -> new BrowserWorkerProtocol.LoginReady("not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> new BrowserWorkerProtocol.LoginSaved("not-a-uuid"));
    }

    @Test
    void oauthRequiresExactHttpsAuthorizationAndLoopbackCallback() {
        String sessionId = java.util.UUID.randomUUID().toString();
        Set<URI> origins = Set.of(URI.create("https://auth.example"));
        URI authorization = URI.create("https://auth.example/authorize?state=test");
        URI redirect = URI.create("http://127.0.0.1:17845/callback");
        BrowserWorkerProtocol.OAuthTask task = new BrowserWorkerProtocol.OAuthTask(
                sessionId, "authorization-1", "endpoint-1", 1, authorization, origins, redirect, Duration.ofSeconds(1));
        assertEquals(redirect, task.redirectUri());
        assertEquals(
                URI.create("http://[::1]:17845/callback?code=one"),
                new BrowserWorkerProtocol.OAuthCallback(sessionId, URI.create("http://[::1]:17845/callback?code=one"))
                        .callbackUri());

        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.OAuthTask(
                        sessionId,
                        "bad value",
                        "endpoint",
                        1,
                        authorization,
                        origins,
                        redirect,
                        Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.OAuthTask(
                        sessionId,
                        "authorization",
                        "endpoint",
                        0,
                        authorization,
                        origins,
                        redirect,
                        Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.OAuthTask(
                        sessionId,
                        "authorization",
                        "endpoint",
                        1,
                        authorization,
                        origins,
                        URI.create("http://localhost:17845/callback"),
                        Duration.ofSeconds(1)));
    }

    @Test
    void oauthRejectsAuthorityTimeoutAndCallbackShapeMismatch() {
        String sessionId = java.util.UUID.randomUUID().toString();
        Set<URI> origins = Set.of(URI.create("https://auth.example"));
        URI authorization = URI.create("https://auth.example/authorize?state=test");
        URI redirect = URI.create("http://127.0.0.1:17845/callback");
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.OAuthTask(
                        sessionId,
                        "authorization",
                        "endpoint",
                        1,
                        authorization,
                        Set.of(URI.create("https://other.example")),
                        redirect,
                        Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.OAuthTask(
                        sessionId,
                        "authorization",
                        "endpoint",
                        1,
                        authorization,
                        origins,
                        redirect,
                        Duration.ofMillis(999)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.OAuthTask(
                        sessionId,
                        "authorization",
                        "endpoint",
                        1,
                        authorization,
                        origins,
                        redirect,
                        Duration.ofMinutes(10).plusMillis(1)));
        assertThrows(
                IllegalArgumentException.class, () -> new BrowserWorkerProtocol.OAuthCallback(sessionId, redirect));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.OAuthCallback(
                        sessionId, URI.create("https://127.0.0.1:17845/callback?code=one")));
    }

    @Test
    void oauthLoopbackRejectsRelativePortUserInfoFragmentAndQueryMismatches() {
        String sessionId = java.util.UUID.randomUUID().toString();
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.OAuthCallback(sessionId, URI.create("/callback?code=one")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.OAuthCallback(
                        sessionId, URI.create("http://127.0.0.1/callback?code=one")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.OAuthCallback(
                        sessionId, URI.create("http://user@127.0.0.1:17845/callback?code=one")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.OAuthCallback(
                        sessionId, URI.create("http://127.0.0.1:17845/callback?code=one#fragment")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.OAuthCallback(
                        sessionId, URI.create("http://127.0.0.1:17845/callback?%20")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.OAuthTask(
                        sessionId,
                        "authorization",
                        "endpoint",
                        1,
                        URI.create("https://auth.example/authorize?state=test"),
                        Set.of(URI.create("https://auth.example")),
                        URI.create("http://127.0.0.1:17845/callback?unexpected=true"),
                        Duration.ofSeconds(1)));
    }

    @Test
    void networkMetadataNormalizesHeadersAndRejectsUnsafeTargets() {
        BrowserWorkerProtocol.NetworkRequest request = new BrowserWorkerProtocol.NetworkRequest(
                URI.create("https://docs.example.com/resource"), " post ", Map.of("X-Test", List.of("one")));
        assertEquals("POST", request.method());
        assertEquals(List.of("one"), request.headers().get("x-test"));
        assertEquals(599, new BrowserWorkerProtocol.NetworkResponse(599, Map.of(), false).statusCode());

        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.NetworkRequest(
                        URI.create("https://user@docs.example.com"), "GET", Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.NetworkRequest(
                        URI.create("https://docs.example.com/#fragment"), "GET", Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.NetworkRequest(
                        URI.create("https://docs.example.com"), "TRACE", Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerProtocol.NetworkRequest(
                        URI.create("https://docs.example.com"), "GET", Map.of(" ", List.of("one"))));
        assertThrows(
                IllegalArgumentException.class, () -> new BrowserWorkerProtocol.NetworkResponse(99, Map.of(), false));
        assertThrows(
                IllegalArgumentException.class, () -> new BrowserWorkerProtocol.NetworkResponse(600, Map.of(), false));
    }
}
