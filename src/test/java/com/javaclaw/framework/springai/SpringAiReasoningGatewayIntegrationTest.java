package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.builtin.BuiltinCapabilityExtension;
import com.javaclaw.framework.builtin.BuiltinExtensionCatalog;
import com.javaclaw.framework.builtin.memory.MemoryMutationGateway;
import com.javaclaw.framework.core.*;
import com.javaclaw.framework.extension.ExtensionArtifact;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.spi.*;
import com.javaclaw.framework.store.JdbcAgentDefinitionStore;
import com.javaclaw.framework.store.JdbcExecutionPlanStore;
import com.javaclaw.framework.store.JdbcRunStore;
import com.javaclaw.platform.data.SchemaInitializer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringAiReasoningGatewayIntegrationTest {

    @Test
    void 固定Persona首次经网关读取且重启后每步复用同一用户级快照() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicReference<String> current = new AtomicReference<>("PERSONA_VERSION_ONE");
        List<Prompt> delivered = new ArrayList<>();
        FixedContextSource persona = fixedPersona(reads, current);
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, providerCalls.get() == 0 ? "test_mutate" : "",
                        providerCalls.get() == 0 ? "test" : "");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", "test_mutate");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).fixedSource(persona).autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            delivered.add(prompt);
            int call = providerCalls.incrementAndGet();
            List<UserMessage> snapshots = prompt.getInstructions().stream()
                    .filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                    .filter(FixedContextSession::isFixed).toList();
            assertEquals(1, snapshots.size());
            assertTrue(snapshots.getFirst().getText().contains("PERSONA_VERSION_ONE"));
            assertFalse(snapshots.getFirst().getText().contains("PERSONA_VERSION_TWO"));
            assertEquals("memory.persona", snapshots.getFirst().getMetadata()
                    .get(FixedContextSession.SOURCE_METADATA));
            return call == 1 ? toolCallResponse(2, 1) : textResponse("done", 2, 1);
        }, toolCallBudget(2), new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("use my preference")),
                    Map.of(), PermissionSet.of("tool.read", "tool.execute"));
            RunHandle handle = fixture.engine.start(request);
            assertEquals(RunState.WAITING_APPROVAL, fixture.engine.get(handle.id()).state());
            assertEquals(1, reads.get());
            current.set("PERSONA_VERSION_TWO");
            fixture.restart();
            var approval = JsonNodeFactory.instance.objectNode().put("approved", true)
                    .put("fingerprint", ToolInvocationFingerprint.create("test_mutate",
                            JsonNodeFactory.instance.objectNode().put("value", 1)));
            RunHandle resumed = fixture.engine.resume(handle.id(),
                    new ResumeCommand("tool.approval", approval));
            RunOutcome outcome = resumed.completion().toCompletableFuture().get();

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(2, providerCalls.get());
            assertEquals(1, reads.get());
            assertEquals(2, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.started")).count());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void 固定Persona无授权时跳过且共享额度不足时暂停() throws Exception {
        for (boolean unauthorized : List.of(false, true)) {
            AtomicInteger reads = new AtomicInteger();
            AtomicInteger plannerCalls = new AtomicInteger();
            AtomicInteger providerCalls = new AtomicInteger();
            ModelTaskGateway planner = request -> {
                plannerCalls.incrementAndGet();
                ObjectNode selection = JsonNodeFactory.instance.objectNode();
                selection.putArray("historyIds");
                if (request.purpose().endsWith(".select_v2")) {
                    selection.putArray("searches");
                    toolIntent(selection, unauthorized ? "" : "test_mutate",
                            unauthorized ? "" : "test");
                } else {
                    selection.putArray("sourceIds");
                    toolChoice(selection, request, "direct", "test_mutate");
                }
                return CompletableFuture.completedFuture(new ModelTaskResult(
                        selection, "planner", 1, 1, false, Map.of()));
            };
            FixtureConfig config = new FixtureConfig()
                    .context(contextConfiguration(true, 48_000, 16_000, 1))
                    .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                    .planner(planner).fixedSource(fixedPersona(reads,
                            new AtomicReference<>("PERSONA_BODY"))).autoApproveContext();
            try (Fixture fixture = new Fixture(prompt -> {
                providerCalls.incrementAndGet();
                return textResponse("unexpected", 1, 1);
            }, toolCallBudget(1), new AtomicInteger(), config)) {
                PermissionSet ceiling = unauthorized ? PermissionSet.of("tool.execute")
                        : PermissionSet.of("tool.read", "tool.execute");
                RunRequest request = fixture.request(List.of(InputBlock.text("use my preference")),
                        Map.of(), ceiling);
                RunHandle handle = fixture.engine.start(request);
                RunSnapshot snapshot = fixture.engine.get(handle.id());

                if (unauthorized) {
                    assertEquals(RunState.COMPLETED, snapshot.state(), snapshot.error());
                } else {
                    assertEquals(RunState.PAUSED, snapshot.state());
                    assertEquals("context.planning_required", snapshot.output().path("kind").asText());
                    assertTrue(snapshot.output().path("reason").asText().contains("budget"));
                }
                assertEquals(0, reads.get());
                assertEquals(unauthorized ? 1 : 0, providerCalls.get());
                assertEquals(1, plannerCalls.get());
            }
        }
    }

    @Test
    void 无工具且零额度的Run跳过固定Persona并完成模型调用() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger plannerCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            assertTrue(request.purpose().endsWith(".select_v2"));
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("searches");
            selection.putArray("historyIds");
            toolIntent(selection, "");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).fixedSource(fixedPersona(reads,
                        new AtomicReference<>("PERSONA_BODY")));
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            assertTrue(prompt.getInstructions().stream()
                    .noneMatch(message -> message instanceof UserMessage user
                            && FixedContextSession.isFixed(user)));
            return textResponse("done", 1, 1);
        }, toolCallBudget(0), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request(
                    List.of(InputBlock.text("tool-free request")),
                    Map.of("framework.disableTools", JsonNodeFactory.instance.booleanNode(true)),
                    PermissionSet.NONE));
            RunOutcome outcome = handle.completion().toCompletableFuture().get();

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(0, reads.get());
            assertEquals(1, plannerCalls.get());
            assertEquals(1, providerCalls.get());
        }
    }

    private static FixedContextSource fixedPersona(AtomicInteger reads,
            AtomicReference<String> current) {
        return new FixedContextSource() {
            @Override public String id() { return "memory.persona"; }
            @Override public String group() { return "memory"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("tool.read");
            }
            @Override public FixedContextSnapshot read(RunRequest request) {
                reads.incrementAndGet();
                String body = current.get();
                return new FixedContextSnapshot(sha256(body), body);
            }
        };
    }

    @Test
    void 最终模型检查点在固定上下文耗尽工具额度后仍可恢复() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicBoolean pauseOnce = new AtomicBoolean();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("searches");
            selection.putArray("historyIds");
            toolIntent(selection, "");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner)
                .fixedSource(fixedPersona(reads, new AtomicReference<>("PERSONA_BODY")))
                .autoApproveContext().fillerTools(2)
                .outputGuard((output, request, runId) -> {
                    if (pauseOnce.compareAndSet(false, true)) {
                        throw new ToolInputRequiredException(
                                JsonNodeFactory.instance.objectNode().put("kind", "test.pause"),
                                "pause after final model checkpoint");
                    }
                    return output;
                });
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            assertEquals(List.of(), toolNames(prompt));
            return textResponse("done", 2, 1);
        }, toolCallBudget(1), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("use my preference"));
            assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state());
            assertEquals(1, reads.get());
            assertEquals(1, providerCalls.get());
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.TOOL).count());

            fixture.restart();
            RunHandle resumed = fixture.engine.resume(handle.id(),
                    new ResumeCommand("delegation.continue", JsonNodeFactory.instance.objectNode()));
            RunOutcome outcome = resumed.completion().toCompletableFuture().get();

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals("done", outcome.output().path("text").asText());
            assertEquals(1, reads.get());
            assertEquals(1, providerCalls.get());
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.MODEL).count());
            assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.model.started")).count());
        }
    }

    @Test
    void 工作区技能与知识正文保留不同用途且均为用户级消息() throws Exception {
        DeferredContextSource skill = contextSource("skills", "SKILL_WORKFLOW_BODY",
                DeferredContextUse.USER_WORKFLOW);
        DeferredContextSource knowledge = contextSource("knowledge", "KNOWLEDGE_EVIDENCE_BODY",
                DeferredContextUse.REFERENCE);
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches").addObject().put("source", "skills")
                        .put("query", "workflow");
                selection.withArray("searches").addObject().put("source", "knowledge")
                        .put("query", "evidence");
                toolIntent(selection, "");
            } else {
                selection.putArray("sourceIds").add("skills:one").add("knowledge:one");
                toolChoice(selection, request, "none");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        List<Prompt> delivered = new ArrayList<>();
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(skill).source(knowledge).autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            delivered.add(prompt);
            assertTrue(prompt.getInstructions().stream()
                    .filter(org.springframework.ai.chat.messages.SystemMessage.class::isInstance)
                    .noneMatch(message -> message.getText().contains("SKILL_WORKFLOW_BODY")
                            || message.getText().contains("KNOWLEDGE_EVIDENCE_BODY")));
            Map<String, UserMessage> selected = prompt.getInstructions().stream()
                    .filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                    .filter(message -> Boolean.TRUE.equals(message.getMetadata().get(
                            OnDemandContextSession.CONTEXT_METADATA)))
                    .collect(java.util.stream.Collectors.toMap(message -> message.getText().contains(
                            "SKILL_WORKFLOW_BODY") ? "skill" : "knowledge", message -> message));
            assertEquals("USER_WORKFLOW", selected.get("skill").getMetadata().get(
                    OnDemandContextSession.CONTEXT_USE_METADATA));
            assertTrue(selected.get("skill").getText().contains("User-configured workspace skill"));
            assertEquals("REFERENCE", selected.get("knowledge").getMetadata().get(
                    OnDemandContextSession.CONTEXT_USE_METADATA));
            assertTrue(selected.get("knowledge").getText().contains("Untrusted reference material"));
            return textResponse("done", 2, 1);
        }, toolCallBudget(4), new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("use workflow evidence")),
                    Map.of(), PermissionSet.of("context.read"));
            RunHandle handle = fixture.engine.start(request);
            RunOutcome outcome = handle.completion().toCompletableFuture().get();
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    private static DeferredContextSource contextSource(String id, String body,
            DeferredContextUse use) {
        return new DeferredContextSource() {
            @Override public String id() { return id; }
            @Override public String description() { return id; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                return List.of(new DeferredContextCandidate("one", "v1", id + " summary",
                        PermissionSet.NONE, use));
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                return body;
            }
        };
    }

    @Test
    void 超长记忆正文只截取模型上下文且持久读取保留完整证据() throws Exception {
        String body = "Prior conversation evidence turn-1\nUser: 上海亲子游\nAssistant: 先查开放时间"
                + "\nTool evidence: " + "x".repeat(20_000) + "TRACE_END";
        String version = sha256(body);
        DeferredContextSource memory = new DeferredContextSource() {
            @Override public String id() { return "memory"; }
            @Override public String description() { return "Prior conversations"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                return List.of(new DeferredContextCandidate(
                        "one", version, "上海亲子游", PermissionSet.NONE));
            }
            @Override public String fetch(RunRequest request, String id, String selectedVersion) {
                assertEquals("one", id);
                assertEquals(version, selectedVersion);
                return body;
            }
        };
        ModelTaskGateway planner = selectContext("memory");
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode()
                        .put("enabled", true).put("selectedBodyChars", 12_000))
                .planner(planner).source(memory).autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            List<UserMessage> selected = deferredMessages(prompt);
            assertEquals(1, selected.size());
            String excerpt = selected.getFirst().getText();
            assertTrue(excerpt.contains("User: 上海亲子游"));
            assertTrue(excerpt.contains("[Reference content truncated; middle omitted]"));
            assertTrue(excerpt.contains("TRACE_END"));
            assertFalse(excerpt.contains(body));
            assertTrue(excerpt.substring(excerpt.indexOf('\n') + 1).length() <= 12_000);
            return textResponse("done", 2, 1);
        }, toolCallBudget(2), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request(
                    List.of(InputBlock.text("上海亲子游")), Map.of(), PermissionSet.of("context.read")));
            RunOutcome outcome = handle.completion().toCompletableFuture().get();
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            AgentStep read = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.TOOL
                            && step.input().path("tool").asText().startsWith("framework_context_fetch_"))
                    .findFirst().orElseThrow();
            assertEquals(body, read.output().path("rawOutput").path("body").asText());
            assertEquals(body, read.output().path("modelOutput").path("body").asText());
        }
    }

    @Test
    void 多项参考正文共享低预算而工作流超限仍暂停() throws Exception {
        String first = "MEMORY_START" + "a".repeat(1_600) + "MEMORY_END";
        String second = "KNOWLEDGE_START" + "b".repeat(1_600) + "KNOWLEDGE_END";
        FixtureConfig references = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode()
                        .put("enabled", true).put("selectedBodyChars", 1_000))
                .planner(selectContext("memory", "knowledge"))
                .source(contextSource("memory", first, DeferredContextUse.REFERENCE))
                .source(contextSource("knowledge", second, DeferredContextUse.REFERENCE))
                .autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            List<UserMessage> selected = deferredMessages(prompt);
            assertEquals(2, selected.size());
            assertTrue(selected.stream().allMatch(message -> message.getText().contains(
                    "[Reference content truncated; middle omitted]")));
            assertTrue(selected.stream().anyMatch(message -> message.getText().contains("MEMORY_START")));
            assertTrue(selected.stream().anyMatch(message -> message.getText().contains("KNOWLEDGE_START")));
            int selectedChars = selected.stream().mapToInt(message ->
                    message.getText().substring(message.getText().indexOf('\n') + 1).length()).sum();
            assertTrue(selectedChars <= 1_000);
            return textResponse("done", 2, 1);
        }, toolCallBudget(4), new AtomicInteger(), references)) {
            RunOutcome outcome = fixture.engine.start(fixture.request(
                    List.of(InputBlock.text("use memory and knowledge")), Map.of(),
                    PermissionSet.of("context.read"))).completion().toCompletableFuture().get();
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
        }

        AtomicInteger providerCalls = new AtomicInteger();
        String workflow = "WORKFLOW_START" + "w".repeat(1_600) + "WORKFLOW_END";
        FixtureConfig instructions = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode()
                        .put("enabled", true).put("selectedBodyChars", 1_000))
                .planner(selectContext("skills"))
                .source(contextSource("skills", workflow, DeferredContextUse.USER_WORKFLOW))
                .autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 2, 1);
        }, toolCallBudget(2), new AtomicInteger(), instructions)) {
            RunHandle handle = fixture.engine.start(fixture.request(
                    List.of(InputBlock.text("use workflow")), Map.of(),
                    PermissionSet.of("context.read")));
            RunSnapshot snapshot = fixture.engine.get(handle.id());
            assertEquals(RunState.PAUSED, snapshot.state());
            assertEquals("context.planning_required", snapshot.output().path("kind").asText());
            assertTrue(snapshot.output().path("reason").asText().contains("workflow"));
            assertEquals(0, providerCalls.get());
        }
    }

    private static ModelTaskGateway selectContext(String... sources) {
        return request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                var searches = selection.putArray("searches");
                for (String source : sources) {
                    searches.addObject().put("source", source).put("query", source);
                }
                toolIntent(selection, "");
            } else {
                var sourceIds = selection.putArray("sourceIds");
                for (String source : sources) sourceIds.add(source + ":one");
                toolChoice(selection, request, "none");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
    }

    private static List<UserMessage> deferredMessages(Prompt prompt) {
        return prompt.getInstructions().stream().filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .filter(message -> Boolean.TRUE.equals(message.getMetadata().get(
                        OnDemandContextSession.CONTEXT_METADATA))).toList();
    }

    @Test
    void 输入恢复后上下文读取审批不丢失最新用户补充() throws Exception {
        AtomicInteger resumedSelections = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                boolean resumed = request.input().has("latestUserInput");
                if (resumed) {
                    assertTrue(request.input().path("latestUserInput").asText().contains("上海明天"));
                    resumedSelections.incrementAndGet();
                    selection.putArray("searches").addObject()
                            .put("source", "docs").put("query", "family trip");
                    toolIntent(selection, "");
                } else {
                    selection.putArray("searches");
                    toolIntent(selection, "code_target", "code");
                }
            } else {
                var sourceIds = selection.putArray("sourceIds");
                if (request.input().path("candidates").toString().contains("docs:one")) {
                    sourceIds.add("docs:one");
                    toolChoice(selection, request, "none");
                } else {
                    toolChoice(selection, request, "direct", "code_target");
                }
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner)
                .source(contextSource("docs", "family trip evidence", DeferredContextUse.REFERENCE))
                .downstreamMutation(DownstreamMutation.PAUSE_AFTER_ACTIVATION_ONCE);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            assertTrue(prompt.getInstructions().stream()
                    .filter(UserMessage.class::isInstance)
                    .anyMatch(message -> message.getText().contains("上海明天")));
            return textResponse("done", 2, 1);
        }, toolCallBudget(4), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request(
                    List.of(InputBlock.text("准备出游")), Map.of(),
                    PermissionSet.of("context.read", "tool.execute")));
            assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state(),
                    fixture.engine.get(handle.id()).error());
            assertEquals(0, providerCalls.get());

            handle = fixture.engine.resume(handle.id(), new ResumeCommand("input",
                    JsonNodeFactory.instance.objectNode().put("text", "上海明天")));
            assertEquals(RunState.WAITING_APPROVAL, fixture.engine.get(handle.id()).state());
            assertEquals(1, resumedSelections.get());
            fixture.restart();
            assertTrue(Set.of(RunState.WAITING_APPROVAL, RunState.PAUSED)
                    .contains(fixture.engine.get(handle.id()).state()));
            for (int attempt = 0; attempt < 3
                    && Set.of(RunState.WAITING_APPROVAL, RunState.PAUSED)
                            .contains(fixture.engine.get(handle.id()).state()); attempt++) {
                var waiting = fixture.runs.eventsAfter(handle.id(), 0).stream()
                        .filter(event -> event.type().equals("core.run.waiting_approval"))
                        .reduce((previous, next) -> next).orElseThrow();
                String fingerprint = ToolApprovalChallenge.fromEventPayload(
                        waiting.payload()).fingerprint();
                handle = fixture.engine.resume(handle.id(), new ResumeCommand("tool.approval",
                        JsonNodeFactory.instance.objectNode().put("approved", true)
                                .put("fingerprint", fingerprint)));
            }
            RunOutcome outcome = handle.completion().toCompletableFuture().get();
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(1, resumedSelections.get(),
                    "the persisted selection must be reused after context-tool approval");
            assertEquals(1, providerCalls.get());
        }
    }

    @Test
    void 已耗尽工具额度时规划器要求目录发现会在第二次主模型调用前暂停() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (providerCalls.get() == 0) {
                if (request.purpose().endsWith(".select_v2")) {
                    selection.putArray("searches");
                    toolIntent(selection, "test_mutate", "test");
                } else {
                    selection.putArray("sourceIds");
                    toolChoice(selection, request, "direct", "test_mutate");
                }
            } else if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, "unknown code target search", "code");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "discover");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).autoApproveTestMutate();
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return toolCallResponse(2, 1);
        }, toolCallBudget(1), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("call then discover"));
            RunSnapshot snapshot = fixture.engine.get(handle.id());

            assertEquals(RunState.PAUSED, snapshot.state());
            assertEquals(1, providerCalls.get());
            assertTrue(snapshot.output().path("reason").asText().contains("budget"));
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.MODEL).count());
        }
    }

    @Test
    void 工具全禁用时规划器要求目录发现不会空目录调用主模型() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, "nonexistent target");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "discover");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("discover target", Map.of(
                    "framework.disableTools", JsonNodeFactory.instance.booleanNode(true))));
            RunSnapshot snapshot = fixture.engine.get(handle.id());

            assertEquals(RunState.PAUSED, snapshot.state());
            assertTrue(snapshot.output().path("reason").asText().contains("no authorized tool catalog"));
            assertEquals(0, providerCalls.get());
        }
    }

    @Test
    void successiveApprovalResumesRetainEarlierToolMessagesAndAtomicSteps() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger tools = new AtomicInteger();
        AtomicReference<Prompt> finalPrompt = new AtomicReference<>();
        ChatModel model = prompt -> {
            int number = calls.incrementAndGet();
            if (number <= 2) return namedToolCallResponse("test_mutate", "{\"value\":" + number + "}", 2, 1);
            finalPrompt.set(prompt);
            return textResponse("done", 2, 1);
        };
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, tools)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            for (int number = 1; number <= 2; number++) {
                assertEquals(RunState.WAITING_APPROVAL, fixture.engine.get(handle.id()).state());
                if (number == 2) fixture.restart();
                var approval = JsonNodeFactory.instance.objectNode().put("approved", true)
                        .put("fingerprint", ToolInvocationFingerprint.create("test_mutate",
                                JsonNodeFactory.instance.objectNode().put("value", number)));
                handle = fixture.engine.resume(handle.id(), new ResumeCommand("tool.approval", approval));
            }
            assertEquals(RunState.COMPLETED, handle.completion().toCompletableFuture().get().state());
            assertEquals(2, tools.get());
            assertEquals(3, calls.get());
            assertEquals(2, finalPrompt.get().getInstructions().stream().filter(ToolResponseMessage.class::isInstance).count());
            var steps = new RunStepQuery(fixture.runs).steps(handle.id());
            assertEquals(3, steps.stream().filter(step -> step.kind() == AgentStep.Kind.MODEL).count());
            assertEquals(2, steps.stream().filter(step -> step.kind() == AgentStep.Kind.TOOL).count());
            assertTrue(steps.stream().allMatch(step -> step.state() == AgentStep.State.COMPLETED));
            org.junit.jupiter.api.Assertions.assertNull(steps.getFirst().causationStepId());
            for (int index = 1; index < steps.size(); index++)
                assertEquals(steps.get(index - 1).id().value(), steps.get(index).causationStepId());
            assertTrue(steps.stream().filter(step -> step.kind() == AgentStep.Kind.TOOL)
                    .allMatch(step -> step.causationStepId() != null && step.output().has("modelOutput")));
            assertTrue(steps.stream().filter(step -> step.kind() == AgentStep.Kind.MODEL)
                    .allMatch(step -> step.input().path("messages").isArray() && step.output().has("message")));
        }
    }

    @Test void controlOnlyRecoveryUsesPersistedFinalResponseWithoutAnotherModelRequest() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        try (Fixture fixture = new Fixture(prompt -> {
            modelCalls.incrementAndGet(); return toolCallResponse(2, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger())) {
            RunHandle turn = fixture.engine.start(fixture.request());
            // Simulate a provider response durably written immediately before the process died.
            var events = StepEvents.durableSink(fixture.runs, turn.id());
            StepId finalStep = StepId.random();
            ObjectNode finalInput = (ObjectNode) new RunStepQuery(fixture.runs).steps(turn.id())
                    .stream().filter(step -> step.kind() == AgentStep.Kind.MODEL)
                    .reduce((previous, current) -> current).orElseThrow().input().deepCopy();
            finalInput.set("toolNames", JsonNodeFactory.instance.arrayNode());
            finalInput.set("toolFingerprints", JsonNodeFactory.instance.objectNode());
            finalInput.remove("toolCandidateStepId");
            StepEvents.started(events, finalStep, AgentStep.Kind.MODEL, finalInput, null);
            ChatResponse finalResponse = textResponse("already finished", 3, 1);
            StepEvents.completed(events, finalStep, StepMessageCodec.response(finalResponse), StepMessageCodec.usage(finalResponse));
            fixture.runs.append(turn.id(), Set.of(RunState.WAITING_APPROVAL), RunState.PAUSED,
                    new com.javaclaw.framework.spi.RunEventDraft("core.run.paused", 1, "test", null, null,
                            JsonNodeFactory.instance.objectNode().put("reason", "PROCESS_RESTART_REQUIRES_RESUME")), null, null);
            fixture.restart();
            var resumed = fixture.engine.resume(turn.id(), new ResumeCommand("delegation.continue", JsonNodeFactory.instance.objectNode()));
            assertEquals(RunState.COMPLETED, resumed.completion().toCompletableFuture()
                    .get(10, java.util.concurrent.TimeUnit.SECONDS).state());
            assertEquals(1, modelCalls.get());
            assertEquals("already finished", fixture.engine.get(turn.id()).output().path("text").asText());
        }
    }

    @Test void lateProviderResponseAfterCancellationSettlesOnceAndItsParentBudgetSurvivesRestart() {
        AtomicReference<Fixture> active = new AtomicReference<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ChatModel ignoresCancellation = prompt -> {
            modelCalls.incrementAndGet();
            Fixture fixture = active.get();
            RunId child = fixture.runs.nonTerminalRuns().stream()
                    .filter(run -> run.request().scope().sessionId().equals("late-child"))
                    .findFirst().orElseThrow().snapshot().id();
            fixture.engine.cancel(child, new CancelReason("TEST", "provider still returning"));
            return textResponse("late but billable", 7, 2);
        };
        try (Fixture fixture = new Fixture(ignoresCancellation, RunBudget.UNBOUNDED, new AtomicInteger())) {
            active.set(fixture);
            ManagedTurn parent = fixture.engine.beginTurn(fixture.request());
            RunRequest childRequest = RunRequest.builder()
                    .agent(AgentDefinitionRef.latest("test.agent")).profile(RunProfileRef.latest("test.profile"))
                    .source(new InvocationSource("subagent", "late-child"))
                    .scope(new RunScope("workspace", "user", "late-child"))
                    .linkage(new RunLinkage(parent.id(), null, "late-charge"))
                    .input(InputBlock.text("finish once")).permissionCeiling(PermissionSet.UNRESTRICTED).build();
            RunHandle child = fixture.engine.start(childRequest);
            assertEquals(RunState.CANCELLED, fixture.engine.get(child.id()).state());
            var step = new RunStepQuery(fixture.runs).steps(child.id()).getFirst();
            assertEquals(AgentStep.State.COMPLETED, step.state());
            assertEquals("late but billable", step.output().path("message").path("text").asText());
            assertEquals(7, step.usage().path("inputTokens").asLong());
            assertEquals(7, fixture.ledger.aggregateSnapshot(parent.id()).inputTokens());
            var events = fixture.runs.eventsAfter(child.id(), 0);
            assertEquals(1, events.stream().filter(event -> event.type().equals("core.run.cancelled")).count());
            assertEquals(0, events.stream().filter(event -> event.type().equals("core.run.completed")).count());
            assertEquals(1, events.stream().filter(event -> event.type().equals("core.step.completed")).count());
            assertEquals(0, events.stream().filter(event -> event.type().equals("core.model.usage")).count());
            parent.close();
            fixture.restart();
            assertEquals(7, fixture.ledger.snapshot(child.id()).inputTokens());
            assertEquals(7, fixture.ledger.aggregateSnapshot(parent.id()).inputTokens());
            assertEquals(1, modelCalls.get());
        }
    }

    @Test void redactedPendingArgumentsPauseButCompletedToolsReplayWithoutParsingCredentials() throws Exception {
        for (boolean toolCompleted : List.of(false, true)) {
            AtomicInteger calls = new AtomicInteger(), tools = new AtomicInteger();
            try (Fixture fixture = new Fixture(prompt -> calls.incrementAndGet() == 1
                    ? toolCallResponse(2, 1) : textResponse("done", 2, 1), RunBudget.UNBOUNDED, tools)) {
                RunHandle turn = fixture.engine.start(fixture.request());
                StepId modelStep = StepId.random();
                var events = StepEvents.durableSink(fixture.runs, turn.id());
                ObjectNode modelInput = JsonNodeFactory.instance.objectNode();
                modelInput.set("messages", StepMessageCodec.messages(List.of(
                        new org.springframework.ai.chat.messages.SystemMessage("test"),
                        new UserMessage("task"))));
                modelInput.putArray("toolNames").add("test_mutate");
                StepEvents.started(events, modelStep, AgentStep.Kind.MODEL, modelInput, null);
                ChatResponse response = namedToolCallResponse("test_mutate", "{\"value\":1,\"token\":\"sk-testcredential123456789\"}", 2, 1);
                StepEvents.completed(events, modelStep, StepMessageCodec.response(response), StepMessageCodec.usage(response));
                assertTrue(fixture.runs.eventsAfter(turn.id(), 0).getLast().payload().path("credentialRedacted").asBoolean());
                if (toolCompleted) {
                    StepId tool = StepId.tool(turn.id(), "model/" + modelStep.value() + "/provider-call");
                    StepEvents.started(events, tool, AgentStep.Kind.TOOL, JsonNodeFactory.instance.objectNode(), modelStep.value());
                    StepEvents.completed(events, tool, JsonNodeFactory.instance.objectNode()
                            .set("modelOutput", JsonNodeFactory.instance.objectNode().put("success", true)), null);
                }
                fixture.runs.append(turn.id(), Set.of(RunState.WAITING_APPROVAL), RunState.PAUSED,
                        new RunEventDraft("core.run.paused", 1, "test", null, null,
                                JsonNodeFactory.instance.objectNode().put("reason", "PROCESS_RESTART_REQUIRES_RESUME")), null, null);
                fixture.restart();
                fixture.engine.resume(turn.id(), new ResumeCommand("delegation.continue", JsonNodeFactory.instance.objectNode()));
                assertEquals(toolCompleted ? RunState.COMPLETED : RunState.PAUSED, fixture.engine.get(turn.id()).state());
                assertEquals(toolCompleted ? 2 : 1, calls.get());
                assertEquals(0, tools.get(), "redacted credentials must never be sent to a tool");
                if (!toolCompleted) assertEquals("tool.recovery_required", fixture.engine.get(turn.id()).output().path("kind").asText());
            }
        }
    }

    @Test
    void interruptedToolWithUnknownOutcomeIsPausedAndNeverAutomaticallyRepeated() {
        AtomicInteger tools = new AtomicInteger();
        try (Fixture fixture = new Fixture(prompt -> toolCallResponse(2, 1), RunBudget.UNBOUNDED, tools)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            AgentStep model = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.MODEL).findFirst().orElseThrow();
            String invocation = "model/" + model.id().value() + "/provider-call";
            StepId toolId = StepId.tool(handle.id(), invocation);
            StepEvents.started(StepEvents.durableSink(fixture.runs, handle.id()), toolId, AgentStep.Kind.TOOL,
                    JsonNodeFactory.instance.objectNode().put("tool", "test_mutate").put("invocationId", invocation),
                    model.id().value());
            var approval = JsonNodeFactory.instance.objectNode().put("approved", true)
                    .put("fingerprint", ToolInvocationFingerprint.create("test_mutate",
                            JsonNodeFactory.instance.objectNode().put("value", 1)));
            fixture.engine.resume(handle.id(), new ResumeCommand("tool.approval", approval));
            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals(0, tools.get());
            assertEquals("tool.recovery_required", fixture.engine.get(handle.id()).output().path("kind").asText());
            assertEquals(AgentStep.State.RUNNING, new RunStepQuery(fixture.runs).step(handle.id(), toolId).orElseThrow().state());
        }
    }

    @Test
    void approvedCallExecutesExactlyOnceBeforeTheNextModelRequest() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        AtomicReference<Prompt> continuedPrompt = new AtomicReference<>();
        ChatModel model = prompt -> {
            int call = modelCalls.incrementAndGet();
            if (call == 1) return toolCallResponse(7, 3);
            assertEquals(1, toolCalls.get(),
                    "the approved invocation must execute before asking the model again");
            continuedPrompt.set(prompt);
            return textResponse("done", 5, 2);
        };

        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, toolCalls)) {
            var handle = fixture.engine.start(fixture.request());
            assertEquals(RunState.WAITING_APPROVAL, fixture.engine.get(handle.id()).state());

            ObjectNode approval = JsonNodeFactory.instance.objectNode();
            approval.put("approved", true);
            approval.put("fingerprint", ToolInvocationFingerprint.create(
                    "test_mutate", JsonNodeFactory.instance.objectNode().put("value", 1)));
            var resumed = fixture.engine.resume(
                    handle.id(), new ResumeCommand("tool.approval", approval));
            RunOutcome outcome = resumed.completion().toCompletableFuture().get();

            assertEquals(RunState.COMPLETED, outcome.state());
            assertEquals(2, modelCalls.get());
            assertEquals(1, toolCalls.get());
            List<org.springframework.ai.chat.messages.Message> instructions =
                    continuedPrompt.get().getInstructions();
            assertInstanceOf(AssistantMessage.class,
                    instructions.get(instructions.size() - 2));
            AssistantMessage assistant = (AssistantMessage) instructions.get(
                    instructions.size() - 2);
            assertEquals("test_mutate", assistant.getToolCalls().getFirst().name());
            assertEquals("{\"value\":1}", assistant.getToolCalls().getFirst().arguments());
            ToolResponseMessage response = assertInstanceOf(
                    ToolResponseMessage.class, instructions.getLast());
            assertEquals(assistant.getToolCalls().getFirst().id(),
                    response.getResponses().getFirst().id());

            assertEquals(2, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.model.usage")).count());
            assertEquals(12, outcome.output().path("usage").path("inputTokens").asLong());
            assertEquals(5, outcome.output().path("usage").path("outputTokens").asLong());
            assertEquals(12, fixture.ledger.snapshot(handle.id()).inputTokens());
            assertEquals(5, fixture.ledger.snapshot(handle.id()).outputTokens());
        }
    }

    @Test
    void unadvertisedWebContentCallWithNoCallbacksReturnsUnavailableFeedback() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ChatModel model = prompt -> {
            delivered.add(prompt);
            assertEquals(List.of(), toolNames(prompt));
            return switch (modelCalls.incrementAndGet()) {
                case 1 -> namedToolCallResponse("web_content", "{\"url\":\"https://example.com\"}", 2, 1);
                case 2 -> {
                    ToolResponseMessage.ToolResponse feedback = lastToolResponse(prompt);
                    assertEquals("provider-call", feedback.id());
                    assertEquals("web_content", feedback.name());
                    assertTrue(feedback.responseData().contains("web_content"));
                    yield textResponse("上海", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };

        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, toolCalls)) {
            RunHandle handle = fixture.engine.start(fixture.request("上海", Map.of(
                    "framework.disableTools", JsonNodeFactory.instance.booleanNode(true))));
            RunOutcome outcome = handle.completion().toCompletableFuture().get();

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals("上海", outcome.output().path("text").asText());
            assertEquals(2, modelCalls.get());
            assertEquals(0, toolCalls.get());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .noneMatch(event -> event.type().equals("core.tool.started")));
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void unadvertisedToolInBatchRejectsBeforeVisibleToolCanExecute() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        ChatModel model = prompt -> switch (modelCalls.incrementAndGet()) {
            case 1 -> {
                assertTrue(toolNames(prompt).contains("test_mutate"));
                AssistantMessage output = AssistantMessage.builder().content("")
                        .toolCalls(List.of(
                                new AssistantMessage.ToolCall("visible", "function",
                                        "test_mutate", "{\"value\":1}"),
                                new AssistantMessage.ToolCall("unadvertised", "function",
                                        "web_content", "{\"url\":\"https://example.com\"}")))
                        .build();
                yield response(output, 2, 1);
            }
            case 2 -> {
                assertEquals(0, toolCalls.get(),
                        "the visible tool must not run before the entire batch is validated");
                assertTrue(prompt.getInstructions().stream()
                        .filter(ToolResponseMessage.class::isInstance)
                        .map(ToolResponseMessage.class::cast)
                        .flatMap(message -> message.getResponses().stream())
                        .anyMatch(item -> item.id().equals("unadvertised")
                                && item.name().equals("web_content")
                                && item.responseData().contains("web_content")));
                yield textResponse("done", 2, 1);
            }
            default -> throw new AssertionError("unexpected provider call");
        };
        FixtureConfig config = new FixtureConfig().autoApproveTestMutate();
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, toolCalls, config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            RunOutcome outcome = handle.completion().toCompletableFuture().get();

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(2, modelCalls.get());
            assertEquals(0, toolCalls.get());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .noneMatch(event -> event.type().equals("core.tool.started")
                            && event.payload().path("tool").asText().equals("test_mutate")));
        }
    }

    @Test
    void rejectedUnadvertisedCallSurvivesRestartBeforeNextProviderRequest() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        AtomicReference<String> resumedFeedback = new AtomicReference<>();
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .downstreamMutation(DownstreamMutation.PAUSE_AFTER_UNAVAILABLE_FEEDBACK_ONCE);
        ChatModel model = prompt -> switch (modelCalls.incrementAndGet()) {
            case 1 -> namedToolCallResponse("web_content", "{}", 2, 1);
            case 2 -> {
                String feedback = prompt.getInstructions().stream()
                        .filter(ToolResponseMessage.class::isInstance)
                        .map(ToolResponseMessage.class::cast)
                        .flatMap(message -> message.getResponses().stream())
                        .filter(item -> item.name().equals("web_content"))
                        .map(ToolResponseMessage.ToolResponse::responseData)
                        .findFirst().orElseThrow();
                resumedFeedback.set(feedback);
                yield textResponse("done", 2, 1);
            }
            default -> throw new AssertionError("unexpected provider call");
        };
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, toolCalls, config)) {
            RunHandle handle = fixture.engine.start(fixture.request("上海", Map.of(
                    "framework.disableTools", JsonNodeFactory.instance.booleanNode(true))));
            assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state());
            assertEquals(1, modelCalls.get());
            assertEquals(0, toolCalls.get());
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.MODEL
                            && step.state() == AgentStep.State.COMPLETED).count());
            Prompt paused = config.pausedPrompt.get();
            assertNotNull(paused);
            String persistedFeedback = lastToolResponse(paused).responseData();
            assertTrue(persistedFeedback.contains("web_content"));

            fixture.restart();
            RunHandle resumed = fixture.engine.resume(handle.id(), new ResumeCommand(
                    "delegation.continue", JsonNodeFactory.instance.objectNode()));
            RunOutcome outcome = resumed.completion().toCompletableFuture().get();

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals("done", outcome.output().path("text").asText());
            assertEquals(2, modelCalls.get());
            assertEquals(0, toolCalls.get());
            assertEquals(persistedFeedback, resumedFeedback.get());
        }
    }

    @Test
    void repeatedUnadvertisedCallsPauseAfterBoundedFeedback() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        try (Fixture fixture = new Fixture(prompt -> {
            assertEquals(List.of(), toolNames(prompt));
            modelCalls.incrementAndGet();
            return namedToolCallResponse("web_content", "{}", 2, 1);
        }, RunBudget.UNBOUNDED, toolCalls)) {
            RunHandle handle = fixture.engine.start(fixture.request("上海", Map.of(
                    "framework.disableTools", JsonNodeFactory.instance.booleanNode(true))));
            RunSnapshot snapshot = fixture.engine.get(handle.id());

            assertEquals(RunState.PAUSED, snapshot.state());
            assertEquals("tool.recovery_required", snapshot.output().path("kind").asText());
            assertEquals(3, modelCalls.get(), "two feedback batches then stop the next bad batch");
            assertEquals(0, toolCalls.get());
            assertEquals(2, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.output().path("rejectedUnavailableToolBatch").asBoolean())
                    .count());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .noneMatch(event -> event.type().equals("core.tool.started")));
        }
    }

    @Test
    void overBudgetToolCallResponseIsMeteredBeforeAnyToolExecutes() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        ChatModel model = prompt -> {
            modelCalls.incrementAndGet();
            return toolCallResponse(7, 3);
        };
        RunBudget budget = new RunBudget(
                Duration.ofMinutes(1), 5, 100, 4, BigDecimal.TEN);

        try (Fixture fixture = new Fixture(model, budget, toolCalls)) {
            var handle = fixture.engine.start(fixture.request());
            RunOutcome outcome = handle.completion().toCompletableFuture().get();

            assertEquals(RunState.FAILED, outcome.state());
            assertTrue(outcome.error().contains(BudgetExceededException.class.getName()));
            assertEquals(1, modelCalls.get());
            assertEquals(0, toolCalls.get());
            assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.model.usage")).count());
            assertEquals(7, fixture.ledger.snapshot(handle.id()).inputTokens());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .noneMatch(event -> event.type().equals("core.tool.started")));
        }
    }

    @Test
    void clarificationToolSuspendsWithoutRetryingTheModelOrTool() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        ChatModel model = prompt -> {
            modelCalls.incrementAndGet();
            return namedToolCallResponse(
                    "test_clarify", "{\"question\":\"Which target?\"}", 4, 1);
        };

        try (Fixture fixture = new Fixture(
                model, RunBudget.UNBOUNDED, toolCalls, true)) {
            var handle = fixture.engine.start(fixture.request());
            RunSnapshot snapshot = fixture.engine.get(handle.id());

            assertEquals(RunState.WAITING_INPUT, snapshot.state());
            assertEquals("clarify_request", snapshot.output().path("kind").asText());
            assertEquals("Which target?",
                    snapshot.output().path("payload").path("question").asText());
            assertEquals(1, modelCalls.get(), "clarification is a control signal, not a retryable failure");
            assertEquals(1, toolCalls.get(), "one model tool call must yield one clarification request");
            assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.completed")).count());
            assertEquals(0, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.failed")).count());
        }
    }

    @Test
    void failedManagedInferenceUsageIsRecordedBeforeTheRunFails() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = prompt -> {
            calls.incrementAndGet();
            throw new ManagedInferenceChatModel.ManagedInferenceModelException(
                    "inference_error", "failed", false,
                    new com.javaclaw.inference.api.InferenceUsage(6, 2),
                    "deliverance:test", null);
        };

        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, new AtomicInteger())) {
            var handle = fixture.engine.start(fixture.request());
            RunOutcome outcome = handle.completion().toCompletableFuture().get();

            assertEquals(RunState.FAILED, outcome.state());
            assertEquals(1, calls.get());
            assertEquals(6, fixture.ledger.snapshot(handle.id()).inputTokens());
            assertEquals(2, fixture.ledger.snapshot(handle.id()).outputTokens());
            assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.model.usage")
                            && event.payload().path("failed").asBoolean()).count());
        }
    }

    @Test
    void catalogCanActivateASeventiethToolWithOneVisibleToolAtATime() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (calls.incrementAndGet()) {
                case 1 -> {
                    assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                    yield namedToolCallResponse("framework_tool_catalog",
                            "{\"action\":\"list\",\"query\":\"code_target\"}", 2, 1);
                }
                case 2 -> {
                    assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                    assertTrue(lastToolResponse(prompt).responseData().contains("code_target"));
                    yield namedToolCallResponse("framework_tool_catalog",
                            "{\"action\":\"activate\",\"names\":[\"code_target\"]}", 2, 1);
                }
                case 3 -> {
                    assertEquals(List.of("code_target"), toolNames(prompt));
                    assertTrue(lastToolResponse(prompt).responseData().contains("code_target"));
                    yield namedToolCallResponse("code_target", "{\"value\":42}", 2, 1);
                }
                case 4 -> {
                    assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                    assertEquals("code_target", lastToolResponse(prompt).name());
                    yield textResponse("done", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .fillerTools(70).codeCalls(codeCalls);
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            assertEquals(RunState.COMPLETED, handle.completion().toCompletableFuture().get().state());
            assertEquals(4, calls.get());
            assertEquals(1, codeCalls.get());
            assertEquals(3, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.completed")).count());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .anyMatch(event -> event.type().equals("core.tool.completed")
                            && event.payload().path("tool").asText().equals("framework_tool_catalog")
                            && event.payload().path("output").path("activated").toString()
                                    .contains("code_target")));
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void catalogPagesStayWithinTheResultBudgetAndExposeEveryAuthorizedName() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger pageNumber = new AtomicInteger(1);
        AtomicInteger codeCalls = new AtomicInteger();
        List<String> listed = new ArrayList<>();
        List<Prompt> delivered = new ArrayList<>();
        ChatModel model = prompt -> {
            delivered.add(prompt);
            providerCalls.incrementAndGet();
            if (providerCalls.get() == 1) {
                assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                return namedToolCallResponse("framework_tool_catalog",
                        "{\"action\":\"list\",\"page\":1}", 2, 1);
            }
            var response = lastToolResponse(prompt);
            if (response.name().equals("code_target")) {
                assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                return textResponse("done", 2, 1);
            }
            com.fasterxml.jackson.databind.JsonNode output;
            try {
                output = json.readTree(response.responseData());
            } catch (Exception failure) {
                throw new IllegalStateException("invalid catalog output", failure);
            }
            if (output.path("action").asText().equals("activate")) {
                assertEquals(List.of("code_target"), toolNames(prompt));
                return namedToolCallResponse("code_target", "{\"value\":1}", 2, 1);
            }
            assertEquals("list", output.path("action").asText());
            assertTrue(response.responseData().length() <= 1_000);
            assertEquals(pageNumber.get(), output.path("page").asInt());
            assertEquals(output.path("tools").size(), output.path("pageSize").asInt());
            assertTrue(output.path("pageSize").asInt() > 0);
            output.path("tools").forEach(tool -> listed.add(tool.path("name").asText()));
            if (output.path("hasNext").asBoolean()) {
                return namedToolCallResponse("framework_tool_catalog",
                        "{\"action\":\"list\",\"page\":" + pageNumber.incrementAndGet() + "}", 2, 1);
            }
            assertEquals(72, listed.size());
            assertEquals(72, Set.copyOf(listed).size());
            assertTrue(listed.contains("code_target"));
            return namedToolCallResponse("framework_tool_catalog",
                    "{\"action\":\"activate\",\"names\":[\"code_target\"]}", 2, 1);
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 1_000, 1))
                .fillerTools(70).codeCalls(codeCalls);
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            assertEquals(RunState.COMPLETED, handle.completion().toCompletableFuture().get().state());
            assertTrue(pageNumber.get() > 1);
            assertEquals(1, codeCalls.get());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void catalogActivationSurvivesRestartBeforeTheTargetProviderCall() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (providerCalls.incrementAndGet()) {
                case 1 -> {
                    assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                    yield namedToolCallResponse("framework_tool_catalog",
                            "{\"action\":\"activate\",\"names\":[\"code_target\"]}", 2, 1);
                }
                case 2 -> {
                    assertEquals(List.of("code_target"), toolNames(prompt));
                    assertTrue(lastToolResponse(prompt).responseData().contains("code_target"));
                    yield namedToolCallResponse("code_target", "{\"value\":3}", 2, 1);
                }
                case 3 -> {
                    assertEquals(List.of(), toolNames(prompt));
                    yield textResponse("done", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .fillerTools(70).codeCalls(codeCalls)
                .downstreamMutation(DownstreamMutation.PAUSE_AFTER_ACTIVATION_ONCE);
        try (Fixture fixture = new Fixture(model, toolCallBudget(2), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state());
            assertEquals(1, providerCalls.get());
            assertEquals(1, config.pauseAfterActivation.get());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .anyMatch(event -> event.type().equals("core.tool.completed")
                            && event.payload().path("tool").asText().equals("framework_tool_catalog")
                            && event.payload().path("output").path("activated").toString()
                                    .contains("code_target")));
            fixture.restart();
            RunHandle resumed = fixture.engine.resume(handle.id(),
                    new ResumeCommand("delegation.continue", JsonNodeFactory.instance.objectNode()));
            assertEquals(RunState.COMPLETED, resumed.completion().toCompletableFuture().get().state());
            assertEquals(3, providerCalls.get());
            assertEquals(1, codeCalls.get());
            assertEquals(2, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.started")).count());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void completedCatalogStepRestoresActivationWithoutTheLaterToolEvent() throws Exception {
        for (int maximumCalls : List.of(2, 4)) {
            AtomicInteger providerCalls = new AtomicInteger();
            AtomicInteger codeCalls = new AtomicInteger();
            List<Prompt> delivered = new ArrayList<>();
            ChatModel model = prompt -> {
                delivered.add(prompt);
                return switch (providerCalls.incrementAndGet()) {
                    case 1 -> {
                        assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                        yield namedToolCallResponse("framework_tool_catalog",
                                "{\"action\":\"activate\",\"names\":[\"code_target\"]}", 2, 1);
                    }
                    case 2 -> {
                        assertEquals(List.of("code_target"), toolNames(prompt));
                        yield namedToolCallResponse("code_target", "{\"value\":7}", 2, 1);
                    }
                    case 3 -> {
                        assertEquals(maximumCalls == 2 ? List.of()
                                : List.of("framework_tool_catalog"), toolNames(prompt));
                        assertEquals("code_target", lastToolResponse(prompt).name());
                        yield textResponse("done", 2, 1);
                    }
                    default -> throw new AssertionError("unexpected provider call");
                };
            };
            FixtureConfig config = new FixtureConfig()
                    .context(contextConfiguration(true, 48_000, 16_000, 1))
                    .fillerTools(70).codeCalls(codeCalls)
                    .downstreamMutation(DownstreamMutation.PAUSE_AFTER_ACTIVATION_ONCE);
            try (Fixture fixture = new Fixture(model, toolCallBudget(maximumCalls),
                    new AtomicInteger(), config)) {
                RunHandle handle = fixture.engine.start(fixture.request());
                assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state());
                AgentStep activation = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                        .filter(step -> step.kind() == AgentStep.Kind.TOOL)
                        .findFirst().orElseThrow();
                assertEquals("framework_tool_catalog",
                        new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                                .filter(step -> step.id().equals(activation.id()))
                                .findFirst().orElseThrow().input().path("tool").asText());
                assertTrue(activation.output().path("rawOutput")
                        .path("activated").toString().contains("code_target"));
                long laterToolEvent = fixture.runs.eventsAfter(handle.id(), 0).stream()
                        .filter(event -> event.type().equals("core.tool.completed")
                                && event.payload().path("tool").asText()
                                        .equals("framework_tool_catalog"))
                        .findFirst().orElseThrow().sequence();
                assertTrue(laterToolEvent > activation.lastSequence());
                fixture.jdbc.update("DELETE FROM agent_run_events WHERE run_id=? AND event_sequence=?",
                        handle.id().value(), laterToolEvent);

                fixture.restart();
                RunHandle resumed = fixture.engine.resume(handle.id(),
                        new ResumeCommand("delegation.continue", JsonNodeFactory.instance.objectNode()));
                RunOutcome outcome = resumed.completion().toCompletableFuture().get();

                assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
                assertEquals(3, providerCalls.get());
                assertEquals(1, codeCalls.get());
                assertEquals(2, fixture.runs.eventsAfter(handle.id(), 0).stream()
                        .filter(event -> event.type().equals("core.tool.started")).count());
                assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
            }
        }
    }

    @Test
    void unauthorizedToolsCannotBeListedOrActivated() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (calls.incrementAndGet()) {
                case 1 -> {
                    assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                    yield namedToolCallResponse("framework_tool_catalog",
                            "{\"action\":\"list\",\"query\":\"code_target\"}", 2, 1);
                }
                case 2 -> {
                    assertFalse(lastToolResponse(prompt).responseData().contains("code_target"));
                    yield namedToolCallResponse("framework_tool_catalog",
                            "{\"action\":\"activate\",\"names\":[\"code_target\"]}", 2, 1);
                }
                case 3 -> {
                    assertTrue(lastToolResponse(prompt).responseData().contains("false"));
                    yield textResponse("done", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .fillerTools(70).codeCalls(codeCalls);
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.requestWithAllowedGroups("test", "filler"));
            RunOutcome outcome = handle.completion().toCompletableFuture().get();
            assertEquals(RunState.COMPLETED, outcome.state());
            assertEquals(3, calls.get());
            assertEquals(0, codeCalls.get());
            assertTrue(delivered.stream().noneMatch(prompt -> toolNames(prompt).contains("code_target")));
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .noneMatch(event -> event.type().equals("core.tool.completed")
                            && event.payload().path("output").path("activated").toString()
                                    .contains("code_target")));
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void aSingleAuthorizedToolIsShownDirectlyWhenItsSchemaFits() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        ChatModel model = prompt -> switch (calls.incrementAndGet()) {
            case 1 -> {
                assertEquals(List.of("code_target"), toolNames(prompt));
                yield namedToolCallResponse("code_target", "{\"value\":1}", 2, 1);
            }
            case 2 -> {
                assertEquals("code_target", lastToolResponse(prompt).name());
                yield textResponse("done", 2, 1);
            }
            default -> throw new AssertionError("unexpected provider call");
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 1_000, 1))
                .denyTestGroup().codeCalls(codeCalls);
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            assertEquals(RunState.COMPLETED, handle.completion().toCompletableFuture().get().state());
            assertEquals(2, calls.get());
            assertEquals(1, codeCalls.get());
        }
    }

    @Test
    void aNameAllowlistCanUseTheDirectPathWithoutAuthorizingTheCatalog() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (providerCalls.incrementAndGet()) {
                case 1 -> {
                    assertEquals(List.of("code_target"), toolNames(prompt));
                    yield namedToolCallResponse("code_target", "{\"value\":1}", 2, 1);
                }
                case 2 -> {
                    assertEquals(List.of(), toolNames(prompt));
                    assertEquals("code_target", lastToolResponse(prompt).name());
                    yield textResponse("done", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 1_000, 1))
                .allowedToolNames(Set.of("code_target")).codeCalls(codeCalls);
        try (Fixture fixture = new Fixture(model, toolCallBudget(1), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            assertEquals(RunState.COMPLETED, handle.completion().toCompletableFuture().get().state());
            assertEquals(2, providerCalls.get());
            assertEquals(1, codeCalls.get());
            assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.started")).count());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void anAllowlistThatDeniesTheRequiredCatalogFailsBeforeProvider() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        Set<String> allowed = new java.util.LinkedHashSet<>();
        allowed.add("test_mutate");
        allowed.add("code_target");
        for (int index = 0; index < 70; index++) allowed.add("filler_%03d".formatted(index));
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 1_000, 1))
                .fillerTools(70).allowedToolNames(allowed);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            RunOutcome outcome = handle.completion().toCompletableFuture().get();
            assertEquals(RunState.FAILED, outcome.state());
            assertEquals(0, providerCalls.get());
            assertTrue(outcome.error().contains("framework_tool_catalog"));
        }
    }

    @Test
    void catalogNeedsTwoRemainingCallsAndChargesBothActivationAndExecution() throws Exception {
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 1_000, 1))
                .fillerTools(70);
        AtomicInteger insufficientProviderCalls = new AtomicInteger();
        try (Fixture fixture = new Fixture(prompt -> {
            insufficientProviderCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, toolCallBudget(1), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            RunOutcome outcome = handle.completion().toCompletableFuture().get();
            assertEquals(RunState.FAILED, outcome.state());
            assertEquals(0, insufficientProviderCalls.get());
            assertTrue(outcome.error().contains("tool-call budget"));
        }

        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        FixtureConfig sufficient = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 1_000, 1))
                .fillerTools(70).codeCalls(codeCalls);
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (providerCalls.incrementAndGet()) {
                case 1 -> {
                    assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                    yield namedToolCallResponse("framework_tool_catalog",
                            "{\"action\":\"activate\",\"names\":[\"code_target\"]}", 2, 1);
                }
                case 2 -> {
                    assertEquals(List.of("code_target"), toolNames(prompt));
                    yield namedToolCallResponse("code_target", "{\"value\":1}", 2, 1);
                }
                case 3 -> {
                    assertEquals(List.of(), toolNames(prompt));
                    assertEquals("code_target", lastToolResponse(prompt).name());
                    yield textResponse("done", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        for (int limit : List.of(2, 3)) {
            providerCalls.set(0);
            codeCalls.set(0);
            delivered.clear();
            try (Fixture fixture = new Fixture(model, toolCallBudget(limit), new AtomicInteger(), sufficient)) {
                RunHandle handle = fixture.engine.start(fixture.request());
                assertEquals(RunState.COMPLETED, handle.completion().toCompletableFuture().get().state());
                assertEquals(3, providerCalls.get());
                assertEquals(1, codeCalls.get());
                assertEquals(2, fixture.runs.eventsAfter(handle.id(), 0).stream()
                        .filter(event -> event.type().equals("core.tool.started")).count());
                assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
            }
        }
    }

    @Test
    void anotherToolInTheActivationResponseDoesNotConsumeTheNextCallActivation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (calls.incrementAndGet()) {
                case 1 -> {
                    assertTrue(toolNames(prompt).contains("framework_tool_catalog"));
                    assertTrue(toolNames(prompt).contains("test_mutate"));
                    assertFalse(toolNames(prompt).contains("code_target"));
                    AssistantMessage output = AssistantMessage.builder().content("")
                            .toolCalls(List.of(
                                    new AssistantMessage.ToolCall("activate", "function",
                                            "framework_tool_catalog",
                                            "{\"action\":\"activate\",\"names\":[\"code_target\"]}"),
                                    new AssistantMessage.ToolCall("mutate", "function",
                                            "test_mutate", "{\"value\":1}")))
                            .build();
                    yield response(output, 2, 1);
                }
                case 2 -> {
                    assertTrue(toolNames(prompt).contains("code_target"));
                    yield namedToolCallResponse("code_target", "{\"value\":1}", 2, 1);
                }
                case 3 -> textResponse("done", 2, 1);
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 1_000, 3))
                .autoApproveTestMutate().fillerTools(70).codeCalls(codeCalls);
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            assertEquals(RunState.COMPLETED, handle.completion().toCompletableFuture().get().state());
            assertEquals(3, calls.get());
            assertEquals(1, codeCalls.get());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void largeToolResultIsSummarizedOnlyForTheModelAndExecutesOnce() throws Exception {
        String fullResult = "result-" + "x".repeat(12_000);
        AtomicInteger codeCalls = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (calls.incrementAndGet()) {
                case 1 -> namedToolCallResponse("framework_tool_catalog",
                        "{\"action\":\"activate\",\"names\":[\"code_target\"]}", 2, 1);
                case 2 -> namedToolCallResponse("code_target", "{\"value\":1}", 2, 1);
                case 3 -> {
                    ToolResponseMessage.ToolResponse latest = lastToolResponse(prompt);
                    assertEquals("code_target", latest.name());
                    assertEquals("provider-call", latest.id());
                    assertTrue(latest.responseData().contains("工具已执行"));
                    assertTrue(latest.responseData().length() <= 1_000);
                    assertFalse(latest.responseData().contains(fullResult));
                    yield textResponse("done", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 4_000, 1_000, 1))
                .codeResult(fullResult).codeCalls(codeCalls);
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            assertEquals(RunState.COMPLETED, handle.completion().toCompletableFuture().get().state());
            assertEquals(3, calls.get());
            assertEquals(1, codeCalls.get());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .anyMatch(event -> event.type().equals("core.tool.completed")
                            && event.payload().path("tool").asText().equals("code_target")
                            && event.payload().path("output").path("payload").asText()
                                    .equals(fullResult)));
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void requiredTaskThatDoesNotFitStopsBeforeCallingProvider() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 4_000, 1_000, 1));
        try (Fixture fixture = new Fixture(prompt -> {
            calls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("task-" + "x".repeat(5_000)));
            RunOutcome outcome = handle.completion().toCompletableFuture().get();
            assertEquals(RunState.FAILED, outcome.state());
            assertEquals(0, calls.get());
            assertTrue(outcome.error().contains("context") || outcome.error().contains("budget"));
        }
    }

    @Test
    void disabledCompactionKeepsTheCompleteToolCatalogAndResult() throws Exception {
        String fullResult = "untrimmed-" + "x".repeat(12_000);
        AtomicInteger calls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (calls.incrementAndGet()) {
                case 1 -> {
                    assertTrue(toolNames(prompt).size() > 64);
                    assertTrue(toolNames(prompt).contains("code_target"));
                    assertFalse(toolNames(prompt).contains("framework_tool_catalog"));
                    yield namedToolCallResponse("code_target", "{\"value\":1}", 2, 1);
                }
                case 2 -> {
                    assertTrue(lastToolResponse(prompt).responseData().contains(fullResult));
                    assertTrue(toolNames(prompt).size() > 64);
                    yield textResponse("done", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(false, 4_000, 1_000, 1))
                .fillerTools(70).codeResult(fullResult);
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            assertEquals(RunState.COMPLETED, handle.completion().toCompletableFuture().get().state());
            assertEquals(2, calls.get());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void downstreamAdvisorCannotDropTheLatestToolResponse() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 4_000, 1_000, 2))
                .autoApproveTestMutate()
                .downstreamMutation(DownstreamMutation.DROP_TOOL_RESPONSE);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return toolCallResponse(2, 1);
        }, RunBudget.UNBOUNDED, toolCalls, config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            RunOutcome outcome = handle.completion().toCompletableFuture().get();
            assertEquals(RunState.FAILED, outcome.state());
            assertEquals(1, providerCalls.get());
            assertEquals(1, toolCalls.get());
        }
    }

    @Test
    void downstreamAdvisorCannotAddContextBeyondTheProviderBudget() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 4_000, 1_000, 2))
                .downstreamMutation(DownstreamMutation.ADD_OVERSIZE_USER);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            RunOutcome outcome = handle.completion().toCompletableFuture().get();
            assertEquals(RunState.FAILED, outcome.state());
            assertEquals(0, providerCalls.get());
            assertTrue(outcome.error().contains("context") || outcome.error().contains("budget"));
        }
    }

    @Test
    void originalTaskSurvivesMultipleApprovalResumesAndRestart() throws Exception {
        String originalTask = "remember-this-original-task-" + "z".repeat(1_000);
        AtomicInteger calls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ChatModel model = prompt -> {
            delivered.add(prompt);
            assertTrue(prompt.getInstructions().stream()
                    .filter(UserMessage.class::isInstance)
                    .anyMatch(message -> message.getText().contains(originalTask)));
            int number = calls.incrementAndGet();
            return number <= 2
                    ? namedToolCallResponse("test_mutate", "{\"value\":" + number + "}", 2, 1)
                    : textResponse("done", 2, 1);
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 4_000, 1_000, 2));
        try (Fixture fixture = new Fixture(model, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request(originalTask));
            for (int number = 1; number <= 2; number++) {
                assertEquals(RunState.WAITING_APPROVAL, fixture.engine.get(handle.id()).state());
                if (number == 2) fixture.restart();
                var approval = JsonNodeFactory.instance.objectNode().put("approved", true)
                        .put("fingerprint", ToolInvocationFingerprint.create("test_mutate",
                                JsonNodeFactory.instance.objectNode().put("value", number)));
                handle = fixture.engine.resume(handle.id(), new ResumeCommand("tool.approval", approval));
            }
            assertEquals(RunState.COMPLETED, handle.completion().toCompletableFuture().get().state());
            assertEquals(3, calls.get());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void toolCallsUseTheConfiguredObservationRegistry() throws Exception {
        AtomicInteger observations = new AtomicInteger();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<ToolCallingObservationContext>() {
            @Override public boolean supportsContext(Observation.Context context) {
                return context instanceof ToolCallingObservationContext;
            }
            @Override public void onStop(ToolCallingObservationContext context) {
                observations.incrementAndGet();
            }
        });
        AtomicInteger modelCalls = new AtomicInteger();
        FixtureConfig config = new FixtureConfig().observations(registry).autoApproveTestMutate();
        try (Fixture fixture = new Fixture(prompt -> modelCalls.incrementAndGet() == 1
                ? toolCallResponse(2, 1) : textResponse("done", 2, 1),
                RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            assertEquals(RunState.COMPLETED, handle.completion().toCompletableFuture().get().state());
            assertEquals(2, modelCalls.get());
            assertEquals(1, observations.get());
        }
    }

    @Test
    void 按需计划直接选择目标工具且只使用一次共享工具预算() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ModelTaskGateway planner = request -> {
            assertEquals(ModelTier.LIGHT, request.tier());
            assertEquals(0, request.maxRetries());
            assertFalse(request.cacheAllowed());
            int number = plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, number == 1 ? "code_target" : "",
                        number == 1 ? "code" : "");
            } else {
                assertEquals("context.on_demand.refine_v2", request.purpose());
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", "code_target");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        ChatModel model = prompt -> {
            delivered.add(prompt);
            int number = providerCalls.incrementAndGet();
            assertFalse(prompt.getInstructions().stream().anyMatch(message ->
                    message.getText() != null
                            && message.getText().contains("IRRELEVANT_HISTORY_MARKER")));
            return switch (number) {
                case 1 -> {
                    assertEquals(List.of("code_target"), toolNames(prompt));
                    yield namedToolCallResponse("code_target", "{\"value\":42}", 2, 1);
                }
                case 2 -> {
                    assertEquals(List.of(), toolNames(prompt));
                    assertEquals("code_target", lastToolResponse(prompt).name());
                    yield textResponse("done", 2, 1);
                }
                default -> throw new AssertionError("unexpected recursive provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).fillerTools(70).codeCalls(codeCalls);
        try (Fixture fixture = new Fixture(model, toolCallBudget(1),
                new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(
                    InputBlock.message("user", "IRRELEVANT_HISTORY_MARKER"),
                    InputBlock.message("assistant", "unrelated reply"),
                    InputBlock.text("Run code_target once")), Map.of());
            RunHandle handle = fixture.engine.start(request);

            RunOutcome outcome = handle.completion().toCompletableFuture().get();
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(3, plannerCalls.get());
            assertEquals(2, providerCalls.get());
            assertEquals(1, codeCalls.get());
            assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.completed")).count());
            assertEquals(2, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText().equals("select_v2"))
                    .count());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void 旅行请求误写web_search只能检索并由候选ID映射真实工具() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                assertEquals(0, request.input().path("tools").size(),
                        "the first stage must expose groups rather than names");
                assertTrue(request.input().path("toolGroups").toString().contains("code"));
                assertEquals(142, java.util.stream.StreamSupport.stream(
                                request.input().path("toolGroups").spliterator(), false)
                        .mapToInt(group -> group.path("count").asInt()).sum());
                assertFalse(request.outputSchema().toString().contains("toolNames"));
                selection.putArray("searches");
                toolIntent(selection, "web_search", "code");
            } else {
                assertEquals("context.on_demand.refine_v2", request.purpose());
                assertEquals(1, request.input().path("toolCandidates").size());
                assertEquals("code_target", request.input().path("toolCandidates")
                        .get(0).path("name").asText());
                assertFalse(request.outputSchema().toString().contains("toolNames"));
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", "code_target");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).fillerTools(140).codeCalls(codeCalls);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            assertEquals(List.of("code_target"), toolNames(prompt));
            assertFalse(toolNames(prompt).contains("web_search"));
            return textResponse("done", 2, 1);
        }, toolCallBudget(1), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("计划中秋节的游玩地方"));
            RunOutcome outcome = handle.completion().toCompletableFuture().get();

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(2, plannerCalls.get());
            assertEquals(1, providerCalls.get());
            assertEquals(0, codeCalls.get());
            assertTrue(new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.MODEL)
                    .allMatch(step -> !step.input().path("toolNames").toString()
                            .contains("web_search")));
        }
    }

    @Test
    void 普通站点密码说明不会中断工具候选检索() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, "site", "web");
            } else {
                assertEquals("context.on_demand.refine_v2", request.purpose());
                assertEquals(Set.of("site_login_now", "site_fill_password"),
                        java.util.stream.StreamSupport.stream(
                                request.input().path("toolCandidates").spliterator(), false)
                                .map(candidate -> candidate.path("name").asText())
                                .collect(java.util.stream.Collectors.toSet()));
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", "site_login_now");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner)
                .webToolDescriptions(Map.of(
                        "site_login_now", "密码：工具内部仅在站点登录时填写已保存的凭据",
                        "site_fill_password", "密码由站点凭据工具管理，不会写入普通日志"));
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            assertEquals(List.of("site_login_now"), toolNames(prompt));
            return textResponse("中秋攻略", 2, 1);
        }, toolCallBudget(1), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("给我中秋节的攻略"));
            RunOutcome outcome = handle.completion().toCompletableFuture().get();

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(2, plannerCalls.get());
            assertEquals(1, providerCalls.get());
            AgentStep candidateStep = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText().equals("tool_search_v2"))
                    .findFirst().orElseThrow();
            assertEquals(AgentStep.State.COMPLETED, candidateStep.state());
            assertEquals(2, candidateStep.output().path("candidates").size());
            candidateStep.output().path("candidates").forEach(candidate ->
                    assertEquals("<敏感内容已隐藏>", candidate.path("summary").asText()));
            var completion = fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.step.completed")
                            && event.payload().path("stepId").asText()
                                    .equals(candidateStep.id().value()))
                    .findFirst().orElseThrow();
            assertFalse(completion.payload().path("credentialRedacted").asBoolean());
        }
    }

    @Test
    void 伪造候选ID纠错一次后在主模型和业务工具前暂停() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("historyIds");
                selection.putArray("searches");
                toolIntent(selection, "code_target", "code");
            } else {
                if (request.purpose().endsWith(".refine_v2")) {
                    selection.putArray("historyIds");
                    selection.putArray("sourceIds");
                } else {
                    assertEquals("context.on_demand.repair_refine_tools_v2", request.purpose());
                }
                selection.put("toolAction", "direct");
                selection.putArray("toolIds").add("web_search");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).codeCalls(codeCalls);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("find a code tool"));
            RunSnapshot snapshot = fixture.engine.get(handle.id());

            assertEquals(RunState.PAUSED, snapshot.state());
            assertEquals(3, plannerCalls.get(), "one select, one refine, one repair");
            assertEquals(0, providerCalls.get());
            assertEquals(0, codeCalls.get());
            assertTrue(new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .noneMatch(step -> step.kind() == AgentStep.Kind.MODEL));
        }
    }

    @Test
    void 重复候选ID纠错后仍不会进入主模型() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                selection.putArray("historyIds");
                toolIntent(selection, "code_target", "code");
            } else {
                String id = request.input().path("toolCandidates").get(0)
                        .path("id").asText();
                if (request.purpose().endsWith(".refine_v2")) {
                    selection.putArray("historyIds");
                    selection.putArray("sourceIds");
                } else {
                    assertEquals("context.on_demand.repair_refine_tools_v2", request.purpose());
                }
                selection.put("toolAction", "direct");
                selection.putArray("toolIds").add(id).add(id);
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("run code"));
            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals(3, plannerCalls.get());
            assertEquals(0, providerCalls.get());
        }
    }

    @Test
    void 工具检索零命中时只展示目录且检查目录许可与两次调用额度() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            boolean catalogDenied = scenario == 1;
            boolean budgetShort = scenario == 2;
            AtomicInteger plannerCalls = new AtomicInteger();
            AtomicInteger providerCalls = new AtomicInteger();
            ModelTaskGateway planner = request -> {
                plannerCalls.incrementAndGet();
                assertEquals("context.on_demand.select_v2", request.purpose());
                ObjectNode selection = JsonNodeFactory.instance.objectNode();
                selection.putArray("searches");
                selection.putArray("historyIds");
                toolIntent(selection, "__unfindable_tool__");
                return CompletableFuture.completedFuture(new ModelTaskResult(
                        selection, "planner", 1, 1, false, Map.of()));
            };
            FixtureConfig config = new FixtureConfig()
                    .context(contextConfiguration(true, 48_000, 16_000, 1))
                    .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                    .planner(planner);
            if (catalogDenied) config.allowedToolNames(Set.of("code_target"));
            try (Fixture fixture = new Fixture(prompt -> {
                providerCalls.incrementAndGet();
                assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                return textResponse("done", 2, 1);
            }, budgetShort ? toolCallBudget(1) : toolCallBudget(2),
                    new AtomicInteger(), config)) {
                RunHandle handle = fixture.engine.start(fixture.request("find a missing tool"));
                RunSnapshot snapshot = fixture.engine.get(handle.id());
                if (catalogDenied || budgetShort) {
                    assertEquals(RunState.PAUSED, snapshot.state());
                    assertEquals(0, providerCalls.get());
                } else {
                    assertEquals(RunState.COMPLETED, snapshot.state());
                    assertEquals(1, providerCalls.get());
                }
                assertEquals(1, plannerCalls.get(), "zero matches need no refine");
            }
        }
    }

    @Test
    void 工具额度为零仍可选择无工具收尾() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            assertEquals("context.on_demand.select_v2", request.purpose());
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("searches");
            selection.putArray("historyIds");
            toolIntent(selection, "");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            assertEquals(List.of(), toolNames(prompt));
            return textResponse("done", 2, 1);
        }, toolCallBudget(0), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("answer without tools"));
            RunOutcome outcome = handle.completion().toCompletableFuture().get();
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(1, plannerCalls.get());
            assertEquals(1, providerCalls.get());
        }
    }

    @Test
    void 按需规划零工具时误报WebContent仍能收到反馈并回答() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            assertEquals("context.on_demand.select_v2", request.purpose());
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("searches");
            selection.putArray("historyIds");
            toolIntent(selection, "");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner);
        ChatModel model = prompt -> {
            delivered.add(prompt);
            assertEquals(List.of(), toolNames(prompt));
            return switch (providerCalls.incrementAndGet()) {
                case 1 -> namedToolCallResponse("web_content", "{}", 2, 1);
                case 2 -> {
                    ToolResponseMessage.ToolResponse feedback = lastToolResponse(prompt);
                    assertEquals("web_content", feedback.name());
                    assertTrue(feedback.responseData().contains("web_content"));
                    yield textResponse("上海", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        try (Fixture fixture = new Fixture(model, toolCallBudget(2), toolCalls, config)) {
            RunHandle handle = fixture.engine.start(fixture.request("上海"));
            RunOutcome outcome = handle.completion().toCompletableFuture().get();

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals("上海", outcome.output().path("text").asText());
            assertTrue(plannerCalls.get() >= 1);
            assertEquals(2, providerCalls.get());
            assertEquals(0, toolCalls.get());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void 单工具槽激活后先兑现A再允许后续规划选择B() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                if (providerCalls.get() == 0) {
                    toolIntent(selection, "__no_matching_tool__");
                } else {
                    // The activated A must be shown once even when the next plan asks for B.
                    toolIntent(selection, "test_mutate", "test");
                    if (providerCalls.get() == 1) {
                        assertTrue(request.input().path("activatedTools").toString()
                                .contains("code_target"));
                    }
                }
            } else {
                assertEquals(2, providerCalls.get());
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", "test_mutate");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).codeCalls(codeCalls);
        try (Fixture fixture = new Fixture(prompt -> switch (providerCalls.incrementAndGet()) {
            case 1 -> {
                assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
                yield namedToolCallResponse("framework_tool_catalog",
                        "{\"action\":\"activate\",\"names\":[\"code_target\"]}", 2, 1);
            }
            case 2 -> {
                assertEquals(List.of("code_target"), toolNames(prompt));
                yield namedToolCallResponse("code_target", "{\"value\":1}", 2, 1);
            }
            case 3 -> {
                assertEquals(List.of("test_mutate"), toolNames(prompt));
                yield textResponse("done", 2, 1);
            }
            default -> throw new AssertionError("unexpected provider call");
        }, toolCallBudget(3), new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("activate code, then inspect mutate"));
            RunOutcome outcome = handle.completion().toCompletableFuture().get();
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(3, providerCalls.get());
            assertEquals(4, plannerCalls.get());
            assertEquals(1, codeCalls.get());
        }
    }

    @Test
    void 候选总数为一时来源摘要不会挤掉工具候选ID() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger fetches = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        DeferredContextSource source = new DeferredContextSource() {
            @Override public String id() { return "docs"; }
            @Override public String description() { return "Documents"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                searches.incrementAndGet();
                return List.of(new DeferredContextCandidate(
                        "one", "v1", "source summary", PermissionSet.NONE));
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                fetches.incrementAndGet();
                return "unexpected body";
            }
        };
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches").addObject()
                        .put("source", "docs").put("query", "summary");
                toolIntent(selection, "code_target", "code");
            } else {
                assertEquals(0, request.input().path("candidates").size());
                assertEquals(1, request.input().path("toolCandidates").size());
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", "code_target");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true)
                        .put("candidates", 1))
                .planner(planner).source(source).autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            assertEquals(List.of("code_target"), toolNames(prompt));
            return textResponse("done", 2, 1);
        }, toolCallBudget(2), new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("inspect a tool and summary")),
                    Map.of(), PermissionSet.of("context.read", "tool.execute"));
            RunHandle handle = fixture.engine.start(request);
            RunOutcome outcome = handle.completion().toCompletableFuture().get();
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(1, searches.get());
            assertEquals(0, fetches.get());
            assertEquals(1, providerCalls.get());
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void 按需计划只选部分工具时仍能通过目录发现其余授权工具() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            int number = plannerCalls.incrementAndGet();
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, number == 1 ? "code_target" : "",
                        number == 1 ? "code" : "");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", "code_target");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (providerCalls.incrementAndGet()) {
                case 1 -> {
                    assertEquals(Set.of("code_target", "framework_tool_catalog"),
                            Set.copyOf(toolNames(prompt)));
                    yield namedToolCallResponse("framework_tool_catalog",
                            "{\"action\":\"activate\",\"names\":[\"filler_009\"]}", 2, 1);
                }
                case 2 -> {
                    assertEquals(List.of("filler_009"), toolNames(prompt));
                    assertEquals("framework_tool_catalog", lastToolResponse(prompt).name());
                    yield namedToolCallResponse("filler_009", "{\"value\":9}", 2, 1);
                }
                case 3 -> {
                    assertEquals(List.of(), toolNames(prompt));
                    assertEquals("filler_009", lastToolResponse(prompt).name());
                    yield textResponse("done", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 2))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).fillerTools(10);
        try (Fixture fixture = new Fixture(model, toolCallBudget(2),
                new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("use a hidden filler tool"));
            RunSnapshot snapshot = fixture.engine.get(handle.id());
            assertNotEquals(RunState.PAUSED, snapshot.state(), snapshot.output().toString());
            RunOutcome outcome = handle.completion().toCompletableFuture()
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(4, plannerCalls.get());
            assertEquals(3, providerCalls.get());
            assertEquals(2, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.started")).count());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void 显式工具搜索缺少可用目录时在主模型调用前暂停() throws Exception {
        for (boolean catalogDenied : List.of(false, true)) {
            AtomicInteger plannerCalls = new AtomicInteger();
            AtomicInteger providerCalls = new AtomicInteger();
            ModelTaskGateway planner = request -> {
                plannerCalls.incrementAndGet();
                ObjectNode selection = JsonNodeFactory.instance.objectNode();
                selection.putArray("historyIds");
                if (request.purpose().endsWith(".select_v2")) {
                    selection.putArray("searches");
                    toolIntent(selection, "missing filler query", "filler");
                } else {
                    assertEquals("context.on_demand.refine_v2", request.purpose());
                    assertTrue(request.input().path("toolCandidates").toString()
                            .contains("filler_009"));
                    selection.putArray("sourceIds");
                    toolChoice(selection, request, "discover");
                }
                return CompletableFuture.completedFuture(new ModelTaskResult(
                        selection, "planner", 1, 1, false, Map.of()));
            };
            FixtureConfig config = new FixtureConfig()
                    .context(contextConfiguration(true, 48_000, 16_000,
                            catalogDenied ? 20 : 2))
                    .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                    .planner(planner).fillerTools(10);
            if (catalogDenied) {
                Set<String> business = new java.util.LinkedHashSet<>();
                business.add("test_mutate");
                business.add("code_target");
                for (int index = 0; index < 10; index++) {
                    business.add("filler_%03d".formatted(index));
                }
                config.allowedToolNames(business);
            }
            RunBudget budget = catalogDenied ? RunBudget.UNBOUNDED : toolCallBudget(1);
            try (Fixture fixture = new Fixture(prompt -> {
                providerCalls.incrementAndGet();
                return textResponse("unexpected", 1, 1);
            }, budget, new AtomicInteger(), config)) {
                RunHandle handle = fixture.engine.start(fixture.request("find filler_009"));
                RunSnapshot snapshot = fixture.engine.get(handle.id());

                assertEquals(RunState.PAUSED, snapshot.state());
                assertEquals("context.planning_required", snapshot.output().path("kind").asText());
                String reason = snapshot.output().path("reason").asText().toLowerCase();
                assertTrue(reason.contains(catalogDenied ? "catalog" : "tool-call budget"),
                        reason);
                assertEquals(2, plannerCalls.get());
                assertEquals(0, providerCalls.get());
                assertTrue(new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                        .noneMatch(step -> step.kind() == AgentStep.Kind.MODEL));
            }
        }
    }

    @Test
    void 按需规划结果无效时暂停且不调用主模型() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode invalid = JsonNodeFactory.instance.objectNode();
            invalid.put("searches", "invalid");
            invalid.putArray("historyIds");
            toolIntent(invalid, "");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    invalid, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());

            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals(1, plannerCalls.get());
            assertEquals(0, providerCalls.get());
            assertTrue(new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .noneMatch(step -> step.kind() == AgentStep.Kind.MODEL));
        }
    }

    @Test
    void 修正未知上下文来源后保留工具意图并完成运行() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger knowledgeSearches = new AtomicInteger();
        AtomicInteger webSearches = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        DeferredContextSource knowledge = new DeferredContextSource() {
            @Override public String id() { return "knowledge"; }
            @Override public String description() { return "Authorized knowledge"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                knowledgeSearches.incrementAndGet();
                return List.of();
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                throw new AssertionError("empty search has no candidate to fetch");
            }
        };
        DeferredContextSource web = new DeferredContextSource() {
            @Override public String id() { return "web"; }
            @Override public String description() { return "Unavailable web context"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("web.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                webSearches.incrementAndGet();
                throw new AssertionError("unauthorized web source must not be searched");
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                throw new AssertionError("unauthorized web source must not be fetched");
            }
        };
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            if (request.purpose().endsWith(".select_v2")) {
                assertEquals(1, request.input().path("sources").size());
                assertEquals("knowledge", request.input().path("sources").get(0)
                        .path("id").asText());
                selection.putArray("searches").addObject()
                        .put("source", "web").put("query", "Shanghai tomorrow");
                selection.withArray("searches").addObject()
                        .put("source", "knowledge").put("query", "Shanghai outing");
                selection.putArray("historyIds");
                toolIntent(selection, "code_target", "code");
            } else if (request.purpose().endsWith(".repair_select_sources_v2")) {
                assertEquals(1, request.outputSchema().path("properties").size(),
                        "repair may change searches only");
                selection.putArray("searches").addObject()
                        .put("source", "knowledge").put("query", "Shanghai outing");
            } else {
                assertEquals("context.on_demand.refine_v2", request.purpose());
                assertEquals(0, request.input().path("candidates").size());
                assertEquals(1, request.input().path("toolCandidates").size(),
                        "the original tool intent must survive source repair");
                selection.putArray("historyIds");
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", "code_target");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(knowledge).source(web).autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            assertEquals(List.of("code_target"), toolNames(prompt));
            return textResponse("done", 2, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("plan Shanghai outing")),
                    Map.of(), PermissionSet.of("context.read", "tool.execute"));
            RunHandle handle = fixture.engine.start(request);
            RunOutcome outcome = handle.completion().toCompletableFuture().get();

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals("done", outcome.output().path("text").asText());
            assertEquals(3, plannerCalls.get());
            assertEquals(1, knowledgeSearches.get());
            assertEquals(0, webSearches.get());
            assertEquals(1, providerCalls.get());
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText()
                                    .equals("repair_select_sources_v2"))
                    .count());
        }
    }

    @Test
    void 三条Web工具组检索经限额修正后重启复用历史与工具意图() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger fetches = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicReference<String> historyId = new AtomicReference<>();
        DeferredContextSource knowledge = new DeferredContextSource() {
            @Override public String id() { return "knowledge"; }
            @Override public String description() { return "Authorized knowledge"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                searches.incrementAndGet();
                assertEquals("Shanghai outing", query);
                return List.of(new DeferredContextCandidate(
                        "outing", "v1", "Shanghai outing summary", PermissionSet.NONE));
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                fetches.incrementAndGet();
                assertEquals("outing", id);
                return "LARGE_DEFERRED_BODY_MARKER Shanghai outing evidence";
            }
        };
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            if (request.purpose().endsWith(".select_v2")) {
                assertEquals(1, request.input().path("sources").size());
                assertEquals("knowledge", request.input().path("sources").get(0)
                        .path("id").asText());
                assertTrue(request.input().path("toolGroups").toString().contains("web"));
                assertEquals(1, request.input().path("history").size());
                historyId.set(request.input().path("history").get(0).path("id").asText());
                var requested = selection.putArray("searches");
                for (String query : List.of("Shanghai weather", "Shanghai sights",
                        "Shanghai restaurants")) {
                    requested.addObject().put("source", "web").put("query", query);
                }
                selection.putArray("historyIds").add(historyId.get());
                toolIntent(selection, "Shanghai outing web search", "web");
            } else if (request.purpose().endsWith(".repair_select_sources_v2")) {
                assertEquals(2, request.outputSchema().path("properties")
                        .path("searches").path("maxItems").asInt());
                assertEquals("knowledge", request.outputSchema().path("properties")
                        .path("searches").path("items").path("properties")
                        .path("source").path("enum").get(0).asText());
                assertEquals(3, request.input().path("requestedQueries").size(),
                        "all rejected queries should reach the bounded repair stage");
                assertEquals(1, request.input().path("allowedSources").size());
                selection.putArray("searches").addObject()
                        .put("source", "knowledge").put("query", "Shanghai outing");
            } else {
                assertEquals("context.on_demand.refine_v2", request.purpose());
                assertEquals(historyId.get(), request.input().path("firstSelection")
                        .path("historyIds").get(0).asText(),
                        "search repair must preserve the initial history selection");
                assertEquals("web", request.input().path("firstSelection")
                        .path("toolIntent").path("groups").get(0).asText(),
                        "search repair must preserve the business tool intent");
                assertEquals("knowledge:outing", request.input().path("candidates")
                        .get(0).path("id").asText());
                selection.putArray("historyIds").add(historyId.get());
                selection.putArray("sourceIds").add("knowledge:outing");
                toolChoice(selection, request, "direct", "web_search");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        List<Prompt> delivered = new ArrayList<>();
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(knowledge).autoApproveContext()
                .webToolDescriptions(Map.of("web_search", "Search current web pages"))
                .downstreamMutation(DownstreamMutation.PAUSE_AFTER_DEFERRED_FETCH_ONCE);
        try (Fixture fixture = new Fixture(prompt -> {
            delivered.add(prompt);
            providerCalls.incrementAndGet();
            assertEquals(List.of("web_search"), toolNames(prompt));
            assertTrue(prompt.getInstructions().stream().anyMatch(message ->
                    message.getText() != null
                            && message.getText().contains("LARGE_DEFERRED_BODY_MARKER")));
            assertTrue(prompt.getInstructions().stream().anyMatch(message ->
                    "Earlier itinerary preference".equals(message.getText())));
            return textResponse("done", 2, 1);
        }, toolCallBudget(3), new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(
                    InputBlock.message("user", "Earlier itinerary preference"),
                    InputBlock.text("plan Shanghai outing")), Map.of(),
                    PermissionSet.of("context.read", "tool.execute"));
            RunHandle handle = fixture.engine.start(request);
            assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state(),
                    fixture.engine.get(handle.id()).output().toString());
            assertEquals(3, plannerCalls.get(), "select, source repair, and refine");
            assertEquals(1, searches.get());
            assertEquals(1, fetches.get());
            assertEquals(0, providerCalls.get());
            AgentStep first = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText().equals("select_v2"))
                    .findFirst().orElseThrow();
            assertEquals(3, first.output().path("selection").path("searches").size());

            fixture.restart();
            RunHandle resumed = fixture.engine.resume(handle.id(),
                    new ResumeCommand("delegation.continue", JsonNodeFactory.instance.objectNode()));
            RunOutcome outcome = resumed.completion().toCompletableFuture().get();

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals("done", outcome.output().path("text").asText());
            assertEquals(3, plannerCalls.get(), "completed planning stages must replay durably");
            assertEquals(1, searches.get(), "completed search must not repeat on resume");
            assertEquals(1, fetches.get(), "completed fetch must not repeat on resume");
            assertEquals(1, providerCalls.get());
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText()
                                    .equals("repair_select_sources_v2"))
                    .count());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void 已授权来源的三条检索仍只修正一次且无效结果安全暂停() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        DeferredContextSource knowledge = new DeferredContextSource() {
            @Override public String id() { return "knowledge"; }
            @Override public String description() { return "Authorized knowledge"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                searches.incrementAndGet();
                return List.of();
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                throw new AssertionError("invalid selection must not fetch context");
            }
        };
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            var requested = selection.putArray("searches");
            if (request.purpose().endsWith(".select_v2")) {
                for (String query : List.of("weather", "sights", "restaurants")) {
                    requested.addObject().put("source", "knowledge").put("query", query);
                }
                selection.putArray("historyIds");
                toolIntent(selection, "");
            } else {
                assertEquals("context.on_demand.repair_select_sources_v2", request.purpose(),
                        "cardinality alone must trigger the bounded repair stage");
                assertEquals(2, request.outputSchema().path("properties")
                        .path("searches").path("maxItems").asInt());
                for (String query : List.of("weather", "sights", "restaurants")) {
                    requested.addObject().put("source", "knowledge").put("query", query);
                }
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(knowledge).autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("plan Shanghai outing")),
                    Map.of(), PermissionSet.of("context.read"));
            RunHandle handle = fixture.engine.start(request);
            RunSnapshot snapshot = fixture.engine.get(handle.id());

            assertEquals(RunState.PAUSED, snapshot.state());
            assertEquals("context.planning_required", snapshot.output().path("kind").asText());
            assertTrue(snapshot.output().path("reason").asText().contains("too many"),
                    snapshot.output().toString());
            assertEquals(2, plannerCalls.get(), "repair must run only once");
            assertEquals(0, searches.get());
            assertEquals(0, providerCalls.get());
            assertTrue(new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .noneMatch(step -> step.kind() == AgentStep.Kind.MODEL));

            fixture.restart();
            fixture.engine.resume(handle.id(), new ResumeCommand(
                    "delegation.continue", JsonNodeFactory.instance.objectNode()));
            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals(2, plannerCalls.get(), "completed repair must replay without a new call");
            assertEquals(0, searches.get());
            assertEquals(0, providerCalls.get());
        }
    }

    @Test
    void 已完成的超额选择检查点恢复后补做修正且不重跑初始规划() throws Exception {
        AtomicInteger selects = new AtomicInteger();
        AtomicInteger repairs = new AtomicInteger();
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        DeferredContextSource knowledge = new DeferredContextSource() {
            @Override public String id() { return "knowledge"; }
            @Override public String description() { return "Authorized knowledge"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                searches.incrementAndGet();
                assertEquals("Shanghai outing", query);
                return List.of();
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                throw new AssertionError("empty search must not fetch context");
            }
        };
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            var requested = selection.putArray("searches");
            if (request.purpose().endsWith(".select_v2")) {
                selects.incrementAndGet();
                for (String query : List.of("weather", "sights", "restaurants")) {
                    requested.addObject().put("source", "web").put("query", query);
                }
                selection.putArray("historyIds");
                toolIntent(selection, "");
            } else {
                assertEquals("context.on_demand.repair_select_sources_v2", request.purpose());
                if (repairs.incrementAndGet() == 1) {
                    // Seed a paused Run, then remove this new-version repair checkpoint below.
                    for (String query : List.of("weather", "sights", "restaurants")) {
                        requested.addObject().put("source", "knowledge").put("query", query);
                    }
                } else {
                    requested.addObject().put("source", "knowledge")
                            .put("query", "Shanghai outing");
                }
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(knowledge).autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("done", 2, 1);
        }, toolCallBudget(1), new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("plan Shanghai outing")),
                    Map.of(), PermissionSet.of("context.read"));
            RunHandle handle = fixture.engine.start(request);
            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            var persisted = new RunStepQuery(fixture.runs).steps(handle.id());
            AgentStep select = persisted.stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText().equals("select_v2"))
                    .findFirst().orElseThrow();
            AgentStep repair = persisted.stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText()
                                    .equals("repair_select_sources_v2"))
                    .findFirst().orElseThrow();
            assertEquals(3, select.output().path("selection").path("searches").size());
            assertEquals(AgentStep.State.COMPLETED, select.state());
            assertEquals(1, selects.get());
            assertEquals(1, repairs.get());
            assertEquals(0, searches.get());
            assertEquals(0, providerCalls.get());

            // Reopen a paused journal with completed select_v2 and no repair step.
            var repairEvents = fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.payload().path("stepId").asText()
                            .equals(repair.id().value()))
                    .toList();
            assertEquals(2, repairEvents.size());
            for (var event : repairEvents) {
                fixture.jdbc.update("DELETE FROM agent_run_events WHERE run_id=? AND event_sequence=?",
                        handle.id().value(), event.sequence());
            }
            assertTrue(new RunStepQuery(fixture.runs).step(handle.id(), repair.id()).isEmpty());

            fixture.restart();
            RunHandle resumed = fixture.engine.resume(handle.id(),
                    new ResumeCommand("delegation.continue", JsonNodeFactory.instance.objectNode()));
            RunOutcome outcome = resumed.completion().toCompletableFuture().get();

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals("done", outcome.output().path("text").asText());
            assertEquals(1, selects.get(), "the completed select must be replayed");
            assertEquals(2, repairs.get(), "a missing repair checkpoint must be created once");
            assertEquals(1, searches.get());
            assertEquals(1, providerCalls.get());
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText()
                                    .equals("repair_select_sources_v2"))
                    .count());
        }
    }

    @Test
    void 修正后仍选择未知来源则安全暂停且不调用主模型() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        DeferredContextSource knowledge = new DeferredContextSource() {
            @Override public String id() { return "knowledge"; }
            @Override public String description() { return "Authorized knowledge"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                searches.incrementAndGet();
                return List.of();
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                throw new AssertionError("no context should be fetched");
            }
        };
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches").addObject()
                        .put("source", "web").put("query", "Shanghai tomorrow");
                selection.putArray("historyIds");
                toolIntent(selection, "");
            } else {
                assertEquals("context.on_demand.repair_select_sources_v2", request.purpose());
                selection.putArray("searches").addObject()
                        .put("source", "web").put("query", "Shanghai tomorrow");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(knowledge);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("plan Shanghai outing")),
                    Map.of(), PermissionSet.of("context.read", "tool.execute"));
            RunHandle handle = fixture.engine.start(request);
            RunSnapshot snapshot = fixture.engine.get(handle.id());

            assertEquals(RunState.PAUSED, snapshot.state());
            assertEquals("context.planning_required", snapshot.output().path("kind").asText());
            assertTrue(snapshot.output().path("reason").asText().contains("web"));
            assertEquals(2, plannerCalls.get(), "source repair must be attempted once only");
            assertEquals(0, searches.get());
            assertEquals(0, providerCalls.get());
            assertTrue(new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .noneMatch(step -> step.kind() == AgentStep.Kind.MODEL));

            fixture.restart();
            fixture.engine.resume(handle.id(), new ResumeCommand(
                    "delegation.continue", JsonNodeFactory.instance.objectNode()));
            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals(2, plannerCalls.get(), "persisted repair must be reused after restart");
            assertEquals(0, searches.get());
            assertEquals(0, providerCalls.get());
        }
    }

    @Test
    void 禁用工具时延迟上下文来源不可检索且不产生工具Step() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        DeferredContextSource source = new DeferredContextSource() {
            @Override public String id() { return "docs"; }
            @Override public String description() { return "Documents"; }
            @Override public PermissionSet requiredPermissions() { return PermissionSet.NONE; }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                searches.incrementAndGet();
                return List.of();
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                throw new AssertionError("disabled source must not be fetched");
            }
        };
        ModelTaskGateway planner = request -> {
            assertEquals(0, request.input().path("sources").size());
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("searches").addObject()
                    .put("source", "docs").put("query", "needle");
            selection.putArray("historyIds");
            toolIntent(selection, "");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(source);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunRequest request = fixture.request("read docs", Map.of(
                    "framework.disableTools", JsonNodeFactory.instance.booleanNode(true)));
            RunHandle handle = fixture.engine.start(request);

            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals(0, searches.get());
            assertEquals(0, providerCalls.get());
            assertTrue(new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .noneMatch(step -> step.kind() == AgentStep.Kind.TOOL));
        }
    }

    @Test
    void 已完成Light任务可补全中断的规划Step且结果未知时仍暂停() throws Exception {
        for (boolean taskCompleted : List.of(true, false)) {
            AtomicInteger plannerCalls = new AtomicInteger();
            AtomicInteger providerCalls = new AtomicInteger();
            AtomicReference<ModelTaskRequest> planned = new AtomicReference<>();
            ModelTaskGateway planner = request -> {
                planned.set(request);
                plannerCalls.incrementAndGet();
                ObjectNode invalid = JsonNodeFactory.instance.objectNode();
                invalid.putArray("searches");
                invalid.putArray("historyIds").add("unknown-history");
                assertEquals("context.on_demand.select_v2", request.purpose());
                toolIntent(invalid, "");
                return CompletableFuture.completedFuture(new ModelTaskResult(
                        invalid, "planner", 1, 1, false, Map.of()));
            };
            List<Prompt> delivered = new ArrayList<>();
            FixtureConfig config = new FixtureConfig()
                    .context(contextConfiguration(true, 48_000, 16_000, 1))
                    .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                    .planner(planner);
            try (Fixture fixture = new Fixture(prompt -> {
                delivered.add(prompt);
                providerCalls.incrementAndGet();
                return textResponse("done", 2, 1);
            }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
                RunHandle handle = fixture.engine.start(fixture.request());
                assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
                assertEquals(0, providerCalls.get());
                AgentStep outer = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                        .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                                && step.input().path("phase").asText().equals("select_v2"))
                        .findFirst().orElseThrow();
                assertEquals(AgentStep.State.COMPLETED, outer.state());
                long outerCompletion = fixture.runs.eventsAfter(handle.id(), 0).stream()
                        .filter(event -> event.type().equals("core.step.completed")
                                && event.payload().path("stepId").asText().equals(outer.id().value()))
                        .findFirst().orElseThrow().sequence();
                fixture.jdbc.update("DELETE FROM agent_run_events WHERE run_id=? AND event_sequence=?",
                        handle.id().value(), outerCompletion);
                ModelTaskRequest original = planned.get();
                assertNotNull(original);
                ObjectNode taskInput = JsonNodeFactory.instance.objectNode()
                        .put("purpose", "context.on_demand.select_v2")
                        .put("attempt", 0)
                        .put("modelPolicy", "planner")
                        .put("inputHash", sha256(outer.input().path("plannerInput").toString()));
                taskInput.set("outputSchema", original.outputSchema());
                taskInput.set("messages", StepMessageCodec.messages(List.of(
                        new UserMessage(original.input().toString()))));
                StepId task = StepId.random();
                var events = StepEvents.durableSink(fixture.runs, handle.id());
                StepEvents.started(events, task, AgentStep.Kind.MODEL_TASK, taskInput, null);
                if (taskCompleted) {
                    ChatResponse valid = textResponse("""
                            {"searches":[],"historyIds":[],"toolIntent":{"query":"","groups":[]}}
                            """, 3, 2);
                    StepEvents.completed(events, task, StepMessageCodec.response(valid),
                            StepMessageCodec.usage(valid));
                }

                fixture.restart();
                fixture.engine.resume(handle.id(),
                        new ResumeCommand("delegation.continue", JsonNodeFactory.instance.objectNode()));

                assertEquals(taskCompleted ? RunState.COMPLETED : RunState.PAUSED,
                        fixture.engine.get(handle.id()).state());
                assertEquals(1, plannerCalls.get(),
                        "persisted LIGHT result must not be recomputed");
                assertEquals(taskCompleted ? 1 : 0, providerCalls.get());
                if (taskCompleted) {
                    AgentStep settled = new RunStepQuery(fixture.runs).step(handle.id(), outer.id())
                            .orElseThrow();
                    assertEquals(AgentStep.State.COMPLETED, settled.state());
                    assertEquals(5, fixture.ledger.snapshot(handle.id()).inputTokens());
                    assertEquals(3, fixture.ledger.snapshot(handle.id()).outputTokens());
                    assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
                }
            }
        }
    }

    @Test
    void 已完成Light任务可补全中断的Refine规划且不重复搜索() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger fetches = new AtomicInteger();
        AtomicReference<ModelTaskRequest> refineRequest = new AtomicReference<>();
        DeferredContextSource source = new DeferredContextSource() {
            @Override public String id() { return "docs"; }
            @Override public String description() { return "Documents"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                searches.incrementAndGet();
                return List.of(new DeferredContextCandidate(
                        "one", "v1", "relevant summary", PermissionSet.NONE));
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                fetches.incrementAndGet();
                return "REFINED_BODY_MARKER";
            }
        };
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches").addObject()
                        .put("source", "docs").put("query", "needle");
                toolIntent(selection, "");
            } else {
                refineRequest.set(request);
                selection.putArray("sourceIds").add("docs:missing");
                toolChoice(selection, request, "none");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        List<Prompt> delivered = new ArrayList<>();
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(source).autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            delivered.add(prompt);
            providerCalls.incrementAndGet();
            assertTrue(prompt.getInstructions().stream().anyMatch(message ->
                    message.getText() != null
                            && message.getText().contains("REFINED_BODY_MARKER")));
            return textResponse("done", 2, 1);
        }, toolCallBudget(2), new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("find needle")),
                    Map.of(), PermissionSet.of("context.read"));
            RunHandle handle = fixture.engine.start(request);
            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals(2, plannerCalls.get());
            assertEquals(1, searches.get());
            assertEquals(0, fetches.get());
            AgentStep outer = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText().equals("refine_v2"))
                    .findFirst().orElseThrow();
            long completion = fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.step.completed")
                            && event.payload().path("stepId").asText().equals(outer.id().value()))
                    .findFirst().orElseThrow().sequence();
            fixture.jdbc.update("DELETE FROM agent_run_events WHERE run_id=? AND event_sequence=?",
                    handle.id().value(), completion);
            ModelTaskRequest original = refineRequest.get();
            assertNotNull(original);
            ObjectNode taskInput = JsonNodeFactory.instance.objectNode()
                    .put("purpose", "context.on_demand.refine_v2")
                    .put("attempt", 0)
                    .put("modelPolicy", "planner")
                    .put("inputHash", sha256(outer.input().path("plannerInput").toString()));
            taskInput.set("outputSchema", original.outputSchema());
            taskInput.set("messages", StepMessageCodec.messages(List.of(
                    new UserMessage(original.input().toString()))));
            StepId task = StepId.random();
            var events = StepEvents.durableSink(fixture.runs, handle.id());
            StepEvents.started(events, task, AgentStep.Kind.MODEL_TASK, taskInput, null);
            ChatResponse valid = textResponse("""
                    {"historyIds":[],"sourceIds":["docs:one"],"toolAction":"none","toolIds":[]}
                    """, 3, 2);
            StepEvents.completed(events, task, StepMessageCodec.response(valid),
                    StepMessageCodec.usage(valid));

            fixture.restart();
            RunHandle resumed = fixture.engine.resume(handle.id(),
                    new ResumeCommand("delegation.continue", JsonNodeFactory.instance.objectNode()));
            RunOutcome outcome = resumed.completion().toCompletableFuture().get();

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(2, plannerCalls.get());
            assertEquals(1, searches.get(), "durable search must be reused");
            assertEquals(1, fetches.get());
            assertEquals(1, providerCalls.get());
            assertEquals(2, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.started")).count());
            assertEquals(AgentStep.State.COMPLETED,
                    new RunStepQuery(fixture.runs).step(handle.id(), outer.id())
                            .orElseThrow().state());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void Refine规划暂停后持久候选定义变化阻止重放到主模型() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true));
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, "code_target", "code");
            } else {
                if (request.purpose().endsWith(".refine_v2")) {
                    assertEquals(1, request.input().path("toolCandidates").size());
                    selection.putArray("sourceIds");
                } else {
                    assertEquals("context.on_demand.repair_refine_tools_v2", request.purpose());
                }
                selection.put("toolAction", "direct");
                selection.putArray("toolIds").add("forged-id");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        config.planner(planner);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("use code_target"));
            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals(3, plannerCalls.get());
            List<AgentStep> retrieved = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText().equals("tool_search_v2"))
                    .toList();
            assertEquals(1, retrieved.size());
            assertEquals("code_target", retrieved.getFirst().output()
                    .path("candidates").get(0).path("name").asText());
            assertTrue(new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .noneMatch(step -> step.kind() == AgentStep.Kind.MODEL));

            config.codeDescriptionPadding(19);
            fixture.restart();
            fixture.engine.resume(handle.id(), new ResumeCommand(
                    "delegation.continue", JsonNodeFactory.instance.objectNode()));

            RunSnapshot snapshot = fixture.engine.get(handle.id());
            assertEquals(RunState.PAUSED, snapshot.state());
            assertTrue(snapshot.output().path("reason").asText().contains("changed"),
                    snapshot.output().toString());
            assertEquals(3, plannerCalls.get(), "persisted select and refine must be reused");
            assertEquals(0, providerCalls.get());
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText().equals("tool_search_v2"))
                    .count());
        }
    }

    @Test
    void 在途MODEL回放前候选定义变化会在Provider调用前暂停() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, "code_target", "code");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", "code_target");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).codeCalls(codeCalls)
                .downstreamMutation(DownstreamMutation.PAUSE_AFTER_ACTIVATION_ONCE);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("use code_target"));
            assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state());
            assertEquals(0, providerCalls.get());
            assertEquals(2, plannerCalls.get());
            Prompt selected = config.pausedPrompt.get();
            assertNotNull(selected);
            ObjectNode modelInput = JsonNodeFactory.instance.objectNode();
            modelInput.set("messages", StepMessageCodec.messages(selected.getInstructions()));
            modelInput.set("toolNames", fixture.json.valueToTree(toolNames(selected)));
            ObjectNode fingerprints = modelInput.putObject("toolFingerprints");
            ((ToolCallingChatOptions) selected.getOptions()).getToolCallbacks().forEach(callback ->
                    fingerprints.put(callback.getToolDefinition().name(),
                            ToolCatalogSession.fingerprint(callback)));
            modelInput.put("toolSchemaCharacters",
                    ((ToolCallingChatOptions) selected.getOptions()).getToolCallbacks().stream()
                            .mapToInt(SpringAiToolCatalog::schemaCharacters).sum());
            modelInput.put("modelPolicy", "test:model");
            modelInput.put("attempt", 1);
            AgentStep retrieval = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText().equals("tool_search_v2"))
                    .findFirst().orElseThrow();
            modelInput.put("toolCandidateStepId", retrieval.id().value());
            StepId inFlight = StepId.random();
            StepEvents.started(StepEvents.durableSink(fixture.runs, handle.id()),
                    inFlight, AgentStep.Kind.MODEL, modelInput, null);
            List<AgentStep> models = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.MODEL).toList();
            assertEquals(1, models.size());
            assertEquals(AgentStep.State.RUNNING, models.getFirst().state());

            config.codeDescriptionPadding(29);
            fixture.restart();
            fixture.engine.resume(handle.id(), new ResumeCommand(
                    "delegation.continue", JsonNodeFactory.instance.objectNode()));

            RunSnapshot snapshot = fixture.engine.get(handle.id());
            assertEquals(RunState.PAUSED, snapshot.state());
            assertTrue(snapshot.output().path("reason").asText().contains("changed"),
                    snapshot.output().toString());
            assertEquals(0, providerCalls.get());
            assertEquals(0, codeCalls.get());
            assertEquals(2, plannerCalls.get(), "MODEL replay must not rerun tool planning");
        }
    }

    @Test
    void 已完成MODEL恢复待执行工具前先核对候选定义() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, "code_target", "code");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", "code_target");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).codeCalls(codeCalls)
                .downstreamMutation(DownstreamMutation.PAUSE_AFTER_ACTIVATION_ONCE);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("use code_target"));
            assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state());
            assertEquals(0, providerCalls.get());
            Prompt selected = config.pausedPrompt.get();
            assertNotNull(selected);
            AgentStep retrieval = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText().equals("tool_search_v2"))
                    .findFirst().orElseThrow();
            ObjectNode modelInput = JsonNodeFactory.instance.objectNode();
            modelInput.set("messages", StepMessageCodec.messages(selected.getInstructions()));
            modelInput.set("toolNames", fixture.json.valueToTree(toolNames(selected)));
            ObjectNode fingerprints = modelInput.putObject("toolFingerprints");
            ((ToolCallingChatOptions) selected.getOptions()).getToolCallbacks().forEach(callback ->
                    fingerprints.put(callback.getToolDefinition().name(),
                            ToolCatalogSession.fingerprint(callback)));
            modelInput.put("toolSchemaCharacters",
                    ((ToolCallingChatOptions) selected.getOptions()).getToolCallbacks().stream()
                            .mapToInt(SpringAiToolCatalog::schemaCharacters).sum());
            modelInput.put("modelPolicy", "test:model");
            modelInput.put("attempt", 1);
            modelInput.put("toolCandidateStepId", retrieval.id().value());
            StepId model = StepId.random();
            var events = StepEvents.durableSink(fixture.runs, handle.id());
            StepEvents.started(events, model, AgentStep.Kind.MODEL, modelInput, null);
            ChatResponse toolCall = namedToolCallResponse("code_target", "{\"value\":42}", 2, 1);
            StepEvents.completed(events, model, StepMessageCodec.response(toolCall),
                    StepMessageCodec.usage(toolCall));
            assertEquals(0, codeCalls.get());

            config.codeDescriptionPadding(29);
            fixture.restart();
            fixture.engine.resume(handle.id(), new ResumeCommand(
                    "delegation.continue", JsonNodeFactory.instance.objectNode()));

            RunSnapshot snapshot = fixture.engine.get(handle.id());
            assertEquals(RunState.PAUSED, snapshot.state());
            assertEquals("tool.recovery_required", snapshot.output().path("kind").asText());
            assertTrue(snapshot.output().path("reason").asText().contains("changed"),
                    snapshot.output().toString());
            assertEquals(0, codeCalls.get(), "definition drift must stop before the tool side effect");
            assertEquals(0, providerCalls.get());
        }
    }

    @Test
    void 已激活工具定义变化时在途MODEL恢复前暂停() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            selection.putArray("searches");
            toolIntent(selection, "__no_matching_tool__");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).codeCalls(codeCalls)
                .downstreamMutation(DownstreamMutation.PAUSE_AFTER_ACTIVATION_ONCE);
        try (Fixture fixture = new Fixture(prompt -> {
            assertEquals(List.of("framework_tool_catalog"), toolNames(prompt));
            providerCalls.incrementAndGet();
            return namedToolCallResponse("framework_tool_catalog",
                    "{\"action\":\"activate\",\"names\":[\"code_target\"]}", 2, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("activate code_target"));
            assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state());
            assertEquals(1, providerCalls.get());
            Prompt selected = config.pausedPrompt.get();
            assertEquals(List.of("code_target"), toolNames(selected));
            ObjectNode modelInput = JsonNodeFactory.instance.objectNode();
            modelInput.set("messages", StepMessageCodec.messages(selected.getInstructions()));
            modelInput.set("toolNames", fixture.json.valueToTree(toolNames(selected)));
            ObjectNode fingerprints = modelInput.putObject("toolFingerprints");
            ((ToolCallingChatOptions) selected.getOptions()).getToolCallbacks().forEach(callback ->
                    fingerprints.put(callback.getToolDefinition().name(),
                            ToolCatalogSession.fingerprint(callback)));
            modelInput.put("toolSchemaCharacters",
                    ((ToolCallingChatOptions) selected.getOptions()).getToolCallbacks().stream()
                            .mapToInt(SpringAiToolCatalog::schemaCharacters).sum());
            modelInput.put("modelPolicy", "test:model");
            modelInput.put("attempt", 1);
            StepEvents.started(StepEvents.durableSink(fixture.runs, handle.id()),
                    StepId.random(), AgentStep.Kind.MODEL, modelInput, null);

            config.codeDescriptionPadding(31);
            fixture.restart();
            fixture.engine.resume(handle.id(), new ResumeCommand(
                    "delegation.continue", JsonNodeFactory.instance.objectNode()));

            RunSnapshot snapshot = fixture.engine.get(handle.id());
            assertEquals(RunState.PAUSED, snapshot.state());
            assertEquals("tool.recovery_required", snapshot.output().path("kind").asText());
            assertTrue(snapshot.output().path("reason").asText().contains("changed"),
                    snapshot.output().toString());
            assertEquals(1, providerCalls.get());
            assertEquals(0, codeCalls.get());
        }
    }

    @Test
    void 目录策略撤销后不再恢复展示目录工具() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            selection.putArray("searches");
            toolIntent(selection, "__no_matching_tool__");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .allowedToolNames(Set.of("code_target", "test_mutate", "framework_tool_catalog"))
                .planner(planner).downstreamMutation(DownstreamMutation.PAUSE_ON_CATALOG_ONCE);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("find a tool"));
            assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state());
            assertEquals(0, providerCalls.get());
            Prompt selected = config.pausedPrompt.get();
            assertEquals(List.of("framework_tool_catalog"), toolNames(selected));
            ObjectNode modelInput = JsonNodeFactory.instance.objectNode();
            modelInput.set("messages", StepMessageCodec.messages(selected.getInstructions()));
            modelInput.set("toolNames", fixture.json.valueToTree(toolNames(selected)));
            ObjectNode fingerprints = modelInput.putObject("toolFingerprints");
            ((ToolCallingChatOptions) selected.getOptions()).getToolCallbacks().forEach(callback ->
                    fingerprints.put(callback.getToolDefinition().name(),
                            ToolCatalogSession.fingerprint(callback)));
            modelInput.put("toolSchemaCharacters",
                    ((ToolCallingChatOptions) selected.getOptions()).getToolCallbacks().stream()
                            .mapToInt(SpringAiToolCatalog::schemaCharacters).sum());
            modelInput.put("modelPolicy", "test:model");
            modelInput.put("attempt", 1);
            StepEvents.started(StepEvents.durableSink(fixture.runs, handle.id()),
                    StepId.random(), AgentStep.Kind.MODEL, modelInput, null);

            config.allowedToolNames(Set.of("code_target", "test_mutate"));
            fixture.restart();
            fixture.engine.resume(handle.id(), new ResumeCommand(
                    "delegation.continue", JsonNodeFactory.instance.objectNode()));

            RunSnapshot snapshot = fixture.engine.get(handle.id());
            assertEquals(RunState.PAUSED, snapshot.state());
            assertEquals("tool.recovery_required", snapshot.output().path("kind").asText());
            assertTrue(snapshot.output().path("reason").asText().contains("no longer authorized"),
                    snapshot.output().toString());
            assertEquals(0, providerCalls.get());
        }
    }

    @Test
    void Provider两次失败后的重试仍复用同一有效工具候选() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                toolIntent(selection, "code_target", "code");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct", "code_target");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).clarificationTool(true);
        try (Fixture fixture = new Fixture(prompt -> {
            int attempt = providerCalls.incrementAndGet();
            assertEquals(List.of("code_target"), toolNames(prompt));
            if (attempt <= 2) throw new IllegalStateException("temporary provider failure");
            return textResponse("done", 2, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request("use code_target"));
            RunOutcome outcome = handle.completion().toCompletableFuture().get();

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(3, providerCalls.get());
            assertEquals(2, plannerCalls.get());
            assertEquals(2, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.model.retrying")).count());
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText().equals("tool_search_v2"))
                    .count());
        }
    }

    @Test
    void 按需搜索只暴露许可候选并去重已选正文() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger fetches = new AtomicInteger();
        AtomicInteger plannerCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        DeferredContextSource source = new DeferredContextSource() {
            @Override public String id() { return "docs"; }
            @Override public String description() { return "Document evidence"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                searches.incrementAndGet();
                assertEquals("needle", query);
                return List.of(
                        new DeferredContextCandidate("one", "v1", "first summary",
                                PermissionSet.NONE),
                        new DeferredContextCandidate("secret", "v1", "SECRET_SUMMARY_MARKER",
                                PermissionSet.of("secret.read")),
                        new DeferredContextCandidate("two", "v1", "second summary",
                                PermissionSet.NONE));
            }
            @Override public String fetch(RunRequest request, String candidateId, String version) {
                fetches.incrementAndGet();
                assertTrue(Set.of("one", "two").contains(candidateId));
                assertEquals("v1", version);
                return "SOURCE_BODY_MARKER";
            }
        };
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            assertFalse(request.input().toString().contains("SOURCE_BODY_MARKER"));
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches").addObject()
                        .put("source", "docs").put("query", "needle");
                selection.putArray("historyIds");
                toolIntent(selection, "");
            } else {
                assertEquals("context.on_demand.refine_v2", request.purpose());
                assertEquals(2, request.input().path("candidates").size());
                assertFalse(request.input().toString().contains("SECRET_SUMMARY_MARKER"));
                selection.putArray("historyIds");
                selection.putArray("sourceIds").add("docs:one").add("docs:two");
                toolChoice(selection, request, "none");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(source).autoApproveContext().singleIoPermit();
        try (Fixture fixture = new Fixture(prompt -> {
            delivered.add(prompt);
            assertEquals(List.of(), toolNames(prompt));
            assertEquals(1, prompt.getInstructions().stream()
                    .filter(message -> message.getText() != null
                            && message.getText().contains("SOURCE_BODY_MARKER"))
                    .count());
            return textResponse("answer", 2, 1);
        }, toolCallBudget(3), new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("find needle")),
                    Map.of(), PermissionSet.of("context.read", "tool.execute"));
            RunHandle handle = fixture.engine.start(request);
            RunOutcome outcome = handle.completion().toCompletableFuture().get();

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(2, plannerCalls.get());
            assertEquals(1, searches.get());
            assertEquals(2, fetches.get());
            assertEquals(3, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.completed")).count());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void 首个来源占满候选额度时仍搜索第二来源并可选择其证据() throws Exception {
        AtomicInteger earlySearches = new AtomicInteger();
        AtomicInteger lateSearches = new AtomicInteger();
        AtomicInteger lateFetches = new AtomicInteger();
        DeferredContextSource early = new DeferredContextSource() {
            @Override public String id() { return "early"; }
            @Override public String description() { return "Early results"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                earlySearches.incrementAndGet();
                return java.util.stream.IntStream.range(0, limit)
                        .mapToObj(index -> new DeferredContextCandidate(
                                "item" + index, "v1", "early summary " + index,
                                PermissionSet.NONE))
                        .toList();
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                throw new AssertionError("early body was not selected");
            }
        };
        DeferredContextSource late = new DeferredContextSource() {
            @Override public String id() { return "late"; }
            @Override public String description() { return "Needed evidence"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                lateSearches.incrementAndGet();
                return List.of(new DeferredContextCandidate(
                        "needed", "v1", "needed late summary", PermissionSet.NONE));
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                lateFetches.incrementAndGet();
                assertEquals("needed", id);
                return "LATE_NEEDED_BODY";
            }
        };
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches").addObject()
                        .put("source", "early").put("query", "broad");
                selection.withArray("searches").addObject()
                        .put("source", "late").put("query", "needed");
                toolIntent(selection, "");
            } else {
                assertEquals(32, request.input().path("candidates").size());
                assertTrue(request.input().path("candidates").toString()
                        .contains("late:needed"));
                selection.putArray("sourceIds").add("late:needed");
                toolChoice(selection, request, "none");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(early).source(late).autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            assertTrue(prompt.getInstructions().stream().anyMatch(message ->
                    message.getText() != null
                            && message.getText().contains("LATE_NEEDED_BODY")));
            return textResponse("done", 2, 1);
        }, toolCallBudget(3), new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("find needed evidence")),
                    Map.of(), PermissionSet.of("context.read"));
            RunOutcome outcome = fixture.engine.start(request)
                    .completion().toCompletableFuture().get();

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(1, earlySearches.get());
            assertEquals(1, lateSearches.get());
            assertEquals(1, lateFetches.get());
        }
    }

    @Test
    void 同查询跨模型步重新搜索并按候选版本复用正文() throws Exception {
        for (boolean versionChanges : List.of(false, true)) {
            AtomicInteger providerCalls = new AtomicInteger();
            AtomicInteger searches = new AtomicInteger();
            AtomicInteger fetches = new AtomicInteger();
            AtomicInteger businessCalls = new AtomicInteger();
            List<Prompt> delivered = new ArrayList<>();
            DeferredContextSource source = new DeferredContextSource() {
                @Override public String id() { return "docs"; }
                @Override public String description() { return "Documents"; }
                @Override public PermissionSet requiredPermissions() {
                    return PermissionSet.of("context.read");
                }
                @Override public List<DeferredContextCandidate> search(
                        RunRequest request, String query, int limit) {
                    searches.incrementAndGet();
                    assertEquals("same-query", query);
                    String version = versionChanges && providerCalls.get() > 0 ? "v2" : "v1";
                    return List.of(new DeferredContextCandidate(
                            "one", version, "document summary", PermissionSet.NONE));
                }
                @Override public String fetch(RunRequest request, String id, String version) {
                    fetches.incrementAndGet();
                    assertEquals("one", id);
                    return "VERSIONED_BODY_" + version;
                }
            };
            ModelTaskGateway planner = request -> {
                if (providerCalls.get() > 0 && request.purpose().endsWith(".select_v2")) {
                    assertFalse(request.input().path("history").toString().contains("persisted:"));
                    assertFalse(request.input().path("history").toString().contains("VERSIONED_BODY_v1"));
                }
                ObjectNode selection = JsonNodeFactory.instance.objectNode();
                selection.putArray("historyIds");
                if (request.purpose().endsWith(".select_v2")) {
                    selection.putArray("searches").addObject()
                            .put("source", "docs").put("query", "same-query");
                    toolIntent(selection, providerCalls.get() == 0 ? "test_mutate" : "",
                            providerCalls.get() == 0 ? "test" : "");
                } else {
                    selection.putArray("sourceIds").add("docs:one");
                    if (providerCalls.get() == 0) {
                        toolChoice(selection, request, "direct", "test_mutate");
                    } else {
                        toolChoice(selection, request, "none");
                    }
                }
                return CompletableFuture.completedFuture(new ModelTaskResult(
                        selection, "planner", 1, 1, false, Map.of()));
            };
            FixtureConfig config = new FixtureConfig()
                    .context(contextConfiguration(true, 48_000, 16_000, 1))
                    .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                    .planner(planner).source(source).autoApproveContext()
                    .autoApproveTestMutate();
            try (Fixture fixture = new Fixture(prompt -> {
                delivered.add(prompt);
                int call = providerCalls.incrementAndGet();
                String expected = versionChanges && call == 2 ? "v2" : "v1";
                assertTrue(prompt.getInstructions().stream().anyMatch(message ->
                        message.getText() != null && message.getText().contains(
                                "VERSIONED_BODY_" + expected)));
                if (versionChanges && call == 2) {
                    assertFalse(prompt.getInstructions().stream().anyMatch(message ->
                            message.getText() != null
                                    && message.getText().contains("VERSIONED_BODY_v1")));
                }
                return call == 1 ? toolCallResponse(2, 1) : textResponse("done", 2, 1);
            }, toolCallBudget(6), businessCalls, config)) {
                RunRequest request = fixture.request(List.of(InputBlock.text("read document")),
                        Map.of(), PermissionSet.of("context.read", "tool.execute"));
                RunHandle handle = fixture.engine.start(request);
                RunOutcome outcome = handle.completion().toCompletableFuture().get();

                assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
                assertEquals(2, providerCalls.get());
                assertEquals(2, searches.get(), "the same query needs a fresh search in a later step");
                assertEquals(versionChanges ? 2 : 1, fetches.get(),
                        "only an unchanged candidate ID and version may reuse its body");
                assertEquals(1, businessCalls.get());
                assertEquals(2, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                        .filter(step -> step.kind() == AgentStep.Kind.MODEL).count());
                assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
            }
        }
    }

    @Test
    void 超过业务工具结果淘汰限额的延迟正文在重启后只读取一次() throws Exception {
        String body = "LARGE_DEFERRED_BODY_MARKER" + "x".repeat(2_000);
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger fetches = new AtomicInteger();
        AtomicInteger plannerCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        DeferredContextSource source = new DeferredContextSource() {
            @Override public String id() { return "docs"; }
            @Override public String description() { return "Documents"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                searches.incrementAndGet();
                return List.of(new DeferredContextCandidate(
                        "large", "v1", "large document", PermissionSet.NONE));
            }
            @Override public String fetch(RunRequest request, String id, String version) {
                fetches.incrementAndGet();
                assertEquals("large", id);
                assertEquals("v1", version);
                return body;
            }
        };
        ModelTaskGateway planner = request -> {
            plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("historyIds");
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches").addObject()
                        .put("source", "docs").put("query", "large");
                toolIntent(selection, "");
            } else {
                selection.putArray("sourceIds").add("docs:large");
                toolChoice(selection, request, "none");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .resultEviction(JsonNodeFactory.instance.objectNode()
                        .put("enabled", true).put("maxCharacters", 1_000))
                .planner(planner).source(source).autoApproveContext()
                .downstreamMutation(DownstreamMutation.PAUSE_AFTER_DEFERRED_FETCH_ONCE);
        try (Fixture fixture = new Fixture(prompt -> {
            delivered.add(prompt);
            assertTrue(prompt.getInstructions().stream().anyMatch(message ->
                    message.getText() != null && message.getText().contains(body)));
            return textResponse("done", 2, 1);
        }, toolCallBudget(2), new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("read the large document")),
                    Map.of(), PermissionSet.of("context.read"));
            RunHandle handle = fixture.engine.start(request);
            assertEquals(RunState.WAITING_INPUT, fixture.engine.get(handle.id()).state());
            assertEquals(1, searches.get());
            assertEquals(1, fetches.get());
            AgentStep read = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.TOOL
                            && step.input().path("tool").asText().startsWith("framework_context_fetch_"))
                    .findFirst().orElseThrow();
            assertEquals(body, read.output().path("rawOutput").path("body").asText());
            assertEquals(body, read.output().path("modelOutput").path("body").asText());

            fixture.restart();
            RunHandle resumed = fixture.engine.resume(handle.id(),
                    new ResumeCommand("delegation.continue", JsonNodeFactory.instance.objectNode()));
            RunOutcome outcome = resumed.completion().toCompletableFuture().get();

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(1, searches.get());
            assertEquals(1, fetches.get());
            assertEquals(2, plannerCalls.get());
            assertEquals(2, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.tool.started")).count());
            assertEquals(1, new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.MODEL).count(),
                    "resuming the same provider step must reuse its completed context reads");
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void 重启后未知结果的上下文读取仍暂停且不重复执行() throws Exception {
        AtomicReference<Fixture> active = new AtomicReference<>();
        AtomicBoolean injected = new AtomicBoolean();
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger searchCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        DeferredContextSource source = new DeferredContextSource() {
            @Override public String id() { return "docs"; }
            @Override public String description() { return "Document evidence"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                searchCalls.incrementAndGet();
                return List.of();
            }
            @Override public String fetch(RunRequest request, String candidateId, String version) {
                throw new AssertionError("fetch must not run");
            }
        };
        ModelTaskGateway planner = request -> {
            assertEquals("context.on_demand.select_v2", request.purpose());
            plannerCalls.incrementAndGet();
            if (injected.compareAndSet(false, true)) {
                Fixture fixture = active.get();
                AgentStep planning = new RunStepQuery(fixture.runs)
                        .steps(request.ownerRunId()).stream()
                        .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                                && step.state() == AgentStep.State.RUNNING
                                && step.input().path("phase").asText().equals("select_v2"))
                        .findFirst().orElseThrow();
                String invocation = planning.input().path("key").asText()
                        + "/search/docs/" + sha256("needle\n32");
                ObjectNode readInput = JsonNodeFactory.instance.objectNode()
                        .put("tool", "framework_context_search_" + sha256("docs").substring(0, 12))
                        .put("invocationId", invocation);
                readInput.putObject("arguments").put("query", "needle").put("limit", 32);
                StepEvents.started(StepEvents.durableSink(fixture.runs, request.ownerRunId()),
                        StepId.tool(request.ownerRunId(), invocation), AgentStep.Kind.TOOL,
                        readInput, null);
            }
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("searches").addObject()
                    .put("source", "docs").put("query", "needle");
            selection.putArray("historyIds");
            toolIntent(selection, "");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(source).autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("unexpected", 1, 1);
        }, toolCallBudget(1), new AtomicInteger(), config)) {
            active.set(fixture);
            RunRequest request = fixture.request(List.of(InputBlock.text("find needle")),
                    Map.of(), PermissionSet.of("context.read"));
            RunHandle handle = fixture.engine.start(request);
            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            fixture.restart();
            fixture.engine.resume(handle.id(),
                    new ResumeCommand("delegation.continue", JsonNodeFactory.instance.objectNode()));

            assertEquals(RunState.PAUSED, fixture.engine.get(handle.id()).state());
            assertEquals(1, plannerCalls.get());
            assertEquals(0, searchCalls.get());
            assertEquals(0, providerCalls.get());
            assertTrue(new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .anyMatch(step -> step.kind() == AgentStep.Kind.TOOL
                            && step.state() == AgentStep.State.RUNNING));
        }
    }

    @Test
    void 已激活工具独立容纳时不强制并列展示目录工具() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (providerCalls.incrementAndGet()) {
                case 1 -> {
                    assertTrue(toolNames(prompt).contains("framework_tool_catalog"));
                    yield namedToolCallResponse("framework_tool_catalog",
                            "{\"action\":\"activate\",\"names\":[\"code_target\"]}", 2, 1);
                }
                case 2 -> {
                    assertEquals(List.of("code_target"), toolNames(prompt));
                    yield namedToolCallResponse("code_target", "{\"value\":7}", 2, 1);
                }
                case 3 -> {
                    assertEquals(List.of(), toolNames(prompt));
                    assertEquals("code_target", lastToolResponse(prompt).name());
                    yield textResponse("done", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 2)
                        .put("maxToolSchemaCharacters", 4_000))
                .fillerTools(70).codeCalls(codeCalls).codeDescriptionPadding(3_700);
        try (Fixture fixture = new Fixture(model, toolCallBudget(2),
                new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            RunOutcome outcome = handle.completion().toCompletableFuture().get();

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(3, providerCalls.get());
            assertEquals(1, codeCalls.get());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void 默认候选上限允许选择第十七个搜索结果() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        List<Prompt> delivered = new ArrayList<>();
        DeferredContextSource source = new DeferredContextSource() {
            @Override public String id() { return "docs"; }
            @Override public String description() { return "Documents"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                return java.util.stream.IntStream.rangeClosed(1, 17)
                        .mapToObj(index -> new DeferredContextCandidate(
                                "doc" + index, "v1", "summary " + index, PermissionSet.NONE))
                        .toList();
            }
            @Override public String fetch(RunRequest request, String candidateId, String version) {
                fetches.incrementAndGet();
                assertEquals("doc17", candidateId);
                return "SEVENTEENTH_BODY_MARKER";
            }
        };
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches").addObject()
                        .put("source", "docs").put("query", "all");
                selection.putArray("historyIds");
                toolIntent(selection, "");
            } else {
                assertEquals("context.on_demand.refine_v2", request.purpose());
                assertEquals(17, request.input().path("candidates").size());
                assertTrue(request.input().path("candidates").toString()
                        .contains("docs:doc17"));
                selection.putArray("historyIds");
                selection.putArray("sourceIds").add("docs:doc17");
                toolChoice(selection, request, "none");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(source).autoApproveContext();
        try (Fixture fixture = new Fixture(prompt -> {
            delivered.add(prompt);
            assertTrue(prompt.getInstructions().stream().anyMatch(message ->
                    message.getText() != null
                            && message.getText().contains("SEVENTEENTH_BODY_MARKER")));
            return textResponse("answer", 2, 1);
        }, toolCallBudget(2), new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("read doc 17")),
                    Map.of(), PermissionSet.of("context.read"));
            RunHandle handle = fixture.engine.start(request);
            RunOutcome outcome = handle.completion().toCompletableFuture().get();

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(1, fetches.get());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void 重复搜索与长查询不会丢失可选来源候选() throws Exception {
        for (boolean duplicate : List.of(true, false)) {
            AtomicInteger searches = new AtomicInteger();
            AtomicInteger fetches = new AtomicInteger();
            String firstQuery = "A".repeat(256);
            String secondQuery = duplicate ? firstQuery : "B".repeat(256);
            DeferredContextSource source = new DeferredContextSource() {
                @Override public String id() { return "docs"; }
                @Override public String description() { return "Document evidence"; }
                @Override public PermissionSet requiredPermissions() {
                    return PermissionSet.of("context.read");
                }
                @Override public List<DeferredContextCandidate> search(
                        RunRequest request, String query, int limit) {
                    searches.incrementAndGet();
                    return List.of(new DeferredContextCandidate(
                            "one", "current", "Matching document", PermissionSet.NONE));
                }
                @Override public String fetch(RunRequest request, String id, String version) {
                    fetches.incrementAndGet();
                    return "MATCHING_DOCUMENT_BODY";
                }
            };
            ModelTaskGateway planner = request -> {
                ObjectNode selection = JsonNodeFactory.instance.objectNode();
                if (request.purpose().endsWith(".select_v2")) {
                    var requested = selection.putArray("searches");
                    requested.addObject().put("source", "docs").put("query", firstQuery);
                    requested.addObject().put("source", "docs").put("query", secondQuery);
                    selection.putArray("historyIds");
                    toolIntent(selection, "");
                } else {
                    assertEquals("context.on_demand.refine_v2", request.purpose());
                    assertTrue(request.input().toString().length() <= 1_000);
                    assertEquals("docs:one", request.input().path("candidates")
                            .get(0).path("id").asText());
                    selection.putArray("historyIds");
                    selection.putArray("sourceIds").add("docs:one");
                    toolChoice(selection, request, "none");
                }
                return CompletableFuture.completedFuture(new ModelTaskResult(
                        selection, "planner", 1, 1, false, Map.of()));
            };
            FixtureConfig config = new FixtureConfig()
                    .context(contextConfiguration(true, 48_000, 16_000, 1))
                    .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true)
                            .put("plannerInputChars", 1_000)
                            .put("searches", duplicate ? 1 : 2))
                    .planner(planner).source(source).autoApproveContext();
            try (Fixture fixture = new Fixture(prompt -> {
                assertTrue(prompt.getInstructions().stream().anyMatch(message ->
                        message.getText() != null
                                && message.getText().contains("MATCHING_DOCUMENT_BODY")));
                return textResponse("done", 2, 1);
            }, toolCallBudget(3), new AtomicInteger(), config)) {
                RunHandle handle = fixture.engine.start(fixture.request(
                        List.of(InputBlock.text("Read the matching document")),
                        Map.of(), PermissionSet.of("context.read")));
                RunOutcome outcome = handle.completion().toCompletableFuture().get();
                assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
                assertEquals(duplicate ? 1 : 2, searches.get());
                assertEquals(1, fetches.get());
            }
        }
    }

    @Test
    void 按需来源的读取工具被策略拒绝时不进入规划目录() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        DeferredContextSource source = new DeferredContextSource() {
            @Override public String id() { return "docs"; }
            @Override public String description() { return "Document evidence"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("context.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                searches.incrementAndGet();
                return List.of(new DeferredContextCandidate("one", "v1", "summary",
                        PermissionSet.NONE));
            }
            @Override public String fetch(RunRequest request, String candidateId, String version) {
                throw new AssertionError("denied fetch must not run");
            }
        };
        ModelTaskGateway planner = request -> {
            assertFalse(request.input().path("sources").toString().contains("docs"));
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("searches");
            selection.putArray("historyIds");
            toolIntent(selection, "");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        String searchName = "framework_context_search_" + sha256("docs").substring(0, 12);
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).source(source)
                .allowedToolNames(Set.of(searchName));
        try (Fixture fixture = new Fixture(prompt -> textResponse("done", 2, 1),
                RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(InputBlock.text("search docs")),
                    Map.of(), PermissionSet.of("context.read"));
            RunOutcome outcome = fixture.engine.start(request).completion().toCompletableFuture().get();
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(0, searches.get());
        }
    }

    @Test
    void 按需遗漏的旧工具交换在重启后仍可按完整调用响应选回() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger mutations = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        AtomicReference<String> firstExchangeId = new AtomicReference<>();
        List<Prompt> delivered = new ArrayList<>();
        ModelTaskGateway planner = request -> {
            int number = plannerCalls.incrementAndGet();
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            var history = selection.putArray("historyIds");
            if (providerCalls.get() == 3 && request.purpose().endsWith(".select_v2")) {
                String expected = firstExchangeId.get();
                assertNotNull(expected);
                assertTrue(request.input().path("history").findValuesAsText("id")
                        .contains(expected), "earlier durable exchange must remain a candidate");
                history.add(expected);
            }
            if (request.purpose().endsWith(".select_v2")) {
                selection.putArray("searches");
                String tool = providerCalls.get() < 2 ? "code_target"
                        : providerCalls.get() == 2 ? "test_mutate" : "";
                toolIntent(selection, tool, tool.startsWith("code") ? "code"
                        : tool.isEmpty() ? "" : "test");
            } else {
                selection.putArray("sourceIds");
                toolChoice(selection, request, "direct",
                        providerCalls.get() < 2 ? "code_target" : "test_mutate");
            }
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        ChatModel model = prompt -> {
            delivered.add(prompt);
            return switch (providerCalls.incrementAndGet()) {
                case 1 -> namedToolCallResponse("code_target", "{\"value\":1}", 2, 1);
                case 2 -> namedToolCallResponse("code_target", "{\"value\":2}", 2, 1);
                case 3 -> {
                    assertFalse(StepMessageCodec.messages(prompt.getInstructions()).toString()
                            .contains("\\\"value\\\":1"));
                    yield namedToolCallResponse("test_mutate", "{\"value\":3}", 2, 1);
                }
                case 4 -> {
                    List<ToolResponseMessage> responses = prompt.getInstructions().stream()
                            .filter(ToolResponseMessage.class::isInstance)
                            .map(ToolResponseMessage.class::cast).toList();
                    assertEquals(2, responses.size());
                    assertTrue(responses.stream().flatMap(value -> value.getResponses().stream())
                            .anyMatch(value -> value.name().equals("code_target")
                                    && value.responseData().contains("\"value\":1")));
                    assertTrue(responses.stream().flatMap(value -> value.getResponses().stream())
                            .anyMatch(value -> value.name().equals("test_mutate")
                                    && value.responseData().contains("\"observed\":3")));
                    yield textResponse("done", 2, 1);
                }
                default -> throw new AssertionError("unexpected provider call");
            };
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner).codeCalls(codeCalls);
        try (Fixture fixture = new Fixture(model, toolCallBudget(3), mutations, config)) {
            RunHandle handle = fixture.engine.start(fixture.request("use older code result later"));
            assertEquals(RunState.WAITING_APPROVAL, fixture.engine.get(handle.id()).state());
            firstExchangeId.set("exchange:" + new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.MODEL)
                    .findFirst().orElseThrow().id().value());
            fixture.restart();
            var approval = JsonNodeFactory.instance.objectNode().put("approved", true)
                    .put("fingerprint", ToolInvocationFingerprint.create("test_mutate",
                            JsonNodeFactory.instance.objectNode().put("value", 3)));
            handle = fixture.engine.resume(handle.id(), new ResumeCommand("tool.approval", approval));
            RunOutcome outcome = handle.completion().toCompletableFuture().get();
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(4, providerCalls.get());
            assertEquals(7, plannerCalls.get());
            assertEquals(2, codeCalls.get());
            assertEquals(1, mutations.get());
            assertJournalMatchesDeliveredPrompts(fixture, handle.id(), delivered);
        }
    }

    @Test
    void 按需选择重复的普通历史只注入一份() throws Exception {
        ModelTaskGateway planner = request -> {
            ObjectNode selection = JsonNodeFactory.instance.objectNode();
            selection.putArray("searches");
            var selected = selection.putArray("historyIds");
            request.input().path("history").forEach(value -> selected.add(value.path("id").asText()));
            toolIntent(selection, "");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    selection, "planner", 1, 1, false, Map.of()));
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .planner(planner);
        try (Fixture fixture = new Fixture(prompt -> {
            assertEquals(1, prompt.getInstructions().stream()
                    .filter(message -> message.getText() != null
                            && message.getText().equals("DUPLICATE_HISTORY_BODY"))
                    .count());
            return textResponse("done", 2, 1);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunRequest request = fixture.request(List.of(
                    InputBlock.message("user", "DUPLICATE_HISTORY_BODY"),
                    InputBlock.message("user", "DUPLICATE_HISTORY_BODY"),
                    InputBlock.text("answer the current task")), Map.of());
            RunOutcome outcome = fixture.engine.start(request).completion().toCompletableFuture().get();
            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
        }
    }

    @Test
    void 按需规划与主模型分别记账并持久化各自的Token用量() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        ChatModel planner = prompt -> {
            plannerCalls.incrementAndGet();
            return textResponse("""
                    {"searches":[],"historyIds":[],"toolIntent":{"query":"","groups":[]}}
                    """, 3, 2);
        };
        FixtureConfig config = new FixtureConfig()
                .context(contextConfiguration(true, 48_000, 16_000, 1))
                .onDemand(JsonNodeFactory.instance.objectNode().put("enabled", true))
                .plannerModel(planner);
        try (Fixture fixture = new Fixture(prompt -> {
            providerCalls.incrementAndGet();
            return textResponse("done", 5, 4);
        }, RunBudget.UNBOUNDED, new AtomicInteger(), config)) {
            RunHandle handle = fixture.engine.start(fixture.request());
            RunOutcome outcome = handle.completion().toCompletableFuture().get();

            assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
            assertEquals(1, plannerCalls.get());
            assertEquals(1, providerCalls.get());
            List<AgentStep> steps = new RunStepQuery(fixture.runs).steps(handle.id());
            AgentStep auxiliary = steps.stream()
                    .filter(step -> step.kind() == AgentStep.Kind.MODEL_TASK)
                    .findFirst().orElseThrow();
            AgentStep main = steps.stream()
                    .filter(step -> step.kind() == AgentStep.Kind.MODEL)
                    .findFirst().orElseThrow();
            assertEquals(3, auxiliary.usage().path("inputTokens").asInt());
            assertEquals(2, auxiliary.usage().path("outputTokens").asInt());
            assertEquals(5, main.usage().path("inputTokens").asInt());
            assertEquals(4, main.usage().path("outputTokens").asInt());
            assertEquals(8, fixture.ledger.snapshot(handle.id()).inputTokens());
            assertEquals(6, fixture.ledger.snapshot(handle.id()).outputTokens());
            assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.model_task.usage")).count());
            assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.model.usage")).count());
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode contextConfiguration(
            boolean enabled, int maxMessages, int maxToolResult, int maxTools) {
        return JsonNodeFactory.instance.objectNode()
                .put("enabled", enabled)
                .put("maxMessageCharacters", maxMessages)
                .put("maxToolSchemaCharacters", 48_000)
                .put("retainedToolExchanges", 4)
                .put("maxToolResultCharacters", maxToolResult)
                .put("maxTools", maxTools);
    }

    private static RunBudget toolCallBudget(int maxToolCalls) {
        return new RunBudget(Duration.ofMinutes(5), 1_000_000, 1_000_000,
                maxToolCalls, new BigDecimal("1000"));
    }

    private static void toolIntent(ObjectNode selection, String query, String... groups) {
        ObjectNode intent = selection.putObject("toolIntent").put("query", query);
        var selected = intent.putArray("groups");
        for (String group : groups) {
            if (!group.isBlank()) selected.add(group);
        }
    }

    private static void toolChoice(ObjectNode selection, ModelTaskRequest request,
            String action, String... names) {
        selection.put("toolAction", action);
        var ids = selection.putArray("toolIds");
        for (String name : names) {
            String id = null;
            for (var candidate : request.input().path("toolCandidates")) {
                if (name.equals(candidate.path("name").asText())) {
                    id = candidate.path("id").asText();
                    break;
                }
            }
            assertNotNull(id, "tool must come from the authorized candidate search: " + name);
            ids.add(id);
        }
    }

    private static List<String> toolNames(Prompt prompt) {
        ToolCallingChatOptions options = assertInstanceOf(
                ToolCallingChatOptions.class, prompt.getOptions());
        var callbacks = options.getToolCallbacks();
        return (callbacks == null ? List.<org.springframework.ai.tool.ToolCallback>of() : callbacks).stream()
                .map(callback -> callback.getToolDefinition().name()).toList();
    }

    private static ToolResponseMessage.ToolResponse lastToolResponse(Prompt prompt) {
        ToolResponseMessage message = assertInstanceOf(
                ToolResponseMessage.class, prompt.getInstructions().getLast());
        return message.getResponses().getLast();
    }

    private static void assertJournalMatchesDeliveredPrompts(
            Fixture fixture, RunId runId, List<Prompt> delivered) {
        List<AgentStep> modelSteps = new RunStepQuery(fixture.runs).steps(runId).stream()
                .filter(step -> step.kind() == AgentStep.Kind.MODEL).toList();
        assertEquals(delivered.size(), modelSteps.size());
        for (int index = 0; index < delivered.size(); index++) {
            Prompt prompt = delivered.get(index);
            AgentStep step = modelSteps.get(index);
            assertEquals(StepMessageCodec.messages(prompt.getInstructions()),
                    step.input().path("messages"));
            assertEquals(fixture.json.valueToTree(toolNames(prompt)),
                    step.input().path("toolNames"));
            var callbacks = ((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks();
            if (callbacks == null) callbacks = List.of();
            if (step.input().has("toolFingerprints")) {
                ObjectNode expected = JsonNodeFactory.instance.objectNode();
                callbacks.forEach(callback ->
                        expected.put(callback.getToolDefinition().name(),
                                ToolCatalogSession.fingerprint(callback)));
                assertEquals(expected, step.input().path("toolFingerprints"));
            }
            int characters = callbacks.stream()
                    .mapToInt(callback -> callback.getToolDefinition().name().length()
                            + callback.getToolDefinition().description().length()
                            + callback.getToolDefinition().inputSchema().length())
                    .sum();
            assertEquals(characters, step.input().path("toolSchemaCharacters").asInt(-1));
        }
    }

    private static ChatResponse toolCallResponse(int inputTokens, int outputTokens) {
        return namedToolCallResponse(
                "test_mutate", "{\"value\":1}", inputTokens, outputTokens);
    }

    private static ChatResponse namedToolCallResponse(
            String toolName, String arguments, int inputTokens, int outputTokens) {
        AssistantMessage output = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "provider-call", "function", toolName, arguments)))
                .build();
        return response(output, inputTokens, outputTokens);
    }

    private static ChatResponse textResponse(
            String text, int inputTokens, int outputTokens) {
        return response(new AssistantMessage(text), inputTokens, outputTokens);
    }

    private static ChatResponse response(
            AssistantMessage output, int inputTokens, int outputTokens) {
        return new ChatResponse(List.of(new Generation(output)),
                ChatResponseMetadata.builder().model("unknown-test-model")
                        .usage(new DefaultUsage(inputTokens, outputTokens)).build());
    }

    private static final class Fixture implements AutoCloseable {
        private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        private final Clock clock = Clock.systemUTC();
        private final ExtensionManager extensions;
        private final JdbcRunStore runs;
        private final JdbcTemplate jdbc;
        private RunUsageLedger ledger = new RunUsageLedger();
        private AgentEngine engine;
        private final java.util.function.Function<RunUsageLedger, AgentEngine> engineFactory;

        private Fixture(ChatModel model, RunBudget budget, AtomicInteger toolCalls) {
            this(model, budget, toolCalls, new FixtureConfig());
        }

        private Fixture(
                ChatModel model,
                RunBudget budget,
                AtomicInteger toolCalls,
                boolean clarificationTool) {
            this(model, budget, toolCalls,
                    new FixtureConfig().clarificationTool(clarificationTool));
        }

        private Fixture(ChatModel model, RunBudget budget, AtomicInteger toolCalls,
                        FixtureConfig config) {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:h2:mem:spring-reasoning-" + UUID.randomUUID()
                            + ";DB_CLOSE_DELAY=-1", "sa", "");
            new SchemaInitializer(dataSource).initialize();
            jdbc = new JdbcTemplate(dataSource);
            DataSourceTransactionManager transactions =
                    new DataSourceTransactionManager(dataSource);
            JdbcAgentDefinitionStore definitions = new JdbcAgentDefinitionStore(
                    jdbc, transactions, json, clock);
            runs = new JdbcRunStore(jdbc, transactions, json, clock);
            JdbcExecutionPlanStore plans = new JdbcExecutionPlanStore(jdbc, json, clock);

            var capabilities = new java.util.LinkedHashMap<CapabilityId,
                    com.fasterxml.jackson.databind.JsonNode>();
            if (config.context != null) {
                capabilities.put(new CapabilityId("context.compaction"), config.context);
            }
            if (config.onDemand != null) {
                capabilities.put(new CapabilityId("context.on_demand"), config.onDemand);
            }
            if (config.resultEviction != null) {
                capabilities.put(new CapabilityId("tool.result-eviction"), config.resultEviction);
            }
            AgentDefinitionDraft agent = new AgentDefinitionDraft(
                    "test.agent", "Test", "test:model", Map.of("system", "test"),
                    capabilities,
                    JsonNodeFactory.instance.objectNode(),
                    JsonNodeFactory.instance.objectNode(), RunBudget.UNBOUNDED,
                    JsonNodeFactory.instance.objectNode(), Map.of(
                            "test.tool", "=1.0.0", "context.compaction", "=2.0.0"));
            definitions.saveAgentDraft("workspace", agent, false);
            definitions.publishAgent("workspace", agent.id());
            RunProfileDraft profile = new RunProfileDraft(
                    "test.profile", "Test", PermissionSet.UNRESTRICTED,
                    budget, Map.of(), JsonNodeFactory.instance.objectNode());
            definitions.saveProfileDraft("workspace", profile, false);
            definitions.publishProfile("workspace", profile.id());

            DirectExecutor executor = new DirectExecutor(config.singleIoPermit);
            ModelTaskGateway modelTasks = config.planner == null
                    ? request -> CompletableFuture.failedFuture(
                            new AssertionError("model task not expected"))
                    : config.planner;
            extensions = new ExtensionManager(new ExtensionContext(
                    clock, Runnable::run, modelTasks));
            var artifacts = new ArrayList<ExtensionArtifact>();
            artifacts.add(ExtensionArtifact.builtin(new ToolExtension(toolCalls, config)));
            artifacts.add(ExtensionArtifact.builtin(new BuiltinCapabilityExtension(
                            "context.compaction", "Context", "Bounded context",
                            JsonNodeFactory.instance.objectNode(),
                            JsonNodeFactory.instance.objectNode(), List.of(), registrar -> {
                                if (config.downstreamMutation != DownstreamMutation.NONE) {
                                    registrar.advisor(new DownstreamAdvisorFactory(
                                            config.downstreamMutation, config.pauseAfterActivation,
                                            config.pausedPrompt));
                                }
                            })));
            if (config.onDemand != null) {
                artifacts.add(ExtensionArtifact.builtin(new BuiltinCapabilityExtension(
                        "context.on_demand", "On Demand", "Deferred context",
                        JsonNodeFactory.instance.objectNode(),
                        JsonNodeFactory.instance.objectNode(), List.of(), registrar -> {
                            config.sources.forEach(registrar::deferredContextSource);
                            config.fixedSources.forEach(registrar::fixedContextSource);
                        })));
            }
            if (config.resultEviction != null) {
                MemoryMutationGateway noMutations = new MemoryMutationGateway() {
                    @Override public com.fasterxml.jackson.databind.JsonNode applyCorrection(
                            RunId runId, RunRequest request, String input, String previous) {
                        return JsonNodeFactory.instance.nullNode();
                    }
                    @Override public com.fasterxml.jackson.databind.JsonNode protectOutput(
                            RunId runId, RunRequest request,
                            com.fasterxml.jackson.databind.JsonNode output) {
                        return output;
                    }
                    @Override public void distill(RunId runId, RunRequest request,
                            com.fasterxml.jackson.databind.JsonNode output) { }
                };
                artifacts.add(BuiltinExtensionCatalog.create(
                                (request, query, topK) -> "", noMutations,
                                (query, request) -> List.of(), (request, state) -> "",
                                context -> List.of()).stream()
                        .filter(artifact -> artifact.extension().descriptor().id()
                                .equals("tool.result-eviction"))
                        .findFirst().orElseThrow());
            }
            extensions.publish(artifacts);

            SpringAiModelRegistry models = new SpringAiModelRegistry();
            models.register("test:model", toolCapable(model));
            if (config.plannerModel != null) {
                models.register("test:planner", config.plannerModel);
                models.route("workspace", ModelTier.LIGHT, "test:planner");
            }
            ToolInvocationGateway toolGateway = new DefaultToolInvocationGateway(
                    (tool, arguments, owner) -> config.clarificationTool
                            || config.autoApproveTestMutate
                            || config.autoApproveContext
                                    && tool.name().startsWith("framework_context_")
                            || tool.name().equals("framework_tool_catalog")
                            || tool.name().startsWith("code_")
                            || tool.name().startsWith("filler_")
                            ? ToolApprovalDecision.ALLOW
                            : ToolApprovalDecision.REQUIRE_HUMAN_APPROVAL,
                    executor, clock);
            engineFactory = currentLedger -> {
                ModelTaskGateway reasoningTasks = config.plannerModel == null ? modelTasks
                        : new SpringAiModelTaskGateway(models, currentLedger,
                                new RunEventModelTaskAuditSink(runs), json, executor, runs);
                SpringAiReasoningGateway reasoning = new SpringAiReasoningGateway(
                        models, new SpringAiAdvisorRegistry(), toolGateway,
                        ExtensionStateStore.disabled(), currentLedger,
                        reasoningTasks,
                        runs, json, executor, config.observations);
                return new AgentEngine(new AgentCompiler(definitions, extensions, json),
                        runs, plans, reasoning, Runnable::run, json, clock, currentLedger);
            };
            engine = engineFactory.apply(ledger);
        }

        private void restart() {
            engine.close();
            ledger = new RunUsageLedger();
            engine = engineFactory.apply(ledger);
        }

        private static ChatModel toolCapable(ChatModel delegate) {
            return new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    return delegate.call(prompt);
                }

                @Override
                public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
                    return ToolCallingChatOptions.builder().build();
                }
            };
        }

        private RunRequest request() {
            return request("mutate once");
        }

        private RunRequest request(String text) {
            return request(text, Map.of());
        }

        private RunRequest requestWithAllowedGroups(String... groups) {
            var allowed = JsonNodeFactory.instance.arrayNode();
            for (String group : groups) allowed.add(group);
            return request("mutate once", Map.of(ToolGroupAccess.ATTRIBUTE, allowed));
        }

        private RunRequest request(String text,
                                   Map<String, com.fasterxml.jackson.databind.JsonNode> attributes) {
            return request(List.of(InputBlock.text(text)), attributes);
        }

        private RunRequest request(List<InputBlock> inputs,
                                   Map<String, com.fasterxml.jackson.databind.JsonNode> attributes) {
            return request(inputs, attributes, PermissionSet.UNRESTRICTED);
        }

        private RunRequest request(List<InputBlock> inputs,
                                   Map<String, com.fasterxml.jackson.databind.JsonNode> attributes,
                                   PermissionSet ceiling) {
            return RunRequest.builder()
                    .agent(AgentDefinitionRef.latest("test.agent"))
                    .profile(RunProfileRef.latest("test.profile"))
                    .source(InvocationSource.chat())
                    .scope(new RunScope("workspace", "user", "session"))
                    .inputs(inputs)
                    .permissionCeiling(ceiling)
                    .budget(RunBudget.UNBOUNDED)
                    .attributes(attributes)
                    .build();
        }

        @Override
        public void close() {
            engine.close();
            extensions.close();
        }
    }

    private static final class FixtureConfig {
        private boolean clarificationTool;
        private boolean autoApproveTestMutate;
        private boolean denyTestGroup;
        private Set<String> allowedToolNames;
        private com.fasterxml.jackson.databind.node.ObjectNode context;
        private com.fasterxml.jackson.databind.node.ObjectNode onDemand;
        private com.fasterxml.jackson.databind.node.ObjectNode resultEviction;
        private ModelTaskGateway planner;
        private ChatModel plannerModel;
        private final List<DeferredContextSource> sources = new ArrayList<>();
        private final List<FixedContextSource> fixedSources = new ArrayList<>();
        private boolean autoApproveContext;
        private boolean singleIoPermit;
        private int fillerTools;
        private Map<String, String> webToolDescriptions = Map.of();
        private int codeDescriptionPadding;
        private String codeResult = "code tool executed";
        private AtomicInteger codeCalls = new AtomicInteger();
        private OutputGuard outputGuard;
        private ObservationRegistry observations = ObservationRegistry.NOOP;
        private DownstreamMutation downstreamMutation = DownstreamMutation.NONE;
        private final AtomicInteger pauseAfterActivation = new AtomicInteger();
        private final AtomicReference<Prompt> pausedPrompt = new AtomicReference<>();

        private FixtureConfig clarificationTool(boolean value) {
            clarificationTool = value;
            return this;
        }
        private FixtureConfig autoApproveTestMutate() {
            autoApproveTestMutate = true;
            return this;
        }
        private FixtureConfig denyTestGroup() {
            denyTestGroup = true;
            return this;
        }
        private FixtureConfig allowedToolNames(Set<String> value) {
            allowedToolNames = Set.copyOf(value);
            return this;
        }
        private FixtureConfig context(com.fasterxml.jackson.databind.node.ObjectNode value) {
            context = value;
            return this;
        }
        private FixtureConfig onDemand(com.fasterxml.jackson.databind.node.ObjectNode value) {
            onDemand = value;
            return this;
        }
        private FixtureConfig resultEviction(com.fasterxml.jackson.databind.node.ObjectNode value) {
            resultEviction = value;
            return this;
        }
        private FixtureConfig planner(ModelTaskGateway value) {
            planner = value;
            return this;
        }
        private FixtureConfig plannerModel(ChatModel value) {
            plannerModel = value;
            return this;
        }
        private FixtureConfig source(DeferredContextSource value) {
            sources.add(value);
            return this;
        }
        private FixtureConfig fixedSource(FixedContextSource value) {
            fixedSources.add(value);
            return this;
        }
        private FixtureConfig autoApproveContext() {
            autoApproveContext = true;
            return this;
        }
        private FixtureConfig singleIoPermit() {
            singleIoPermit = true;
            return this;
        }
        private FixtureConfig fillerTools(int value) {
            fillerTools = value;
            return this;
        }
        private FixtureConfig webToolDescriptions(Map<String, String> value) {
            webToolDescriptions = Map.copyOf(value);
            return this;
        }
        private FixtureConfig codeDescriptionPadding(int value) {
            codeDescriptionPadding = value;
            return this;
        }
        private FixtureConfig codeResult(String value) {
            codeResult = value;
            return this;
        }
        private FixtureConfig codeCalls(AtomicInteger value) {
            codeCalls = value;
            return this;
        }
        private FixtureConfig outputGuard(OutputGuard value) {
            outputGuard = value;
            return this;
        }
        private FixtureConfig observations(ObservationRegistry value) {
            observations = value;
            return this;
        }
        private FixtureConfig downstreamMutation(DownstreamMutation value) {
            downstreamMutation = value;
            return this;
        }
    }

    private enum DownstreamMutation {
        NONE,
        DROP_TOOL_RESPONSE,
        ADD_OVERSIZE_USER,
        PAUSE_AFTER_ACTIVATION_ONCE,
        PAUSE_ON_CATALOG_ONCE,
        PAUSE_AFTER_DEFERRED_FETCH_ONCE,
        PAUSE_AFTER_UNAVAILABLE_FEEDBACK_ONCE
    }

    private static final class DownstreamAdvisorFactory implements AdvisorSpecFactory {
        private final DownstreamMutation mutation;
        private final AtomicInteger pauseAfterActivation;
        private final AtomicReference<Prompt> pausedPrompt;

        private DownstreamAdvisorFactory(DownstreamMutation mutation,
                                         AtomicInteger pauseAfterActivation,
                                         AtomicReference<Prompt> pausedPrompt) {
            this.mutation = mutation;
            this.pauseAfterActivation = pauseAfterActivation;
            this.pausedPrompt = pausedPrompt;
        }

        @Override public CapabilityId capabilityId() {
            return new CapabilityId("context.compaction");
        }
        @Override public String advisorId() { return "test.downstream-mutation"; }
        @Override public AdvisorSpec create(com.fasterxml.jackson.databind.JsonNode configuration,
                                            CompilationContext context) {
            return new AdvisorSpec(advisorId(), "test.downstream-mutation",
                    org.springframework.ai.chat.client.advisor.ToolCallingAdvisor.DEFAULT_ORDER + 100,
                    configuration);
        }
        @Override public org.springframework.ai.chat.client.advisor.api.Advisor createAdvisor(
                AdvisorSpec specification, AdvisorRuntimeContext context) {
            return new org.springframework.ai.chat.client.advisor.api.CallAdvisor() {
                @Override public String getName() { return specification.id(); }
                @Override public int getOrder() { return specification.order(); }
                @Override
                public org.springframework.ai.chat.client.ChatClientResponse adviseCall(
                        org.springframework.ai.chat.client.ChatClientRequest request,
                        org.springframework.ai.chat.client.advisor.api.CallAdvisorChain chain) {
                    if (mutation == DownstreamMutation.PAUSE_AFTER_ACTIVATION_ONCE
                            && toolNames(request.prompt()).equals(List.of("code_target"))
                            && pauseAfterActivation.getAndIncrement() == 0) {
                        pausedPrompt.set(request.prompt());
                        throw new ToolInputRequiredException(
                                JsonNodeFactory.instance.objectNode().put("kind", "test.pause"),
                                "pause after catalog activation");
                    }
                    if (mutation == DownstreamMutation.PAUSE_ON_CATALOG_ONCE
                            && toolNames(request.prompt()).equals(List.of("framework_tool_catalog"))
                            && pauseAfterActivation.getAndIncrement() == 0) {
                        pausedPrompt.set(request.prompt());
                        throw new ToolInputRequiredException(
                                JsonNodeFactory.instance.objectNode().put("kind", "test.pause"),
                                "pause before tool catalog provider call");
                    }
                    if (mutation == DownstreamMutation.PAUSE_AFTER_DEFERRED_FETCH_ONCE
                            && request.prompt().getInstructions().stream().anyMatch(message ->
                                    message.getText() != null
                                            && message.getText().contains("LARGE_DEFERRED_BODY_MARKER"))
                            && pauseAfterActivation.getAndIncrement() == 0) {
                        throw new ToolInputRequiredException(
                                JsonNodeFactory.instance.objectNode().put("kind", "test.pause"),
                                "pause after deferred fetch");
                    }
                    if (mutation == DownstreamMutation.PAUSE_AFTER_UNAVAILABLE_FEEDBACK_ONCE
                            && request.prompt().getInstructions().stream()
                                    .filter(ToolResponseMessage.class::isInstance)
                                    .map(ToolResponseMessage.class::cast)
                                    .flatMap(message -> message.getResponses().stream())
                                    .anyMatch(response -> response.name().equals("web_content"))
                            && pauseAfterActivation.getAndIncrement() == 0) {
                        pausedPrompt.set(request.prompt());
                        throw new ToolInputRequiredException(
                                JsonNodeFactory.instance.objectNode().put("kind", "test.pause"),
                                "pause after unavailable tool feedback");
                    }
                    List<org.springframework.ai.chat.messages.Message> messages =
                            new ArrayList<>(request.prompt().getInstructions());
                    if (mutation == DownstreamMutation.DROP_TOOL_RESPONSE
                            && messages.getLast() instanceof ToolResponseMessage) {
                        messages.removeLast();
                    } else if (mutation == DownstreamMutation.ADD_OVERSIZE_USER) {
                        messages.add(new UserMessage("advisor-added-" + "x".repeat(5_000)));
                    }
                    return chain.nextCall(request.mutate()
                            .prompt(new Prompt(messages, request.prompt().getOptions())).build());
                }
            };
        }
    }

    private static final class ToolExtension implements AgentFrameworkExtension {
        private final AtomicInteger calls;
        private final FixtureConfig config;
        private final ExtensionDescriptor descriptor = new ExtensionDescriptor(
                "test.tool", SemanticVersion.parse("1.0.0"), ">=2.0.0 <3.0.0",
                ">=2.0.0 <3.0.0", List.of(), Set.of(), ExtensionScope.PLAN_SCOPED,
                HotUpdateCompatibility.PLAN_ISOLATED, 1, Map.of());

        private ToolExtension(AtomicInteger calls, FixtureConfig config) {
            this.calls = calls;
            this.config = config;
        }

        @Override public ExtensionDescriptor descriptor() { return descriptor; }

        @Override
        public void register(ExtensionRegistrar registrar) {
            if (config.outputGuard != null) registrar.outputGuard(config.outputGuard);
            if (config.allowedToolNames != null) {
                registrar.toolPolicy((tool, configuration, request) ->
                        config.allowedToolNames.contains(tool.name())
                                ? ToolPolicyDecision.ALLOW : ToolPolicyDecision.DENY);
            }
            if (config.denyTestGroup) {
                registrar.toolPolicy((tool, configuration, request) ->
                        tool.group().equals("test")
                                ? com.javaclaw.framework.spi.ToolPolicyDecision.DENY
                                : com.javaclaw.framework.spi.ToolPolicyDecision.ALLOW);
            }
            if (config.clarificationTool) {
                registrar.retryPolicy(new RetryPolicy() {
                    @Override public String id() { return "retry-every-failure"; }
                    @Override public int order() { return 0; }
                    @Override public java.util.Optional<RetryDirective> evaluate(RetryContext context) {
                        return java.util.Optional.of(RetryDirective.retryAfter(Duration.ZERO));
                    }
                });
            }
            registrar.tool(context -> new FrameworkTool() {
                @Override
                public ToolDescriptor descriptor() {
                    ObjectNode schema = JsonNodeFactory.instance.objectNode();
                    schema.put("type", "object");
                    String property = config.clarificationTool ? "question" : "value";
                    schema.putObject("properties").putObject(property).put("type",
                            config.clarificationTool ? "string" : "integer");
                    schema.putArray("required").add(property);
                    schema.put("additionalProperties", false);
                    return new ToolDescriptor(
                            config.clarificationTool ? "test_clarify" : "test_mutate",
                            config.clarificationTool ? "request clarification" : "mutate once",
                            schema, "test",
                            PermissionSet.of(config.clarificationTool
                                    ? "interaction.request" : "tool.execute"),
                            false);
                }

                @Override
                public com.fasterxml.jackson.databind.JsonNode execute(
                        com.fasterxml.jackson.databind.JsonNode arguments,
                        ToolExecutionContext context) {
                    calls.incrementAndGet();
                    if (config.clarificationTool) {
                        ObjectNode waiting = JsonNodeFactory.instance.objectNode();
                        waiting.put("kind", "clarify_request");
                        waiting.putObject("payload").put(
                                "question", arguments.path("question").asText());
                        throw new ToolInputRequiredException(waiting, "clarification required");
                    }
                    return JsonNodeFactory.instance.objectNode()
                            .put("observed", arguments.path("value").asInt());
                }
            });
            for (int index = 0; index < config.fillerTools; index++) {
                final String name = "filler_%03d".formatted(index);
                registrar.tool(context -> auxiliaryTool(name, "filler", new AtomicInteger(), "filler"));
            }
            config.webToolDescriptions.forEach((name, description) ->
                    registrar.tool(context -> auxiliaryTool(name, "web", new AtomicInteger(),
                            "web", description)));
            if (config.context != null || config.fillerTools > 0) {
                registrar.tool(context -> auxiliaryTool(
                        "code_target", "code", config.codeCalls, config.codeResult,
                        config.codeDescriptionPadding));
            }
        }

        private static FrameworkTool auxiliaryTool(String name, String group,
                                                   AtomicInteger calls, String output) {
            return auxiliaryTool(name, group, calls, output, 0);
        }

        private static FrameworkTool auxiliaryTool(String name, String group,
                                                   AtomicInteger calls, String output,
                                                   int descriptionPadding) {
            return auxiliaryTool(name, group, calls, output,
                    name + " for catalog integration" + "d".repeat(descriptionPadding));
        }

        private static FrameworkTool auxiliaryTool(String name, String group,
                                                   AtomicInteger calls, String output,
                                                   String description) {
            return new FrameworkTool() {
                @Override public ToolDescriptor descriptor() {
                    ObjectNode schema = JsonNodeFactory.instance.objectNode();
                    schema.put("type", "object");
                    schema.putObject("properties").putObject("value").put("type", "integer");
                    schema.putArray("required").add("value");
                    schema.put("additionalProperties", false);
                    return new ToolDescriptor(name, description, schema,
                            group, PermissionSet.of("tool.execute"), false);
                }

                @Override public com.fasterxml.jackson.databind.JsonNode execute(
                        com.fasterxml.jackson.databind.JsonNode arguments,
                        ToolExecutionContext context) {
                    calls.incrementAndGet();
                    return JsonNodeFactory.instance.objectNode()
                            .put("payload", output)
                            .put("value", arguments.path("value").asInt());
                }
            };
        }
    }

    private static final class DirectExecutor implements CancellableTaskExecutor {
        private final boolean singlePermit;
        private final AtomicInteger active = new AtomicInteger();

        private DirectExecutor(boolean singlePermit) { this.singlePermit = singlePermit; }

        @Override public void execute(Runnable command) { command.run(); }

        @Override
        public <T> CancellableTask<T> submit(
                String name, Duration timeout, CancellationToken cancellation, Callable<T> task) {
            CompletableFuture<T> completion = new CompletableFuture<>();
            boolean acquired = false;
            try {
                cancellation.throwIfCancelled();
                if (singlePermit && !active.compareAndSet(0, 1)) {
                    throw new IllegalStateException("nested execution exceeded one I/O permit");
                }
                acquired = singlePermit;
                completion.complete(task.call());
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            } finally {
                if (acquired) active.set(0);
            }
            return new CancellableTask<>() {
                @Override public java.util.concurrent.CompletionStage<T> completion() {
                    return completion;
                }
                @Override public java.util.concurrent.CompletionStage<Void> termination() {
                    return CompletableFuture.completedFuture(null);
                }
                @Override public boolean cancel() { return false; }
            };
        }
    }
}
