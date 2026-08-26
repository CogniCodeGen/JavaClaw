package com.javaclaw.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.framework.api.ModelTokenUsage;
import com.javaclaw.framework.api.ModelUsageFact;
import com.javaclaw.infrastructure.agent.JdbcTokenUsageProjector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token 用量追踪器
 *
 * <p>按会话和日期统计 token 消耗（区分输入/输出），持久化到全局 H2 数据库并按 {@code workspace_id} 隔离，
 * 供 UI 状态栏与成本估算使用。</p>
 */
public class TokenTracker {

    private static final Logger log = LoggerFactory.getLogger(TokenTracker.class);

    /** 每日输入/输出 token 明细 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DailyUsage {
        public long input;
        /** Raw provider input retained solely for the legacy background price estimate. */
        public long pricingInput;
        public long output;
        /** 提供商 usage 中的全部 prompt token（缓存命中率分母；数值与 input 同源但不重复计总量） */
        public long meteredInput;
        /** 其中命中提供商上下文缓存的部分；是 input 的子集 */
        public long cachedInput;
        public long cacheWriteInput;
        public long reasoning;
        public long modelCalls;

        public DailyUsage() {}

        public DailyUsage(long input, long output) {
            this.input = input;
            this.output = output;
        }

        public long total() {
            return input + output;
        }
    }

    /** 当前会话的 token 累计（总量） */
    private final AtomicLong sessionTokens = new AtomicLong(0);

    /** 会话开始时间（用于计算耗时） */
    private final long sessionStartMillis = System.currentTimeMillis();

    /** 按日期统计的 token 用量 */
    private final ConcurrentHashMap<String, DailyUsage> dailyUsage = new ConcurrentHashMap<>();

    /** token 变更回调（用于更新 UI） */
    private Runnable onTokensChanged;

    /** 当前流式对话的实时输出字符数（对话结束后重置） */
    private final AtomicLong streamingChars = new AtomicLong(0);

    /** 当前流式对话的输入字符数 */
    private volatile int streamingInputChars = 0;

    /** 当前流式对话累计的真实输入 token（取自 API Usage 返回，对话结束后重置） */
    private final AtomicLong streamingRealInputTokens = new AtomicLong(0);

    /** 当前流式对话累计的真实输出 token（取自 API Usage 返回，对话结束后重置） */
    private final AtomicLong streamingRealOutputTokens = new AtomicLong(0);

    /** 当前流式对话是否收到过真实 usage（决定 UI/落盘用真实值还是字符估算回退） */
    private volatile boolean streamingHasRealUsage = false;

    private final String workspaceId;
    private final JdbcTemplate jdbc;
    private final AgentConfig settings;
    private final JdbcTokenUsageProjector projector;

    public TokenTracker(String workspaceId, JdbcTemplate jdbc, AgentConfig settings) {
        this(workspaceId, jdbc, settings, null);
    }

    public TokenTracker(
            String workspaceId,
            JdbcTemplate jdbc,
            AgentConfig settings,
            JdbcTokenUsageProjector projector) {
        this.workspaceId = java.util.Objects.requireNonNull(workspaceId, "workspaceId");
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
        this.projector = projector;
        load();
    }

    public void setOnTokensChanged(Runnable callback) {
        this.onTokensChanged = callback;
    }

    /**
     * 开始一次新的流式对话，记录输入字符数并重置流式计数
     */
    public void beginStreaming(int inputChars) {
        streamingInputChars = inputChars;
        streamingChars.set(0);
        streamingRealInputTokens.set(0);
        streamingRealOutputTokens.set(0);
        streamingHasRealUsage = false;
    }

    /**
     * 在流式输出过程中累加输出字符数，实时更新 UI
     */
    public void addStreamingChars(int chars) {
        streamingChars.addAndGet(chars);
        fireChanged();
    }

    /**
     * 累加一次来自模型 API 返回的真实 token 用量
     *
     * <p>框架事件适配器可逐段累加 Spring AI 返回的 usage；流结束时由
     * {@link #recordUsage()} 一次性落盘。</p>
     */
    public void addStreamingUsage(long inputTokens, long outputTokens) {
        if (inputTokens <= 0 && outputTokens <= 0) return;
        streamingRealInputTokens.addAndGet(Math.max(0, inputTokens));
        streamingRealOutputTokens.addAndGet(Math.max(0, outputTokens));
        streamingHasRealUsage = true;
        fireChanged();
    }

    /**
     * 获取当前会话累计 token 数（含正在进行的流式输出）
     *
     * <p>若已收到过真实 usage，优先返回真实累计；否则用字符数 / 3 近似。</p>
     */
    public long getSessionTokens() {
        long live;
        if (streamingHasRealUsage) {
            live = streamingRealInputTokens.get() + streamingRealOutputTokens.get();
        } else {
            live = (streamingInputChars + streamingChars.get()) / 3;
        }
        return sessionTokens.get() + live;
    }

    /** 当前会话持续时间（秒） */
    public long getSessionDurationSeconds() {
        return (System.currentTimeMillis() - sessionStartMillis) / 1000;
    }

    /**
     * 记录一次对话的 token 消耗（流式结束时调用）
     *
     * <p>若流式过程中已通过 {@link #addStreamingUsage(long, long)} 累计到 API 返回的真实 usage，
     * 则优先采用真实值；否则退化为字符数 / 3 的粗略估算（兼容不返回 usage 的提供商/场景）。</p>
     */
    public void recordUsage(int inputChars, int outputChars) {
        long inputTokens;
        long outputTokens;
        if (streamingHasRealUsage) {
            inputTokens = streamingRealInputTokens.get();
            outputTokens = streamingRealOutputTokens.get();
            log.debug("采用 API 真实 usage：input={}, output={}", inputTokens, outputTokens);
        } else {
            // 降级：中文约 2 字符/token，英文约 4 字符/token，取 3 作为平均
            inputTokens = inputChars / 3;
            outputTokens = outputChars / 3;
            log.debug("未收到 API usage，字符估算：input={}, output={}", inputTokens, outputTokens);
        }
        sessionTokens.addAndGet(inputTokens + outputTokens);

        streamingInputChars = 0;
        streamingChars.set(0);
        streamingRealInputTokens.set(0);
        streamingRealOutputTokens.set(0);
        streamingHasRealUsage = false;

        if (inputTokens == 0 && outputTokens == 0) {
            fireChanged();
            return;
        }

        Instant occurredAt = Instant.now();
        ModelTokenUsage delta = new ModelTokenUsage(inputTokens, outputTokens);
        persistUnidentified(occurredAt, delta, inputTokens);
        addDailyInMemory(usageDate(occurredAt), delta, inputTokens);

        fireChanged();
    }

    /**
     * 记录一次非流式模型调用的真实 token 用量（通用入口）
     *
     * <p>适用于所有经统一 RunUsageLedger 观测到的模型调用。把真实 token 直接累加到会话计数与当日
     * 统计中，并立即落盘，使状态栏和月度成本能反映所有模型消耗。</p>
     *
     * <p>{@code source} 仅用于诊断日志归因，不影响落盘结构。</p>
     */
    public void recordModelUsage(String source, long inputTokens, long outputTokens) {
        long in = Math.max(0, inputTokens);
        long out = Math.max(0, outputTokens);
        if (in == 0 && out == 0) return;
        if (log.isDebugEnabled()) {
            log.debug("[{}] 记录真实 token: input={}, output={}",
                    source == null ? "model" : source, in, out);
        }
        recordTaskUsage(in, out);
    }

    /** The sole detailed daily-accounting entry point used by RunUsageLedger. */
    public void recordModelUsage(String source, ModelTokenUsage usage) {
        ModelTokenUsage value = usage == null ? ModelTokenUsage.ZERO : usage;
        if (value.totalTokens() == 0 && value.modelCalls() == 0) return;
        if (log.isDebugEnabled()) {
            log.debug("[{}] 记录模型用量: input={}, output={}, cacheRead={}, cacheWrite={}, reasoning={}, calls={}",
                    source == null ? "model" : source, value.inputTokens(), value.outputTokens(),
                    value.cacheReadInputTokens(), value.cacheWriteInputTokens(),
                    value.reasoningTokens(), value.modelCalls());
        }
        recordTaskUsage(value);
    }

    /**
     * 兼容旧版 Spring AI Observation 的 prompt/缓存命中入口。
     *
     * <p>新代码必须通过 {@link #recordModelUsage(String, ModelTokenUsage)} 一次写入完整 usage；
     * 同一次模型调用不得再调用本方法，否则会重复累计缓存分母。保留本入口只为旧适配器兼容。</p>
     *
     * <p>该兼容入口也采用增量落盘，避免覆盖统一 usage 投影。</p>
     */
    @Deprecated(forRemoval = false)
    public void recordCachedObservation(long promptTokens, long cachedTokens) {
        if (promptTokens <= 0) return;
        Instant occurredAt = Instant.now();
        persistCacheObservation(occurredAt, promptTokens, cachedTokens);
        String today = usageDate(occurredAt);
        dailyUsage.compute(today, (k, prev) -> {
            DailyUsage d = prev == null ? new DailyUsage(0, 0) : prev;
            d.meteredInput += promptTokens;
            d.cachedInput += Math.min(Math.max(0, cachedTokens), promptTokens);
            return d;
        });
    }

    /** 今日缓存命中率（0~1）；无观测数据返回 -1 */
    public double getTodayCacheHitRate() {
        DailyUsage d = dailyUsage.get(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        if (d == null || d.meteredInput <= 0) return -1;
        return (double) d.cachedInput / d.meteredInput;
    }

    /**
     * 记录一次任务模式下的真实 token 用量
     *
     * <p>托管任务的智能体调用不走流式聊天通道，因此不能复用
     * {@link #beginStreaming(int)}/{@link #recordUsage(int, int)} 的流式会话状态。
     * 本方法把真实 token 直接累加到会话计数与当日统计中，并立即落盘，
     * 供状态栏与月度成本估算使用。</p>
     */
    public void recordTaskUsage(long inputTokens, long outputTokens) {
        long in = Math.max(0, inputTokens);
        long out = Math.max(0, outputTokens);
        if (in == 0 && out == 0) return;
        Instant occurredAt = Instant.now();
        ModelTokenUsage usage = new ModelTokenUsage(in, out);
        persistUnidentified(occurredAt, usage, in);
        sessionTokens.addAndGet(in + out);
        addDailyInMemory(usageDate(occurredAt), usage, in);
        fireChanged();
    }

    public void recordTaskUsage(ModelTokenUsage usage) {
        ModelTokenUsage value = usage == null ? ModelTokenUsage.ZERO : usage;
        long in = value.inputTokens();
        long out = value.outputTokens();
        if (in == 0 && out == 0 && value.modelCalls() == 0) return;
        Instant occurredAt = Instant.now();
        persistUnidentified(occurredAt, value, in);
        sessionTokens.addAndGet(in + out);
        addDailyInMemory(usageDate(occurredAt), value, in);
        fireChanged();
    }

    /** Updates the live read model after the durable projector committed a new receipt. */
    public void applyProjectedUsage(ModelUsageFact fact) {
        ModelUsageFact value = java.util.Objects.requireNonNull(fact, "fact");
        sessionTokens.addAndGet(value.usage().totalTokens());
        addDailyInMemory(usageDate(value.occurredAt()),
                value.usage(), value.pricingInputTokens());
        fireChanged();
    }

    /** Reloads durable daily totals after a startup or retry reconciliation. */
    public void reloadPersistedUsage() {
        load();
        fireChanged();
    }

    /** 获取今日累计 token 总数 */
    public long getTodayTokens() {
        DailyUsage u = dailyUsage.get(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        return u == null ? 0 : u.total();
    }

    /** 获取今日输入/输出 token 明细 */
    public DailyUsage getTodayUsage() {
        DailyUsage u = dailyUsage.get(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        return u == null ? new DailyUsage(0, 0) : copy(u);
    }

    /** 获取本月累计 token 总数 */
    public long getMonthlyTokens() {
        return aggregateMonth().total();
    }

    /** 获取本月输入/输出 token 明细 */
    public DailyUsage getMonthlyUsage() {
        return aggregateMonth();
    }

    private DailyUsage aggregateMonth() {
        LocalDate now = LocalDate.now();
        String prefix = String.format("%04d-%02d", now.getYear(), now.getMonthValue());
        DailyUsage agg = new DailyUsage(0, 0);
        for (var e : dailyUsage.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                agg.input += e.getValue().input;
                agg.pricingInput += e.getValue().pricingInput;
                agg.output += e.getValue().output;
                agg.meteredInput += e.getValue().meteredInput;
                agg.cachedInput += e.getValue().cachedInput;
                agg.cacheWriteInput += e.getValue().cacheWriteInput;
                agg.reasoning += e.getValue().reasoning;
                agg.modelCalls += e.getValue().modelCalls;
            }
        }
        return agg;
    }

    /**
     * 估算本月成本（CNY），按当前配置的模型查 {@link PricingTable}
     */
    public double getMonthlyCostCny() {
        DailyUsage u = aggregateMonth();
        String model = settings.getModelName();
        return PricingTable.estimateCostCny(model, u.pricingInput, u.output);
    }

    /**
     * 获取按日期统计的总量（最近 30 天）— 兼容旧 UI
     */
    public Map<String, Long> getRecentDailyUsage() {
        Map<String, Long> recent = new LinkedHashMap<>();
        LocalDate now = LocalDate.now();
        for (int i = 29; i >= 0; i--) {
            String date = now.minusDays(i).format(DateTimeFormatter.ISO_LOCAL_DATE);
            DailyUsage u = dailyUsage.get(date);
            recent.put(date, u == null ? 0L : u.total());
        }
        return recent;
    }

    /**
     * 重置当前会话计数
     */
    public void resetSession() {
        sessionTokens.set(0);
        fireChanged();
    }

    /**
     * 格式化 token 数为可读字符串
     */
    public static String formatTokens(long tokens) {
        if (tokens < 1000) return tokens + "";
        if (tokens < 10_000) return String.format("%.1fK", tokens / 1000.0);
        if (tokens < 1_000_000) return String.format("%.0fK", tokens / 1000.0);
        return String.format("%.1fM", tokens / 1_000_000.0);
    }

    /**
     * 格式化成本为可读字符串（¥xx.xx）
     */
    public static String formatCostCny(double cost) {
        if (cost <= 0) return "¥0.00";
        if (cost < 0.01) return "¥<0.01";
        return String.format("¥%.2f", cost);
    }

    /** 格式化持续时间（HH:MM:SS 或 MM:SS） */
    public static String formatDuration(long seconds) {
        if (seconds < 0) seconds = 0;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s)
                     : String.format("%d:%02d", m, s);
    }

    private void fireChanged() {
        if (onTokensChanged != null) {
            try {
                onTokensChanged.run();
            } catch (Exception e) {
                log.debug("onTokensChanged 回调异常", e);
            }
        }
    }

    // ==================== 持久化 ====================

    private void persistUnidentified(
            Instant occurredAt, ModelTokenUsage usage, long pricingInputTokens) {
        try {
            if (projector != null) {
                projector.addUnidentified(occurredAt, usage, pricingInputTokens);
                return;
            }
            addFallbackDelta(usageDate(occurredAt), usage, pricingInputTokens);
        } catch (DataAccessException failure) {
            log.warn("保存 token 用量数据失败: {}", failure.getMessage());
        }
    }

    private void persistCacheObservation(
            Instant occurredAt, long promptTokens, long cachedTokens) {
        try {
            if (projector != null) {
                projector.addCacheObservation(occurredAt, promptTokens, cachedTokens);
                return;
            }
            long cached = Math.min(Math.max(0, cachedTokens), promptTokens);
            String date = usageDate(occurredAt);
            int updated = jdbc.update("""
                    UPDATE token_usage_daily
                    SET metered_input = metered_input + ?, cached_input = cached_input + ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE workspace_id = ? AND usage_date = ?
                    """, promptTokens, cached, workspaceId, date);
            if (updated == 0) {
                jdbc.update("""
                        INSERT INTO token_usage_daily(
                            workspace_id, usage_date, input_tokens, pricing_input_tokens,
                            output_tokens, metered_input, cached_input, cache_write_input,
                            reasoning_tokens, model_calls, updated_at)
                        VALUES (?, ?, 0, 0, 0, ?, ?, 0, 0, 0, CURRENT_TIMESTAMP)
                        """, workspaceId, date, promptTokens, cached);
            }
        } catch (DataAccessException failure) {
            log.warn("保存缓存 token 观测失败: {}", failure.getMessage());
        }
    }

    private void addFallbackDelta(
            String date, ModelTokenUsage usage, long pricingInputTokens) {
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
                workspaceId, date);
        if (updated != 0) return;
        jdbc.update("""
                INSERT INTO token_usage_daily(
                    workspace_id, usage_date, input_tokens, pricing_input_tokens,
                    output_tokens, metered_input, cached_input, cache_write_input,
                    reasoning_tokens, model_calls, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, workspaceId, date, usage.inputTokens(), Math.max(0, pricingInputTokens),
                usage.outputTokens(), usage.inputTokens(), usage.cacheReadInputTokens(),
                usage.cacheWriteInputTokens(), usage.reasoningTokens(), usage.modelCalls());
    }

    private void load() {
        try {
            dailyUsage.clear();
            String sql = """
                    SELECT usage_date, input_tokens, pricing_input_tokens, output_tokens,
                           metered_input, cached_input, cache_write_input,
                           reasoning_tokens, model_calls
                    FROM token_usage_daily
                    WHERE workspace_id = ?
                    ORDER BY usage_date
                    """;
            jdbc.query(sql, result -> {
                DailyUsage usage = new DailyUsage();
                usage.input = result.getLong("input_tokens");
                usage.pricingInput = result.getLong("pricing_input_tokens");
                usage.output = result.getLong("output_tokens");
                usage.meteredInput = result.getLong("metered_input");
                usage.cachedInput = result.getLong("cached_input");
                usage.cacheWriteInput = result.getLong("cache_write_input");
                usage.reasoning = result.getLong("reasoning_tokens");
                usage.modelCalls = result.getLong("model_calls");
                dailyUsage.put(result.getString("usage_date"), usage);
            }, workspaceId);
            log.info("已从 H2 加载 token 用量数据: {} 天记录", dailyUsage.size());
        } catch (DataAccessException e) {
            // 带堆栈输出便于定位数据库或 schema 不兼容的根因
            log.warn("加载 token 用量数据失败", e);
        }
    }

    private static DailyUsage copy(DailyUsage source) {
        DailyUsage target = new DailyUsage(source.input, source.output);
        target.pricingInput = source.pricingInput;
        target.meteredInput = source.meteredInput;
        target.cachedInput = source.cachedInput;
        target.cacheWriteInput = source.cacheWriteInput;
        target.reasoning = source.reasoning;
        target.modelCalls = source.modelCalls;
        return target;
    }

    private void addDailyInMemory(
            String date, ModelTokenUsage usage, long pricingInputTokens) {
        dailyUsage.compute(date, (key, previous) -> {
            DailyUsage current = previous == null ? new DailyUsage() : previous;
            current.input += usage.inputTokens();
            current.pricingInput += Math.max(0, pricingInputTokens);
            current.output += usage.outputTokens();
            current.meteredInput += usage.inputTokens();
            current.cachedInput += usage.cacheReadInputTokens();
            current.cacheWriteInput += usage.cacheWriteInputTokens();
            current.reasoning += usage.reasoningTokens();
            current.modelCalls += usage.modelCalls();
            return current;
        });
    }

    private String usageDate(Instant occurredAt) {
        return projector == null
                ? occurredAt.atZone(ZoneId.systemDefault()).toLocalDate()
                        .format(DateTimeFormatter.ISO_LOCAL_DATE)
                : projector.usageDate(occurredAt);
    }
}
