package com.javaclaw.agent.automation;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.kernel.AgentLoopKernel;
import com.javaclaw.agent.prompt.PromptHashes;
import com.javaclaw.agent.runtime.ItemSink;
import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.agent.tool.AutomationPlans;
import com.javaclaw.agent.tool.TurnToolSession;
import com.javaclaw.agent.tool.UserInputGateway;
import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.AttemptId;
import com.javaclaw.core.api.ItemId;
import com.javaclaw.core.api.ItemState;
import com.javaclaw.core.api.ModelResponse;
import com.javaclaw.core.api.ModelToolCall;
import com.javaclaw.core.api.ModelUsage;
import com.javaclaw.core.api.StoredItem;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ThreadStatus;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.core.api.ToolExecutionResult;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStatus;
import com.javaclaw.core.api.TurnSteering;
import com.javaclaw.sandbox.api.SandboxPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationExecutionTest {
    @TempDir
    Path temporary;

    private static final String LOOP = """
            {"maxIterations":3,"successCriteria":[{"id":"tests","description":"测试退出码为零",
              "kind":"COMMAND_EXIT","tool":"verify","arguments":{},"expected":"0"}]}
            """;

    @Test
    void loopUsesTheSameKernelAndDoesNotTrustTheModelsSuccessClaim() throws Exception {
        var modelCalls = new AtomicInteger();
        var verificationCalls = new AtomicInteger();
        var opens = new AtomicInteger();
        var kernel = new AgentLoopKernel(
                request -> {
                    modelCalls.incrementAndGet();
                    return new ModelResponse("已经完成", "", List.of(), new ModelUsage(2, 2, 0));
                },
                (context, events) -> {
                    opens.incrementAndGet();
                    return verification(() -> verificationCalls.incrementAndGet() == 1 ? 1 : 0);
                });
        var sink = new RecordingSink();
        kernel.execute(context(AutomationKind.LOOP, LOOP), sink);
        assertEquals(2, modelCalls.get());
        assertEquals(2, verificationCalls.get());
        assertEquals(1, opens.get(), "所有迭代必须复用同一不可变工具快照");
        assertEquals("COMPLETED", sink.lastCheckpoint().status());
        assertEquals(2, sink.lastCheckpoint().iteration());
        assertEquals(
                List.of(false, true),
                sink.values(ThreadItem.Evaluation.class).stream()
                        .map(ThreadItem.Evaluation::passed)
                        .toList());
    }

    @Test
    void failingEvidenceExhaustsTheFiniteLoopWithoutMarkingItComplete() {
        var context = context(AutomationKind.LOOP, LOOP);
        var sink = new RecordingSink();
        var execution = new AutomationExecution(null, null);
        assertThrows(
                ExecutionPausedException.class,
                () -> execution.execute(
                        context, sink, verification(() -> 1), (identity, task, purpose, allowed) -> "助手宣称成功"));
        assertEquals("EXHAUSTED", sink.lastCheckpoint().status());
        assertTrue(sink.values(ThreadItem.Evaluation.class).stream().noneMatch(ThreadItem.Evaluation::passed));
    }

    @Test
    void workflowDeterministicNodesRunWithoutAModelOrArbitraryCode() throws Exception {
        String definition = """
                {"nodes":[
                  {"id":"start","kind":"START","next":"text"},
                  {"id":"text","kind":"TRANSFORM","next":"condition","parameters":{"operation":"constant","value":"真实结果"}},
                  {"id":"condition","kind":"CONDITION","next":"output","otherwise":"end",
                    "parameters":{"input":"text","operator":"equals","expected":"真实结果"}},
                  {"id":"output","kind":"OUTPUT","next":"end","parameters":{"input":"text","name":"结果"}},
                  {"id":"end","kind":"END"}]}
                """;
        var sink = new RecordingSink();
        new AutomationExecution(null, null)
                .execute(
                        context(AutomationKind.WORKFLOW, definition),
                        sink,
                        verification(() -> {
                            throw new AssertionError("不应调用工具");
                        }),
                        (id, task, purpose, allowed) -> {
                            throw new AssertionError("不应调用模型");
                        });
        assertEquals("真实结果", sink.values(ThreadItem.Artifact.class).getFirst().content());
        assertEquals("COMPLETED", sink.lastCheckpoint().status());
        assertEquals(
                List.of("start:1", "text:1", "condition:1", "output:1"),
                sink.lastCheckpoint().completedSteps());
    }

    @Test
    void graphValidationRejectsImplicitCyclesAndExecutableExpressions() {
        assertThrows(IllegalArgumentException.class, () -> AutomationPlans.parse(AutomationKind.WORKFLOW, """
                {"nodes":[{"id":"start","kind":"START","next":"loop"},
                  {"id":"loop","kind":"CONDITION","next":"loop","otherwise":"end",
                   "parameters":{"input":"start","operator":"isEmpty","expected":"true"}},
                  {"id":"end","kind":"END"}]}
                """));
        assertThrows(IllegalArgumentException.class, () -> AutomationPlans.parse(AutomationKind.WORKFLOW, """
                {"nodes":[{"id":"start","kind":"START","next":"code"},
                  {"id":"code","kind":"TRANSFORM","next":"end","parameters":{"operation":"eval","value":"Runtime.exec()"}},
                  {"id":"end","kind":"END"}]}
                """));
        assertThrows(
                IllegalArgumentException.class,
                () -> AutomationPlans.parse(AutomationKind.LOOP, "{\"maxIterations\":-1}"));
        assertEquals(
                25,
                AutomationPlans.parse(AutomationKind.LOOP, "{\"maxIterations\":0}")
                        .limits()
                        .iterations());
    }

    @Test
    void sddMustConfirmSpecificationAndTasksBeforeImplementationAndVerifyBeforeArchive() throws Exception {
        var confirmations = new AtomicInteger();
        var stages = new ArrayList<String>();
        UserInputGateway inputs = (request, announce) -> {
            announce.run();
            confirmations.incrementAndGet();
            assertTrue(request.prompt().contains("SHA-256"));
            return new UserInputGateway.Response("采用", false);
        };
        var sink = new RecordingSink();
        new AutomationExecution(null, inputs)
                .execute(
                        context(AutomationKind.SDD, LOOP.replace("\"maxIterations\":3", "\"maxIterations\":6")),
                        sink,
                        verification(() -> 0),
                        (identity, task, purpose, toolsAllowed) -> {
                            if (toolsAllowed) {
                                assertEquals(2, confirmations.get());
                            }
                            stages.add(identity);
                            return "本阶段的真实产物 " + identity;
                        });
        assertEquals(2, confirmations.get());
        assertEquals(5, stages.size());
        assertEquals(
                "sdd-archive", sink.values(ThreadItem.Artifact.class).getLast().category());
        assertEquals("COMPLETED", sink.lastCheckpoint().status());
    }

    @Test
    void unattendedConfirmationPausesRatherThanInventingApproval() {
        var sink = new RecordingSink();
        var execution = new AutomationExecution(null, null);
        assertThrows(
                ExecutionPausedException.class,
                () -> execution.execute(
                        context(AutomationKind.LOOP, "{}"),
                        sink,
                        verification(() -> 0),
                        (id, task, purpose, allowed) -> "等待验收"));
        assertEquals("WAITING", sink.lastCheckpoint().status());
        assertFalse(sink.values(ThreadItem.Checkpoint.class).stream()
                .anyMatch(value -> "COMPLETED".equals(value.status())));
    }

    private TurnExecutionContext context(AutomationKind kind, String definition) {
        Instant now = Instant.now();
        var threadId = new ThreadId("thread_execution_test");
        var turnId = new TurnId("turn_execution_test");
        var config = new TurnConfig(
                "fake",
                "fake",
                "medium",
                temporary,
                SandboxPolicy.readOnly(Set.of(temporary), Set.of()),
                ApprovalPolicy.ON_RISK,
                Set.of(),
                Map.of(
                        "profileKind",
                        kind.name(),
                        "automationKind",
                        kind.name(),
                        "automationDefinition",
                        definition,
                        "automationDefinitionHash",
                        PromptHashes.sha256(definition),
                        "automationExecutionId",
                        "execution_test",
                        "maxModelCalls",
                        "10",
                        "maxTokens",
                        "200000"));
        var thread = new AgentThread(
                threadId, "workspace_test", null, null, "测试", temporary, ThreadStatus.ACTIVE, 0, 0, 1, now, now);
        var turn = new AgentTurn(
                turnId,
                threadId,
                new AttemptId("attempt_execution_test"),
                TurnStatus.IN_PROGRESS,
                List.of(new TurnInput.Text("完成任务并验证")),
                config,
                null,
                now,
                null);
        return new TurnExecutionContext(thread, turn, List.of(), new AtomicBoolean(), TurnSteering.NONE);
    }

    private static TurnToolSession verification(java.util.function.IntSupplier exitCode) {
        return new TurnToolSession() {
            @Override
            public List<ToolDescriptor> availableTools() {
                return List.of(new ToolDescriptor("verify", "运行测试", "{\"type\":\"object\"}"));
            }

            @Override
            public ToolExecutionResult execute(ModelToolCall call) {
                int code = exitCode.getAsInt();
                return new ToolExecutionResult(
                        new ThreadItem.CommandExecution(List.of("test"), code, "", "", false, false), "exit=" + code);
            }
        };
    }

    private static final class RecordingSink implements ItemSink {
        private final List<StoredItem> items = new ArrayList<>();

        @Override
        public StoredItem append(ThreadItem item) {
            var now = Instant.now();
            var stored = new StoredItem(
                    ItemId.random(),
                    new ThreadId("thread_execution_test"),
                    new TurnId("turn_execution_test"),
                    items.size() + 1L,
                    ItemState.COMPLETED,
                    item,
                    now,
                    now);
            items.add(stored);
            return stored;
        }

        private <T extends ThreadItem> List<T> values(Class<T> type) {
            return items.stream()
                    .map(StoredItem::item)
                    .filter(type::isInstance)
                    .map(type::cast)
                    .toList();
        }

        private ThreadItem.Checkpoint lastCheckpoint() {
            return values(ThreadItem.Checkpoint.class).getLast();
        }
    }
}
