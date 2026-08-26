package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.RunEventDraft;
import com.javaclaw.framework.store.JdbcExecutionPlanStore;
import com.javaclaw.framework.store.JdbcRunStore;
import com.javaclaw.platform.data.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunUsageAccountRestorerTest {
    @Test
    void reopensExpiredTerminalAccountFromRunAndPlanWithoutReprojectingHistory() {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:usage-restore-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new SchemaInitializer(dataSource).initialize();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Clock clock = Clock.systemUTC();
        JdbcRunStore runs = new JdbcRunStore(
                jdbc, new DataSourceTransactionManager(dataSource), json, clock);
        JdbcExecutionPlanStore plans = new JdbcExecutionPlanStore(jdbc, json, clock);
        AtomicLong ticker = new AtomicLong(1L);
        AtomicInteger projections = new AtomicInteger();
        RunUsageLedger ledger = new RunUsageLedger(new com.javaclaw.framework.spi.RunUsageObserver() {
            @Override public void recorded(RunId runId, RunScope scope, long inputTokens,
                                           long outputTokens, BigDecimal cost) { }
            @Override public void recorded(RunId runId, RunScope scope, ModelTokenUsage usage,
                                           BigDecimal cost) { projections.incrementAndGet(); }
        }, ticker::get);

        RunBudget budget = new RunBudget(
                Duration.ofMinutes(5), 1_000, 1_000, 10, BigDecimal.TEN);
        ExecutionPlanDescriptor descriptor = new ExecutionPlanDescriptor(
                "plan-usage", AgentDefinitionRef.latest("agent"), RunProfileRef.latest("chat"),
                "definition", "profile", 1, List.of(), "model", Map.of(), "prompt",
                Map.of(), JsonNodeFactory.instance.objectNode(), PermissionSet.UNRESTRICTED,
                budget, List.of(), JsonNodeFactory.instance.objectNode(), "checksum");
        plans.save(descriptor.id(), descriptor.extensionGeneration(),
                json.valueToTree(descriptor), descriptor.checksum());
        RunRequest request = RunRequest.builder()
                .agent(descriptor.definition()).profile(descriptor.profile())
                .source(InvocationSource.chat())
                .scope(new RunScope("workspace", "user", "session"))
                .input(InputBlock.text("test")).permissionCeiling(PermissionSet.UNRESTRICTED)
                .budget(budget).build();
        RunId runId = new RunId("terminal-owner");
        runs.create(runId, request, descriptor.id(), draft(
                "core.run.created", JsonNodeFactory.instance.objectNode()));
        var usage = JsonNodeFactory.instance.objectNode();
        usage.put("modelCallId", "historical-call");
        usage.put("inputTokens", 20);
        usage.put("outputTokens", 4);
        usage.put("modelCalls", 1);
        runs.appendEvent(runId, draft("core.model_task.usage", usage));
        runs.append(runId, Set.of(RunState.CREATED), RunState.COMPLETED,
                draft("core.run.completed", JsonNodeFactory.instance.objectNode()), null, null);

        RunUsageAccountRestorer restorer = new RunUsageAccountRestorer(
                runs, plans, ledger, json);
        assertEquals(20, restorer.ensureAccount(runId).inputTokens());
        assertEquals(0, projections.get());
        ledger.recordOnce(runId, "historical-call", new ModelTokenUsage(20, 4), BigDecimal.ZERO);
        assertEquals(20, ledger.snapshot(runId).inputTokens());

        ticker.set(1L + Duration.ofHours(2).toNanos());
        assertFalse(ledger.hasAccount(runId));
        assertEquals(20, restorer.ensureAccount(runId).inputTokens());
        ledger.recordOnce(runId, "new-call", new ModelTokenUsage(3, 1), BigDecimal.ZERO);
        assertEquals(23, ledger.snapshot(runId).inputTokens());
        assertEquals(1, projections.get());

        ticker.set(1L + Duration.ofHours(2).toNanos() + Duration.ofMinutes(59).toNanos());
        restorer.ensureAccount(runId);
        ticker.set(1L + Duration.ofHours(3).toNanos() + Duration.ofMinutes(1).toNanos());
        assertTrue(ledger.hasAccount(runId),
                "starting a provider call must refresh a terminal account's retention window");
    }

    private static RunEventDraft draft(
            String type, com.fasterxml.jackson.databind.JsonNode payload) {
        return new RunEventDraft(type, type.endsWith(".usage") ? 2 : 1,
                "test", "correlation", null, payload);
    }
}
