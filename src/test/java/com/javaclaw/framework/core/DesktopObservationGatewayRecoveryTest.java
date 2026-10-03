package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.agent.vision.VisionPreprocessor;
import com.javaclaw.desktop.agent.DesktopSessionTools;
import com.javaclaw.desktop.api.*;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.*;
import com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class DesktopObservationGatewayRecoveryTest {
    @TempDir Path temporary;

    @Test
    void registeredHostObservationRefreshesGatewayInputWithoutForgingBusinessVerification() throws Exception {
        AtomicLong time = new AtomicLong(1000);
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(time.get()); }
        };
        RunId run = new RunId("host-observation-recovery");
        RunControl control = new RunControl(RunBudget.UNBOUNDED, clock);
        RunRequest request = RunRequest.builder().agent(AgentDefinitionRef.latest("test.agent"))
                .profile(RunProfileRef.latest("test.profile")).source(InvocationSource.workflow("fixture"))
                .scope(new RunScope("workspace", "user", "session"))
                .input(InputBlock.text("查看应用内容"))
                .permissionCeiling(PermissionSet.UNRESTRICTED).budget(RunBudget.UNBOUNDED).build();
        AtomicInteger inputs = new AtomicInteger();
        String old = "123e4567-e89b-42d3-a456-426614174000";
        String fresh = "123e4567-e89b-42d3-a456-426614174001";
        DesktopTarget target = new DesktopTarget("fixture", "window", 7, "Example", "Example",
                0, 0, 4, 4, DesktopTarget.VISIBLE);
        DesktopSessionService service = (DesktopSessionService) Proxy.newProxyInstance(
                DesktopSessionService.class.getClassLoader(), new Class<?>[] { DesktopSessionService.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "info" -> new DesktopSessionInfo("native-session", target, true, true);
                    case "captureObservation" -> CompletableFuture.completedFuture(Optional.of(
                            new DesktopObservation("native-session", fresh,
                                    new DesktopFrame("window", 1, time.get() - 100, 4, 4, 16,
                                            new byte[64]), List.of())));
                    case "commitObservation" -> CompletableFuture.completedFuture(true);
                    case "perform" -> {
                        DesktopAction input = (DesktopAction) args[2];
                        yield CompletableFuture.completedFuture(new DesktopActionResult(
                                inputs.incrementAndGet() == 1 ? DesktopActionResult.Status.UNKNOWN
                                        : DesktopActionResult.Status.ACCEPTED,
                                "platform result", input.windowGeneration(),
                                DesktopActionResult.Mode.BACKGROUND_SEMANTIC,
                                DesktopActionResult.Reason.DELIVERY_UNCERTAIN, true,
                                input.observationId(), DesktopActionResult.NextStep.OBSERVE));
                    }
                    case "acknowledgeActionResult" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ModelTaskGateway vision = ignored -> CompletableFuture.completedFuture(new ModelTaskResult(
                JsonNodeFactory.instance.objectNode().put("summary", "current window").put("visibleText", "")
                        .set("targets", JsonNodeFactory.instance.arrayNode()), "fixture", 1, 1, false, Map.of()));
        DesktopSessionTools source = new DesktopSessionTools(service,
                new DesktopSessionOwner("workspace", run.value(), "workflow", "fixture"), temporary,
                null, new VisionPreprocessor(vision, run));
        var registry = new SpringAiAnnotatedToolRegistry(new ObjectMapper());
        List<RunEventEnvelope> journal = new ArrayList<>();
        ReasoningEventSink events = (type, schema, producer, payload) -> journal.add(
                new RunEventEnvelope(run.value(), journal.size() + 1, clock.instant(),
                        type, schema, producer, null, null, payload));
        var gateway = new DefaultToolInvocationGateway((tool, args, owner) -> ToolApprovalDecision.ALLOW,
                directExecutor(), clock);
        try (var registration = registry.register("workspace", ignored -> ToolObjectBundle.of(List.of(source)))) {
            List<FrameworkTool> tools = registry.create(new ToolContext(run, request.scope(),
                    PermissionSet.UNRESTRICTED, control, control.deadline(), request));
            try {
                FrameworkTool click = tools.stream().filter(tool -> tool.descriptor().name()
                        .equals("desktop_session_click")).findFirst().orElseThrow();
                FrameworkTool observe = tools.stream().filter(tool -> tool.descriptor().name()
                        .equals("desktop_session_observe")).findFirst().orElseThrow();
                assertEquals(ToolExecutionStatus.UNCERTAIN, invoke(gateway, click, click(old),
                        "first", run, request, control, events).status());
                assertTrue(control.hasPendingDesktopInput());
                assertThrows(CompletionException.class, () -> invoke(gateway, click, click(fresh),
                        "blocked", run, request, control, events));
                assertEquals(1, inputs.get(), "unobserved requests never dispatch input");
                time.set(4000);
                assertEquals(ToolExecutionStatus.SUCCEEDED, invoke(gateway, observe,
                        JsonNodeFactory.instance.objectNode().put("sessionId", "native-session"),
                        "observe", run, request, control, events).status());
                assertEquals(1, DesktopObservationBaseline.fromEvents(journal).size(),
                        "live receipts must remain independently replayable after restart");
                assertFalse(control.hasPendingDesktopInput());
                assertEquals(ToolExecutionStatus.SUCCEEDED, invoke(gateway, click, click(fresh),
                        "next", run, request, control, events).status());
                assertEquals(2, inputs.get());
                assertThrows(CompletionException.class, () -> invoke(gateway, click, click(old),
                        "old-replay", run, request, control, events));
                assertEquals(2, inputs.get(), "old observation cannot dispatch after baseline refresh");
                assertEquals("UNKNOWN", journal.stream().filter(event -> event.type().equals("core.tool.receipt")
                        && event.payload().path("invocationId").asText().equals("first"))
                        .findFirst().orElseThrow().payload().path("status").asText());
                assertTrue(journal.stream().noneMatch(event -> event.type().equals("core.effect.reconciled")));
            } finally { for (FrameworkTool tool : tools) tool.close(); }
        }
    }

    private static JsonNode click(String observation) {
        return JsonNodeFactory.instance.objectNode().put("sessionId", "native-session")
                .put("observationId", observation).put("generation", 1).put("elementId", "")
                .put("x", 1).put("y", 1).put("button", 1).put("clicks", 1);
    }

    private static ToolInvocationResult invoke(DefaultToolInvocationGateway gateway, FrameworkTool tool,
            JsonNode args, String id, RunId run, RunRequest request, RunControl control, ReasoningEventSink events) {
        return gateway.invoke(new ToolInvocationRequest(tool, args,
                new ToolExecutionContext(run, id, control, control.deadline()), request,
                PermissionSet.UNRESTRICTED, JsonNodeFactory.instance.objectNode(), List.of(), List.of(),
                control, events)).toCompletableFuture().join();
    }

    private static CancellableTaskExecutor directExecutor() {
        return new CancellableTaskExecutor() {
            @Override public void execute(Runnable command) { command.run(); }
            @Override public <T> CancellableTask<T> submit(String name, Duration timeout,
                    CancellationToken cancellation, Callable<T> task) {
                CompletableFuture<T> result = new CompletableFuture<>();
                try { result.complete(task.call()); }
                catch (Throwable failure) { result.completeExceptionally(failure); }
                return new CancellableTask<>() {
                    @Override public CompletionStage<T> completion() { return result; }
                    @Override public CompletionStage<Void> termination() { return CompletableFuture.completedFuture(null); }
                    @Override public boolean cancel() { return false; }
                };
            }
        };
    }
}
