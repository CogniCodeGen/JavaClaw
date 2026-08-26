package com.javaclaw.infrastructure.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.ModelTokenUsage;
import com.javaclaw.framework.api.ModelUsageFact;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Idempotently projects durable model-usage facts into the workspace daily read model. */
public final class JdbcTokenUsageProjector {
    private static final Logger log = LoggerFactory.getLogger(JdbcTokenUsageProjector.class);

    private final String workspaceId;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Clock clock;
    private final ZoneId usageZone;

    public JdbcTokenUsageProjector(
            String workspaceId,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper json,
            Clock clock,
            ZoneId usageZone) {
        this.workspaceId = requireText(workspaceId, "workspaceId");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.usageZone = Objects.requireNonNull(usageZone, "usageZone");
    }

    /** Returns true only when this call inserted a new receipt and daily delta. */
    public synchronized boolean project(ModelUsageFact fact) {
        Objects.requireNonNull(fact, "fact");
        if (!workspaceId.equals(fact.scope().workspaceId())) {
            throw new IllegalArgumentException("usage fact belongs to another workspace");
        }
        Boolean applied = transactions.execute(status -> {
            Integer existing = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM token_usage_projection_receipts
                    WHERE workspace_id = ? AND model_call_id = ?
                    """, Integer.class, workspaceId, fact.modelCallId());
            if (existing != null && existing > 0) return false;

            String usageDate = usageDate(fact.occurredAt());
            jdbc.update("""
                    INSERT INTO token_usage_projection_receipts(
                        workspace_id, model_call_id, run_id, event_timestamp_ms,
                        usage_date, projected_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, workspaceId, fact.modelCallId(), fact.runId().value(),
                    fact.occurredAt().toEpochMilli(), usageDate, clock.millis());
            addDailyDelta(usageDate, fact.usage(), fact.pricingInputTokens());
            return true;
        });
        return Boolean.TRUE.equals(applied);
    }

    /** Replays only facts without receipts; receipt uniqueness makes repeated startup safe. */
    public synchronized int reconcile() {
        int applied = 0;
        for (ModelUsageFact fact : durableFacts()) {
            if (project(fact)) applied++;
        }
        return applied;
    }

    /** Persists a legacy, non-durable estimate as one additive daily delta. */
    public synchronized void addUnidentified(
            Instant occurredAt, ModelTokenUsage usage, long pricingInputTokens) {
        ModelTokenUsage value = usage == null ? ModelTokenUsage.ZERO : usage;
        transactions.executeWithoutResult(status ->
                addDailyDelta(usageDate(occurredAt), value, pricingInputTokens));
    }

    /** Compatibility path for the old transport cache observation. */
    public synchronized void addCacheObservation(
            Instant occurredAt, long meteredInputTokens, long cachedInputTokens) {
        long metered = Math.max(0, meteredInputTokens);
        long cached = Math.min(metered, Math.max(0, cachedInputTokens));
        if (metered == 0) return;
        String date = usageDate(occurredAt);
        transactions.executeWithoutResult(status -> {
            int updated = jdbc.update("""
                    UPDATE token_usage_daily
                    SET metered_input = metered_input + ?,
                        cached_input = cached_input + ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE workspace_id = ? AND usage_date = ?
                    """, metered, cached, workspaceId, date);
            if (updated == 0) {
                jdbc.update("""
                        INSERT INTO token_usage_daily(
                            workspace_id, usage_date, input_tokens, pricing_input_tokens,
                            output_tokens, metered_input, cached_input, cache_write_input,
                            reasoning_tokens, model_calls, updated_at)
                        VALUES (?, ?, 0, 0, 0, ?, ?, 0, 0, 0, CURRENT_TIMESTAMP)
                        """, workspaceId, date, metered, cached);
            }
        });
    }

    public String usageDate(Instant occurredAt) {
        return Objects.requireNonNull(occurredAt, "occurredAt")
                .atZone(usageZone).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private void addDailyDelta(
            String usageDate, ModelTokenUsage usage, long pricingInputTokens) {
        int updated = jdbc.update("""
                UPDATE token_usage_daily
                SET input_tokens = input_tokens + ?,
                    pricing_input_tokens = pricing_input_tokens + ?,
                    output_tokens = output_tokens + ?,
                    metered_input = metered_input + ?,
                    cached_input = cached_input + ?,
                    cache_write_input = cache_write_input + ?,
                    reasoning_tokens = reasoning_tokens + ?,
                    model_calls = model_calls + ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE workspace_id = ? AND usage_date = ?
                """, usage.inputTokens(), Math.max(0, pricingInputTokens),
                usage.outputTokens(), usage.inputTokens(), usage.cacheReadInputTokens(),
                usage.cacheWriteInputTokens(), usage.reasoningTokens(), usage.modelCalls(),
                workspaceId, usageDate);
        if (updated != 0) return;
        jdbc.update("""
                INSERT INTO token_usage_daily(
                    workspace_id, usage_date, input_tokens, pricing_input_tokens,
                    output_tokens, metered_input, cached_input, cache_write_input,
                    reasoning_tokens, model_calls, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, workspaceId, usageDate, usage.inputTokens(),
                Math.max(0, pricingInputTokens), usage.outputTokens(), usage.inputTokens(),
                usage.cacheReadInputTokens(), usage.cacheWriteInputTokens(),
                usage.reasoningTokens(), usage.modelCalls());
    }

    private List<ModelUsageFact> durableFacts() {
        List<ModelUsageFact> facts = new ArrayList<>();
        jdbc.query("""
                SELECT e.run_id, e.timestamp_ms, e.payload_json,
                       r.user_id, r.session_id
                FROM agent_run_events e
                JOIN agent_runs r ON r.run_id = e.run_id
                WHERE r.workspace_id = ?
                  AND e.type IN ('core.model.usage', 'core.model_task.usage')
                ORDER BY e.timestamp_ms, e.run_id, e.event_sequence
                """, result -> {
            try {
                JsonNode payload = json.readTree(result.getString("payload_json"));
                String callId = payload.path("modelCallId").asText("").strip();
                if (callId.isEmpty()) return;
                long input = nonNegative(payload, "inputTokens", 0);
                long output = nonNegative(payload, "outputTokens", 0);
                ModelTokenUsage usage = new ModelTokenUsage(
                        input,
                        nonNegative(payload, "cacheReadInputTokens", 0),
                        nonNegative(payload, "cacheWriteInputTokens", 0),
                        output,
                        nonNegative(payload, "reasoningTokens", 0),
                        nonNegative(payload, "modelCalls", 1));
                long occurredAt = nonNegative(payload, "occurredAtEpochMillis",
                        result.getLong("timestamp_ms"));
                long pricingInput = nonNegative(payload, "pricingInputTokens", input);
                JsonNode costNode = payload.get("estimatedCostCny");
                BigDecimal cost = costNode != null && costNode.isNumber()
                        ? costNode.decimalValue() : BigDecimal.ZERO;
                facts.add(new ModelUsageFact(
                        new RunId(result.getString("run_id")),
                        new RunScope(workspaceId, result.getString("user_id"),
                                result.getString("session_id")),
                        callId, Instant.ofEpochMilli(occurredAt), usage, pricingInput, cost));
            } catch (Exception malformed) {
                log.warn("跳过无法解析的模型用量事件: run={} ({})",
                        result.getString("run_id"), malformed.toString());
            }
        }, workspaceId);
        return List.copyOf(facts);
    }

    private static long nonNegative(JsonNode value, String field, long fallback) {
        JsonNode node = value.get(field);
        return node == null || !node.isNumber() ? Math.max(0, fallback)
                : Math.max(0, node.asLong());
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label).strip();
        if (checked.isEmpty()) throw new IllegalArgumentException(label + " is blank");
        return checked;
    }
}
