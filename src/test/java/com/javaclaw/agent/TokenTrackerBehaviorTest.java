package com.javaclaw.agent;

import com.javaclaw.config.AgentConfig;
import com.javaclaw.framework.api.ModelTokenUsage;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenTrackerBehaviorTest {

    @TempDir
    Path temporaryDirectory;

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private AgentConfig settings;
    private TokenTracker tracker;

    @BeforeEach
    void createTracker() {
        context = ApplicationContexts.createRoot(
                new DataRoot(temporaryDirectory.resolve("data")));
        jdbc = context.getBean(JdbcTemplate.class);
        settings = context.getBean(AgentConfig.class);
        settings.setModelName("qwen-plus");
        jdbc.update("""
                MERGE INTO token_usage_daily(
                    workspace_id, usage_date, input_tokens, output_tokens,
                    metered_input, cached_input, updated_at)
                KEY(workspace_id, usage_date)
                VALUES ('token-test', '2000-01-01', 7, 3, 0, 0, CURRENT_TIMESTAMP)
                """);
        tracker = new TokenTracker("token-test", jdbc, settings);
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void streamingPrefersRealUsageThenFallsBackToCharacterEstimates() {
        AtomicInteger changes = new AtomicInteger();
        tracker.setOnTokensChanged(changes::incrementAndGet);
        tracker.beginStreaming(9);
        tracker.addStreamingChars(6);
        assertEquals(5, tracker.getSessionTokens());

        tracker.addStreamingUsage(0, 0);
        tracker.addStreamingUsage(-2, 5);
        assertEquals(5, tracker.getSessionTokens());
        tracker.addStreamingUsage(10, -3);
        assertEquals(15, tracker.getSessionTokens());
        tracker.recordUsage(999, 999);
        assertEquals(15, tracker.getSessionTokens());
        assertEquals(10, tracker.getTodayUsage().input);
        assertEquals(5, tracker.getTodayUsage().output);

        tracker.beginStreaming(12);
        tracker.addStreamingChars(9);
        tracker.recordUsage(12, 9);
        assertEquals(22, tracker.getSessionTokens());
        assertEquals(14, tracker.getTodayUsage().input);
        assertEquals(8, tracker.getTodayUsage().output);
        tracker.beginStreaming(0);
        tracker.recordUsage(0, 0);
        assertTrue(changes.get() >= 5);

        tracker.setOnTokensChanged(() -> { throw new IllegalStateException("ui closed"); });
        tracker.resetSession();
        assertEquals(0, tracker.getSessionTokens());
        assertTrue(tracker.getSessionDurationSeconds() >= 0);
    }

    @Test
    void taskAndCacheUsageClampInvalidValuesAggregateAndPersist() {
        assertEquals(-1, tracker.getTodayCacheHitRate());
        assertEquals(0, tracker.getTodayTokens());
        assertEquals(0, tracker.getTodayUsage().total());
        tracker.recordTaskUsage(0, 0);
        tracker.recordTaskUsage(-5, -2);
        tracker.recordModelUsage(null, -1, 0);
        tracker.recordModelUsage("worker", 20, 10);
        tracker.recordTaskUsage(5, 2);
        assertEquals(37, tracker.getSessionTokens());
        assertEquals(37, tracker.getTodayTokens());
        assertEquals(37, tracker.getMonthlyTokens());
        assertEquals(25, tracker.getMonthlyUsage().input);
        assertTrue(tracker.getMonthlyCostCny() > 0);

        tracker.recordCachedObservation(0, 10);
        tracker.recordCachedObservation(-1, 0);
        tracker.recordCachedObservation(100, -3);
        tracker.recordCachedObservation(50, 100);
        assertEquals(2.0 / 7.0, tracker.getTodayCacheHitRate(), 0.0001);
        tracker.recordTaskUsage(1, 0);
        assertEquals(30, tracker.getRecentDailyUsage().size());
        assertEquals(38L, tracker.getRecentDailyUsage().get(LocalDate.now().toString()));

        TokenTracker reloaded = new TokenTracker("token-test", jdbc, settings);
        assertEquals(38, reloaded.getTodayTokens());
        assertEquals(50.0 / 176.0, reloaded.getTodayCacheHitRate(), 0.0001);
    }

    @Test
    void detailedUsagePersistsAllSubsetsAndCountsOnlyInputPlusOutput() {
        tracker.recordModelUsage("detailed",
                new ModelTokenUsage(1_000, 300, 75, 400, 125, 2));

        TokenTracker.DailyUsage today = tracker.getTodayUsage();
        assertEquals(1_000, today.input);
        assertEquals(400, today.output);
        assertEquals(1_400, today.total());
        assertEquals(1_000, today.meteredInput);
        assertEquals(300, today.cachedInput);
        assertEquals(75, today.cacheWriteInput);
        assertEquals(125, today.reasoning);
        assertEquals(2, today.modelCalls);
        assertEquals(0.3, tracker.getTodayCacheHitRate(), 0.0001);

        TokenTracker reloaded = new TokenTracker("token-test", jdbc, settings);
        TokenTracker.DailyUsage persisted = reloaded.getTodayUsage();
        assertEquals(today.input, persisted.input);
        assertEquals(today.output, persisted.output);
        assertEquals(today.meteredInput, persisted.meteredInput);
        assertEquals(today.cachedInput, persisted.cachedInput);
        assertEquals(today.cacheWriteInput, persisted.cacheWriteInput);
        assertEquals(today.reasoning, persisted.reasoning);
        assertEquals(today.modelCalls, persisted.modelCalls);
    }

    @Test
    void formattingAndPricingCoverSmallLargeFreeAndUnknownModels() {
        TokenTracker.DailyUsage usage = new TokenTracker.DailyUsage(3, 4);
        assertEquals(7, usage.total());
        assertEquals("999", TokenTracker.formatTokens(999));
        assertEquals("1.0K", TokenTracker.formatTokens(1_000));
        assertEquals("10K", TokenTracker.formatTokens(10_000));
        assertEquals("1.0M", TokenTracker.formatTokens(1_000_000));
        assertEquals("¥0.00", TokenTracker.formatCostCny(-1));
        assertEquals("¥<0.01", TokenTracker.formatCostCny(0.001));
        assertEquals("¥1.23", TokenTracker.formatCostCny(1.234));
        assertEquals("0:00", TokenTracker.formatDuration(-1));
        assertEquals("1:05", TokenTracker.formatDuration(65));
        assertEquals("1:01:01", TokenTracker.formatDuration(3_661));

        assertEquals(PricingTable.Price.FREE, PricingTable.lookup(null));
        assertEquals(PricingTable.Price.FREE, PricingTable.lookup(" "));
        assertEquals(PricingTable.Price.FREE, PricingTable.lookup("unknown-model"));
        assertEquals(PricingTable.Price.FREE, PricingTable.lookup("gemini-2.0-flash"));
        assertTrue(PricingTable.lookup("qwen-plus").inputPer1k() > 0);
        assertEquals(PricingTable.lookup("qwen-plus"), PricingTable.lookup("QWEN-PLUS-2026"));
        assertTrue(PricingTable.estimateCostCny("qwen-plus", 1_000, 1_000) > 0);
    }

    @Test
    void missingSchemaIsAContainedPersistenceFailure() {
        JdbcTemplate broken = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:token-broken-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1"));
        TokenTracker isolated = new TokenTracker("broken", broken, settings);
        isolated.recordTaskUsage(1, 2);
        assertEquals(3, isolated.getTodayTokens());
    }
}
