package com.javaclaw.client;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PromptOptimizationDraft;
import com.javaclaw.api.PromptOptimizationId;
import com.javaclaw.api.PromptOptimizationProvenance;
import com.javaclaw.api.PromptOptimizationRef;
import com.javaclaw.api.PromptOptimizationResult;
import com.javaclaw.api.PromptOptimizationState;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.facade.PromptOptimizationClient;
import com.javaclaw.client.testkit.ScriptedRpcConnection;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.PromptOptimizationRpcContracts;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PromptOptimizationClientTest {
    private static final CanonicalJson JSON = new CanonicalJson();

    @Test
    void sdkCarriesExactRoleAndBillingConfirmationBeforeAnyProviderCall() {
        PromptOptimizationDraft expected = draft();
        AtomicInteger requests = new AtomicInteger();
        PromptOptimizationClient client = client(request -> {
            requests.incrementAndGet();
            WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
            PromptOptimizationRpcContracts.StartPayload payload =
                    JSON.decode(command.payload(), PromptOptimizationRpcContracts.StartPayload.class);
            assertEquals("agent/role/prompt/optimization/start", request.method());
            assertEquals(0, command.expectedRevision());
            assertEquals(expected.ref().sourceRole(), payload.role());
            return JsonRpcResponse.success(request.id(), JSON.encode(expected));
        });

        assertEquals(
                expected,
                client.start(
                        expected.ref().workspaceId(),
                        expected.ref().sourceRole(),
                        ExecutionOverrides.empty(),
                        true,
                        PromptOptimizationRpcContracts.BILLING_CONFIRMATION,
                        new CommandOptions("prompt-opt-start", 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> client.start(
                        expected.ref().workspaceId(),
                        expected.ref().sourceRole(),
                        ExecutionOverrides.empty(),
                        false,
                        PromptOptimizationRpcContracts.BILLING_CONFIRMATION,
                        new CommandOptions("prompt-opt-unconfirmed", 0)));
        assertEquals(1, requests.get());
    }

    @Test
    void sdkRejectsAdoptionWithoutExactHumanConfirmationBeforeRpc() {
        AtomicInteger requests = new AtomicInteger();
        PromptOptimizationClient client = client(request -> {
            requests.incrementAndGet();
            throw new AssertionError("unconfirmed adoption must not reach RPC");
        });

        assertThrows(
                IllegalArgumentException.class,
                () -> client.adopt(
                        draft().ref().id(),
                        false,
                        PromptOptimizationRpcContracts.ADOPTION_CONFIRMATION,
                        new CommandOptions("prompt-opt-adopt-unconfirmed", 3)));
        assertEquals(0, requests.get());
    }

    private static PromptOptimizationClient client(Function<JsonRpcRequest, JsonRpcResponse> handler) {
        return new PromptOptimizationClient(
                new RpcClientConnection(new ScriptedRpcConnection(handler), JSON, ignored -> {}));
    }

    private static PromptOptimizationDraft draft() {
        String digest = "a".repeat(64);
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        PromptOptimizationRef ref = new PromptOptimizationRef(
                PromptOptimizationId.parse("00000000-0000-0000-0000-000000000001"),
                WorkspaceId.parse("00000000-0000-0000-0000-000000000002"),
                new AgentRoleRef("profile", 3),
                ThreadId.parse("00000000-0000-0000-0000-000000000003"),
                TurnId.parse("00000000-0000-0000-0000-000000000004"));
        return new PromptOptimizationDraft(
                ref,
                new PromptOptimizationResult(
                        PromptOptimizationState.QUEUED, 1, Optional.empty(), Optional.empty(), Optional.empty()),
                new PromptOptimizationProvenance("profile-optimization-v1", digest, now, now),
                Optional.empty());
    }
}
