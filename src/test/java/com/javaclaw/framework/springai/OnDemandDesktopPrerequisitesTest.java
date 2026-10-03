package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.StepId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnDemandDesktopPrerequisitesTest {
    @Test
    void uncertainLaunchAdvancesFromRediscoveryToOpenOnlyWithTrustedMatchingReceipt() {
        RunId run = new RunId("desktop-recovery");
        ObjectNode launch = JsonNodeFactory.instance.objectNode()
                .put("schemaVersion", 1).put("kind", "desktop.launch")
                .put("requestedApplication", "QQ").put("processId", 202);
        launch.putArray("targets");
        AgentStep uncertain = tool(run, 1, "launch", "desktop_session_launch_application",
                "UNCERTAIN", launch, "application", "QQ");
        ObjectNode targets = targetList();
        targets.withArray("targets").addObject()
                .put("targetId", "qq-chat").put("processId", 202)
                .put("application", "QQ").put("visible", true);
        AgentStep discovered = tool(run, 3, "targets", "desktop_session_targets",
                "SUCCEEDED", targets, null, null);

        assertEquals("desktop_session_targets",
                OnDemandDesktopPrerequisites.pendingDesktopPrerequisite(List.of(uncertain), List.of()));
        assertEquals("desktop_session_targets",
                OnDemandDesktopPrerequisites.pendingDesktopPrerequisite(
                        List.of(uncertain, discovered), List.of()));
        assertEquals("desktop_session_open",
                OnDemandDesktopPrerequisites.pendingDesktopPrerequisite(
                        List.of(uncertain, discovered),
                        List.of(receipt(run, 4, "targets", "desktop_session_targets", "OBSERVED"))));
    }

    @Test
    void rediscoveryFindsEitherWindowOfTheLaunchedProcessDespiteMatchingSystemTitle() {
        ObjectNode targets = targetList();
        targets.withArray("targets").addObject()
                .put("targetId", "status-item").put("processId", 101)
                .put("application", "系统界面").put("title", "QQ")
                .put("visible", true).put("systemSurface", true);
        targets.withArray("targets").addObject()
                .put("targetId", "qq-chat").put("processId", 202)
                .put("application", "QQ").put("title", "会话")
                .put("visible", true);
        targets.withArray("targets").addObject()
                .put("targetId", "qq-settings").put("processId", 202)
                .put("application", "QQ").put("title", "设置")
                .put("visible", true);

        assertTrue(OnDemandDesktopPrerequisites.launchTargetDiscovered(202, "QQ", targets));
        assertFalse(OnDemandDesktopPrerequisites.launchTargetDiscovered(303, "QQ", targets),
                "a known PID must not fall back to a matching title or application name");
    }

    @Test
    void unknownProcessUsesExactOwningApplicationAndRequiresStructuredVisibleTarget() {
        ObjectNode targets = targetList();
        targets.withArray("targets").addObject()
                .put("targetId", "status-item").put("processId", 101)
                .put("application", "系统界面").put("title", "QQ")
                .put("visible", true);
        assertFalse(OnDemandDesktopPrerequisites.launchTargetDiscovered(0, "QQ", targets));

        targets.withArray("targets").addObject()
                .put("targetId", "qq-hidden").put("processId", 202)
                .put("application", "QQ").put("title", "会话")
                .put("visible", false);
        assertFalse(OnDemandDesktopPrerequisites.launchTargetDiscovered(0, "QQ", targets));
        ((ObjectNode) targets.withArray("targets").get(1)).put("visible", true);
        assertTrue(OnDemandDesktopPrerequisites.launchTargetDiscovered(0, "QQ", targets));

        targets.put("kind", "desktop.observation");
        assertFalse(OnDemandDesktopPrerequisites.launchTargetDiscovered(0, "QQ", targets));
    }

    @Test
    void uncertainBundleLaunchMatchesStableOwnerIdAcrossDisplayNamesAndWindows() {
        RunId run = new RunId("bundle-recovery");
        ObjectNode launch = JsonNodeFactory.instance.objectNode()
                .put("schemaVersion", 1).put("kind", "desktop.launch")
                .put("requestedApplication", "com.example.reader")
                .put("applicationId", "com.example.reader");
        launch.putArray("targets");
        AgentStep uncertain = tool(run, 1, "launch", "desktop_session_launch_application",
                "UNCERTAIN", launch, "application", "com.example.reader");
        ObjectNode targets = targetList();
        targets.withArray("targets").addObject()
                .put("targetId", "system-item").put("processId", 101)
                .put("application", "系统界面").put("applicationId", "com.example.reader")
                .put("visible", true).put("systemSurface", true);
        targets.withArray("targets").addObject()
                .put("targetId", "reader-settings").put("processId", 202)
                .put("application", "阅读器").put("applicationId", "com.example.reader")
                .put("visible", true);
        AgentStep discovered = tool(run, 3, "targets", "desktop_session_targets",
                "SUCCEEDED", targets, null, null);
        assertEquals("desktop_session_open",
                OnDemandDesktopPrerequisites.pendingDesktopPrerequisite(
                        List.of(uncertain, discovered),
                        List.of(receipt(run, 4, "targets", "desktop_session_targets", "OBSERVED"))));
        assertFalse(OnDemandDesktopPrerequisites.launchTargetDiscovered(0,
                "com.example.reader", "com.example.other", targets));
    }

    @Test
    void knownLauncherPidCanFindTheVerifiedChildApplicationButNotANameOnlyMatch() {
        ObjectNode targets = targetList();
        targets.withArray("targets").addObject()
                .put("targetId", "reader").put("processId", 303)
                .put("application", "阅读器").put("applicationId", "com.example.reader")
                .put("visible", true);
        assertTrue(OnDemandDesktopPrerequisites.launchTargetDiscovered(202,
                "阅读器", "com.example.reader", targets));
        assertFalse(OnDemandDesktopPrerequisites.launchTargetDiscovered(202,
                "阅读器", "com.example.other", targets));
    }

    private static ObjectNode targetList() {
        ObjectNode data = JsonNodeFactory.instance.objectNode()
                .put("schemaVersion", 1).put("kind", "desktop.targets");
        data.putArray("targets");
        return data;
    }

    private static AgentStep tool(RunId run, long sequence, String invocation,
            String name, String status, ObjectNode data, String argumentName, String argument) {
        ObjectNode input = JsonNodeFactory.instance.objectNode()
                .put("tool", name).put("invocationId", invocation);
        ObjectNode arguments = input.putObject("arguments");
        if (argumentName != null) arguments.put(argumentName, argument);
        ObjectNode output = JsonNodeFactory.instance.objectNode().put("status", status);
        output.set("rawOutput", data);
        return new AgentStep(StepId.tool(run, invocation), "thread", run,
                AgentStep.Kind.TOOL, AgentStep.State.COMPLETED, null, input, output,
                null, null, Instant.EPOCH, Instant.EPOCH, sequence, sequence + 1);
    }

    private static RunEventEnvelope receipt(RunId run, long sequence, String invocation,
            String name, String status) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode()
                .put("invocationId", invocation).put("tool", name).put("status", status);
        return new RunEventEnvelope(run.value(), sequence, Instant.EPOCH,
                "core.tool.receipt", 1, "framework.core", "test", "test", payload);
    }
}
