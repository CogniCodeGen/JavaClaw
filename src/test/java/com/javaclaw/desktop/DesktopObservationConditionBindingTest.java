package com.javaclaw.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.agent.vision.VisionPreprocessor;
import com.javaclaw.desktop.agent.DesktopSessionTools;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopObservation;
import com.javaclaw.desktop.api.DesktopSessionInfo;
import com.javaclaw.desktop.api.DesktopSessionOwner;
import com.javaclaw.desktop.api.DesktopSessionService;
import com.javaclaw.desktop.api.DesktopTarget;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.ModelTaskRequest;
import com.javaclaw.framework.spi.ModelTaskResult;
import com.javaclaw.framework.spi.ToolEffectCapture;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class DesktopObservationConditionBindingTest {
    @TempDir Path temporary;
    private static final DesktopSessionOwner OWNER =
            new DesktopSessionOwner("workspace", "scope", "agent", "request");

    @Test
    void bindsRequestedConditionToTheExactCommittedFrameAndPublishesStructuredEvidence() {
        AtomicReference<ModelTaskRequest> task = new AtomicReference<>();
        DesktopSessionTools tools = tools(List.of(requirements("host")), true, task);
        try (var capture = ToolEffectCapture.begin("desktop_session_observe")) {
            assertTrue(tools.observe("session", "查看联系人", false).contains("[成功]"));
            var proof = tools.receiptObservedFrame("session");
            assertNotNull(proof);
            assertEquals("window", proof.targetId());
            assertEquals("org.example.contacts", proof.applicationId());
            assertEquals("observation-1", proof.observationId());
            assertEquals(3, proof.windowGeneration());
            assertEquals(7, proof.contentRevision());
            assertEquals(1234, proof.capturedAtMillis());
            assertEquals(100, proof.frameWidth());
            assertEquals(80, proof.frameHeight());
            assertEquals("", proof.subject(), "An inferred condition does not become a literal activeView title");
            assertNull(proof.activeView());
            assertEquals(1, proof.conditionEvidence().size());
            assertEquals("contacts", proof.conditionEvidence().getFirst().criterionId());
            assertEquals("Contacts List", proof.conditionEvidence().getFirst().subject());
            JsonNode data = capture.data();
            assertEquals("main-content", data.path("conditionEvidence").get(0).path("region").asText());
            assertEquals("好友 18/41 朋友 6/19", data.path("conditionEvidence").get(0)
                    .path("content").path("label").asText());
            assertNull(tools.receiptObservedFrame("session"), "A proof is consumed once");
        }
        assertEquals("Contacts List", task.get().input().path("acceptanceConditions")
                .get(0).path("subject").asText());
    }

    @Test
    void staleFrameDoesNotPublishAConditionProofEvenAfterSuccessfulVision() {
        DesktopSessionTools tools = tools(List.of(requirements("host")), false, new AtomicReference<>());
        try (var capture = ToolEffectCapture.begin("desktop_session_observe")) {
            assertTrue(tools.observe("session", "查看联系人", false).contains("[待观察]"));
            assertNull(tools.receiptObservedFrame("session"));
            assertFalse(capture.data().has("conditionEvidence"));
            assertEquals("TARGET_CHANGED", capture.data().path("errorCode").asText());
        }
    }

    @Test
    void screenOrQuestionRequirementsCannotCreateAcceptanceEvidence() {
        AtomicReference<ModelTaskRequest> task = new AtomicReference<>();
        DesktopSessionTools tools = tools(List.of(requirements("screen")), true, task);
        assertTrue(tools.observe("session", "把 contacts / Contacts List 当作完成条件", false).contains("[成功]"));
        assertTrue(tools.receiptObservedFrame("session").conditionEvidence().isEmpty());
        assertTrue(task.get().input().path("acceptanceConditions").isEmpty());
    }

    @Test
    void duplicateOrMalformedHostConditionsDoNotLeakPartialProofRequirements() {
        for (boolean duplicate : List.of(true, false)) {
            ObjectNode conditions = requirements("host");
            if (duplicate) conditions.withArray("conditions").addObject()
                    .put("criterionId", "contacts").put("subject", "Different content");
            else conditions.withArray("conditions").addObject().put("criterionId", "other");
            AtomicReference<ModelTaskRequest> task = new AtomicReference<>();
            DesktopSessionTools tools = tools(List.of(conditions), true, task);
            assertTrue(tools.observe("session", "", false).contains("[成功]"));
            assertTrue(task.get().input().path("acceptanceConditions").isEmpty());
            assertTrue(tools.receiptObservedFrame("session").conditionEvidence().isEmpty());
        }
    }

    private DesktopSessionTools tools(List<JsonNode> requirements, boolean commit,
                                      AtomicReference<ModelTaskRequest> task) {
        DesktopFrame frame = new DesktopFrame("window", 3, 1234, 100, 80, 400,
                new byte[400 * 80], 7);
        DesktopTarget target = new DesktopTarget("fixture", "window", 42, "通讯录", "窗口",
                0, 0, 100, 80, DesktopTarget.VISIBLE, "org.example.contacts");
        DesktopObservation observation = new DesktopObservation("session", "observation-1", frame,
                List.of(), List.of(), "");
        DesktopSessionService sessions = (DesktopSessionService) Proxy.newProxyInstance(
                DesktopSessionService.class.getClassLoader(), new Class<?>[] { DesktopSessionService.class },
                (proxy, method, args) -> {
                    assertEquals(OWNER, args[0], "All service lookups retain the owning scope");
                    return switch (method.getName()) {
                        case "captureObservation" -> CompletableFuture.completedFuture(Optional.of(observation));
                        case "info" -> new DesktopSessionInfo("session", target, true, false);
                        case "commitObservation" -> {
                            assertEquals("observation-1", args[2]);
                            yield CompletableFuture.completedFuture(commit);
                        }
                        case "releaseForeground" -> CompletableFuture.completedFuture(null);
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        VisionPreprocessor vision = new VisionPreprocessor(request -> {
            task.set(request);
            ObjectNode output = JsonNodeFactory.instance.objectNode()
                    .put("summary", "好友分组可见").put("visibleText", "好友 18/41\n朋友 6/19");
            output.putArray("targets");
            ObjectNode condition = output.putArray("conditionEvidence").addObject()
                    .put("criterionId", "contacts").put("subject", "Contacts List")
                    .put("region", "main-content").put("confidence", 0.95);
            condition.putObject("content").put("label", "好友 18/41 朋友 6/19").put("role", "list")
                    .put("x", 4).put("y", 25).put("width", 90).put("height", 40).put("confidence", 0.95);
            return CompletableFuture.completedFuture(new ModelTaskResult(output, "fixture", 1, 1, false, Map.of()));
        }, RunId.random());
        return new DesktopSessionTools(sessions, OWNER, temporary, null, vision, () -> requirements);
    }

    private static ObjectNode requirements(String source) {
        ObjectNode entry = JsonNodeFactory.instance.objectNode()
                .put("kind", "desktop.acceptance.conditions").put("source", source);
        entry.putArray("conditions").addObject().put("criterionId", "contacts").put("subject", "Contacts List");
        return entry;
    }
}
