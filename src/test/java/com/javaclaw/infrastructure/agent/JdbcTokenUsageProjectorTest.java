package com.javaclaw.infrastructure.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.agent.TokenTracker;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.framework.api.ModelTokenUsage;
import com.javaclaw.framework.api.ModelUsageFact;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.spring.ApplicationContexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcTokenUsageProjectorTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Instant TODAY = LocalDate.now(SHANGHAI)
            .atTime(10, 0).atZone(SHANGHAI).toInstant();
    private static final Clock CLOCK = Clock.fixed(
            TODAY, ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void atomicallyDeduplicatesFactsAndUsesOccurrenceTimeAcrossMidnight() {
        try (Fixture fixture = fixture("dedupe")) {
            JdbcTokenUsageProjector projector = fixture.projector();
            ModelUsageFact beforeMidnight = fact("call-before",
                    Instant.parse("2026-08-24T15:59:59Z"),
                    new ModelTokenUsage(150, 30, 20, 40, 10, 1), 100);
            ModelUsageFact afterMidnight = fact("call-after",
                    Instant.parse("2026-08-24T16:00:00Z"),
                    new ModelTokenUsage(12, 2, 1, 3, 1, 1), 9);

            assertTrue(projector.project(beforeMidnight));
            assertFalse(projector.project(beforeMidnight));
            assertTrue(projector.project(afterMidnight));

            assertEquals(2, count(fixture.jdbc(), "token_usage_projection_receipts"));
            Map<String, Object> first = fixture.jdbc().queryForMap("""
                    SELECT * FROM token_usage_daily
                    WHERE workspace_id = 'ws' AND usage_date = '2026-08-24'
                    """);
            assertEquals(150L, number(first, "INPUT_TOKENS"));
            assertEquals(100L, number(first, "PRICING_INPUT_TOKENS"));
            assertEquals(40L, number(first, "OUTPUT_TOKENS"));
            assertEquals(150L, number(first, "METERED_INPUT"));
            assertEquals(30L, number(first, "CACHED_INPUT"));
            assertEquals(20L, number(first, "CACHE_WRITE_INPUT"));
            assertEquals(10L, number(first, "REASONING_TOKENS"));
            assertEquals(1L, number(first, "MODEL_CALLS"));
            assertEquals(1, fixture.jdbc().queryForObject("""
                    SELECT COUNT(*) FROM token_usage_daily
                    WHERE workspace_id = 'ws' AND usage_date = '2026-08-25'
                    """, Integer.class));
        }
    }

    @Test
    void concurrentDuplicateDeliveriesApplyEachModelCallOnlyOnce() {
        try (Fixture fixture = fixture("concurrent");
             var executor = Executors.newFixedThreadPool(8)) {
            JdbcTokenUsageProjector projector = fixture.projector();
            var futures = java.util.stream.IntStream.range(0, 64)
                    .mapToObj(index -> CompletableFuture.supplyAsync(() -> projector.project(
                            fact("concurrent-" + (index % 8), CLOCK.instant(),
                                    new ModelTokenUsage(10, 2, 1, 4, 1, 1), 7)), executor))
                    .toList();

            long applied = futures.stream().filter(CompletableFuture::join).count();

            assertEquals(8, applied);
            assertEquals(8, count(fixture.jdbc(), "token_usage_projection_receipts"));
            assertEquals(80L, fixture.jdbc().queryForObject(
                    "SELECT input_tokens FROM token_usage_daily WHERE workspace_id = 'ws'",
                    Long.class));
            assertEquals(56L, fixture.jdbc().queryForObject(
                    "SELECT pricing_input_tokens FROM token_usage_daily WHERE workspace_id = 'ws'",
                    Long.class));
            assertEquals(8L, fixture.jdbc().queryForObject(
                    "SELECT model_calls FROM token_usage_daily WHERE workspace_id = 'ws'",
                    Long.class));
        }
    }

    @Test
    void startupReconciliationRepairsMissingReceiptsWithoutInflatingCurrentSession() {
        try (Fixture fixture = fixture("reconcile")) {
            seedRunEvent(fixture.jdbc(), "run", "ws", "session-a", "replayed-call",
                    1, TODAY, 25, 20);
            seedRunEvent(fixture.jdbc(), "other-run", "other-workspace", "session-b",
                    "other-call", 1, TODAY, 999, 999);
            TokenTracker tracker = new TokenTracker(
                    "ws", fixture.jdbc(), fixture.settings(), fixture.projector());
            ManagedTaskExecutor tasks = fixture.context().getBean(ManagedTaskExecutor.class);
            try (TaskScope scope = tasks.openScope("usage-reconcile-test", 2);
                 TokenUsageProjectionCoordinator coordinator =
                         new TokenUsageProjectionCoordinator(
                                 fixture.projector(), tracker, tasks, scope)) {
                assertEquals(0, tracker.getSessionTokens());
                assertEquals(30, tracker.getTodayTokens());
                assertEquals(25, tracker.getTodayUsage().input);
                assertEquals(20, tracker.getTodayUsage().pricingInput);
                assertEquals(1, count(fixture.jdbc(), "token_usage_projection_receipts"));
            }

            TokenTracker restartedTracker = new TokenTracker(
                    "ws", fixture.jdbc(), fixture.settings(), fixture.projector());
            try (TaskScope scope = fixture.context().getBean(ManagedTaskExecutor.class)
                    .openScope("usage-reconcile-restart-test", 2);
                 TokenUsageProjectionCoordinator coordinator =
                         new TokenUsageProjectionCoordinator(
                                 fixture.projector(), restartedTracker,
                                 fixture.context().getBean(ManagedTaskExecutor.class), scope)) {
                assertEquals(0, restartedTracker.getSessionTokens());
                assertEquals(30, restartedTracker.getTodayTokens());
                assertEquals(1, count(fixture.jdbc(), "token_usage_projection_receipts"));
            }
        }
    }

    private Fixture fixture(String name) {
        AnnotationConfigApplicationContext context = ApplicationContexts.createRoot(
                new DataRoot(temporaryDirectory.resolve("data-" + name)));
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        JdbcTokenUsageProjector projector = new JdbcTokenUsageProjector(
                "ws", jdbc, context.getBean(PlatformTransactionManager.class),
                context.getBean(ObjectMapper.class), CLOCK, SHANGHAI);
        return new Fixture(context, jdbc, context.getBean(AgentConfig.class), projector);
    }

    private static ModelUsageFact fact(
            String callId, Instant occurredAt, ModelTokenUsage usage, long pricingInput) {
        return new ModelUsageFact(new RunId("run-" + callId),
                new RunScope("ws", "user", "session"), callId, occurredAt,
                usage, pricingInput, new BigDecimal("0.25"));
    }

    private static void seedRunEvent(
            JdbcTemplate jdbc, String runId, String workspaceId, String sessionId,
            String callId, long sequence, Instant occurredAt, long input, long pricingInput) {
        jdbc.update("""
                INSERT INTO agent_runs(run_id, workspace_id, user_id, session_id,
                    request_json, execution_plan_id, state, last_sequence, version,
                    created_at, updated_at)
                VALUES (?, ?, 'user', ?, '{}', 'plan', 'SUCCEEDED', ?, 0, ?, ?)
                """, runId, workspaceId, sessionId, sequence,
                occurredAt.toEpochMilli(), occurredAt.toEpochMilli());
        jdbc.update("""
                INSERT INTO agent_run_events(run_id, event_sequence, timestamp_ms, type,
                    schema_version, producer, payload_json)
                VALUES (?, ?, ?, 'core.model.usage', 3, 'test', ?)
                """, runId, sequence, occurredAt.toEpochMilli(), """
                {"modelCallId":"%s","occurredAtEpochMillis":%d,
                 "inputTokens":%d,"pricingInputTokens":%d,"outputTokens":5,
                 "cacheReadInputTokens":4,"cacheWriteInputTokens":3,
                 "reasoningTokens":2,"modelCalls":1,"estimatedCostCny":0.25}
                """.formatted(callId, occurredAt.toEpochMilli(), input, pricingInput));
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static long number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private record Fixture(
            AnnotationConfigApplicationContext context,
            JdbcTemplate jdbc,
            AgentConfig settings,
            JdbcTokenUsageProjector projector) implements AutoCloseable {
        @Override
        public void close() {
            context.close();
        }
    }
}
