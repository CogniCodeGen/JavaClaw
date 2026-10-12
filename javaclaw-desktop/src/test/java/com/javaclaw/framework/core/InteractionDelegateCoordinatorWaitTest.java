package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.spi.EffectReceiptV1;
import com.javaclaw.framework.spi.RunCancelledException;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.ToolContext;
import com.javaclaw.framework.spi.ToolExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionDelegateCoordinatorWaitTest {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final RunId RUN = new RunId("desktop-wait-run");
    private static final String TOOL = "desktop_session_observe";
    private static final String OBSERVATION_INVOCATION = "observe-call";
    private static final String WAIT_INVOCATION = "wait-call";
    private static final Instant START = Instant.parse("2026-10-09T04:00:00Z");
    private static final long CAPTURED = START.plusMillis(2_500).toEpochMilli();

    @Test
    @DisplayName("省略等待模式时持久化有界等待，并保留可信观察基线及调用参数")
    void omittedModeSuspendsWithBoundedModeAndExactBaseline() {
        Fixture fixture = fixture(events());
        ObjectNode input = input();

        Instant beforeAdmission = Instant.now();
        JsonNode wait = assertThrows(InteractionEventWaitRequiredException.class,
                () -> fixture.coordinator().waitForEvent(fixture.context(), fixture.execution(), input)).context();
        Instant afterAdmission = Instant.now();

        assertEquals("interaction.waiting_event", wait.path("kind").asText());
        assertEquals("BOUNDED", wait.path("waitMode").asText());
        assertEquals(WAIT_INVOCATION, wait.path("invocationId").asText());
        assertEquals("session", wait.path("sessionId").asText());
        assertEquals(CAPTURED, wait.path("afterCapturedAtMillis").asLong());
        assertEquals(4, wait.path("afterWindowGeneration").asLong());
        assertEquals(7, wait.path("afterContentRevision").asLong());
        assertEquals(15_000, wait.path("timeoutMillis").asLong());
        Instant waitDeadline = Instant.parse(wait.path("waitDeadline").asText());
        assertFalse(waitDeadline.isBefore(beforeAdmission.plusMillis(15_000)));
        assertFalse(waitDeadline.isAfter(afterAdmission.plusMillis(15_000)));
        assertFalse(waitDeadline.isAfter(fixture.execution().deadline()));
        assertFalse(input.has("waitMode"), "解析等待模式不得改写工具输入");
    }

    @Test
    @DisplayName("显式持续监听模式保存在挂起上下文中")
    void explicitUntilChangeModeIsPreserved() {
        Fixture fixture = fixture(events());

        JsonNode wait = assertThrows(InteractionEventWaitRequiredException.class,
                () -> fixture.coordinator().waitForEvent(fixture.context(), fixture.execution(),
                        input().put("waitMode", "UNTIL_CHANGE"))).context();

        assertEquals("UNTIL_CHANGE", wait.path("waitMode").asText());
        assertEquals(15_000, wait.path("timeoutMillis").asLong());
        assertEquals(CAPTURED, wait.path("afterCapturedAtMillis").asLong());
        assertFalse(wait.has("waitDeadline"), "持续监听不受单次轮询超时截止时间限制");
    }

    @Test
    @DisplayName("有界等待截止时间不得晚于所属工具调用的任务期限")
    void ownerDeadlineBoundsWaitDeadline() {
        Fixture fixture = fixture(events());
        Instant ownerDeadline = Instant.now().plusSeconds(1);
        ToolExecutionContext execution = new ToolExecutionContext(RUN, WAIT_INVOCATION,
                () -> false, ownerDeadline);

        JsonNode wait = assertThrows(InteractionEventWaitRequiredException.class,
                () -> fixture.coordinator().waitForEvent(fixture.context(), execution, input())).context();

        assertEquals("BOUNDED", wait.path("waitMode").asText());
        assertEquals(ownerDeadline, Instant.parse(wait.path("waitDeadline").asText()));
    }

    @Test
    @DisplayName("缺失或不匹配的可信观察基线直接拒绝，不挂起执行")
    void missingOrMismatchedBaselineReturnsClosedRejection() {
        Fixture withoutObservation = fixture(events().subList(0, 2));
        assertBaselineRejected(withoutObservation, input());

        Fixture withObservation = fixture(events());
        assertBaselineRejected(withObservation, input().put("sessionId", "other-session"));
        assertBaselineRejected(withObservation, input().put("afterCapturedAtMillis", CAPTURED - 1));

        List<RunEventEnvelope> untrusted = events();
        untrusted.set(2, event(3, "core.tool.started", 1,
                ((ObjectNode) untrusted.get(2).payload()).put("trustedDesktopTool", false)));
        assertBaselineRejected(fixture(untrusted), input());
    }

    @Test
    @DisplayName("更新任务契约或重新选择模式后，旧观察不能授权等待")
    void newerContractOrModeBoundaryInvalidatesObservation() {
        for (String type : List.of("core.task.contract_revised", "core.interaction.mode_selected")) {
            List<RunEventEnvelope> history = events();
            boolean contract = type.equals("core.task.contract_revised");
            history.add(event(6, type, contract ? 3 : 1,
                    contract ? NODES.objectNode().put("version", 3)
                            : NODES.objectNode().put("mode", "DESKTOP")));

            assertBaselineRejected(fixture(history), input());
        }
    }

    @Test
    @DisplayName("非法等待模式不能进入挂起状态")
    void invalidModesAreRejectedBeforeSuspension() {
        Fixture fixture = fixture(events());
        for (JsonNode mode : invalidModes()) {
            ObjectNode input = input();
            input.set("waitMode", mode);

            assertThrows(IllegalArgumentException.class,
                    () -> fixture.coordinator().waitForEvent(fixture.context(), fixture.execution(), input),
                    mode.toString());
        }
    }

    @Test
    @DisplayName("取消后的工具调用不能创建等待")
    void cancelledExecutionCannotSuspend() {
        Fixture fixture = fixture(events());
        ToolExecutionContext cancelled = new ToolExecutionContext(RUN, WAIT_INVOCATION,
                () -> true, fixture.execution().deadline());

        assertThrows(RunCancelledException.class,
                () -> fixture.coordinator().waitForEvent(fixture.context(), cancelled, input()));
    }

    @Test
    @DisplayName("新调用默认有界，旧持久化等待保留持续监听的兼容语义")
    void missingModeKeepsStoredWaitCompatibility() {
        assertEquals(InteractionEventWaitMode.BOUNDED,
                InteractionEventWaitMode.forInvocation(NODES.objectNode()));
        assertEquals(InteractionEventWaitMode.UNTIL_CHANGE,
                InteractionEventWaitMode.forStoredWait(NODES.objectNode()));

        for (InteractionEventWaitMode mode : InteractionEventWaitMode.values()) {
            ObjectNode value = NODES.objectNode().put("waitMode", mode.name());
            assertEquals(mode, InteractionEventWaitMode.forInvocation(value));
            assertEquals(mode, InteractionEventWaitMode.forStoredWait(value));
        }
        for (JsonNode mode : invalidModes()) {
            ObjectNode value = NODES.objectNode();
            value.set("waitMode", mode);
            assertThrows(IllegalArgumentException.class, () -> InteractionEventWaitMode.forStoredWait(value));
        }
    }

    private static void assertBaselineRejected(Fixture fixture, ObjectNode input) {
        JsonNode rejected = fixture.coordinator().waitForEvent(fixture.context(), fixture.execution(), input);
        assertEquals("interaction.wait_rejected", rejected.path("kind").asText());
        assertEquals("WAIT_BASELINE_REQUIRED", rejected.path("errorCode").asText());
        for (String flag : List.of("dispatchAttempted", "subscriptionCreated", "inputAuthority", "acceptanceEvidence")) {
            assertTrue(rejected.path(flag).isBoolean(), flag);
            assertFalse(rejected.path(flag).booleanValue(), flag);
        }
    }

    private static List<JsonNode> invalidModes() {
        return List.of(NODES.textNode(""), NODES.textNode("bounded"), NODES.textNode("UNKNOWN"),
                NODES.textNode(" BOUNDED"), NODES.nullNode(), NODES.booleanNode(true),
                NODES.numberNode(1), NODES.objectNode(), NODES.arrayNode());
    }

    private static Fixture fixture(List<RunEventEnvelope> history) {
        RunStore store = (RunStore) Proxy.newProxyInstance(RunStore.class.getClassLoader(),
                new Class<?>[]{RunStore.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("eventsAfter")) {
                        assertEquals(RUN, arguments[0]);
                        assertEquals(0L, arguments[1]);
                        return List.copyOf(history);
                    }
                    throw new AssertionError("等待基线检查不得调用存储写入或其他接口: " + method.getName());
                });
        RunRequest request = RunRequest.builder()
                .agent(AgentDefinitionRef.latest("system.interaction-executor"))
                .profile(RunProfileRef.latest("interaction-executor"))
                .source(new InvocationSource("interaction", "wait-test"))
                .scope(new RunScope("workspace", "user", "interaction-session"))
                .input(InputBlock.text("等待界面变化"))
                .attributes(Map.of(InteractionExecutionPolicy.ALLOWED_MODES_ATTRIBUTE,
                                NODES.arrayNode().add("DESKTOP"),
                        InteractionExecutionPolicy.INITIAL_MODE_ATTRIBUTE, NODES.textNode("DESKTOP")))
                .build();
        Instant ownerDeadline = Instant.now().plusSeconds(60);
        ToolContext context = new ToolContext(RUN, request.scope(), PermissionSet.NONE,
                () -> false, ownerDeadline, request);
        ToolExecutionContext execution = new ToolExecutionContext(RUN, WAIT_INVOCATION,
                () -> false, ownerDeadline);
        InteractionDelegateCoordinator coordinator = new InteractionDelegateCoordinator(() -> null,
                store, new ObjectMapper(), new RunUsageLedger(), (parent, child) -> {
                    throw new AssertionError("事件等待不得启动子任务");
                });
        return new Fixture(coordinator, context, execution);
    }

    private static ObjectNode input() {
        return NODES.objectNode().put("sessionId", "session")
                .put("afterCapturedAtMillis", CAPTURED).put("timeoutMillis", 15_000);
    }

    private static List<RunEventEnvelope> events() {
        ObjectNode arguments = NODES.objectNode().put("sessionId", "session")
                .put("question", "当前窗口显示了什么？");
        String fingerprint = ToolInvocationFingerprint.create(TOOL, arguments);
        ObjectNode started = NODES.objectNode().put("tool", TOOL)
                .put("invocationId", OBSERVATION_INVOCATION).put("trustedDesktopTool", true)
                .put("fingerprint", fingerprint);
        started.set("arguments", arguments);
        ObjectNode completed = NODES.objectNode().put("tool", TOOL)
                .put("invocationId", OBSERVATION_INVOCATION).put("status", "SUCCEEDED");
        completed.set("output", rawObservation());
        ObjectNode receipt = receipt().put("fingerprint", fingerprint);
        return new ArrayList<>(List.of(
                event(1, "core.task.contract", 3, NODES.objectNode().put("version", 3)),
                event(2, "core.interaction.mode_selected", 1, NODES.objectNode().put("mode", "DESKTOP")),
                event(3, "core.tool.started", 1, started),
                event(4, "core.tool.completed", 2, completed),
                event(5, "core.tool.receipt", 1, receipt)));
    }

    private static ObjectNode rawObservation() {
        ObjectNode raw = NODES.objectNode().put("schemaVersion", 1).put("protocol", "computer-use")
                .put("kind", "desktop.observation").put("sessionId", "session")
                .put("targetId", "window").put("application", "Example")
                .put("applicationId", "com.example.reader").put("observationId", "observation")
                .put("windowGeneration", 4).put("contentRevision", 7).put("capturedAtMillis", CAPTURED);
        raw.putObject("frame").put("targetId", "window").put("windowGeneration", 4)
                .put("contentRevision", 7).put("capturedAtMillis", CAPTURED)
                .put("width", 800).put("height", 600).put("coordinateSpace", "WINDOW_FRAME_PIXELS");
        raw.putObject("content").put("summary", "消息列表与联系人入口可见");
        return raw;
    }

    private static ObjectNode receipt() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("sessionId", "session");
        metadata.put("targetId", "window");
        metadata.put("observationId", "observation");
        metadata.put("applicationId", "com.example.reader");
        metadata.put("windowGeneration", "4");
        metadata.put("contentRevision", "7");
        metadata.put("capturedAtMillis", Long.toString(CAPTURED));
        return new EffectReceiptV1(OBSERVATION_INVOCATION, TOOL, "observe", "Example",
                EffectReceiptV1.Status.OBSERVED, START.plusMillis(2_800),
                "core.tool.completed:" + RUN.value() + ":" + OBSERVATION_INVOCATION,
                "owned desktop frame observed", "", metadata).toJson();
    }

    private static RunEventEnvelope event(long sequence, String type, int version, JsonNode payload) {
        return new RunEventEnvelope(RUN.value(), sequence, START.plusSeconds(sequence - 1), type,
                version, "framework.core", null, null, payload);
    }

    private record Fixture(InteractionDelegateCoordinator coordinator, ToolContext context,
                           ToolExecutionContext execution) { }
}
