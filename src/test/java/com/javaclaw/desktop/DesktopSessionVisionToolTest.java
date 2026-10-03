package com.javaclaw.desktop;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.agent.ToolRiskRegistry;
import com.javaclaw.agent.vision.VisionPreprocessor;
import com.javaclaw.desktop.agent.DesktopSessionTools;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopAction;
import com.javaclaw.desktop.api.DesktopActionResult;
import com.javaclaw.desktop.api.DesktopApplicationLaunchResult;
import com.javaclaw.desktop.api.DesktopApplicationLaunchUncertainException;
import com.javaclaw.desktop.api.DesktopSessionOwner;
import com.javaclaw.desktop.api.DesktopSessionInfo;
import com.javaclaw.desktop.api.DesktopObservation;
import com.javaclaw.desktop.api.DesktopSessionService;
import com.javaclaw.desktop.api.DesktopSessionState;
import com.javaclaw.desktop.api.DesktopTarget;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ModelTaskRequest;
import com.javaclaw.framework.spi.ModelTaskResult;
import com.javaclaw.framework.spi.ToolEffectCapture;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopSessionVisionToolTest {
    @TempDir Path temporary;

    @Test
    void generatedTargetPassedAsObservationTokenUsesItsCurrentFrameAndTarget() {
        DesktopSessionOwner owner = new DesktopSessionOwner("workspace", "scope", "chat", "source");
        String observationId = "123e4567-e89b-42d3-a456-426614174000";
        String targetToken = observationId + ":v12";
        AtomicReference<DesktopAction> performed = new AtomicReference<>();
        AtomicReference<String> acknowledged = new AtomicReference<>();
        DesktopSessionService sessions = (DesktopSessionService) Proxy.newProxyInstance(
                DesktopSessionService.class.getClassLoader(),
                new Class<?>[] { DesktopSessionService.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "info" -> new DesktopSessionInfo("session", new DesktopTarget(
                            "macos", "window", 1, "演示文稿", "概览", 0, 0, 800, 600,
                            DesktopTarget.VISIBLE), true, true);
                    case "perform" -> {
                        performed.set((DesktopAction) args[2]);
                        yield CompletableFuture.completedFuture(new DesktopActionResult(
                                DesktopActionResult.Status.VERIFIED, "已派发", 4));
                    }
                    case "acknowledgeActionResult" -> {
                        acknowledged.set((String) args[2]);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        DesktopSessionTools tools = new DesktopSessionTools(sessions, owner, temporary);

        assertTrue(tools.click("session", targetToken, 4, "", 12, 40, 1, 1)
                .contains("[成功]"));
        assertEquals(observationId, performed.get().observationId());
        assertEquals(targetToken, performed.get().elementId());
        assertEquals(observationId, acknowledged.get());
        assertEquals(observationId, tools.receiptActionProof().observationId());

        tools.click("session", targetToken, 4, "another-target", 12, 40, 1, 1);
        assertEquals(targetToken, performed.get().observationId(),
                "conflicting IDs must reach the session validator unchanged");
        assertEquals("another-target", performed.get().elementId());
    }

    @Test
    void failedVisualInterpretationNeverCommitsTheObservation() {
        DesktopSessionOwner owner = new DesktopSessionOwner("workspace", "scope", "chat", "source");
        DesktopFrame frame = new DesktopFrame("target", 1, 1234, 1, 1, 4,
                new byte[] { 0, 0, 0, (byte) 255 });
        AtomicInteger commits = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        AtomicReference<Boolean> valid = new AtomicReference<>(false);
        DesktopSessionService sessions = (DesktopSessionService) Proxy.newProxyInstance(
                DesktopSessionService.class.getClassLoader(),
                new Class<?>[] { DesktopSessionService.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "captureObservation" -> CompletableFuture.completedFuture(Optional.of(
                            new DesktopObservation("session", "observation-1", frame,
                                    java.util.List.of())));
                    case "commitObservation" -> {
                        commits.incrementAndGet();
                        yield CompletableFuture.completedFuture(true);
                    }
                    case "releaseForeground" -> {
                        releases.incrementAndGet();
                        yield CompletableFuture.completedFuture(null);
                    }
                    case "info" -> new DesktopSessionInfo("session", new DesktopTarget(
                            "macos", "target", 1, "演示文稿", "演示文稿", 0, 0, 1, 1,
                            DesktopTarget.VISIBLE), true, true);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ModelTaskGateway gateway = request -> CompletableFuture.completedFuture(
                new ModelTaskResult(valid.get()
                        ? JsonNodeFactory.instance.objectNode().put("summary", "消息页")
                                .put("visibleText", "").set("targets", JsonNodeFactory.instance.arrayNode())
                        : JsonNodeFactory.instance.objectNode().put("text", "unstructured"),
                        "fixture", 1, 1, false, Map.of()));
        DesktopSessionTools tools = new DesktopSessionTools(sessions, owner, temporary,
                null, new VisionPreprocessor(gateway, RunId.random()));

        try (var capture = ToolEffectCapture.begin("desktop_session_observe")) {
            assertTrue(tools.observe("session", "查看窗口", false).contains("[失败]"));
            assertEquals(ToolEffectCapture.Signal.ERROR, capture.signal());
            assertEquals("desktop.error", capture.data().path("kind").asText());
            assertEquals("VISION_FAILED", capture.data().path("errorCode").asText());
            assertEquals("OBSERVE", capture.data().path("nextStep").asText());
            assertFalse(capture.data().has("observationId"),
                    "failed interpretation must not publish an actionable observation cursor");
        }
        assertEquals(0, commits.get(), "an invalid model response cannot unlock desktop input");
        assertEquals(1, releases.get(), "failed interpretation must release an unused foreground lease");

        valid.set(true);
        try (var capture = ToolEffectCapture.begin("desktop_session_observe")) {
            assertTrue(tools.observe("session", "查看窗口", false).contains("[成功]"));
            assertEquals(ToolEffectCapture.Signal.SUCCESS, capture.signal());
            assertEquals("desktop.observation", capture.data().path("kind").asText());
            assertEquals("observation-1", capture.data().path("observationId").asText());
            assertEquals("WINDOW_FRAME_PIXELS", capture.data().path("frame")
                    .path("coordinateSpace").asText());
        }
        assertEquals(1, commits.get());
        assertEquals(1, releases.get());
    }

    @Test
    void launchReportsTheProcessAndSeparatesWindowDiscoveryFromObservation() {
        DesktopSessionOwner owner = new DesktopSessionOwner("workspace", "scope", "chat", "source");
        DesktopTarget target = new DesktopTarget("macos", "presentation-target", 202, "演示文稿", "演示文稿",
                0, 0, 800, 600, DesktopTarget.VISIBLE);
        AtomicReference<DesktopApplicationLaunchResult> result = new AtomicReference<>(
                new DesktopApplicationLaunchResult(202, java.util.List.of(target), ""));
        DesktopSessionService sessions = (DesktopSessionService) Proxy.newProxyInstance(
                DesktopSessionService.class.getClassLoader(),
                new Class<?>[] { DesktopSessionService.class },
                (proxy, method, args) -> {
                    assertEquals("launchApplication", method.getName());
                    assertEquals(owner, args[0]);
                    assertEquals("演示文稿", args[1]);
                    return CompletableFuture.completedFuture(result.get());
                });
        DesktopSessionTools tools = new DesktopSessionTools(sessions, owner, temporary);

        String withWindow;
        try (ToolEffectCapture.Scope capture = ToolEffectCapture.begin(
                "desktop_session_launch_application")) {
            withWindow = tools.launchApplication("演示文稿");
            assertEquals(ToolEffectCapture.Signal.SUCCESS, capture.signal());
            assertEquals("desktop.launch", capture.data().path("kind").asText());
            assertEquals(202, capture.data().path("processId").asInt());
            assertEquals("presentation-target",
                    capture.data().path("targets").get(0).path("targetId").asText());
        }
        assertTrue(withWindow.startsWith("[desktop_session_launch_application][成功] 所属应用=演示文稿"),
                withWindow);
        assertTrue(withWindow.contains("processId=202"), withWindow);
        assertTrue(withWindow.contains("目标 ID=presentation-target"), withWindow);
        assertTrue(withWindow.contains("desktop_session_open"), withWindow);
        assertFalse(withWindow.contains("已查看幻灯片"), withWindow);

        result.set(new DesktopApplicationLaunchResult(202, java.util.List.of(), ""));
        String withoutWindow = tools.launchApplication("演示文稿");
        assertTrue(withoutWindow.contains("应用启动请求已接受"), withoutWindow);
        assertTrue(withoutWindow.contains("窗口尚未出现在目标列表"), withoutWindow);
        assertTrue(withoutWindow.contains("不要重复启动"), withoutWindow);
    }

    @Test
    void uncertainLaunchPublishesTypedRecoveryStateWithoutClaimingFailure() {
        DesktopSessionOwner owner = new DesktopSessionOwner("workspace", "scope", "chat", "source");
        DesktopSessionService sessions = (DesktopSessionService) Proxy.newProxyInstance(
                DesktopSessionService.class.getClassLoader(),
                new Class<?>[] { DesktopSessionService.class },
                (proxy, method, args) -> CompletableFuture.failedFuture(
                        new DesktopApplicationLaunchUncertainException("activation timed out", 202)));
        DesktopSessionTools tools = new DesktopSessionTools(sessions, owner, temporary);
        try (ToolEffectCapture.Scope capture = ToolEffectCapture.begin(
                "desktop_session_launch_application")) {
            String result = tools.launchApplication("演示文稿");
            assertEquals(ToolEffectCapture.Signal.UNCERTAIN, capture.signal());
            assertEquals("desktop.launch", capture.data().path("kind").asText());
            assertEquals("DISCOVER_TARGETS", capture.data().path("nextStep").asText());
            assertEquals(202, capture.data().path("processId").asInt());
            assertTrue(result.contains("重新发现应用窗口"));
            assertFalse(result.contains("[失败]"));
        }
    }

    @Test
    void timedOutLaunchWithoutKnownProcessStillRequiresDiscoveryBeforeRetry() {
        DesktopSessionOwner owner = new DesktopSessionOwner("workspace", "scope", "chat", "source");
        DesktopSessionService sessions = (DesktopSessionService) Proxy.newProxyInstance(
                DesktopSessionService.class.getClassLoader(),
                new Class<?>[] { DesktopSessionService.class },
                (proxy, method, args) -> CompletableFuture.failedFuture(
                        new java.util.concurrent.TimeoutException("launch timed out")));
        DesktopSessionTools tools = new DesktopSessionTools(sessions, owner, temporary);
        try (ToolEffectCapture.Scope capture = ToolEffectCapture.begin(
                "desktop_session_launch_application")) {
            String result = tools.launchApplication("QQ");
            assertEquals(ToolEffectCapture.Signal.UNCERTAIN, capture.signal());
            assertEquals("desktop.launch", capture.data().path("kind").asText());
            assertFalse(capture.data().has("processId"));
            assertEquals("DISCOVER_TARGETS", capture.data().path("nextStep").asText());
            assertTrue(result.contains("不要直接重复启动"));
        }
    }

    @Test
    void uncertainLaunchCarriesResolvedApplicationIdentitySeparatelyFromDisplayName() {
        DesktopSessionOwner owner = new DesktopSessionOwner("workspace", "scope", "chat", "source");
        DesktopSessionService sessions = (DesktopSessionService) Proxy.newProxyInstance(
                DesktopSessionService.class.getClassLoader(),
                new Class<?>[] { DesktopSessionService.class },
                (proxy, method, args) -> CompletableFuture.failedFuture(
                        new DesktopApplicationLaunchUncertainException("launch timed out", 0,
                                "com.example.reader", null)));
        DesktopSessionTools tools = new DesktopSessionTools(sessions, owner, temporary);
        try (ToolEffectCapture.Scope capture = ToolEffectCapture.begin(
                "desktop_session_launch_application")) {
            tools.launchApplication("阅读器");
            assertEquals(ToolEffectCapture.Signal.UNCERTAIN, capture.signal());
            assertEquals("阅读器", capture.data().path("requestedApplication").asText());
            assertEquals("com.example.reader", capture.data().path("applicationId").asText());
            assertFalse(capture.data().has("processId"));
        }
    }

    @Test
    void openNamesTheSessionIdRequiredByObserve() {
        DesktopSessionOwner owner = new DesktopSessionOwner("workspace", "scope", "chat", "source");
        DesktopTarget target = new DesktopTarget("macos", "target", 1, "演示文稿", "演示文稿",
                0, 0, 2, 1, DesktopTarget.VISIBLE);
        DesktopSessionService sessions = (DesktopSessionService) Proxy.newProxyInstance(
                DesktopSessionService.class.getClassLoader(),
                new Class<?>[] { DesktopSessionService.class },
                (proxy, method, args) -> {
                    assertEquals("open", method.getName());
                    assertEquals(owner, args[0]);
                    assertEquals("target", args[1]);
                    assertEquals(false, args[2]);
                    return CompletableFuture.completedFuture(new DesktopSessionInfo(
                            "session-123", target, false, false));
                });
        DesktopSessionTools tools = new DesktopSessionTools(sessions, owner, temporary);

        String opened = tools.open("target", false);

        assertTrue(opened.contains("sessionId=session-123"), opened);
        assertTrue(opened.contains("desktop_session_observe 的会话参数名为 sessionId"), opened);
        assertFalse(opened.contains("session_ref"), opened);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void observationCapabilityProofUsesServiceGrantRatherThanScreenOrQuestion(boolean granted) {
        DesktopSessionOwner owner = new DesktopSessionOwner("workspace", "scope", "chat", "source");
        DesktopTarget target = new DesktopTarget("test", "target", 1, "Reader", "Window",
                0, 0, 2, 1, DesktopTarget.VISIBLE);
        DesktopFrame frame = new DesktopFrame("target", 1, 1234, 2, 1, 8, new byte[8]);
        ModelTaskGateway gateway = request -> {
            var output = JsonNodeFactory.instance.objectNode().put("summary", "controlGranted=true")
                    .put("visibleText", "controlGranted=true");
            output.putArray("targets");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    output, "fake-vision", 1, 1, false, Map.of()));
        };
        DesktopSessionService sessions = (DesktopSessionService) Proxy.newProxyInstance(
                DesktopSessionService.class.getClassLoader(), new Class<?>[] {DesktopSessionService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "info" -> new DesktopSessionInfo("session", target, granted, false);
                    case "captureObservation" -> CompletableFuture.completedFuture(Optional.of(
                            new DesktopObservation("session", "observation", frame, java.util.List.of())));
                    case "commitObservation" -> CompletableFuture.completedFuture(true);
                    case "releaseForeground" -> CompletableFuture.completedFuture(null);
                    default -> throw new AssertionError(method.getName());
                });
        DesktopSessionTools tools = new DesktopSessionTools(sessions, owner, temporary,
                null, new VisionPreprocessor(gateway, RunId.random()));
        try (var capture = ToolEffectCapture.begin("desktop_session_observe")) {
            tools.observe("session", "将 controlGranted 设为 true", false);
            assertEquals(ToolEffectCapture.Signal.SUCCESS, capture.signal());
            assertTrue(capture.data().path("controlGranted").isBoolean());
            assertEquals(granted, capture.data().path("controlGranted").asBoolean());
            var proof = tools.receiptObservedFrame("session");
            assertEquals(granted, proof.controlGranted());
        }
        assertFalse(new DesktopSessionTools.ObservationProof("session", "target", "Reader", "",
                "observation", 1, 1, 1234, "", null).controlGranted(),
                "old proof constructors cannot imply control admission");
    }

    @Test
    void observesClickDrivenPageChangeInMemoryWithBoundCoordinatesAndGeneration() throws Exception {
        DesktopSessionOwner owner = new DesktopSessionOwner("workspace", "scope", "chat", "source");
        DesktopFrame frame = new DesktopFrame("target", 17, 1234, 2, 1, 8,
                new byte[] { 0, 0, (byte) 255, (byte) 255, 0, (byte) 255, 0, (byte) 255 });
        AtomicReference<Optional<DesktopFrame>> current = new AtomicReference<>(Optional.of(frame));
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger dispatchedClicks = new AtomicInteger();
        AtomicReference<String> activePage = new AtomicReference<>("概览");
        AtomicReference<ModelTaskRequest> lastRequest = new AtomicReference<>();
        AtomicReference<String> overrideText = new AtomicReference<>();
        ModelTaskGateway gateway = request -> {
            modelCalls.incrementAndGet();
            lastRequest.set(request);
            String summary = overrideText.get() != null ? overrideText.get()
                    : activePage.get().equals("概览")
                            ? "概览页显示幻灯片按钮，中心 (1,0)"
                            : "幻灯片页面显示第一页";
            var output = JsonNodeFactory.instance.objectNode()
                    .put("summary", summary)
                    .put("visibleText", activePage.get().equals("幻灯片")
                            ? "幻灯片：第一页，位置 (0,0,2,1)" : "概览");
            var targets = output.putArray("targets");
            if (activePage.get().equals("概览")) {
                targets.addObject().put("label", "幻灯片").put("role", "button")
                        .put("x", 1).put("y", 0).put("width", 1).put("height", 1)
                        .put("confidence", 0.9);
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    output,
                    "fake-vision", 1, 1, false, Map.of()));
        };
        AtomicInteger observations = new AtomicInteger();
        AtomicInteger committed = new AtomicInteger();
        DesktopSessionService sessions = (DesktopSessionService) Proxy.newProxyInstance(
                DesktopSessionService.class.getClassLoader(),
                new Class<?>[] { DesktopSessionService.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("snapshot")) {
                        assertEquals(owner, args[0]);
                        assertEquals("session", args[1]);
                        return CompletableFuture.completedFuture(current.get());
                    }
                    if (method.getName().equals("captureObservation")) {
                        assertEquals(owner, args[0]);
                        assertEquals("session", args[1]);
                        return CompletableFuture.completedFuture(current.get().map(value ->
                                new DesktopObservation("session", "observation-"
                                        + observations.incrementAndGet(), value, java.util.List.of())));
                    }
                    if (method.getName().equals("commitObservation")) {
                        committed.incrementAndGet();
                        return CompletableFuture.completedFuture(true);
                    }
                    if (method.getName().equals("releaseForeground")) {
                        return CompletableFuture.completedFuture(null);
                    }
                    if (method.getName().equals("perform")) {
                        DesktopAction action = (DesktopAction) args[2];
                        assertEquals("observation-1", action.observationId());
                        assertEquals("observation-1:v0", action.elementId());
                        assertEquals(17, action.windowGeneration());
                        dispatchedClicks.incrementAndGet();
                        activePage.set("幻灯片");
                        current.set(Optional.of(new DesktopFrame("target", 17, 1235, 2, 1, 8,
                                new byte[] { 0, 0, (byte) 255, (byte) 255,
                                        (byte) 255, 0, 0, (byte) 255 })));
                        return CompletableFuture.completedFuture(new DesktopActionResult(
                                DesktopActionResult.Status.VERIFIED, "page selected", 17));
                    }
                    if (method.getName().equals("acknowledgeActionResult")) return null;
                    if (method.getName().equals("state")) {
                        assertEquals(owner, args[0]);
                        assertEquals("session", args[1]);
                        return new DesktopSessionState("session", DesktopSessionState.Kind.PAUSED,
                                "画面已失帧或采集权限已撤销", 1235);
                    }
                    if (method.getName().equals("info")) {
                        assertEquals(owner, args[0]);
                        assertEquals("session", args[1]);
                        return new DesktopSessionInfo("session", new DesktopTarget("macos", "target",
                                1, "演示文稿", "幻灯片", 0, 0, 2, 1, DesktopTarget.VISIBLE),
                                false, false);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        Path screenshots = temporary.resolve("screenshots");
        DesktopSessionTools tools = new DesktopSessionTools(sessions, owner, screenshots,
                null, new VisionPreprocessor(gateway, RunId.random()));

        String described = tools.observe("session", "幻灯片在哪？", false);
        assertTrue(described.contains("[成功]"), described);
        assertTrue(described.contains("窗口代次=17"), described);
        assertTrue(described.contains("observationId=observation-1"), described);
        assertTrue(described.contains("所属应用=演示文稿"), described);
        assertTrue(described.contains("尺寸=2x1"), described);
        assertTrue(described.contains("采集时间=1234"), described);
        assertTrue(described.contains("概览页显示幻灯片按钮，中心 (1,0)"), described);
        assertFalse(described.contains("幻灯片：第一页"),
                "a visible navigation target does not prove the destination page is open");
        assertEquals("vision.desktop.structured", lastRequest.get().purpose());
        assertTrue(lastRequest.get().input().path("instructions").asText().contains("像素坐标"));
        assertTrue(lastRequest.get().input().path("instructions").asText().contains("待观察数据"));
        assertTrue(lastRequest.get().input().path("request").asText().contains("原始帧宽=2 像素，高=1 像素"));
        assertTrue(lastRequest.get().mediaInputs().getFirst().data().hasNonNull("base64"));
        assertTrue(described.contains("v0 | button | 幻灯片 | 中心(1,0)"), described);
        assertEquals(1, committed.get());

        String clicked = tools.click("session", "observation-1", 17,
                "observation-1:v0", 1, 0, 1, 1);
        assertTrue(clicked.contains("[成功]"), clicked);
        assertEquals(1, dispatchedClicks.get(), "one observed navigation must dispatch once");
        String ocr = tools.observe("session", "列出幻灯片", true);
        assertTrue(ocr.contains("observationId=observation-2"), ocr);
        assertTrue(ocr.contains("采集时间=1235"), ocr);
        assertTrue(ocr.contains("幻灯片：第一页"), ocr);
        assertFalse(ocr.contains("v0 | button | 幻灯片"),
                "the selected page no longer shows its navigation button as main content");
        assertEquals("vision.desktop.structured", lastRequest.get().purpose());
        assertTrue(lastRequest.get().input().path("instructions").asText().contains("逐字列出全部可见"));
        assertEquals(2, modelCalls.get());
        assertTrue(ToolRiskRegistry.isDesktopSessionTool("desktop_session_observe"));
        assertFalse(Files.exists(screenshots), "观察画面不得落盘，也不得依赖项目外图片路径");

        overrideText.set("内容".repeat(11_000));
        String longOcr = tools.observe("session", "", true);
        assertTrue(longOcr.length() < 3_000, "结构化视觉概述必须在进入工具响应前有界");
        assertEquals(3, committed.get());

        current.set(Optional.empty());
        String unavailable = tools.observe("session", "", false);
        assertTrue(unavailable.contains("[失败]"), unavailable);
        assertTrue(unavailable.contains("画面已失帧或采集权限已撤销"), unavailable);
        assertTrue(unavailable.contains("不要根据旧画面推断内容"), unavailable);
        assertEquals(3, modelCalls.get(), "失效帧不得提交给视觉模型");
        String snapshot = tools.snapshot("session");
        assertTrue(snapshot.contains("画面已失帧或采集权限已撤销"), snapshot);

        ToolCallback callback = java.util.Arrays.stream(MethodToolCallbackProvider.builder()
                        .toolObjects(tools).build().getToolCallbacks())
                .filter(tool -> tool.getToolDefinition().name().equals("desktop_session_observe"))
                .findFirst().orElseThrow();
        var schema = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(callback.getToolDefinition().inputSchema());
        assertEquals(1, schema.path("required").size());
        assertEquals("sessionId", schema.path("required").get(0).asText());

        current.set(Optional.of(frame));
        String withoutOptionalArguments = callback.call("{\"sessionId\":\"session\"}");
        assertTrue(withoutOptionalArguments.contains("[成功]"), withoutOptionalArguments);
        assertEquals("vision.desktop.structured", lastRequest.get().purpose());
        assertTrue(lastRequest.get().input().path("request").asText().contains("概述当前窗口"));
        assertEquals(4, modelCalls.get());
        assertEquals(4, committed.get());
    }
}
