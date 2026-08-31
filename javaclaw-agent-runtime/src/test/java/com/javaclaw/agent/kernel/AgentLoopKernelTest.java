package com.javaclaw.agent.kernel;

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

import com.javaclaw.agent.context.AttachmentInputResolver;
import com.javaclaw.agent.model.ContextWindowExceededException;
import com.javaclaw.agent.model.ModelGateway;
import com.javaclaw.agent.prompt.AgentsInstructionResolver;
import com.javaclaw.agent.prompt.AgentsInstructionSettings;
import com.javaclaw.agent.runtime.ItemSink;
import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.agent.tool.TurnToolSession;
import com.javaclaw.agent.tool.TurnToolSessionFactory;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentLoopKernelTest {
    @TempDir
    Path temporary;

    @Test
    void providerErrorCannotEchoCredentialsIntoThePersistentFailure() {
        AgentLoopKernel kernel = new AgentLoopKernel(
                request -> {
                    throw new IllegalArgumentException("Authorization: Bearer PRIVATE_TOKEN");
                },
                (turn, sink) -> noTools());
        var error = assertThrows(IllegalStateException.class, () -> kernel.execute(context(), ignored -> null));
        org.junit.jupiter.api.Assertions.assertFalse(error.getMessage().contains("PRIVATE_TOKEN"));
        org.junit.jupiter.api.Assertions.assertNull(error.getCause());
    }

    private static final String VALID_PLAN = """
            {"goal":"保留 UI 并升级内核","scope":"仅技术架构","steps":["核对旧 UI 基线","执行回归"],
             "dependencies":["先建立视觉基线"],"acceptanceCriteria":["旧主题颜色与布局一致"],"risks":[],"openQuestions":[]}
            """;

    @Test
    void validatesStructuredPlanAndAllowsOnlyOneBudgetedRepair() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<ThreadItem> items = new ArrayList<>();
        AgentLoopKernel kernel = new AgentLoopKernel(
                request -> {
                    assertEquals(List.of(), request.tools());
                    return new ModelResponse(
                            calls.incrementAndGet() == 1 ? "[PLAN_COMPLETE]" : VALID_PLAN,
                            "",
                            List.of(),
                            new ModelUsage(10, 10, 0));
                },
                (turn, sink) -> noTools());
        TurnExecutionContext context = context(Map.of("profileKind", "PLAN", "maxModelCalls", "2"));
        kernel.execute(context, item -> {
            items.add(item);
            return stored(item, items.size());
        });
        assertEquals(2, calls.get());
        assertEquals(2, context.scope().budget().usedCalls());
        var plan = org.junit.jupiter.api.Assertions.assertInstanceOf(ThreadItem.Plan.class, items.getLast());
        assertEquals("仅技术架构", plan.details().scope());
        assertEquals(List.of("旧主题颜色与布局一致"), plan.details().acceptanceCriteria());
    }

    @Test
    void invalidSecondPlanCannotBecomeACompletedArtifact() {
        AtomicInteger calls = new AtomicInteger();
        List<ThreadItem> items = new ArrayList<>();
        AgentLoopKernel kernel = new AgentLoopKernel(
                request -> {
                    calls.incrementAndGet();
                    return new ModelResponse("{\"steps\":[]}", "", List.of(), ModelUsage.ZERO);
                },
                (turn, sink) -> noTools());
        assertThrows(
                IllegalArgumentException.class,
                () -> kernel.execute(context(Map.of("profileKind", "PLAN")), item -> {
                    items.add(item);
                    return null;
                }));
        assertEquals(2, calls.get());
        org.junit.jupiter.api.Assertions.assertTrue(items.stream().noneMatch(ThreadItem.Plan.class::isInstance));
    }

    @Test
    void promptOptimizationNeverOpensToolsOrContextContributors() throws Exception {
        AgentLoopKernel kernel = new AgentLoopKernel(
                request -> {
                    assertEquals(List.of(), request.tools());
                    return new ModelResponse(
                            "{\"draft\":\"保留用户目标与既有 UI\",\"changes\":[\"明确任务范围\"],\"warnings\":[]}",
                            "",
                            List.of(),
                            ModelUsage.ZERO);
                },
                (turn, sink) -> {
                    throw new AssertionError("optimization must not discover tools");
                },
                List.of(context -> {
                    throw new AssertionError("optimization must not read private context");
                }));
        List<ThreadItem> items = new ArrayList<>();
        kernel.execute(
                context(Map.of(
                        "invocationPurpose", "PROMPT_OPTIMIZATION", "profileId", "original", "profileRevision", "4")),
                item -> {
                    items.add(item);
                    return stored(item, items.size());
                });
        var draft = org.junit.jupiter.api.Assertions.assertInstanceOf(ThreadItem.PromptDraft.class, items.getLast());
        assertEquals(4, draft.expectedRevision());
        assertEquals("original", draft.profileId());
    }

    @Test
    void rejectsInputThatCannotFitTheFiniteTokenBudgetBeforeCallingProvider() {
        AgentLoopKernel kernel = new AgentLoopKernel(
                request -> {
                    throw new AssertionError("over-budget request reached provider");
                },
                (turn, sink) -> noTools());
        assertThrows(
                com.javaclaw.agent.runtime.BudgetExceededException.class,
                () -> kernel.execute(context(Map.of("maxTokens", "1")), ignored -> null));
    }

    @Test
    void automaticallyCompactsWithFreeTextAndSamplesAgainWithoutToolsOrSchemaRepair() throws Exception {
        Path configuration = java.nio.file.Files.createDirectories(temporary.resolve("config"));
        java.nio.file.Files.writeString(configuration.resolve("AGENTS.md"), "AGENT_EXACT：不得改写缓存项目指令");
        var resolver = new AgentsInstructionResolver(configuration, AgentsInstructionSettings::defaults);
        AtomicInteger calls = new AtomicInteger();
        List<com.javaclaw.core.api.ModelRequest> requests = new ArrayList<>();
        List<ThreadItem> items = new ArrayList<>();
        AgentLoopKernel kernel = new AgentLoopKernel(
                request -> {
                    requests.add(request);
                    int call = calls.incrementAndGet();
                    if (call == 1) {
                        assertEquals(List.of(), request.tools());
                        org.junit.jupiter.api.Assertions.assertTrue(request.messages().stream()
                                .anyMatch(message -> message.content().contains("CONTEXT CHECKPOINT COMPACTION")));
                        org.junit.jupiter.api.Assertions.assertTrue(request.messages().stream()
                                .noneMatch(message -> message.content().contains("AGENT_EXACT")));
                        return new ModelResponse(
                                "进展：已读取需求。\n约束：保持原策略。\n待办：继续采样。", "", List.of(), new ModelUsage(20, 10, 0));
                    }
                    org.junit.jupiter.api.Assertions.assertTrue(request.messages().stream()
                            .anyMatch(message -> message.role() == com.javaclaw.core.api.ModelMessage.Role.USER
                                    && message.content().contains("另一个语言模型已经开始解决此问题")));
                    assertEquals(
                            1,
                            request.messages().stream()
                                    .filter(message -> message.role() == com.javaclaw.core.api.ModelMessage.Role.USER)
                                    .filter(message -> message.content().contains("AGENT_EXACT：不得改写缓存项目指令"))
                                    .count());
                    return new ModelResponse("压缩后完成", "", List.of(), new ModelUsage(10, 5, 0));
                },
                (turn, sink) -> noTools(),
                List.of(),
                AttachmentInputResolver.UNAVAILABLE,
                null,
                null,
                null,
                resolver);

        kernel.execute(
                context(Map.of(
                        "modelContextWindowTokens", "1000",
                        "modelAutoCompactTokenLimit", "1",
                        "maxModelCalls", "2")),
                item -> {
                    items.add(item);
                    return stored(item, items.size());
                });

        assertEquals(2, calls.get());
        assertEquals(2, requests.size());
        assertEquals(
                List.of("contextCompaction", "agentMessage"),
                items.stream().map(ThreadItem::kind).toList());
        assertEquals(2, requests.getLast().conversationStartIndex());
    }

    @Test
    void checksCompactionAgainBeforeContinuingTheToolLoop() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<ThreadItem> items = new ArrayList<>();
        AgentLoopKernel kernel = new AgentLoopKernel(
                request -> {
                    int call = calls.incrementAndGet();
                    if (call == 1 || call == 3) {
                        assertEquals(List.of(), request.tools());
                        org.junit.jupiter.api.Assertions.assertTrue(request.messages().stream()
                                .anyMatch(message -> message.content().contains("CONTEXT CHECKPOINT COMPACTION")));
                        return new ModelResponse("进展：已建立检查点。\n待办：继续当前工具循环。", "", List.of(), ModelUsage.ZERO);
                    }
                    if (call == 2) {
                        return new ModelResponse(
                                "",
                                "",
                                List.of(new ModelToolCall("call-1", "echo", "{\"text\":\"hi\"}")),
                                ModelUsage.ZERO);
                    }
                    return new ModelResponse("连续压缩后完成", "", List.of(), ModelUsage.ZERO);
                },
                (turn, events) -> new TurnToolSession() {
                    @Override
                    public List<ToolDescriptor> availableTools() {
                        return List.of(new ToolDescriptor("echo", "echo", "{\"type\":\"object\"}"));
                    }

                    @Override
                    public ToolExecutionResult execute(ModelToolCall call) {
                        return new ToolExecutionResult(
                                new ThreadItem.DynamicToolCall("echo", Map.of("text", "hi")), "hi");
                    }
                });

        kernel.execute(
                context(Map.of(
                        "modelContextWindowTokens", "1000",
                        "modelAutoCompactTokenLimit", "1",
                        "maxModelCalls", "4")),
                item -> {
                    items.add(item);
                    return stored(item, items.size());
                });

        assertEquals(4, calls.get());
        assertEquals(
                List.of("contextCompaction", "dynamicToolCall", "contextCompaction", "agentMessage"),
                items.stream().map(ThreadItem::kind).toList());
    }

    @Test
    void contextWindowErrorTriggersOnlyOneRecoveryCompactionAndOneResample() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<ThreadItem> items = new ArrayList<>();
        AgentLoopKernel kernel = new AgentLoopKernel(
                request -> {
                    int call = calls.incrementAndGet();
                    if (call == 1) {
                        throw new ContextWindowExceededException("fake context limit");
                    }
                    if (call == 2) {
                        assertEquals(List.of(), request.tools());
                        return new ModelResponse("进展：恢复压缩成功。\n待办：重试一次。", "", List.of(), ModelUsage.ZERO);
                    }
                    return new ModelResponse("恢复后完成", "", List.of(), ModelUsage.ZERO);
                },
                (turn, sink) -> noTools());

        kernel.execute(context(Map.of("maxModelCalls", "3")), item -> {
            items.add(item);
            return stored(item, items.size());
        });

        assertEquals(3, calls.get());
        assertEquals(
                1,
                items.stream()
                        .filter(ThreadItem.ContextCompaction.class::isInstance)
                        .count());
        assertEquals("agentMessage", items.getLast().kind());
    }

    private static TurnToolSession noTools() {
        return new TurnToolSession() {
            @Override
            public List<ToolDescriptor> availableTools() {
                return List.of();
            }

            @Override
            public ToolExecutionResult execute(ModelToolCall call) {
                throw new AssertionError("unexpected tool call");
            }
        };
    }

    @Test
    void executesToolCallsThroughTheSingleRuntime() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ModelGateway model = request -> calls.getAndIncrement() == 0
                ? new ModelResponse(
                        "",
                        "need a tool",
                        List.of(new ModelToolCall("call-1", "echo", "{\"text\":\"hi\"}")),
                        new ModelUsage(10, 2, 1))
                : new ModelResponse("done", "", List.of(), new ModelUsage(20, 4, 0));
        TurnToolSessionFactory tools = (turn, events) -> new TurnToolSession() {
            @Override
            public List<ToolDescriptor> availableTools() {
                return List.of(new ToolDescriptor("echo", "echo", "{\"type\":\"object\"}"));
            }

            @Override
            public ToolExecutionResult execute(ModelToolCall call) {
                return new ToolExecutionResult(new ThreadItem.DynamicToolCall("echo", Map.of("text", "hi")), "hi");
            }
        };
        AgentLoopKernel kernel = new AgentLoopKernel(model, tools);
        List<ThreadItem> emitted = new ArrayList<>();
        List<ModelUsage> usage = new ArrayList<>();

        kernel.execute(context(), new ItemSink() {
            @Override
            public StoredItem append(ThreadItem item) {
                emitted.add(item);
                return stored(item, emitted.size());
            }

            @Override
            public void usage(ModelUsage delta) {
                usage.add(delta);
            }
        });

        assertEquals(
                List.of("reasoningSummary", "dynamicToolCall", "agentMessage"),
                emitted.stream().map(ThreadItem::kind).toList());
        assertEquals(List.of(new ModelUsage(10, 2, 1), new ModelUsage(20, 4, 0)), usage);
        assertEquals(2, calls.get());
    }

    @Test
    void sharesTheProfileModelBudgetWithNestedAdapterCalls() {
        AtomicInteger calls = new AtomicInteger();
        ModelGateway model = request -> {
            calls.incrementAndGet();
            return new ModelResponse("", "", List.of(new ModelToolCall("call-1", "nested", "{}")), ModelUsage.ZERO);
        };
        TurnToolSessionFactory tools = (turn, events) -> new TurnToolSession() {
            @Override
            public List<ToolDescriptor> availableTools() {
                return List.of();
            }

            @Override
            public ToolExecutionResult execute(ModelToolCall call) {
                turn.scope().budget().consumeCall("nested adapter");
                return new ToolExecutionResult(new ThreadItem.DynamicToolCall("nested", Map.of()), "done");
            }
        };
        AgentLoopKernel kernel = new AgentLoopKernel(model, tools);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> kernel.execute(context(Map.of("maxIterations", "8", "maxModelCalls", "1")), ignored -> null));

        assertEquals("Turn model-call budget exceeded while invoking nested adapter", failure.getMessage());
        assertEquals(1, calls.get());
    }

    private static TurnExecutionContext context() {
        return context(Map.of());
    }

    private static TurnExecutionContext context(Map<String, String> attributes) {
        Path cwd = Path.of(".").toAbsolutePath().normalize();
        Instant now = Instant.now();
        ThreadId threadId = new ThreadId("thr_test");
        TurnId turnId = new TurnId("turn_test");
        TurnConfig config = new TurnConfig(
                "test",
                "test",
                "medium",
                cwd,
                SandboxPolicy.readOnly(Set.of(cwd), Set.of(cwd.resolve(".git"))),
                ApprovalPolicy.ON_RISK,
                Set.of("echo"),
                attributes);
        AgentThread thread =
                new AgentThread(threadId, "workspace", null, null, "", cwd, ThreadStatus.ACTIVE, 0, 0, 1, now, now);
        AgentTurn turn = new AgentTurn(
                turnId,
                threadId,
                new AttemptId("attempt_test"),
                TurnStatus.IN_PROGRESS,
                List.of(new TurnInput.Text("hello")),
                config,
                null,
                now,
                null);
        return new TurnExecutionContext(thread, turn, List.of(), new AtomicBoolean(), TurnSteering.NONE);
    }

    private static StoredItem stored(ThreadItem item, int ordinal) {
        Instant now = Instant.now();
        return new StoredItem(
                new ItemId("item_" + ordinal),
                new ThreadId("thr_test"),
                new TurnId("turn_test"),
                ordinal,
                ItemState.COMPLETED,
                item,
                now,
                now);
    }
}
