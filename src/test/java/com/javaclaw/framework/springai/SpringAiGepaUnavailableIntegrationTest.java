package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.AgentDefinitionDraft;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.CapabilityId;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunProfileDraft;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.RunState;
import com.javaclaw.framework.builtin.BuiltinExtensionCatalog;
import com.javaclaw.framework.builtin.memory.MemoryMutationGateway;
import com.javaclaw.framework.core.AgentCompiler;
import com.javaclaw.framework.core.AgentEngine;
import com.javaclaw.framework.core.DefaultToolInvocationGateway;
import com.javaclaw.framework.core.RunUsageLedger;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.spi.CancellableTask;
import com.javaclaw.framework.spi.CancellableTaskExecutor;
import com.javaclaw.framework.spi.CancellationToken;
import com.javaclaw.framework.spi.ExtensionContext;
import com.javaclaw.framework.spi.ExtensionStateStore;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ToolApprovalDecision;
import com.javaclaw.framework.store.JdbcAgentDefinitionStore;
import com.javaclaw.framework.store.JdbcExecutionPlanStore;
import com.javaclaw.framework.store.JdbcRunStore;
import com.javaclaw.platform.data.SchemaInitializer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SpringAiGepaUnavailableIntegrationTest {
    @Test
    void 长答复完成主Run并持久化v2未评估事件() throws Exception {
        // The decision protocol bounds userMessage at 12,000 chars; the resulting
        // JSON encoding of the text node exceeds GEPA's 12,000-char evaluation input limit.
        String answer = "answer".repeat(1_999) + "extra";
        AtomicInteger auxiliaryCalls = new AtomicInteger();
        ModelTaskGateway auxiliary = request -> {
            auxiliaryCalls.incrementAndGet();
            return CompletableFuture.failedFuture(new AssertionError("GEPA must not call LIGHT"));
        };
        ChatModel model = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                var decisionArgs = JsonNodeFactory.instance.objectNode()
                        .put("decision", "CLAIM_DONE")
                        .put("userMessage", answer);
                decisionArgs.set("evidenceRefs", JsonNodeFactory.instance.arrayNode());
                decisionArgs.set("unmetCriterionIds", JsonNodeFactory.instance.arrayNode());
                String arguments = decisionArgs.toString();
                AssistantMessage decision = AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "decision-call", "function",
                                HarnessDecisionToolCallback.NAME, arguments)))
                        .build();
                return new ChatResponse(List.of(new Generation(decision)),
                        ChatResponseMetadata.builder().model("test:model")
                                .usage(new DefaultUsage(3, 4)).build());
            }
            @Override public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }
        };
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        Clock clock = Clock.systemUTC();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:gepa-unavailable-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                "sa", "");
        new SchemaInitializer(dataSource).initialize();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        JdbcAgentDefinitionStore definitions = new JdbcAgentDefinitionStore(
                jdbc, transactions, json, clock);
        JdbcRunStore runs = new JdbcRunStore(jdbc, transactions, json, clock);
        JdbcExecutionPlanStore plans = new JdbcExecutionPlanStore(jdbc, json, clock);
        definitions.saveAgentDraft("workspace", new AgentDefinitionDraft(
                "test.agent", "Test", "test:model", Map.of("system", "test"),
                Map.of(new CapabilityId("gepa.evaluate"),
                        JsonNodeFactory.instance.objectNode().put("enabled", true)),
                JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.objectNode(),
                RunBudget.UNBOUNDED, JsonNodeFactory.instance.objectNode(), Map.of()), false);
        definitions.publishAgent("workspace", "test.agent");
        definitions.saveProfileDraft("workspace", new RunProfileDraft(
                "test.profile", "Test", PermissionSet.UNRESTRICTED,
                RunBudget.UNBOUNDED, Map.of(), JsonNodeFactory.instance.objectNode()), false);
        definitions.publishProfile("workspace", "test.profile");

        DirectExecutor executor = new DirectExecutor();
        try (ExtensionManager extensions = new ExtensionManager(
                new ExtensionContext(clock, Runnable::run, auxiliary))) {
            MemoryMutationGateway mutations = new MemoryMutationGateway() {
                @Override public com.fasterxml.jackson.databind.JsonNode applyCorrection(
                        com.javaclaw.framework.api.RunId run, RunRequest request,
                        String input, String previous) {
                    return JsonNodeFactory.instance.nullNode();
                }
                @Override public com.fasterxml.jackson.databind.JsonNode protectOutput(
                        com.javaclaw.framework.api.RunId run, RunRequest request,
                        com.fasterxml.jackson.databind.JsonNode output) {
                    return output;
                }
                @Override public void distill(com.javaclaw.framework.api.RunId run,
                                               RunRequest request,
                                               com.fasterxml.jackson.databind.JsonNode output) { }
            };
            extensions.publish(BuiltinExtensionCatalog.create(
                            (request, query, topK) -> "", mutations,
                            (query, request) -> List.of(), (request, state) -> "",
                            context -> List.of()).stream()
                    .filter(artifact -> List.of("gepa.signal", "gepa.evaluate")
                            .contains(artifact.extension().descriptor().id()))
                    .toList());
            SpringAiModelRegistry models = new SpringAiModelRegistry();
            models.register("test:model", model);
            RunUsageLedger usage = new RunUsageLedger();
            SpringAiReasoningGateway reasoning = new SpringAiReasoningGateway(
                    models, new SpringAiAdvisorRegistry(),
                    new DefaultToolInvocationGateway((tool, arguments, request) ->
                            ToolApprovalDecision.ALLOW, executor, clock),
                    ExtensionStateStore.disabled(), usage, auxiliary, runs, json, executor,
                    ObservationRegistry.NOOP);
            try (AgentEngine engine = new AgentEngine(
                    new AgentCompiler(definitions, extensions, json), runs, plans,
                    reasoning, Runnable::run, json, clock, usage)) {
                RunRequest request = RunRequest.builder()
                        .agent(AgentDefinitionRef.latest("test.agent"))
                        .profile(RunProfileRef.latest("test.profile"))
                        .source(InvocationSource.chat())
                        .scope(new RunScope("workspace", "user", "session"))
                        .input(InputBlock.text("answer at length"))
                        .permissionCeiling(PermissionSet.UNRESTRICTED)
                        .build();
                var handle = engine.start(request);
                var outcome = handle.completion().toCompletableFuture().get();

                assertEquals(RunState.COMPLETED, outcome.state(), outcome.error());
                assertEquals(answer, outcome.output().path("text").asText());
                var assessment = outcome.output().path("assessments").path("gepa.evaluate");
                assertEquals("unavailable", assessment.path("mode").asText());
                assertEquals("answer_too_large", assessment.path("reason").asText());
                assertFalse(assessment.has("score"));
                assertFalse(assessment.has("needsRevision"));
                assertEquals(0, auxiliaryCalls.get());
                var events = runs.eventsAfter(handle.id(), 0).stream()
                        .filter(event -> event.type().equals("gepa.evaluate.assessment"))
                        .toList();
                assertEquals(1, events.size());
                assertEquals(2, events.getFirst().schemaVersion());
                assertEquals(assessment, events.getFirst().payload());
            }
        }
    }

    private static final class DirectExecutor implements CancellableTaskExecutor {
        @Override public void execute(Runnable command) { command.run(); }

        @Override public <T> CancellableTask<T> submit(
                String name, Duration timeout, CancellationToken cancellation, Callable<T> task) {
            CompletableFuture<T> completion = new CompletableFuture<>();
            try {
                cancellation.throwIfCancelled();
                completion.complete(task.call());
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
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
