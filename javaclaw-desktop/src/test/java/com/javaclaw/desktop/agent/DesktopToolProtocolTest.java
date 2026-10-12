package com.javaclaw.desktop.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.desktop.api.*;
import com.javaclaw.framework.spi.ToolEffectCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class DesktopToolProtocolTest {
    private static final DesktopSessionOwner OWNER =
            new DesktopSessionOwner("workspace", "scope", "chat", "source");
    private static final DesktopTarget TARGET = new DesktopTarget("test", "target", 1,
            "Reader", "Window", 0, 0, 2, 1, DesktopTarget.VISIBLE);
    @TempDir Path temporary;

    @ParameterizedTest
    @EnumSource(DesktopActionResult.Status.class)
    void actionDeliveryAndAdmissionMatchTheTrustedResult(DesktopActionResult.Status status) {
        DesktopActionResult nativeResult = new DesktopActionResult(status, "result", 1)
                .withContext(DesktopActionResult.Mode.BACKGROUND_SEMANTIC,
                        "observation", DesktopActionResult.NextStep.OBSERVE);
        DesktopSessionTools tools = tools((method, args) -> switch (method) {
            case "info" -> new DesktopSessionInfo("session", TARGET, true, true);
            case "perform" -> CompletableFuture.completedFuture(nativeResult);
            case "acknowledgeActionResult" -> null;
            default -> throw new AssertionError(method);
        });
        try (var capture = ToolEffectCapture.begin("desktop_session_click")) {
            tools.click("session", "observation", 1, "", 0, 0, 1, 1);
            JsonNode data = capture.data();
            assertEquals("computer-use", data.path("protocol").asText());
            assertEquals(1, data.path("schemaVersion").asInt());
            assertEquals("desktop.action", data.path("kind").asText());
            assertEquals(status.name(), data.path("status").asText());
            assertEquals("UNKNOWN", data.path("effect").asText(),
                    "input acknowledgement does not prove the application postcondition");
            assertEquals(switch (status) {
                case VERIFIED, ACCEPTED -> "SENT";
                case UNKNOWN -> "MAYBE_SENT";
                default -> "NOT_SENT";
            }, data.path("delivery").asText());
            assertEquals(switch (status) {
                case VERIFIED, ACCEPTED -> ToolEffectCapture.Signal.SUCCESS;
                case UNKNOWN -> ToolEffectCapture.Signal.UNCERTAIN;
                case STALE_FRAME, UNSUPPORTED -> ToolEffectCapture.Signal.REOBSERVE;
                default -> ToolEffectCapture.Signal.ERROR;
            }, capture.signal());
            var proof = tools.receiptActionProof();
            assertNotNull(proof);
            assertEquals(nativeResult, proof.result());
            assertEquals(proof.targetId(), data.path("targetId").asText());
            assertEquals(proof.observationId(), data.path("observationId").asText());
            assertEquals(proof.result().nextStep().name(), data.path("nextStep").asText());
        }
    }

    @Test
    void invalidInputPublishesDefiniteNotSentProofBeforeCallingTheService() {
        AtomicInteger calls = new AtomicInteger();
        DesktopSessionTools tools = tools((method, args) -> {
            calls.incrementAndGet();
            throw new AssertionError("invalid action must not call service: " + method);
        });
        try (var capture = ToolEffectCapture.begin("desktop_session_click")) {
            tools.click("session", "observation", 1, "", 0, 0, 99, 1);
            assertEquals(0, calls.get());
            assertEquals(ToolEffectCapture.Signal.ERROR, capture.signal());
            assertEquals("desktop.error", capture.data().path("kind").asText());
            assertEquals("NOT_SENT", capture.data().path("delivery").asText());
            var proof = tools.receiptActionProof();
            assertNotNull(proof, "receipt adapter must not infer uncertain delivery from absent proof");
            assertFalse(proof.result().dispatchAttempted());
            assertEquals(DesktopActionResult.Status.FAILED, proof.result().status());
            assertEquals("observation", proof.observationId());
            assertEquals(proof.result().nextStep().name(), capture.data().path("nextStep").asText());
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void lostSynchronousOrAsynchronousInputResultInstallsAnExactObservationFence(boolean async) {
        AtomicInteger fences = new AtomicInteger();
        DesktopSessionTools tools = tools((method, args) -> switch (method) {
            case "info" -> new DesktopSessionInfo("session", TARGET, true, true);
            case "perform" -> {
                if (!async) throw new IllegalStateException("lost result while starting input");
                yield CompletableFuture.failedFuture(new IllegalStateException("lost input result"));
            }
            case "markDeliveryUncertain" -> {
                assertEquals(OWNER, args[0]);
                assertEquals("session", args[1]);
                assertEquals("observation", args[2]);
                fences.incrementAndGet();
                yield null;
            }
            default -> throw new AssertionError(method);
        });
        try (var capture = ToolEffectCapture.begin("desktop_session_click")) {
            tools.click("session", "observation", 1, "", 0, 0, 1, 1);
            assertEquals(1, fences.get());
            assertEquals(ToolEffectCapture.Signal.UNCERTAIN, capture.signal());
            assertEquals("MAYBE_SENT", capture.data().path("delivery").asText());
            assertEquals("UNKNOWN", capture.data().path("effect").asText());
            var proof = tools.receiptActionProof();
            assertEquals(DesktopActionResult.Status.UNKNOWN, proof.result().status());
            assertTrue(proof.result().dispatchAttempted());
        }
    }

    @Test
    void pendingPreviousInputIsAReconciliationBarrierRatherThanMissingPermissions() {
        DesktopActionResult blocked = new DesktopActionResult(DesktopActionResult.Status.DENIED,
                "pending input", 1, DesktopActionResult.Mode.NONE,
                DesktopActionResult.Reason.DELIVERY_UNCERTAIN, false,
                "observation", DesktopActionResult.NextStep.RECONCILE);
        JsonNode data = DesktopToolPayloads.action("desktop_session_click", "session", "target",
                new DesktopAction(DesktopAction.Kind.CLICK, 0, 0, 1, 1, 0, "",
                        1, "observation", "", 0), blocked);
        assertEquals("NOT_SENT", data.path("delivery").asText());
        assertEquals("DELIVERY_UNCERTAIN", data.path("reason").asText());
        assertEquals("RECONCILE", data.path("nextStep").asText());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void openedCapabilityProofUsesActualServiceGrantRatherThanRequestedControl(boolean granted) {
        DesktopSessionTools tools = tools((method, args) -> {
            assertEquals("open", method);
            assertEquals(!granted, args[2], "the request differs deliberately from the service result");
            return CompletableFuture.completedFuture(new DesktopSessionInfo("session", TARGET, granted, false));
        });
        try (var capture = ToolEffectCapture.begin("desktop_session_open")) {
            tools.open("target", !granted);
            assertEquals(ToolEffectCapture.Signal.SUCCESS, capture.signal());
            assertEquals(granted, capture.data().path("controlGranted").asBoolean());
            assertEquals(granted, tools.receiptOpenedSession("target").controlGranted());
        }
        assertFalse(new DesktopSessionTools.OpenedSessionProof("target", "Reader", "", "session")
                .controlGranted(), "old proof constructors cannot imply control admission");
    }

    @Test
    void sessionControlRequiredIsTypedNotSentRecoveryThroughOpen() {
        DesktopActionResult readOnly = new DesktopActionResult(DesktopActionResult.Status.DENIED,
                "read-only session", 1, DesktopActionResult.Mode.NONE,
                DesktopActionResult.Reason.SESSION_CONTROL_REQUIRED, false,
                "observation", DesktopActionResult.NextStep.OPEN_SESSION);
        DesktopSessionTools tools = tools((method, args) -> switch (method) {
            case "info" -> new DesktopSessionInfo("session", TARGET, false, false);
            case "perform" -> CompletableFuture.completedFuture(readOnly);
            case "acknowledgeActionResult" -> null;
            default -> throw new AssertionError(method);
        });
        try (var capture = ToolEffectCapture.begin("desktop_session_click")) {
            tools.click("session", "observation", 1, "", 0, 0, 1, 1);
            assertEquals(ToolEffectCapture.Signal.ERROR, capture.signal());
            assertEquals("SESSION_CONTROL_REQUIRED", capture.data().path("reason").asText());
            assertEquals("OPEN_SESSION", capture.data().path("nextStep").asText());
            assertEquals("target", capture.data().path("targetId").asText());
            assertEquals("NOT_SENT", capture.data().path("delivery").asText());
            assertFalse(capture.data().path("dispatchAttempted").asBoolean());
            assertEquals(readOnly, tools.receiptActionProof().result());
        }
    }

    @Test
    void realControlPermissionFailureStillRequiresCheckingPermissions() {
        DesktopSessionTools tools = tools((method, args) -> switch (method) {
            case "info" -> new DesktopSessionInfo("session", TARGET, false, false);
            case "perform" -> throw new SecurityException("OS input permission missing");
            default -> throw new AssertionError(method);
        });
        try (var capture = ToolEffectCapture.begin("desktop_session_click")) {
            tools.click("session", "observation", 1, "", 0, 0, 1, 1);
            assertEquals("ACCESS_DENIED", capture.data().path("reason").asText());
            assertEquals("CHECK_PERMISSIONS", capture.data().path("nextStep").asText());
            assertEquals("NOT_SENT", capture.data().path("delivery").asText());
            assertFalse(tools.receiptActionProof().result().dispatchAttempted());
        }
    }

    @Test
    void controlledOpenUsesHostBackgroundPolicyWithoutRequestingSystemInput() {
        List<String> calls = new ArrayList<>();
        DesktopSessionTools tools = tools((method, args) -> {
            calls.add(method);
            assertEquals("open", method);
            assertEquals(DesktopInputPolicy.BACKGROUND_STRICT, args[3]);
            return CompletableFuture.completedFuture(new DesktopSessionInfo("session", TARGET, true, false));
        });
        try (var capture = ToolEffectCapture.begin("desktop_session_open")) {
            assertTrue(tools.open("target", true).contains("[成功]"));
            assertEquals(List.of("open"), calls);
            assertEquals("BACKGROUND_STRICT", capture.data().path("inputPolicy").asText());
            assertFalse(capture.data().path("foregroundGranted").asBoolean());
            assertEquals("ACCESSIBILITY", capture.data().path("inputMode").asText());
            assertNotNull(tools.receiptOpenedSession("target"));
        }
    }

    @Test
    @DisplayName("只读打开会话不申请系统输入授权")
    void readOnlyOpenDoesNotRequestSystemInput() {
        DesktopSessionTools tools = tools((method, args) -> {
            assertEquals("open", method, "只读打开不得升级模式或派发输入");
            assertEquals(false, args[2]);
            return CompletableFuture.completedFuture(new DesktopSessionInfo("session", TARGET, false, false));
        });

        try (var capture = ToolEffectCapture.begin("desktop_session_open")) {
            tools.open("target", false);
            assertEquals(ToolEffectCapture.Signal.SUCCESS, capture.signal());
            assertEquals("READ_ONLY", capture.data().path("inputMode").asText());
            assertFalse(capture.data().path("foregroundGranted").asBoolean());
            assertFalse(tools.receiptOpenedSession("target").controlGranted());
        }
    }

    @Test
    void rejectedSessionOpenCannotPublishSuccessOrFallback() {
        DesktopSessionTools tools = tools((method, args) -> {
            assertEquals("open", method);
            return CompletableFuture.failedFuture(new SecurityException("public semantic permission missing"));
        });
        try (var capture = ToolEffectCapture.begin("desktop_session_open")) {
            assertTrue(tools.open("target", true).contains("[失败]"));
            assertEquals(ToolEffectCapture.Signal.ERROR, capture.signal());
            assertNull(tools.receiptOpenedSession("target"));
        }
    }

    @Test
    void legacyServiceDefaultPolicyIsStrict() {
        DesktopSessionTools tools = tools((method, args) -> {
            assertEquals("open", method);
            assertEquals(DesktopInputPolicy.BACKGROUND_STRICT, args[3]);
            return CompletableFuture.completedFuture(new DesktopSessionInfo("session", TARGET, true, false));
        });
        try (var capture = ToolEffectCapture.begin("desktop_session_open")) {
            tools.open("target", true);
            assertEquals(ToolEffectCapture.Signal.SUCCESS, capture.signal());
        }
    }

    @Test
    void openRejectsSystemPolicyReturnedForStrictHostRequest() {
        DesktopSessionTools tools = tools((method, args) -> {
            assertEquals("open", method);
            return CompletableFuture.completedFuture(new DesktopSessionInfo("session", TARGET, true, true));
        });
        try (var capture = ToolEffectCapture.begin("desktop_session_open")) {
            tools.open("target", true);
            assertEquals(ToolEffectCapture.Signal.ERROR, capture.signal());
            assertNull(tools.receiptOpenedSession("target"));
        }
    }

    @Test
    void controlledBackgroundSessionDispatchesThroughServiceWithoutSystemInputGate() {
        DesktopSessionTools tools = tools((method, args) -> switch (method) {
            case "info" -> new DesktopSessionInfo("session", TARGET, true, false);
            case "perform" -> CompletableFuture.completedFuture(new DesktopActionResult(
                    DesktopActionResult.Status.VERIFIED, "public semantic accepted", 1));
            case "acknowledgeActionResult" -> null;
            default -> throw new AssertionError(method);
        });
        try (var capture = ToolEffectCapture.begin("desktop_session_click")) {
            tools.click("session", "observation", 1, "observation:e1", 0, 0, 1, 1);
            assertEquals("SENT", capture.data().path("delivery").asText());
            assertTrue(capture.data().path("dispatchAttempted").asBoolean());
        }
    }

    @Test
    void takeoverCannotPromoteStrictPolicyOrRequestSystemAuthorization() {
        AtomicInteger calls = new AtomicInteger();
        DesktopSessionTools tools = tools((method, args) -> {
            calls.incrementAndGet();
            assertEquals("info", method);
            return new DesktopSessionInfo("session", TARGET, true, false);
        });
        try (var capture = ToolEffectCapture.begin("desktop_session_takeover")) {
            assertTrue(tools.takeover("session").contains("[失败]"));
            assertEquals("POLICY_BLOCKED", capture.data().path("reason").asText());
            assertEquals(1, calls.get());
        }
    }

    @ParameterizedTest
    @CsvSource({"true,true,SYSTEM_EVENTS", "true,false,ACCESSIBILITY", "false,false,READ_ONLY"})
    @DisplayName("打开及观察载荷公开实际会话输入模式")
    void sessionPayloadsReportActualInputMode(boolean controlled, boolean foreground, String mode) {
        DesktopSessionInfo session = new DesktopSessionInfo("session", TARGET, controlled, foreground);
        DesktopFrame frame = new DesktopFrame("target", 1, 100, 2, 1, 8, new byte[8]);
        List<JsonNode> payloads = List.of(DesktopToolPayloads.opened(session),
                DesktopToolPayloads.observation(session, "observation", frame,
                        List.of(), List.of(), null, "", "", ""));

        for (JsonNode payload : payloads) {
            assertEquals(controlled, payload.path("controlGranted").asBoolean());
            assertTrue(payload.path("foregroundGranted").isBoolean());
            assertEquals(foreground, payload.path("foregroundGranted").asBoolean());
            assertEquals(mode, payload.path("inputMode").asText());
        }
    }

    @Test
    void inventoryListsExactIdsAndMarksEightPlusAsIncomplete() {
        List<String> all = IntStream.range(0, 9).mapToObj(n -> "session-" + n).toList();
        DesktopSessionTools tools = tools((method, args) -> {
            assertEquals("liveSessionIds", method);
            assertEquals(OWNER, args[0]);
            return Optional.of(all);
        });
        JsonNode inventory = tools.currentContext().getFirst();
        assertTrue(inventory.path("known").asBoolean());
        assertFalse(inventory.path("complete").asBoolean(),
                "an omitted live session must not be classified as expired");
        assertEquals(8, inventory.path("sessionIds").size());
        for (int i = 0; i < 8; i++) assertEquals(all.get(i), inventory.path("sessionIds").get(i).asText());
        assertTrue(inventory.toString().length() <= 400);
    }

    @Test
    void inventoryBudgetDropsWholeIdsAndPreservesUnknownOrUnavailableState() {
        DesktopSessionTools longId = tools((method, args) -> Optional.of(List.of("x".repeat(600))));
        JsonNode inventory = longId.currentContext().getFirst();
        assertTrue(inventory.path("sessionIds").isEmpty(), "identity must never be truncated into another ID");
        assertFalse(inventory.path("complete").asBoolean());
        assertTrue(inventory.toString().length() <= 400);
        assertTrue(tools((method, args) -> Optional.empty()).currentContext().isEmpty());
        assertTrue(tools((method, args) -> { throw new IllegalStateException("unavailable"); })
                .currentContext().isEmpty());
        assertTrue(tools((method, args) -> Optional.of(List.of("duplicate", "duplicate")))
                .currentContext().isEmpty());
    }

    @Test
    void observationPreservesFrameTokensAndBoundsUntrustedLabelsAndContent() {
        DesktopFrame frame = new DesktopFrame("target", 3, 100, 2, 1, 8,
                new byte[8], 4);
        String text = "untrusted label ".repeat(10_000);
        var element = new DesktopElement("exact-element-id", text, text, 0, 0, 1, 1, 1);
        var region = new DesktopVisualRegion("exact-visual-id", "button", text.substring(0, 256),
                0, 0, 1, 1, 1);
        JsonNode data = DesktopToolPayloads.observation(new DesktopSessionInfo("session", TARGET, true, true),
                "exact-observation-id", frame, List.of(element), List.of(region), null, text, text, text);
        assertEquals("exact-observation-id", data.path("observationId").asText());
        assertTrue(data.path("controlGranted").isBoolean());
        assertTrue(data.path("controlGranted").asBoolean());
        assertEquals(3, data.path("frame").path("windowGeneration").asLong());
        assertEquals(4, data.path("frame").path("contentRevision").asLong());
        assertEquals("WINDOW_FRAME_PIXELS", data.path("frame").path("coordinateSpace").asText());
        assertEquals("exact-element-id", data.path("elements").get(0).path("id").asText());
        assertEquals("exact-visual-id", data.path("visualTargets").get(0).path("id").asText());
        assertEquals("UNTRUSTED_SCREEN_CONTENT", data.path("content").path("trust").asText());
        assertTrue(data.path("content").path("truncated").asBoolean());
        assertTrue(data.toString().length() < 23_000, "untrusted labels must not bypass content limits");
    }

    private DesktopSessionTools tools(ServiceCall call) {
        DesktopSessionService sessions = (DesktopSessionService) Proxy.newProxyInstance(
                DesktopSessionService.class.getClassLoader(), new Class<?>[]{DesktopSessionService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    // 可选生命周期诊断沿用接口默认实现，不改变原有协议用例的服务行为。
                    case "surface", "beginWindowAction", "finishWindowAction", "snapshotWindowTracking", "defaultInputPolicy" ->
                            InvocationHandler.invokeDefault(proxy, method, args);
                    default -> call.invoke(method.getName(), args);
                });
        return new DesktopSessionTools(sessions, OWNER, temporary);
    }

    private interface ServiceCall { Object invoke(String method, Object[] arguments) throws Exception; }
}
