package com.javaclaw.infrastructure.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.plugin.v2.PluginManagementService;
import com.javaclaw.application.plugin.v2.PluginManagementService.*;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.*;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.workflow.model.*;
import com.javaclaw.workflow.model.RetryPolicy;
import com.javaclaw.workflow.runtime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

/** 验证宿主代理的数据边界、运行版本固定及恢复失败时的拒绝行为。 */
class RemotePluginHostAdaptersTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test void agentSnapshotRoutesSkillsContextAndTypedHooksWithinThePinnedLease() throws Exception {
        Fixture fixture = new Fixture();
        RemotePluginRuntime runtime = new RemotePluginRuntime(fixture.service(), json);
        var request = request();
        try (var snapshot = runtime.capture(request)) {
            String prepared = snapshot.prepare(request, null);
            assertTrue(prepared.contains("skill-a"));
            assertTrue(prepared.contains("context-a"));
            var restricted = snapshot.restrict(new RunConstraints(request.permissionCeiling(), request.budget()), request);
            assertEquals(PermissionSet.of("tool.execute"), restricted.permissions());
            assertEquals(3, restricted.budget().maxToolCalls());
            assertEquals(10, request.budget().maxToolCalls());
            var hookInput = fixture.calls.stream().filter(value -> value.operation.equals("constraint"))
                    .findFirst().orElseThrow().input;
            assertTrue(hookInput.path("allowedPermissions").toString().contains("filesystem.read"));
            assertEquals(10, hookInput.path("remainderBudget").asInt());
            fixture.currentHash = "b".repeat(64);
            var tool = snapshot.tools(null).getFirst();
            JsonNode result = tool.execute(json.createObjectNode(), new ToolExecutionContext(
                    new RunId("agent-run"), "tool-invocation", () -> false, Instant.now().plusSeconds(5)));
            assertEquals("a", result.path("text").asText());
            assertEquals("a".repeat(64), fixture.calls.getLast().lease.artifactHash());
            assertEquals(7, snapshot.locks().getFirst().configurationRevision());
        }
        assertTrue(fixture.leases.stream().allMatch(value -> value.closed));
    }

    @Test void agentRestoreUsesExactArtifactsAndNeverReplacesMissingOnes() {
        Fixture fixture = new Fixture();
        RemotePluginRuntime runtime = new RemotePluginRuntime(fixture.service(), json);
        var old = runtime.capture(request());
        var locks = old.locks(); old.close(); fixture.currentHash = "b".repeat(64);
        try (var restored = runtime.restore(locks)) {
            assertEquals("a".repeat(64), restored.locks().getFirst().artifactHash());
            assertEquals("a".repeat(64), fixture.leases.getLast().hash);
        }
        fixture.exactAvailable = false;
        assertThrows(IllegalStateException.class, () -> runtime.restore(locks));
    }

    @Test void enabledConstraintFailsClosedInEveryUnavailableState() {
        Fixture fixture = new Fixture();
        RemotePluginRuntime runtime = new RemotePluginRuntime(fixture.service(), json);
        for (State unavailable : State.values()) {
            if (unavailable == State.READY || unavailable == State.DEGRADED) continue;
            fixture.state = unavailable;
            assertThrows(IllegalStateException.class, () -> runtime.capture(request()), unavailable.name());
        }
        assertTrue(fixture.leases.isEmpty());
        fixture.enabled = false;
        try (var disabled = runtime.capture(request())) { assertTrue(disabled.locks().isEmpty()); }
    }

    @Test void workflowPlansPinInstancesAndValidateReturnedStatePaths() throws Exception {
        Fixture fixture = new Fixture();
        var provider = workflowProvider(fixture);
        try (var first = provider.compile(graph())) {
            assertEquals("a", execute(first));
            fixture.currentHash = "b".repeat(64);
            try (var second = provider.compile(graph())) {
                assertEquals("a", execute(first)); assertEquals("b", execute(second));
            }
            fixture.invalidStatePath = true;
            assertThrows(IllegalArgumentException.class, () -> execute(first));
            fixture.exactAvailable = false;
            assertTrue(provider.restore(first.locks()).isEmpty());
        }
        assertTrue(fixture.leases.stream().allMatch(value -> value.closed));
    }

    @Test void workflowNeverDisclosesHostReservedRootsOrMutatesTheOriginalState() throws Exception {
        Fixture fixture = new Fixture();
        GraphState state = new GraphState().apply(StatePatch.builder()
                .set("_agent.turnId", "host-turn")
                .set("_agent.driverAttemptId", "host-attempt")
                .set("_host.secret", "private")
                .set("public.value", "visible")
                .set("public._nested", "ordinary-data")
                .build());
        try (var plan = workflowProvider(fixture).compile(graph())) {
            execute(plan, state);
        }
        JsonNode sent = fixture.calls.getLast().input.path("state");
        assertFalse(sent.has("_agent"));
        assertFalse(sent.has("_host"));
        assertEquals("visible", sent.path("public").path("value").asText());
        assertEquals("ordinary-data", sent.path("public").path("_nested").asText());
        assertEquals("host-turn", state.get("_agent.turnId").asText());
        assertEquals("host-attempt", state.get("_agent.driverAttemptId").asText());
    }

    @Test void workflowRejectsReservedRootsInBothPatchesAndInterruptResponses() {
        Fixture fixture = new Fixture();
        try (var plan = workflowProvider(fixture).compile(graph())) {
            for (String path : List.of("_agent.turnId", "_agent.driverAttemptId", "_internal", "_host.secret")) {
                fixture.statePath = path;
                assertThrows(IllegalArgumentException.class, () -> execute(plan), path);
                fixture.statePath = "result.value";
                fixture.interruptResponseKey = path;
                assertThrows(IllegalArgumentException.class, () -> execute(plan), path);
                fixture.interruptResponseKey = null;
            }
        }
    }

    @Test void remoteWorkflowNeverRetriesAndUnknownOutcomeBypassesErrorLoops() {
        for (String errorCode : List.of("UNKNOWN_OUTCOME", "PLUGIN_ERROR")) {
            Fixture fixture = new Fixture();
            fixture.rpcFailureCode = errorCode;
            var empty = json.createObjectNode();
            var nodes = List.of(
                    new NodeDefinition("start", NodeType.START, "test.pass", "开始", empty, 0, 0,
                            RetryPolicy.NONE, ResumeSafety.SAFE),
                    new NodeDefinition("remote", NodeType.SYSTEM, "plugin.fixture.node", "副作用", empty, 0, 0,
                            new RetryPolicy(3, 0, 1), ResumeSafety.SAFE),
                    new NodeDefinition("end", NodeType.END, "test.pass", "结束", empty, 0, 0,
                            RetryPolicy.NONE, ResumeSafety.SAFE));
            var edges = new ArrayList<>(List.of(
                    new EdgeDefinition("begin", "start", "remote", EdgeKind.NORMAL, null, 0, false),
                    new EdgeDefinition("finish", "remote", "end", EdgeKind.NORMAL, null, 0, false)));
            if (errorCode.equals("UNKNOWN_OUTCOME"))
                edges.add(new EdgeDefinition("errorLoop", "remote", "start", EdgeKind.ERROR, null, 0, false));
            var definition = new GraphDefinition(1, "side-effect", "副作用", "", 1, GraphKind.SYSTEM,
                    "start", nodes, edges, 10);
            try (var plan = workflowProvider(fixture).compile(definition)) {
                var registry = new NodeExecutorRegistry();
                registry.register(new NodeExecutor() {
                    public String type() { return "test.pass"; }
                    public NodeResult execute(NodeExecutionContext context) { return NodeResult.next(); }
                });
                plan.executors().values().forEach(registry::register);
                var store = (com.javaclaw.workflow.store.GraphCheckpointStore) Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[]{com.javaclaw.workflow.store.GraphCheckpointStore.class},
                        (proxy, method, arguments) -> null);
                List<GraphEvent> events = new ArrayList<>();
                var result = new GraphEngine(registry, store).execute(new GraphRun(definition, "thread", new GraphState()),
                        new com.javaclaw.workflow.runtime.CancellationToken(), events::add, WorkflowExecutionServices.EMPTY);
                assertEquals(RunStatus.FAILED, result.status());
                assertEquals(1, fixture.calls.size());
                assertTrue(events.stream().noneMatch(event -> event instanceof GraphEvent.NodeRetry));
                if (errorCode.equals("UNKNOWN_OUTCOME")) assertTrue(result.error().contains("UNKNOWN_OUTCOME"));
            }
        }
    }

    private RemoteWorkflowExtensionProvider workflowProvider(Fixture fixture) {
        var source = new DriverManagerDataSource("jdbc:h2:mem:remote-host-" + UUID.randomUUID(), "sa", "");
        var workspaces = new WorkspaceManager(new DataRoot(temp), new JdbcTemplate(source),
                new DataSourceTransactionManager(source)) {
            @Override public String getCurrentWorkspaceId() { return "workspace-a"; }
        };
        return new RemoteWorkflowExtensionProvider(WorkflowExtensionPlanProvider.NONE, fixture.service(), workspaces, json);
    }
    private String execute(WorkflowExtensionPlan plan) throws Exception {
        return execute(plan, new GraphState());
    }
    private String execute(WorkflowExtensionPlan plan, GraphState state) throws Exception {
        var result = plan.find("plugin.fixture.node").orElseThrow().execute(new NodeExecutionContext(
                "workflow-run", "thread", graph().nodes().getFirst(), state,
                new com.javaclaw.workflow.runtime.CancellationToken(), GraphListener.NOOP, WorkflowExecutionServices.EMPTY));
        return new GraphState().apply(result.patch()).get("result.value").asText();
    }
    private GraphDefinition graph() {
        return new GraphDefinition(1, "test", "Test", "", 1, GraphKind.CUSTOM, "node", List.of(
                new NodeDefinition("node", NodeType.SYSTEM, "plugin.fixture.node", "远程节点", json.createObjectNode(),
                        0, 0, com.javaclaw.workflow.model.RetryPolicy.NONE, ResumeSafety.SAFE)), List.of(), 5);
    }
    private RunRequest request() {
        return RunRequest.builder().agent(AgentDefinitionRef.latest("test.agent"))
                .profile(RunProfileRef.latest("test.profile")).source(InvocationSource.chat())
                .scope(new RunScope("workspace-a", "user", "session")).input(InputBlock.text("你好"))
                .permissionCeiling(PermissionSet.of("tool.execute", "filesystem.read"))
                .budget(new RunBudget(Duration.ofSeconds(30), 1000, 1000, 10, BigDecimal.TEN)).build();
    }

    private final class Fixture {
        private String currentHash = "a".repeat(64);
        private State state = State.READY;
        private boolean enabled = true;
        private boolean exactAvailable = true, invalidStatePath;
        private String statePath = "result.value", interruptResponseKey;
        private String rpcFailureCode;
        private final List<Lease> leases = new ArrayList<>();
        private final List<Call> calls = new ArrayList<>();
        private final List<ContributionInfo> contributions = List.of(
                info("tool", "TOOL", "{}"), info("skill", "SKILL", "{}"), info("context", "CONTEXT", "{}"),
                info("policy", "HOOK", "{\"phase\":\"constraint\",\"constraint\":true}"), info("node", "WORKFLOW", "{}"));
        private ContributionInfo info(String id, String kind, String metadata) {
            return new ContributionInfo(id, kind, "2.0", id, id, "{}", metadata, "{}");
        }
        private PluginManagementService service() {
            return (PluginManagementService) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PluginManagementService.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "list" -> List.of(summary());
                        case "details" -> new PluginDetails(summary(), "{}", new ConfigurationSnapshot(7,
                                "{}", "{}", Map.of(), Set.of()), Set.of(), contributions);
                        case "acquireRun" -> {
                            if (args.length == 4 && !exactAvailable) throw new IllegalStateException("固定工件不存在");
                            Lease lease = new Lease(args.length == 4 ? (String) args[2] : currentHash);
                            leases.add(lease); yield lease;
                        }
                        case "invoke" -> invoke(args);
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
        private PluginSummary summary() {
            return new PluginSummary("fixture", "示例", "2.0.0", "", "test", currentHash, false, enabled,
                    StartupPolicy.MANUAL, state, 7, Set.of("TOOL", "SKILL", "CONTEXT", "HOOK", "WORKFLOW"), "");
        }
        private PluginInvocation invoke(Object[] args) throws Exception {
            Lease lease = (Lease) args[0]; String kind = (String) args[1], operation = (String) args[3];
            JsonNode input = json.readTree((String) args[4]); calls.add(new Call(lease, kind, operation, input));
            if (rpcFailureCode != null) {
                var failure = CompletableFuture.<InvocationResult>failedFuture(new PluginException(rpcFailureCode, "执行结果不可确认"));
                return new PluginInvocation() {
                    public String requestId() { return "failed-call"; }
                    public CompletableFuture<InvocationResult> completion() { return failure; }
                    public boolean cancel() { return false; }
                };
            }
            String result;
            if (kind.equals("HOOK")) {
                var budget = input.path("input").path("budget").deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) budget).put("maxToolCalls", 3);
                result = json.writeValueAsString(Map.of("output", Map.of("allowed", true, "budget", budget),
                        "allowedPermissions", List.of("tool.execute"), "remainderBudget", 3));
            } else if (kind.equals("WORKFLOW")) {
                var response = json.createObjectNode();
                response.putObject("setValues").put(invalidStatePath ? "../invalid" : statePath, lease.hash.substring(0, 1));
                if (interruptResponseKey != null)
                    response.put("interruptPrompt", "确认继续").put("interruptResponseKey", interruptResponseKey);
                result = response.toString();
            }
            else result = json.writeValueAsString(Map.of("text", (kind.equals("TOOL") ? "" : kind.toLowerCase() + "-") + lease.hash.charAt(0)));
            String id = UUID.randomUUID().toString();
            var completion = CompletableFuture.completedFuture(new InvocationResult(id, result));
            return new PluginInvocation() {
                public String requestId() { return id; }
                public CompletableFuture<InvocationResult> completion() { return completion; }
                public boolean cancel() { return false; }
            };
        }
        private final class Lease implements RunLease {
            private final String hash;
            private boolean closed;
            private Lease(String hash) { this.hash = hash; }
            public String runId() { return "lease-run"; }
            public String workspaceId() { return "workspace-a"; }
            public String pluginId() { return "fixture"; }
            public String artifactHash() { return hash; }
            public long configurationRevision() { return 7; }
            public String instanceId() { return "instance-" + hash.charAt(0); }
            public Set<String> grantedPermissions() { return Set.of(); }
            public List<ContributionInfo> contributions() { return contributions; }
            public String manifestJson() { return "{\"version\":\"2.0.0\"}"; }
            public void close() { closed = true; }
        }
        private record Call(Lease lease, String kind, String operation, JsonNode input) { }
    }
}
