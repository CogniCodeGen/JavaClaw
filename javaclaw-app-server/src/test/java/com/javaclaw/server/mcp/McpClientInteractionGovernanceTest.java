package com.javaclaw.server.mcp;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpElicitationRequest;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointSpec;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpSamplingMessage;
import com.javaclaw.api.McpSamplingRequest;
import com.javaclaw.api.McpSamplingRole;
import com.javaclaw.api.McpTransport;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.McpClientInteractionPort;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.PersistenceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpClientInteractionGovernanceTest {
    private static final Instant NOW = Instant.parse("2026-09-02T08:00:00Z");

    private final CanonicalJson json = new CanonicalJson();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void guardAcceptsBoundedElicitationAndSamplingFromTheBoundEndpoint() throws Exception {
        RecordingInteractions delegate = new RecordingInteractions(json.parse("{\"answer\":\"accepted\"}"));
        McpInteractionGuard guard = new McpInteractionGuard("docs", delegate, json, clock);
        CancellationSource cancellation = new CancellationSource();
        McpElicitationRequest elicitation = elicitation("docs", NOW.plus(Duration.ofMinutes(15)));
        McpSamplingRequest sampling = sampling("docs");

        assertEquals(delegate.response(), guard.elicit(elicitation, cancellation));
        assertEquals(delegate.response(), guard.sample(sampling, cancellation));
        assertSame(elicitation, delegate.elicitation());
        assertSame(sampling, delegate.sampling());
        assertSame(cancellation, delegate.cancellation());
    }

    @Test
    void guardRejectsExpiredZeroAndOverlongElicitationBeforeDelegation() {
        RecordingInteractions delegate = new RecordingInteractions(json.parse("{\"answer\":\"unused\"}"));
        McpInteractionGuard guard = new McpInteractionGuard("docs", delegate, json, clock);
        CancellationSource cancellation = new CancellationSource();

        assertThrows(
                PersistenceException.class, () -> guard.elicit(elicitation("docs", NOW.minusNanos(1)), cancellation));
        assertThrows(PersistenceException.class, () -> guard.elicit(elicitation("docs", NOW), cancellation));
        assertThrows(
                PersistenceException.class,
                () -> guard.elicit(
                        elicitation("docs", NOW.plus(Duration.ofMinutes(15)).plusNanos(1)), cancellation));
        assertEquals(0, delegate.invocationCount());
    }

    @Test
    void guardRejectsReverseRequestsWhoseEndpointDoesNotMatchTheBoundSource() {
        RecordingInteractions delegate = new RecordingInteractions(json.parse("{\"answer\":\"unused\"}"));
        McpInteractionGuard guard = new McpInteractionGuard("docs", delegate, json, clock);

        assertThrows(PersistenceException.class, () -> guard.sample(sampling("foreign"), new CancellationSource()));
        assertThrows(
                PersistenceException.class,
                () -> guard.elicit(elicitation("foreign", NOW.plus(Duration.ofMinutes(1))), new CancellationSource()));
        assertEquals(0, delegate.invocationCount());
    }

    @Test
    void deferredFactoryFailsClosedUntilBoundAndRejectsRebinding() {
        DeferredMcpClientInteractionFactory deferred = new DeferredMcpClientInteractionFactory();
        TurnId turnId = TurnId.random();
        WorkspaceId workspaceId = WorkspaceId.random();
        McpEndpoint endpoint = endpoint(workspaceId);
        RecordingInteractions interactions = new RecordingInteractions(json.parse("{\"answer\":\"ok\"}"));

        assertThrows(IllegalStateException.class, () -> deferred.bind(turnId, workspaceId, endpoint));

        CapturingFactory factory = new CapturingFactory(interactions);
        deferred.bind(factory);
        assertSame(interactions, deferred.bind(turnId, workspaceId, endpoint));
        assertSame(turnId, factory.turnId());
        assertSame(workspaceId, factory.workspaceId());
        assertSame(endpoint, factory.endpoint());
        assertThrows(
                IllegalStateException.class,
                () -> deferred.bind((ignoredTurn, ignoredWorkspace, ignoredEndpoint) -> interactions));
    }

    private McpElicitationRequest elicitation(String endpointId, Instant expiresAt) {
        CanonicalPayload schema = json.parse("""
                {
                  "type":"object",
                  "additionalProperties":false,
                  "properties":{"answer":{"type":"string"}},
                  "required":["answer"]
                }
                """);
        return new McpElicitationRequest("question", endpointId, "请选择。", schema, expiresAt);
    }

    private McpSamplingRequest sampling(String endpointId) {
        McpSamplingMessage message =
                new McpSamplingMessage(McpSamplingRole.USER, json.parse("{\"type\":\"text\",\"text\":\"总结\"}"));
        return new McpSamplingRequest("sample", endpointId, List.of(message), 128);
    }

    private static McpEndpoint endpoint(WorkspaceId workspaceId) {
        McpEndpointSpec spec = new McpEndpointSpec(
                workspaceId,
                "Docs",
                McpTransport.STREAMABLE_HTTPS,
                Optional.of(URI.create("https://mcp.example/rpc")),
                Optional.empty(),
                McpAuthType.NONE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(10));
        return new McpEndpoint("docs", 1, McpEndpointState.ENABLED, 1, spec, NOW, NOW);
    }

    private static final class RecordingInteractions implements McpClientInteractionPort {
        private final Optional<CanonicalPayload> response;
        private McpElicitationRequest elicitation;
        private McpSamplingRequest sampling;
        private CancellationToken cancellation;
        private int invocationCount;

        private RecordingInteractions(CanonicalPayload response) {
            this.response = Optional.of(response);
        }

        @Override
        public Optional<CanonicalPayload> elicit(McpElicitationRequest request, CancellationToken token) {
            elicitation = request;
            cancellation = token;
            invocationCount++;
            return response;
        }

        @Override
        public Optional<CanonicalPayload> sample(McpSamplingRequest request, CancellationToken token) {
            sampling = request;
            cancellation = token;
            invocationCount++;
            return response;
        }

        private Optional<CanonicalPayload> response() {
            return response;
        }

        private McpElicitationRequest elicitation() {
            return elicitation;
        }

        private McpSamplingRequest sampling() {
            return sampling;
        }

        private CancellationToken cancellation() {
            return cancellation;
        }

        private int invocationCount() {
            return invocationCount;
        }
    }

    private static final class CapturingFactory implements McpClientInteractionFactory {
        private final McpClientInteractionPort interactions;
        private TurnId turnId;
        private WorkspaceId workspaceId;
        private McpEndpoint endpoint;

        private CapturingFactory(McpClientInteractionPort interactions) {
            this.interactions = interactions;
        }

        @Override
        public McpClientInteractionPort bind(TurnId turn, WorkspaceId workspace, McpEndpoint boundEndpoint) {
            turnId = turn;
            workspaceId = workspace;
            endpoint = boundEndpoint;
            return interactions;
        }

        private TurnId turnId() {
            return turnId;
        }

        private WorkspaceId workspaceId() {
            return workspaceId;
        }

        private McpEndpoint endpoint() {
            return endpoint;
        }
    }
}
