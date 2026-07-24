package com.javaclaw.workflow;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.workflow.editor.WorkflowEditorModel;
import com.javaclaw.workflow.model.*;
import com.javaclaw.workflow.node.BasicNodeExecutors;
import com.javaclaw.workflow.runtime.*;
import com.javaclaw.workflow.store.GraphCheckpointStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GraphRuntimeTest {
    private GraphExecutionManager manager;

    @AfterEach void close() { if (manager != null) manager.close(); }

    @Test
    void 空白工作流可直接运行并回显输入() throws Exception {
        NodeExecutorRegistry registry = baseRegistry();
        MemoryStore store = new MemoryStore();
        manager = new GraphExecutionManager(registry, store);
        GraphDefinition graph = WorkflowEditorModel.blank("空白模板");
        CountDownLatch done = new CountDownLatch(1);

        GraphRun run = manager.start(graph, "thread",
                new GraphState().apply(StatePatch.builder()
                        .set("input", "workflow-default-output").build()),
                finishLatch(done), Map.of());

        assertTrue(done.await(3, TimeUnit.SECONDS));
        GraphRun saved = store.loadRun(run.id());
        assertEquals(RunStatus.COMPLETED, saved.status());
        assertEquals(3, saved.stepCount());
        assertEquals("workflow-default-output", saved.output());
        assertEquals("workflow-default-output", saved.state().get("output").asText());
    }

    @Test
    void 条件边按优先级选择且状态跨节点合并() throws Exception {
        NodeExecutorRegistry registry = baseRegistry();
        registry.register(executor("mark", ctx -> NodeResult.next(StatePatch.builder()
                .set(ctx.node().config().path("key").asText(), true).build())));
        MemoryStore store = new MemoryStore(); manager = new GraphExecutionManager(registry, store);

        NodeDefinition start = node("start", NodeType.START, "start");
        NodeDefinition condition = node("route", NodeType.CONDITION, "condition");
        NodeDefinition yes = configured("yes", NodeType.SYSTEM, "mark", "key", "selected.yes");
        NodeDefinition no = configured("no", NodeType.SYSTEM, "mark", "key", "selected.no");
        NodeDefinition end = node("end", NodeType.END, "end");
        var trueRule = new ConditionRule("score", ConditionOperator.GTE, JsonNodeFactory.instance.numberNode(80));
        GraphDefinition graph = graph(List.of(start, condition, yes, no, end), List.of(
                edge("a", "start", "route"),
                new EdgeDefinition("yes-edge", "route", "yes", EdgeKind.CONDITIONAL, trueRule, 1, false),
                new EdgeDefinition("no-edge", "route", "no", EdgeKind.CONDITIONAL, null, 99, true),
                edge("b", "yes", "end"), edge("c", "no", "end")), 20);
        CountDownLatch done = new CountDownLatch(1);
        GraphRun run = manager.start(graph, "thread", new GraphState().apply(
                StatePatch.builder().set("score", 90).build()), finishLatch(done), Map.of());

        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertEquals(RunStatus.COMPLETED, store.loadRun(run.id()).status());
        assertTrue(store.loadRun(run.id()).state().get("selected.yes").asBoolean());
        assertFalse(store.loadRun(run.id()).state().exists("selected.no"));
    }

    @Test
    void 节点失败按策略重试后成功() throws Exception {
        NodeExecutorRegistry registry = baseRegistry();
        AtomicInteger calls = new AtomicInteger();
        registry.register(executor("flaky", ctx -> {
            if (calls.incrementAndGet() < 3) throw new IllegalStateException("暂时失败");
            return NodeResult.next();
        }));
        MemoryStore store = new MemoryStore(); manager = new GraphExecutionManager(registry, store);
        NodeDefinition start = node("start", NodeType.START, "start");
        NodeDefinition work = new NodeDefinition("work", NodeType.SYSTEM, "flaky", "重试节点",
                JsonNodeFactory.instance.objectNode(), 0, 0, new RetryPolicy(3, 1, 1), ResumeSafety.SAFE);
        NodeDefinition end = node("end", NodeType.END, "end");
        GraphDefinition graph = graph(List.of(start, work, end),
                List.of(edge("a", "start", "work"), edge("b", "work", "end")), 10);
        CountDownLatch done = new CountDownLatch(1);
        GraphRun run = manager.start(graph, "thread", new GraphState(), finishLatch(done), Map.of());

        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertEquals(3, calls.get());
        assertEquals(RunStatus.COMPLETED, store.loadRun(run.id()).status());
    }

    @Test
    void 人工节点持久化中断并以响应恢复() throws Exception {
        NodeExecutorRegistry registry = baseRegistry();
        MemoryStore store = new MemoryStore(); manager = new GraphExecutionManager(registry, store);
        NodeDefinition start = node("start", NodeType.START, "start");
        var cfg = JsonNodeFactory.instance.objectNode().put("prompt", "批准吗？").put("responseKey", "approval");
        NodeDefinition human = new NodeDefinition("human", NodeType.HUMAN_INPUT, "human_input", "人工输入",
                cfg, 0, 0, RetryPolicy.NONE, ResumeSafety.SAFE);
        NodeDefinition end = node("end", NodeType.END, "end");
        GraphDefinition graph = graph(List.of(start, human, end),
                List.of(edge("a", "start", "human"), edge("b", "human", "end")), 10);

        CountDownLatch waiting = new CountDownLatch(1);
        GraphRun run = manager.start(graph, "thread", new GraphState(), finishLatch(waiting), Map.of());
        assertTrue(waiting.await(3, TimeUnit.SECONDS));
        assertEquals(RunStatus.WAITING_INPUT, store.loadRun(run.id()).status());
        assertEquals("approval", store.loadRun(run.id()).interrupt().responseKey());

        CountDownLatch complete = new CountDownLatch(1);
        manager.resume(run.id(), "允许", false, finishLatch(complete), Map.of());
        assertTrue(complete.await(3, TimeUnit.SECONDS));
        assertEquals(RunStatus.COMPLETED, store.loadRun(run.id()).status());
        assertEquals("允许", store.loadRun(run.id()).state().get("approval").asText());
        assertFalse(store.loadRun(run.id()).state().exists(GraphExecutionManager.RESUME_NODE_STATE_KEY));

        CountDownLatch waitingAgain = new CountDownLatch(1);
        GraphRun second = manager.start(graph, "thread",
                store.loadRun(run.id()).state(), finishLatch(waitingAgain), Map.of());
        assertTrue(waitingAgain.await(3, TimeUnit.SECONDS));
        assertEquals(RunStatus.WAITING_INPUT, store.loadRun(second.id()).status(),
                "持久化的旧响应不能让新 run 跳过 HUMAN_INPUT");
    }

    @Test
    void 取消会触发运行终态且不再跳转() throws Exception {
        NodeExecutorRegistry registry = baseRegistry();
        CountDownLatch entered = new CountDownLatch(1);
        registry.register(executor("blocking", ctx -> {
            entered.countDown();
            while (true) { ctx.cancellation().throwIfCancelled(); Thread.sleep(10); }
        }));
        MemoryStore store = new MemoryStore(); manager = new GraphExecutionManager(registry, store);
        NodeDefinition start = node("start", NodeType.START, "start");
        NodeDefinition block = node("block", NodeType.SYSTEM, "blocking");
        NodeDefinition end = node("end", NodeType.END, "end");
        GraphDefinition graph = graph(List.of(start, block, end),
                List.of(edge("a", "start", "block"), edge("b", "block", "end")), 10);
        CountDownLatch done = new CountDownLatch(1);
        GraphRun run = manager.start(graph, "thread", new GraphState(), finishLatch(done), Map.of());
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTrue(manager.cancel(run.id()));
        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertEquals(RunStatus.CANCELLED, store.loadRun(run.id()).status());
    }

    @Test
    void 不合作节点返回前收到取消时不提交补丁() throws Exception {
        NodeExecutorRegistry registry = baseRegistry();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        registry.register(executor("ignores-cancel", ctx -> {
            entered.countDown();
            release.await();
            return NodeResult.next(StatePatch.builder().set("late.result", "不应提交").build());
        }));
        MemoryStore store = new MemoryStore(); manager = new GraphExecutionManager(registry, store);
        GraphDefinition graph = graph(List.of(node("start", NodeType.START, "start"),
                        node("work", NodeType.SYSTEM, "ignores-cancel"),
                        node("end", NodeType.END, "end")),
                List.of(edge("a", "start", "work"), edge("b", "work", "end")), 10);
        CountDownLatch done = new CountDownLatch(1);
        GraphRun run = manager.start(graph, "thread", new GraphState(), finishLatch(done), Map.of());

        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTrue(manager.cancel(run.id()));
        release.countDown();
        assertTrue(done.await(3, TimeUnit.SECONDS));
        GraphRun saved = store.loadRun(run.id());
        assertEquals(RunStatus.CANCELLED, saved.status());
        assertFalse(saved.state().exists("late.result"));
    }

    @Test
    void 不合作节点异常前收到取消时不提交错误状态() throws Exception {
        NodeExecutorRegistry registry = baseRegistry();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        registry.register(executor("late-failure", ctx -> {
            entered.countDown();
            release.await();
            throw new IllegalStateException("取消后的异常");
        }));
        MemoryStore store = new MemoryStore(); manager = new GraphExecutionManager(registry, store);
        GraphDefinition graph = graph(List.of(node("start", NodeType.START, "start"),
                        node("work", NodeType.SYSTEM, "late-failure"),
                        node("normal", NodeType.END, "end"),
                        node("recover", NodeType.END, "end")),
                List.of(edge("a", "start", "work"),
                        edge("normal-edge", "work", "normal"),
                        new EdgeDefinition("error", "work", "recover",
                                EdgeKind.ERROR, null, 0, false)), 10);
        CountDownLatch done = new CountDownLatch(1);
        GraphRun run = manager.start(graph, "thread", new GraphState(), finishLatch(done), Map.of());

        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTrue(manager.cancel(run.id()));
        release.countDown();
        assertTrue(done.await(3, TimeUnit.SECONDS));
        GraphRun saved = store.loadRun(run.id());
        assertEquals(RunStatus.CANCELLED, saved.status());
        assertFalse(saved.state().exists("_error.nodeId"));
        assertFalse(saved.state().exists("_error.message"));
    }

    @Test
    void 终态回调前已释放运行占用() throws Exception {
        NodeExecutorRegistry registry = baseRegistry();
        MemoryStore store = new MemoryStore();
        manager = new GraphExecutionManager(registry, store);
        GraphDefinition graph = graph(
                List.of(node("start", NodeType.START, "start"), node("end", NodeType.END, "end")),
                List.of(edge("finish", "start", "end")), 10);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Boolean> activeAtTerminal = new AtomicReference<>();

        GraphRun run = manager.start(graph, "thread", new GraphState(), event -> {
            if (event instanceof GraphEvent.RunFinished finished) {
                activeAtTerminal.set(manager.isActive(finished.runId()));
                done.countDown();
            }
        }, Map.of());

        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertEquals(Boolean.FALSE, activeAtTerminal.get());
        assertFalse(manager.isActive(run.id()));
    }

    @Test
    void 校验器拒绝直接自环与缺少条件默认边() {
        NodeExecutorRegistry registry = baseRegistry();
        NodeDefinition start = node("start", NodeType.START, "start");
        NodeDefinition route = node("route", NodeType.CONDITION, "condition");
        NodeDefinition end = node("end", NodeType.END, "end");
        GraphDefinition graph = graph(List.of(start, route, end), List.of(
                edge("a", "start", "route"),
                new EdgeDefinition("loop", "route", "route", EdgeKind.CONDITIONAL,
                        new ConditionRule("x", ConditionOperator.EXISTS, null), 1, false)), 10);
        List<ValidationIssue> issues = GraphValidator.validate(graph, registry);
        assertTrue(issues.stream().anyMatch(i -> i.message().contains("自环")));
        assertTrue(issues.stream().anyMatch(i -> i.message().contains("默认出口")));
    }

    @Test
    void 错误边捕获失败并把错误写入状态() throws Exception {
        NodeExecutorRegistry registry = baseRegistry();
        registry.register(executor("broken", ctx -> { throw new IllegalStateException("boom"); }));
        MemoryStore store = new MemoryStore(); manager = new GraphExecutionManager(registry, store);
        GraphDefinition graph = graph(List.of(node("start", NodeType.START, "start"),
                        node("work", NodeType.SYSTEM, "broken"), node("normal", NodeType.END, "end"),
                        node("recover", NodeType.END, "end")),
                List.of(edge("a", "start", "work"),
                        edge("normal-edge", "work", "normal"),
                        new EdgeDefinition("error", "work", "recover", EdgeKind.ERROR, null, 0, false)), 10);
        CountDownLatch done = new CountDownLatch(1);
        GraphRun run = manager.start(graph, "thread", new GraphState(), finishLatch(done), Map.of());
        assertTrue(done.await(3, TimeUnit.SECONDS));
        GraphRun saved = store.loadRun(run.id());
        assertEquals(RunStatus.COMPLETED, saved.status());
        assertNull(saved.error(), "错误边已处理后，完成态不应保留运行级错误");
        assertEquals("boom", saved.state().get("_error.message").asText());
    }

    @Test
    void 自定义图拒绝依赖内置模式上下文的系统流水线节点() {
        NodeExecutorRegistry registry = baseRegistry();
        registry.register(executor("system.pipeline", ctx -> NodeResult.next()));
        GraphDefinition graph = customGraph(List.of(node("start", NodeType.START, "start"),
                        node("pipeline", NodeType.SYSTEM, "system.pipeline"),
                        node("end", NodeType.END, "end")),
                List.of(edge("a", "start", "pipeline"), edge("b", "pipeline", "end")), 10);

        List<ValidationIssue> issues = GraphValidator.validate(graph, registry);

        assertTrue(issues.stream().anyMatch(i -> i.elementId().equals("pipeline")
                && i.message().contains("内置模式上下文")));
    }

    @Test
    void 自定义图要求Agent的终态路径经过Output节点() {
        NodeExecutorRegistry registry = baseRegistry();
        registry.register(executor("agent", ctx -> NodeResult.next()));
        NodeDefinition agent = node("agent", NodeType.AGENT, "agent");
        GraphDefinition missingOutput = customGraph(
                List.of(node("start", NodeType.START, "start"), agent,
                        node("end", NodeType.END, "end")),
                List.of(edge("a", "start", "agent"), edge("b", "agent", "end")), 10);

        assertTrue(GraphValidator.validate(missingOutput, registry).stream()
                .anyMatch(i -> i.message().contains("OUTPUT")));

        NodeDefinition output = node("output", NodeType.OUTPUT, "output");
        GraphDefinition withOutput = customGraph(
                List.of(node("start", NodeType.START, "start"), agent, output,
                        node("end", NodeType.END, "end")),
                List.of(edge("a", "start", "agent"), edge("b", "agent", "output"),
                        edge("c", "output", "end")), 10);

        assertFalse(GraphValidator.validate(withOutput, registry).stream()
                .anyMatch(i -> i.message().contains("OUTPUT")));
    }

    @Test
    void 自定义副作用节点必须使用确认恢复策略() {
        NodeExecutorRegistry registry = baseRegistry();
        registry.register(executor("tool", ctx -> NodeResult.next()));
        NodeDefinition unsafeTool = new NodeDefinition("tool", NodeType.TOOL, "tool", "工具",
                JsonNodeFactory.instance.objectNode().put("toolName", "local_tool"),
                0, 0, RetryPolicy.NONE, ResumeSafety.SAFE);
        GraphDefinition graph = customGraph(
                List.of(node("start", NodeType.START, "start"), unsafeTool,
                        node("end", NodeType.END, "end")),
                List.of(edge("a", "start", "tool"), edge("b", "tool", "end")), 10);

        assertTrue(GraphValidator.validate(graph, registry).stream()
                .anyMatch(i -> i.elementId().equals("tool")
                        && i.message().contains("CONFIRM_RETRY")));
    }

    @Test
    void 启动前校验失败仍会落失败终态并通知监听器() throws Exception {
        NodeExecutorRegistry registry = baseRegistry();
        MemoryStore store = new MemoryStore();
        manager = new GraphExecutionManager(registry, store);
        GraphDefinition invalid = graph(
                List.of(node("start", NodeType.START, "start"),
                        node("end", NodeType.END, "missing-executor")),
                List.of(edge("a", "start", "end")), 10);
        CountDownLatch done = new CountDownLatch(1);

        GraphRun run = manager.start(invalid, "thread", new GraphState(), finishLatch(done), Map.of());

        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertEquals(RunStatus.FAILED, store.loadRun(run.id()).status());
        assertTrue(store.loadRun(run.id()).error().contains("图定义无效"));
    }

    @Test
    void 循环达到最大步数后失败() throws Exception {
        NodeExecutorRegistry registry = baseRegistry();
        MemoryStore store = new MemoryStore(); manager = new GraphExecutionManager(registry, store);
        GraphDefinition graph = graph(List.of(node("start", NodeType.START, "start"),
                        node("a", NodeType.CONDITION, "condition"), node("b", NodeType.CONDITION, "condition"),
                        node("end", NodeType.END, "end")),
                List.of(edge("s", "start", "a"),
                        new EdgeDefinition("done", "a", "end", EdgeKind.CONDITIONAL,
                                new ConditionRule("stop", ConditionOperator.EXISTS, null), 1, false),
                        new EdgeDefinition("continue", "a", "b", EdgeKind.CONDITIONAL,
                                null, 99, true), edge("ba", "b", "a")), 5);
        CountDownLatch done = new CountDownLatch(1);
        GraphRun run = manager.start(graph, "thread", new GraphState(), finishLatch(done), Map.of());
        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertEquals(RunStatus.FAILED, store.loadRun(run.id()).status());
        assertTrue(store.loadRun(run.id()).error().contains("最大步数"));
    }

    @Test
    void 节点前后写检查点且暂停后可继续() throws Exception {
        NodeExecutorRegistry registry = baseRegistry();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        registry.register(executor("gate", ctx -> { entered.countDown(); release.await(); return NodeResult.next(); }));
        MemoryStore store = new MemoryStore(); manager = new GraphExecutionManager(registry, store);
        GraphDefinition graph = graph(List.of(node("start", NodeType.START, "start"),
                        node("work", NodeType.SYSTEM, "gate"), node("end", NodeType.END, "end")),
                List.of(edge("a", "start", "work"), edge("b", "work", "end")), 10);
        CountDownLatch paused = new CountDownLatch(1);
        GraphRun run = manager.start(graph, "thread", new GraphState(), finishLatch(paused), Map.of());
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTrue(manager.pause(run.id()));
        release.countDown();
        assertTrue(paused.await(3, TimeUnit.SECONDS));
        assertEquals(RunStatus.PAUSED, store.loadRun(run.id()).status());
        assertTrue(store.phases.contains(CheckpointPhase.BEFORE_NODE));
        assertTrue(store.phases.contains(CheckpointPhase.AFTER_NODE));
        assertTrue(store.phases.contains(CheckpointPhase.PAUSE));

        CountDownLatch complete = new CountDownLatch(1);
        manager.resume(run.id(), null, false, finishLatch(complete), Map.of());
        assertTrue(complete.await(3, TimeUnit.SECONDS));
        assertEquals(RunStatus.COMPLETED, store.loadRun(run.id()).status());
        assertTrue(store.phases.contains(CheckpointPhase.TERMINAL));
    }

    @Test
    void 异常恢复在副作用节点前要求再次确认() throws Exception {
        NodeExecutorRegistry registry = baseRegistry();
        AtomicInteger calls = new AtomicInteger();
        registry.register(executor("side-effect", ctx -> { calls.incrementAndGet(); return NodeResult.next(); }));
        MemoryStore store = new MemoryStore(); manager = new GraphExecutionManager(registry, store);
        NodeDefinition work = new NodeDefinition("work", NodeType.SYSTEM, "side-effect", "副作用",
                JsonNodeFactory.instance.objectNode(), 0, 0, RetryPolicy.NONE, ResumeSafety.CONFIRM_RETRY);
        GraphDefinition graph = graph(List.of(node("start", NodeType.START, "start"), work,
                        node("end", NodeType.END, "end")),
                List.of(edge("a", "start", "work"), edge("b", "work", "end")), 10);
        long now = System.currentTimeMillis();
        GraphRun recovery = new GraphRun("recovery", graph.id(), graph.version(), "thread", graph,
                new GraphState(), RunStatus.RECOVERY_REQUIRED, "work", null,
                1, 0, null, null, null, now, now);
        store.createRun(recovery);
        assertThrows(SecurityException.class,
                () -> manager.resume(recovery.id(), null, false, GraphListener.NOOP, Map.of()));
        CountDownLatch done = new CountDownLatch(1);
        manager.resume(recovery.id(), null, true, finishLatch(done), Map.of());
        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
        assertEquals(RunStatus.COMPLETED, store.loadRun(recovery.id()).status());
    }

    private static NodeExecutorRegistry baseRegistry() {
        NodeExecutorRegistry registry = new NodeExecutorRegistry(); BasicNodeExecutors.register(registry); return registry;
    }
    private static NodeExecutor executor(String type, Throwing action) {
        return new NodeExecutor() { public String type() { return type; }
            public NodeResult execute(NodeExecutionContext context) throws Exception { return action.run(context); } };
    }
    private interface Throwing { NodeResult run(NodeExecutionContext context) throws Exception; }
    private static NodeDefinition node(String id, NodeType type, String executor) {
        return new NodeDefinition(id, type, executor, id, JsonNodeFactory.instance.objectNode(),
                0, 0, RetryPolicy.NONE, ResumeSafety.SAFE);
    }
    private static NodeDefinition configured(String id, NodeType type, String executor, String key, String value) {
        return new NodeDefinition(id, type, executor, id, JsonNodeFactory.instance.objectNode().put(key, value),
                0, 0, RetryPolicy.NONE, ResumeSafety.SAFE);
    }
    private static EdgeDefinition edge(String id, String source, String target) {
        return new EdgeDefinition(id, source, target, EdgeKind.NORMAL, null, 0, false);
    }
    private static GraphDefinition graph(List<NodeDefinition> nodes, List<EdgeDefinition> edges, int max) {
        return new GraphDefinition(1, "test", "test", "", 1, GraphKind.SYSTEM, "start", nodes, edges, max);
    }
    private static GraphDefinition customGraph(
            List<NodeDefinition> nodes, List<EdgeDefinition> edges, int max) {
        return new GraphDefinition(1, "test", "test", "", 1, GraphKind.CUSTOM,
                "start", nodes, edges, max);
    }
    private static GraphListener finishLatch(CountDownLatch latch) {
        return event -> { if (event instanceof GraphEvent.RunFinished) latch.countDown(); };
    }

    private static final class MemoryStore implements GraphCheckpointStore {
        private final Map<String, GraphRun> runs = new ConcurrentHashMap<>();
        private final Map<String, GraphState> threads = new ConcurrentHashMap<>();
        private final List<CheckpointPhase> phases = new CopyOnWriteArrayList<>();
        public void createRun(GraphRun run) { runs.put(run.id(), run); }
        public void updateRun(GraphRun run) { runs.put(run.id(), run); }
        public void checkpoint(GraphRun run, String nodeId, CheckpointPhase phase) {
            phases.add(phase); run.nextCheckpointSeq(); updateRun(run);
        }
        public GraphRun loadRun(String runId) { return runs.get(runId); }
        public List<GraphRun> listRuns(String workflowId, int limit) { return new ArrayList<>(runs.values()); }
        public GraphRun findWaitingRun(String workflowId, String threadId) { return runs.values().stream()
                .filter(r -> r.workflowId().equals(workflowId) && r.threadId().equals(threadId)
                        && r.status() == RunStatus.WAITING_INPUT).findFirst().orElse(null); }
        public GraphRun findRecoverableRun(String workflowId, String threadId) { return runs.values().stream()
                .filter(r -> r.workflowId().equals(workflowId) && r.threadId().equals(threadId)
                        && (r.status() == RunStatus.PAUSED || r.status() == RunStatus.RECOVERY_REQUIRED))
                .findFirst().orElse(null); }
        public GraphState loadThreadState(String workflowId, String threadId) { return threads.getOrDefault(workflowId+threadId, new GraphState()); }
        public void saveThreadState(String workflowId, String threadId, GraphState state) { threads.put(workflowId+threadId, state); }
        public int markRunningAsRecoveryRequired() { return 0; }
    }
}
