package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.*;
import com.javaclaw.framework.springai.SpringAiModelRegistry;
import com.javaclaw.framework.springai.SpringAiModelTaskGateway;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import static org.junit.jupiter.api.Assertions.*;

class ModelTaskUsageRecoveryTest {
    @ParameterizedTest
    @EnumSource(value = RunState.class, names = {"PAUSED", "FAILED"})
    void 晚到收费可恢复暂停任务但不追加真正结束任务的步骤结果(RunState finalState) throws Exception {
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:late-usage-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new com.javaclaw.platform.data.SchemaInitializer(dataSource).initialize();
        var mapper = new ObjectMapper().findAndRegisterModules();
        var runs = new com.javaclaw.framework.store.JdbcRunStore(new org.springframework.jdbc.core.JdbcTemplate(dataSource),
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource), mapper, Clock.systemUTC());
        var run = RunId.random();
        var owner = RunRequest.builder().agent(AgentDefinitionRef.latest("fixture"))
                .profile(RunProfileRef.latest("fixture")).source(InvocationSource.chat())
                .scope(new RunScope("workspace", "user", "session")).input(InputBlock.text("观察画面")).build();
        var empty = JsonNodeFactory.instance.objectNode();
        runs.create(run, owner, "fixture", draft("core.run.created", empty));
        runs.append(run, Set.of(RunState.CREATED), RunState.RUNNING,
                draft("core.run.started", empty), null, null);
        var ledger = new RunUsageLedger();
        ledger.open(run, RunBudget.UNBOUNDED, owner.scope());
        var providerStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var models = new SpringAiModelRegistry();
        models.register("fixture:model", prompt -> {
            providerStarted.countDown();
            while (release.getCount() != 0) {
                try { release.await(); }
                catch (InterruptedException ignored) { /* 物理请求仍在进行。 */ }
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("{\"ok\":true}"))),
                    ChatResponseMetadata.builder().model("fixture").usage(new DefaultUsage(7, 3)).build());
        });
        models.route("workspace", ModelTier.LIGHT, "fixture:model");
        var executor = new PhysicalExecutor();
        var gateway = new SpringAiModelTaskGateway(models, ledger,
                new RunEventModelTaskAuditSink(runs), mapper, executor, runs);
        var schema = mapper.readTree("{\"type\":\"object\"}");
        var request = new ModelTaskRequest("vision", ModelTier.LIGHT, empty, List.of(), schema,
                run, "vision", Duration.ofSeconds(5), 2, () -> false, true);
        try {
            var result = gateway.execute(request).toCompletableFuture();
            assertTrue(providerStarted.await(1, TimeUnit.SECONDS));
            executor.completion.completeExceptionally(new TimeoutException("视觉超时"));
            assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertEquals(AgentStep.State.FAILED, new RunStepQuery(runs).steps(run).getFirst().state());
            runs.append(run, Set.of(RunState.RUNNING), finalState,
                    draft("core.run." + finalState.name().toLowerCase(), empty), null, null);
            int before = runs.eventsAfter(run, 0).size();
            release.countDown();
            executor.termination.get(2, TimeUnit.SECONDS);
            var journal = runs.eventsAfter(run, 0);
            assertEquals(7, ledger.snapshot(run).inputTokens());
            assertEquals(AgentStep.State.FAILED, new RunStepQuery(runs).steps(run).getFirst().state());
            assertFalse(journal.stream().anyMatch(event -> event.type().equals("core.step.completed")));
            assertFalse(journal.stream().anyMatch(event -> event.type().equals("core.model_task.completed")));
            var restored = RunUsageRecovery.totals(journal);
            assertEquals(finalState == RunState.PAUSED ? 7 : 0, restored.inputTokens());
            assertEquals(finalState == RunState.PAUSED ? 3 : 0, restored.outputTokens());
            if (finalState.terminal()) assertEquals(before, journal.size());
            if (finalState == RunState.PAUSED) {
                var usage = journal.stream().filter(event -> event.type().equals("core.step.usage")).findFirst().orElseThrow();
                var duplicated = new java.util.ArrayList<>(journal);
                duplicated.add(usage);
                assertEquals(restored, RunUsageRecovery.totals(duplicated), "恢复按 stepId 去重，不重复收费");
            }
        } finally { release.countDown(); }
    }

    private static RunEventDraft draft(String type, com.fasterxml.jackson.databind.JsonNode payload) {
        return new RunEventDraft(type, 1, "fixture", null, null, payload);
    }
    private static final class PhysicalExecutor implements CancellableTaskExecutor {
        final CompletableFuture<Object> completion = new CompletableFuture<>();
        final CompletableFuture<Void> termination = new CompletableFuture<>();
        @Override public void execute(Runnable command) { Thread.startVirtualThread(command); }
        @Override @SuppressWarnings("unchecked")
        public <T> CancellableTask<T> submit(String name, Duration timeout, CancellationToken cancellation, Callable<T> action) {
            Thread carrier = Thread.startVirtualThread(() -> {
                try { completion.complete(action.call()); }
                catch (Throwable failure) { completion.completeExceptionally(failure); }
                finally { termination.complete(null); }
            });
            return new CancellableTask<>() {
                @Override public CompletionStage<T> completion() { return (CompletionStage<T>) (CompletionStage<?>) completion; }
                @Override public CompletionStage<Void> termination() { return termination; }
                @Override public boolean cancel() { carrier.interrupt(); return true; }
            };
        }
    }
}
