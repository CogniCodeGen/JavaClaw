package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.builtin.InteractionTools;
import com.javaclaw.framework.spi.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InteractionDelegationRejectionTest {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RunId RUN = new RunId("delegation-parent");
    private static final RunScope SCOPE = new RunScope("workspace", "user", "session");
    private static final RunBudget BUDGET = new RunBudget(Duration.ofMinutes(1), 1000, 1000, 10, BigDecimal.TEN);
    private static final CancellableTaskExecutor DIRECT = new CancellableTaskExecutor() {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public <T> CancellableTask<T> submit(String name, Duration timeout,
                CancellationToken cancellation, Callable<T> action) {
            CompletableFuture<T> completion = new CompletableFuture<>();
            try { cancellation.throwIfCancelled(); completion.complete(action.call()); }
            catch (Throwable failure) { completion.completeExceptionally(failure); }
            return new CancellableTask<>() {
                @Override public java.util.concurrent.CompletionStage<T> completion() { return completion; }
                @Override public java.util.concurrent.CompletionStage<Void> termination() {
                    return CompletableFuture.completedFuture(null);
                }
                @Override public boolean cancel() { return false; }
            };
        }
    };

    @Test void missingContractRejectsBeforeChildLookupOrBackendAccess() {
        Fixture fixture = new Fixture(null);
        var rejected = assertThrows(InteractionDelegationRejectedException.class,
                () -> fixture.coordinator.delegate(fixture.context(), fixture.execution("delegate"), task()));
        assertEquals(InteractionDelegationRejectedException.CONTRACT_REQUIRED, rejected.errorCode());
        fixture.assertNoDispatch(1);
    }

    @Test void fileOnlyAndUnknownContractsRejectBeforeChildLookupOrBackendAccess() {
        for (TaskContractV3 contract : List.of(fileContract(), TaskContractV3.unknown("整理文件"))) {
            Fixture fixture = new Fixture(contract);
            var rejected = assertThrows(InteractionDelegationRejectedException.class,
                    () -> fixture.coordinator.delegate(fixture.context(), fixture.execution("delegate"), task()));
            assertEquals(InteractionDelegationRejectedException.NO_CRITERIA, rejected.errorCode());
            fixture.assertNoDispatch(1);
        }
    }

    @Test void anotherRunsContractCannotAuthorizeDelegation() {
        Fixture fixture = new Fixture(fileContract(), "another-run");
        var rejected = assertThrows(InteractionDelegationRejectedException.class,
                () -> fixture.coordinator.delegate(fixture.context(), fixture.execution("delegate"), task()));
        assertEquals(InteractionDelegationRejectedException.CONTRACT_REQUIRED, rejected.errorCode());
        fixture.assertNoDispatch(1);
    }

    @Test void trustedPreDispatchFailureHasNoEffectAndDoesNotBlockAContinuation() {
        for (TaskContractV3 contract : new TaskContractV3[]{null, fileContract()}) {
            Fixture fixture = new Fixture(contract);
            FrameworkTool tool = fixture.tool(fixture.coordinator);
            ToolInvocationResult result = fixture.invoke(tool, "delegate-first");
            assertEquals(ToolExecutionStatus.FAILED, result.status());
            assertEquals("interaction.delegation_rejected", result.output().path("kind").asText());
            assertEquals(contract == null ? InteractionDelegationRejectedException.CONTRACT_REQUIRED
                    : InteractionDelegationRejectedException.NO_CRITERIA, result.errorCode());
            assertFalse(result.output().path("dispatchAttempted").asBoolean(true));
            assertFalse(result.output().path("inputAuthority").asBoolean(true));
            assertFalse(result.output().path("acceptanceEvidence").asBoolean(true));
            assertTrue(result.displayMessage().contains("不能自行重写"));
            assertEquals("FAILED", fixture.receipts().getFirst().path("status").asText());
            assertEquals("NOT_SENT", fixture.receipts().getFirst().path("metadata").path("delivery").asText());
            assertEquals("NONE", fixture.receipts().getFirst().path("metadata").path("effect").asText());
            assertEquals("false", fixture.receipts().getFirst().path("metadata").path("dispatchAttempted").asText());
            assertTrue(fixture.events.stream().anyMatch(event -> event.type.equals("core.tool.completed")
                    && event.payload.path("status").asText().equals("FAILED")));
            assertTrue(fixture.events.stream().filter(event -> event.type.equals("core.tool.started"))
                    .allMatch(event -> event.payload.path("trustedInteractionDelegate").isBoolean()
                            && event.payload.path("trustedInteractionDelegate").booleanValue()));

            // 已确认没有派发的同一委派不会留下未知效果栅栏，后续步骤可以继续。
            fixture.control.enterTaskRepair();
            assertEquals(ToolExecutionStatus.FAILED, fixture.invoke(tool, "delegate-continuation").status());
            assertEquals(2, fixture.receipts().size());
            fixture.assertNoDispatch(2);
        }
    }

    @Test void onlyTheExactHostDelegateAndCompleteRejectionShapeCanClaimNoDispatch() throws Exception {
        Fixture fixture = new Fixture(null);
        FrameworkTool tool = fixture.tool(fixture.coordinator);
        ToolExecutionResultV1 valid = tool.executeResult(input(), fixture.execution("delegate"));
        assertTrue(InteractionTools.isTrustedControlRejection(tool, valid));
        for (String field : List.of("dispatchAttempted", "inputAuthority", "acceptanceEvidence")) {
            ObjectNode missing = (ObjectNode) valid.data();
            missing.remove(field);
            assertFalse(InteractionTools.isTrustedControlRejection(tool, failed(missing, valid.errorCode())));
            ObjectNode textual = (ObjectNode) valid.data();
            textual.put(field, "false");
            assertFalse(InteractionTools.isTrustedControlRejection(tool, failed(textual, valid.errorCode())));
            ObjectNode positive = (ObjectNode) valid.data();
            positive.put(field, true);
            assertFalse(InteractionTools.isTrustedControlRejection(tool, failed(positive, valid.errorCode())));
        }
        assertFalse(InteractionTools.isTrustedControlRejection(tool, failed(valid.data(), "UNRELATED_ERROR")));
        assertFalse(InteractionTools.isTrustedControlRejection(tool, ToolExecutionResultV1.success(valid.data())));
        FrameworkTool forged = new FrameworkTool() {
            @Override public ToolDescriptor descriptor() { return tool.descriptor(); }
            @Override public JsonNode execute(JsonNode input, ToolExecutionContext execution) { return valid.data(); }
            @Override public ToolExecutionResultV1 executeResult(JsonNode input, ToolExecutionContext execution) {
                return valid;
            }
        };
        assertFalse(InteractionTools.isTrustedControlRejection(forged, valid));
        fixture.invoke(forged, "forged");
        assertTrue(fixture.events.stream().filter(event -> event.type.equals("core.tool.started"))
                .noneMatch(event -> event.payload.has("trustedInteractionDelegate")));
        assertEquals("UNKNOWN", fixture.receipts().getFirst().path("status").asText());
        assertFalse(fixture.receipts().getFirst().path("metadata").path("effect").asText().equals("NONE"));
    }

    @Test void unrelatedErrorsCancellationAndSuspensionStillPropagate() throws Exception {
        for (RuntimeException failure : List.of(new SecurityException("permission denied"),
                new IllegalArgumentException("invalid interaction task"), new RunCancelledException(),
                new InteractionWaitRequiredException(NODES.objectNode().put("childRunId", "child")),
                new InteractionEventWaitRequiredException(NODES.objectNode().put("sessionId", "session")))) {
            Fixture fixture = new Fixture(null);
            FrameworkTool tool = fixture.tool(failingGateway(failure));
            assertSame(failure, assertThrows(failure.getClass(),
                    () -> tool.executeResult(input(), fixture.execution("direct"))));
            CompletionException propagated = assertThrows(CompletionException.class,
                    () -> fixture.invoke(tool, "gateway"));
            assertSame(failure, propagated.getCause());
            if (failure instanceof InteractionWaitRequiredException
                    || failure instanceof InteractionEventWaitRequiredException) {
                assertTrue(fixture.receipts().isEmpty());
                assertTrue(fixture.events.stream().anyMatch(event -> event.type.equals("core.tool.suspended")));
            } else {
                assertEquals("UNKNOWN", fixture.receipts().getFirst().path("status").asText());
            }
        }
    }

    @Test void cancellationAndRecursiveRoleChecksPrecedeContractReads() {
        Fixture fixture = new Fixture(null);
        var cancelled = new ToolExecutionContext(RUN, "cancelled", () -> true, Instant.now().plusSeconds(30));
        assertThrows(RunCancelledException.class,
                () -> fixture.coordinator.delegate(fixture.context(), cancelled, task()));
        RunRequest child = RunRequest.builder().agent(AgentDefinitionRef.latest(InteractionExecutionPolicy.AGENT_ID))
                .profile(RunProfileRef.latest(InteractionExecutionPolicy.PROFILE_ID))
                .source(new InvocationSource("interaction", "executor")).scope(SCOPE)
                .input(InputBlock.text("child")).build();
        ToolContext childContext = new ToolContext(RUN, SCOPE, PermissionSet.UNRESTRICTED,
                fixture.control, fixture.control.deadline(), child);
        assertThrows(SecurityException.class,
                () -> fixture.coordinator.delegate(childContext, fixture.execution("recursive"), task()));
        fixture.assertNoDispatch(0);
    }

    private static InteractionDelegateGateway failingGateway(RuntimeException failure) {
        return new InteractionDelegateGateway() {
            @Override public InteractionResult delegate(ToolContext parent, ToolExecutionContext execution,
                    InteractionTask task) { throw failure; }
            @Override public JsonNode selectMode(ToolContext child, ToolExecutionContext execution,
                    InteractionMode mode) { throw new AssertionError("不应选择后端"); }
        };
    }

    private static ToolExecutionResultV1 failed(JsonNode data, String code) {
        return new ToolExecutionResultV1(ToolExecutionStatus.FAILED, data, code, "rejected");
    }

    private static TaskContractV3 fileContract() {
        return new TaskContractV3(3, "整理文件", List.of(new TaskCriterionV3("file", "读取文件", "file.read",
                CapabilityMetadata.TargetKind.FILE, "report.txt", EffectReceiptV1.Status.OBSERVED, "")),
                true, true, "definition");
    }

    private static InteractionTask task() {
        return new InteractionTask(1, "delegate", 1, InteractionMode.AUTO, "整理文件",
                NODES.objectNode(), List.of(), List.of());
    }

    private static ObjectNode input() { return NODES.objectNode().put("goal", "整理文件"); }

    private record Event(String type, JsonNode payload) { }

    private static final class Fixture {
        final List<String> storeCalls = new ArrayList<>();
        final AtomicInteger agentAccesses = new AtomicInteger();
        final AtomicInteger observedChildren = new AtomicInteger();
        final List<Event> events = new ArrayList<>();
        final Clock clock = Clock.systemUTC();
        final RunControl control = new RunControl(BUDGET, clock);
        final RunRequest owner;
        final InteractionDelegateCoordinator coordinator;

        Fixture(TaskContractV3 contract) {
            this(contract, RUN.value());
        }

        Fixture(TaskContractV3 contract, String contractRunId) {
            ObjectNode capabilities = NODES.objectNode();
            capabilities.set("interaction.run", NODES.objectNode());
            owner = RunRequest.builder().agent(AgentDefinitionRef.latest("main"))
                    .profile(RunProfileRef.latest("main")).source(InvocationSource.chat()).scope(SCOPE)
                    .input(InputBlock.text("整理文件")).budget(BUDGET).permissionCeiling(PermissionSet.UNRESTRICTED)
                    .attributes(Map.of("framework.compiledCapabilities", capabilities)).build();
            List<RunEventEnvelope> history = contract == null ? List.of() : List.of(new RunEventEnvelope(
                    contractRunId, 1, Instant.now(), "core.task.contract", 3, "framework.core", "parent", null,
                    JSON.valueToTree(contract)));
            RunStore store = (RunStore) Proxy.newProxyInstance(RunStore.class.getClassLoader(),
                    new Class<?>[]{RunStore.class}, (proxy, method, arguments) -> {
                        storeCalls.add(method.getName());
                        if (method.getName().equals("eventsAfter")) return history;
                        throw new AssertionError("预派发拒绝不得读取子任务或产生运行记录: " + method.getName());
                    });
            coordinator = new InteractionDelegateCoordinator(() -> {
                agentAccesses.incrementAndGet();
                throw new AssertionError("预派发拒绝不得访问交互后端");
            }, store, JSON, new RunUsageLedger(), (parent, child) -> observedChildren.incrementAndGet());
        }

        ToolContext context() { return new ToolContext(RUN, SCOPE, PermissionSet.UNRESTRICTED,
                control, control.deadline(), owner); }
        ToolExecutionContext execution(String invocation) {
            return new ToolExecutionContext(RUN, invocation, control, control.deadline());
        }
        FrameworkTool tool(InteractionDelegateGateway gateway) {
            return new InteractionTools(gateway, JSON).create(context()).stream()
                    .filter(tool -> tool.descriptor().name().equals(InteractionExecutionPolicy.DELEGATE_TOOL))
                    .findFirst().orElseThrow();
        }
        ToolInvocationResult invoke(FrameworkTool tool, String invocation) {
            var request = new ToolInvocationRequest(tool, input(), execution(invocation), owner,
                    PermissionSet.UNRESTRICTED, NODES.objectNode(), List.of(), List.of(), control,
                    (type, version, producer, payload) -> events.add(new Event(type, payload.deepCopy())));
            return new DefaultToolInvocationGateway((descriptor, arguments, run) -> ToolApprovalDecision.ALLOW,
                    DIRECT, clock, JSON).invoke(request).toCompletableFuture().join();
        }
        List<JsonNode> receipts() { return events.stream().filter(event -> event.type.equals("core.tool.receipt"))
                .map(Event::payload).toList(); }
        void assertNoDispatch(int contractReads) {
            assertEquals(java.util.Collections.nCopies(contractReads, "eventsAfter"), storeCalls);
            assertEquals(0, agentAccesses.get());
            assertEquals(0, observedChildren.get());
        }
    }
}
