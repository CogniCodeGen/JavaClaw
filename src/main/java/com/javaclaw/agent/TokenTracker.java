package com.javaclaw.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.javaclaw.config.DataManager;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.LocalDate;
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
 * <p>按会话和日期统计 token 消耗（区分输入/输出），持久化到工作区数据目录，供 UI 状态栏与成本估算使用。</p>
 */
public class TokenTracker {

    private static final Logger log = LoggerFactory.getLogger(TokenTracker.class);
    private static final String DATA_FILE = "token-usage.json";
    private static final int SCHEMA_VERSION = 2;
    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** 每日输入/输出 token 明细 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DailyUsage {
        public long input;
        public long output;
        /** 传输层观测到的全部 prompt token（缓存命中率分母；与 input 来源不同，勿混算） */
        public long meteredInput;
        /** 其中命中提供商上下文缓存的部分（按折扣计费，评估真实成本的关键） */
        public long cachedInput;

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

    public TokenTracker() {
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
     * <p>在流式过程中，编排器和子智能体每次模型调用都会在 {@code Msg.getChatUsage()} 携带
     * 一段 usage；本方法逐段累加，流结束时由 {@link #recordUsage()} 一次性落盘。</p>
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

        final long finalInput = inputTokens;
        final long finalOutput = outputTokens;
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        dailyUsage.compute(today, (k, prev) -> {
            if (prev == null) return new DailyUsage(finalInput, finalOutput);
            prev.input += finalInput;
            prev.output += finalOutput;
            return prev;
        });

        fireChanged();
        save();
    }

    /**
     * 记录一次非流式模型调用的真实 token 用量（通用入口）
     *
     * <p>适用于所有"非聊天主流程"的单次模型调用：GoalManager / ToolRouter /
     * VisionPreprocessor / EvaluationPipeline / PlanEvolver / AgentPromptOptimizer
     * 以及任务模式下 ChallengerAgent 等。把真实 token 直接累加到会话计数与当日
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

    /**
     * 记录一次传输层观测到的 prompt/缓存命中 token（由 {@code UsageMeteredTransport} 回调）。
     *
     * <p>与 {@link #recordModelUsage} 是两套口径：本方法覆盖<b>所有</b>经共享传输层的模型调用
     * （聊天、SDD 各阶段、路由器、评估器……），用于计算当日缓存命中率
     * {@code cachedInput / meteredInput}——这是判断「百万级输入到底按几折计费」的依据。
     * 不触碰 sessionTokens / input / output，避免与既有记账重复。</p>
     *
     * <p>落盘节流：缓存观测每次模型调用都会触发，跟随下一次常规 {@code save()} 顺带落盘即可，
     * 此处只更新内存（进程异常退出最多丢当日命中率统计，可接受）。</p>
     */
    public void recordCachedObservation(long promptTokens, long cachedTokens) {
        if (promptTokens <= 0) return;
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
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
     * 从一次 {@code model.stream(...).collectList().block()} 收集到的响应列表中提取真实
     * token 用量。
     *
     * <p>大多数 OpenAI 兼容流式 API 只在最终 chunk 携带 usage，AgentScope 把它逐次
     * 透传到 {@link ChatResponse}。本方法把所有非空 usage 累加，等价于"取最后一次
     * 非零 usage"——在常见提供商下两者一致。</p>
     *
     * @return {@code long[]{inputTokens, outputTokens}}，无 usage 时返回 {@code {0,0}}
     */
    public static long[] extractUsage(List<ChatResponse> responses) {
        long in = 0, out = 0;
        if (responses != null) {
            for (ChatResponse resp : responses) {
                if (resp == null) continue;
                ChatUsage u = resp.getUsage();
                if (u == null) continue;
                in += Math.max(0, u.getInputTokens());
                out += Math.max(0, u.getOutputTokens());
            }
        }
        return new long[]{in, out};
    }

    /**
     * 从单条 {@link Msg} 中提取 ChatUsage 并记录（用于 {@code agent.call()} 类一次性
     * 阻塞调用，返回的 Msg 携带累计 ChatUsage 的场景）。
     *
     * @return 是否记录了非零用量
     */
    public boolean recordMsgUsage(String source, Msg msg) {
        if (msg == null) return false;
        try {
            ChatUsage u = msg.getChatUsage();
            if (u != null && (u.getInputTokens() > 0 || u.getOutputTokens() > 0)) {
                recordModelUsage(source, u.getInputTokens(), u.getOutputTokens());
                return true;
            }
        } catch (Throwable t) {
            log.debug("读取 Msg.getChatUsage 失败，忽略", t);
        }
        return false;
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

        sessionTokens.addAndGet(in + out);

        final long finalInput = in;
        final long finalOutput = out;
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        dailyUsage.compute(today, (k, prev) -> {
            if (prev == null) return new DailyUsage(finalInput, finalOutput);
            prev.input += finalInput;
            prev.output += finalOutput;
            return prev;
        });

        fireChanged();
        save();
    }

    /** 获取今日累计 token 总数 */
    public long getTodayTokens() {
        DailyUsage u = dailyUsage.get(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        return u == null ? 0 : u.total();
    }

    /** 获取今日输入/输出 token 明细 */
    public DailyUsage getTodayUsage() {
        DailyUsage u = dailyUsage.get(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        return u == null ? new DailyUsage(0, 0) : new DailyUsage(u.input, u.output);
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
                agg.output += e.getValue().output;
            }
        }
        return agg;
    }

    /**
     * 估算本月成本（CNY），按当前配置的模型查 {@link PricingTable}
     */
    public double getMonthlyCostCny() {
        DailyUsage u = aggregateMonth();
        String model = com.javaclaw.config.AgentConfig.getInstance().getModelName();
        return PricingTable.estimateCostCny(model, u.input, u.output);
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

    private void save() {
        try {
            File file = new File(DataManager.getInstance().getDataRoot().toFile(), DATA_FILE);
            Map<String, Object> root = new HashMap<>();
            root.put("schemaVersion", SCHEMA_VERSION);
            root.put("data", dailyUsage);
            mapper.writeValue(file, root);
        } catch (Exception e) {
            log.warn("保存 token 用量数据失败: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void load() {
        try {
            File file = new File(DataManager.getInstance().getDataRoot().toFile(), DATA_FILE);
            if (!file.exists()) return;
            Map<String, Object> root = mapper.readValue(file, Map.class);
            if (root.containsKey("schemaVersion")) {
                Map<String, Object> data = (Map<String, Object>) root.get("data");
                if (data != null) {
                    for (var entry : data.entrySet()) {
                        DailyUsage u = mapper.convertValue(entry.getValue(), DailyUsage.class);
                        dailyUsage.put(entry.getKey(), u);
                    }
                }
            } else {
                // 旧格式迁移：Map<String, Long> → 全部计入 input（无法区分历史输入输出）
                for (var entry : root.entrySet()) {
                    Object val = entry.getValue();
                    if (val instanceof Number n) {
                        long total = n.longValue();
                        dailyUsage.put(entry.getKey(), new DailyUsage(total, 0));
                    }
                }
                log.info("检测到旧版 token-usage.json 格式，已迁移 {} 条记录", dailyUsage.size());
            }
            log.info("已加载 token 用量数据: {} 天记录", dailyUsage.size());
        } catch (Exception e) {
            // 带堆栈输出便于定位 JSON 损坏或 schema 不兼容的根因
            log.warn("加载 token 用量数据失败", e);
        }
    }
}
