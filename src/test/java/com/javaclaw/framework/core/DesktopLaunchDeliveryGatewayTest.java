package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.desktop.agent.DesktopSessionTools;
import com.javaclaw.desktop.api.*;
import com.javaclaw.desktop.service.DefaultDesktopSessionService;
import com.javaclaw.desktop.spi.DesktopPlatformProvider;
import com.javaclaw.desktop.spi.DesktopPlatformSession;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.*;
import com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry;
import com.javaclaw.util.ProjectAccessPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual annotated desktop callback, host receipts and effect admission gateway. */
class DesktopLaunchDeliveryGatewayTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final RunBudget BUDGET = new RunBudget(
            Duration.ofMinutes(1), 1000, 1000, 10, BigDecimal.TEN);
    private static final CancellableTaskExecutor DIRECT_EXECUTOR = new CancellableTaskExecutor() {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public <T> CancellableTask<T> submit(String name, Duration timeout,
                CancellationToken cancellation, Callable<T> task) {
            CompletableFuture<T> result = new CompletableFuture<>();
            try {
                cancellation.throwIfCancelled();
                result.complete(task.call());
            } catch (Throwable failure) { result.completeExceptionally(failure); }
            return new CancellableTask<>() {
                @Override public java.util.concurrent.CompletionStage<T> completion() { return result; }
                @Override public java.util.concurrent.CompletionStage<Void> termination() {
                    return CompletableFuture.completedFuture(null);
                }
                @Override public boolean cancel() { return false; }
            };
        }
    };

    @Test
    void exactNotFoundNativeAdmissionCanBeRetriedUnderDiscoveryAdmissionPolicy() throws Exception {
        var failure = new DesktopApplicationLaunchRejectedException(
                DesktopApplicationLaunchRejectedException.Reason.APPLICATION_NOT_FOUND,
                -3, "arbitrary localized failure text", null);
        try (Fixture fixture = new Fixture(failure)) {
            assertFalse(fixture.launch.descriptor().idempotent());
            assertEquals(ToolEffectPolicy.DISCOVERY_GATED, fixture.launch.descriptor().effectPolicy());
            ToolInvocationResult first = fixture.invoke("first");
            ToolInvocationResult second = fixture.invoke("second");

            assertEquals(ToolExecutionStatus.FAILED, first.status());
            assertEquals(ToolExecutionStatus.FAILED, second.status());
            assertEquals("desktop.launch", first.output().path("kind").asText());
            assertEquals("REJECTED", first.output().path("admission").asText());
            assertEquals("FAILED", first.output().path("status").asText());
            assertEquals("NOT_SENT", first.output().path("delivery").asText());
            assertEquals("APPLICATION_NOT_FOUND", first.output().path("reasonCode").asText());
            assertEquals(2, fixture.providerCalls.get(), "both proven rejections may reach admission");
            assertEquals(2, fixture.receipts.size());
            JsonNode metadata = fixture.receipts.getFirst().path("metadata");
            assertEquals("FAILED", fixture.receipts.getFirst().path("status").asText());
            assertEquals("飞书", metadata.path("requestedApplication").asText());
            assertEquals("REJECTED", metadata.path("admission").asText());
            assertEquals("APPLICATION_NOT_FOUND", metadata.path("reasonCode").asText());
            assertEquals("NOT_SENT", metadata.path("delivery").asText());
            assertEquals("false", metadata.path("dispatchAttempted").asText());
            assertEquals("-3", metadata.path("nativeCode").asText());
            assertEquals("DISCOVER_APPLICATIONS", metadata.path("nextStep").asText());
        }
    }

    @Test
    void legacyFailureMessageCannotProveNotSentAndSecondLaunchNeverReachesProvider() throws Exception {
        try (Fixture fixture = new Fixture(new IllegalStateException(
                "Exact installed application was not found; NOT_SENT; APPLICATION_NOT_FOUND"))) {
            assertUncertainAndBlocked(fixture);
            assertFalse(fixture.receipts.getFirst().path("metadata").has("nativeCode"));
        }
    }

    @Test
    void dispatchedNativeFailureRemainsUnknownAndCannotBeRepeated() throws Exception {
        try (Fixture fixture = new Fixture(new DesktopApplicationLaunchUncertainException(
                "native API returned -6 after dispatch", 202, "com.example.reader", null))) {
            assertUncertainAndBlocked(fixture);
            assertEquals("202", fixture.receipts.getFirst().path("metadata").path("processId").asText());
            assertEquals("com.example.reader", fixture.receipts.getFirst().path("metadata")
                    .path("applicationId").asText());
        }
    }

    @Test
    void launchProofUsesTheSameNormalizedApplicationIdentityAsServiceAdmission() throws Exception {
        try (Fixture fixture = new Fixture(new DesktopApplicationLaunchRejectedException(
                DesktopApplicationLaunchRejectedException.Reason.APPLICATION_NOT_FOUND,
                -3, "not found", null))) {
            ToolInvocationResult result = fixture.invoke("spaced", "  飞书  ");
            assertEquals("飞书", result.output().path("requestedApplication").asText());
            assertEquals("FAILED", fixture.receipts.getFirst().path("status").asText());
            assertEquals("NOT_SENT", fixture.receipts.getFirst().path("metadata").path("delivery").asText());
            assertEquals("飞书", fixture.receipts.getFirst().path("metadata")
                    .path("requestedApplication").asText());
        }
    }

    @Test
    void taskRepairCanRetryAnAttemptThatWasProvenNotDispatched() throws Exception {
        try (Fixture fixture = new Fixture(new DesktopApplicationLaunchRejectedException(
                DesktopApplicationLaunchRejectedException.Reason.APPLICATION_NOT_FOUND,
                -3, "not found", null))) {
            assertEquals(ToolExecutionStatus.FAILED, fixture.invoke("first").status());
            fixture.control.enterTaskRepair();
            assertEquals(ToolExecutionStatus.FAILED, fixture.invoke("repair").status());
            assertEquals(2, fixture.providerCalls.get());
            assertEquals(2, fixture.receipts.size());
            assertEquals("NOT_SENT", fixture.receipts.getLast().path("metadata").path("delivery").asText());
        }
    }

    @Test
    void notSentAttemptCannotEraseAnotherUnknownAttemptWithTheSameEffectKeyOnRepair() throws Exception {
        try (Fixture fixture = new Fixture(new DesktopApplicationLaunchRejectedException(
                DesktopApplicationLaunchRejectedException.Reason.APPLICATION_NOT_FOUND,
                -3, "not found", null))) {
            fixture.invoke("proven-not-sent");
            var arguments = JsonNodeFactory.instance.objectNode().put("application", "飞书");
            String effectKey = ToolEffectKey.create("desktop_session_launch_application",
                    arguments, "older-fingerprint");
            fixture.control.restoreEffectStart("older-unknown", "older-fingerprint", effectKey,
                    false, ToolEffectPolicy.LEGACY, "");
            fixture.control.restoreEffectReceipt("older-unknown", EffectReceiptV1.Status.UNKNOWN,
                    "MAYBE_SENT");
            fixture.control.enterTaskRepair();

            CompletionException blocked = assertThrows(CompletionException.class,
                    () -> fixture.invoke("repair"));
            assertInstanceOf(ToolPermissionDeniedException.class, blocked.getCause());
            assertEquals(1, fixture.providerCalls.get(), "the unresolved older launch still blocks dispatch");
            assertEquals(1, fixture.receipts.size());
        }
    }

    private static void assertUncertainAndBlocked(Fixture fixture) {
        ToolInvocationResult first = fixture.invoke("first");
        assertEquals(ToolExecutionStatus.UNCERTAIN, first.status());
        assertEquals("UNKNOWN", first.output().path("status").asText());
        assertEquals("MAYBE_SENT", first.output().path("delivery").asText());
        assertEquals("UNKNOWN", fixture.receipts.getFirst().path("status").asText());
        assertEquals("MAYBE_SENT", fixture.receipts.getFirst().path("metadata").path("delivery").asText());
        CompletionException rejected = assertThrows(CompletionException.class,
                () -> fixture.invoke("second"));
        assertInstanceOf(ToolPermissionDeniedException.class, rejected.getCause());
        assertEquals(1, fixture.providerCalls.get(), "unknown launch cannot be sent again");
        assertEquals(1, fixture.receipts.size());
    }

    private static final class Fixture implements AutoCloseable {
        private final AtomicInteger providerCalls = new AtomicInteger();
        private final List<JsonNode> receipts = new CopyOnWriteArrayList<>();
        private final RunId runId = new RunId("desktop-launch-test");
        private final RunControl control = new RunControl(BUDGET, CLOCK);
        private final RunRequest request = RunRequest.builder()
                .agent(AgentDefinitionRef.latest("test.agent"))
                .profile(RunProfileRef.latest("test.profile"))
                .source(InvocationSource.workflow("workflow-test"))
                .scope(new RunScope("workspace", "user", "session"))
                .input(InputBlock.text("打开飞书"))
                .permissionCeiling(PermissionSet.UNRESTRICTED).budget(BUDGET).build();
        private final DefaultDesktopSessionService service;
        private final List<FrameworkTool> tools;
        private final FrameworkTool launch;
        private final AutoCloseable registration;
        private final DefaultToolInvocationGateway gateway = new DefaultToolInvocationGateway(
                (tool, arguments, owner) -> ToolApprovalDecision.ALLOW, DIRECT_EXECUTOR, CLOCK);

        private Fixture(RuntimeException failure) {
            DesktopPlatformProvider provider = new DesktopPlatformProvider() {
                @Override public String id() { return "test"; }
                @Override public DesktopAvailability probe() {
                    return new DesktopAvailability(true, "test", DesktopAvailability.CAPTURE, "ready");
                }
                @Override public List<DesktopTarget> discoverTargets() { return List.of(); }
                @Override public DesktopApplicationLaunch launchApplication(String application) {
                    providerCalls.incrementAndGet();
                    throw failure;
                }
                @Override public DesktopPlatformSession open(DesktopTarget target) {
                    throw new UnsupportedOperationException("not used");
                }
            };
            service = new DefaultDesktopSessionService(List.of(provider), (owner, target, purpose) -> true);
            DesktopSessionTools source = new DesktopSessionTools(service,
                    new DesktopSessionOwner("workspace", runId.value(), "workflow", "workflow-test"),
                    ProjectAccessPolicy.projectRoot().resolve("target"));
            var registry = new SpringAiAnnotatedToolRegistry(new ObjectMapper());
            registration = registry.register("workspace", ignored -> ToolObjectBundle.of(List.of(source)));
            tools = registry.create(new ToolContext(runId, request.scope(), PermissionSet.UNRESTRICTED,
                    control, control.deadline(), request));
            launch = tools.stream().filter(tool -> tool.descriptor().name()
                    .equals("desktop_session_launch_application")).findFirst().orElseThrow();
        }

        private ToolInvocationResult invoke(String invocationId) {
            return invoke(invocationId, "飞书");
        }

        private ToolInvocationResult invoke(String invocationId, String application) {
            return gateway.invoke(new ToolInvocationRequest(launch,
                    JsonNodeFactory.instance.objectNode().put("application", application),
                    new ToolExecutionContext(runId, invocationId, control, control.deadline()),
                    request, PermissionSet.UNRESTRICTED, JsonNodeFactory.instance.objectNode(),
                    List.of(), List.of(), control, (type, version, producer, payload) -> {
                        if (type.equals("core.tool.receipt")) receipts.add(payload.deepCopy());
                    })).toCompletableFuture().join();
        }

        @Override public void close() throws Exception {
            for (FrameworkTool tool : tools) tool.close();
            registration.close();
            service.close();
        }
    }
}
