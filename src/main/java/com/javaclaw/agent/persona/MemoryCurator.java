package com.javaclaw.agent.persona;

import com.javaclaw.agent.TokenTracker;
import com.javaclaw.prompt.MemoryPrompts;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 长期记忆维护器（借鉴 Harness MemoryFlushManager + MemoryConsolidator）
 *
 * <p>两阶段流水：</p>
 * <ol>
 *   <li><b>蒸馏 (distillFromTurn)</b> —— 一次完整对话结束后调用，
 *       用轻量模型从用户输入 + 助手回复中提炼"应长期记住的事实条目"，
 *       追加到当前工作区 {@code memory/YYYY-MM-DD.md} 日流水账。</li>
 *   <li><b>合并 (consolidate)</b> —— 当日流水文件数量超阈值时，
 *       把所有日文件 + 现有 MEMORY.md 合并送给轻量模型整理为新的 MEMORY.md，
 *       完成后删除已合并的日文件。</li>
 * </ol>
 *
 * <p>关键设计：</p>
 * <ul>
 *   <li>全部在 Reactor boundedElastic 线程上异步执行，不阻塞主对话流</li>
 *   <li>失败完全静默吞掉（记忆出错不能影响主流程）</li>
 *   <li>{@link #consolidating} CAS 保护避免重复合并</li>
 *   <li>使用轻量模型控制成本（与 EvaluationPipeline / GoalManager 同一池）</li>
 * </ul>
 *
 * @author JavaClaw
 */
public class MemoryCurator {

    private static final Logger log = LoggerFactory.getLogger(MemoryCurator.class);

    /** 触发合并的日流水文件数量阈值 */
    private static final int CONSOLIDATE_THRESHOLD = 7;

    /** 单次回复参与蒸馏的最大字符数（超过截尾） */
    private static final int MAX_REPLY_CHARS_FOR_DISTILL = 6000;

    /** 输入太短跳过蒸馏（避免 hello/thx 之类无意义对话也烧 token） */
    private static final int MIN_INPUT_CHARS_FOR_DISTILL = 10;

    private final ChatModelBase lightModel;
    private final WorkspaceContextFiles contextFiles;
    private final TokenTracker tokenTracker;
    private final GenerateOptions generateOptions;

    /** 合并互斥：consolidate 是相对耗时的模型调用，避免多次触发并发跑 */
    private final AtomicBoolean consolidating = new AtomicBoolean(false);

    public MemoryCurator(ChatModelBase lightModel,
                         WorkspaceContextFiles contextFiles,
                         TokenTracker tokenTracker) {
        this.lightModel = lightModel;
        this.contextFiles = contextFiles;
        this.tokenTracker = tokenTracker;
        this.generateOptions = GenerateOptions.builder().build();
    }

    // ==================== 阶段 1：蒸馏一次对话到日流水账 ====================

    /**
     * 异步从一次完整对话提炼事实，追加到今日流水账。
     * 失败静默：返回 Mono.empty 不影响调用方。
     */
    public Mono<Void> distillFromTurn(String userInput, String replyText) {
        if (userInput == null || userInput.trim().length() < MIN_INPUT_CHARS_FOR_DISTILL) {
            return Mono.empty();
        }
        if (replyText == null || replyText.isBlank()) {
            return Mono.empty();
        }

        return Mono.fromRunnable(() -> distillSync(userInput, replyText))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("记忆蒸馏失败（已静默忽略）: {}", e.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    private void distillSync(String userInput, String replyText) {
        String reply = replyText.length() > MAX_REPLY_CHARS_FOR_DISTILL
                ? replyText.substring(0, MAX_REPLY_CHARS_FOR_DISTILL) + "...(截断)"
                : replyText;

        String conversation = "[用户输入]\n" + userInput.trim() + "\n\n[助手回复]\n" + reply.trim();

        Msg sys = Msg.builder().role(MsgRole.SYSTEM).name("system").textContent(MemoryPrompts.DISTILL_PROMPT).build();
        Msg user = Msg.builder().role(MsgRole.USER).name("user").textContent(conversation).build();

        String facts = streamCollect(sys, user, "MemoryCurator.distill").trim();
        if (facts.isEmpty() || "无".equals(facts) || facts.equalsIgnoreCase("none")) {
            log.debug("本轮对话无值得记忆的事实");
            return;
        }
        contextFiles.appendTodayMemory(facts);
        log.info("已提炼 {} 字符事实追加到日流水账: {}",
                facts.length(), contextFiles.getDailyMemoryFile(java.time.LocalDate.now()));
    }

    // ==================== 阶段 2：日流水账合并到 MEMORY.md ====================

    /**
     * 异步检查并执行合并：日流水账文件数 ≥ 阈值时合并到 MEMORY.md。
     * 通过 CAS 防重入。
     */
    public Mono<Void> consolidateIfNeeded() {
        return Mono.fromRunnable(this::consolidateIfNeededSync)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("记忆合并失败（已静默忽略）: {}", e.getMessage());
                    consolidating.set(false);
                    return Mono.empty();
                })
                .then();
    }

    private void consolidateIfNeededSync() {
        List<Path> dailies = contextFiles.listDailyMemoryFiles();
        if (dailies.size() < CONSOLIDATE_THRESHOLD) {
            log.debug("日流水账数量 {} < 阈值 {}，跳过合并", dailies.size(), CONSOLIDATE_THRESHOLD);
            return;
        }
        if (!consolidating.compareAndSet(false, true)) {
            log.info("已有合并任务在执行，本次跳过");
            return;
        }
        try {
            log.info("开始合并 {} 个日流水账到 MEMORY.md", dailies.size());
            String currentMemory = contextFiles.readMemoryMd(Integer.MAX_VALUE);
            String dailiesContent = contextFiles.readDailyMemoriesCombined(dailies);

            String userContent = "## 已有长期记忆 (MEMORY.md)\n\n"
                    + (currentMemory.isBlank() ? "（暂无）" : currentMemory)
                    + "\n\n## 近期事实条目（按日期）\n\n"
                    + dailiesContent;

            Msg sys = Msg.builder().role(MsgRole.SYSTEM).name("system").textContent(MemoryPrompts.CONSOLIDATE_PROMPT).build();
            Msg user = Msg.builder().role(MsgRole.USER).name("user").textContent(userContent).build();

            String newMemory = streamCollect(sys, user, "MemoryCurator.consolidate").trim();
            if (newMemory.isEmpty()) {
                log.warn("合并模型输出空，保留原状");
                return;
            }
            contextFiles.writeMemoryMd(newMemory);
            contextFiles.deleteDailyMemoryFiles(dailies);
            log.info("已合并 {} 个日流水账到 MEMORY.md（新长度 {} 字符）", dailies.size(), newMemory.length());
        } finally {
            consolidating.set(false);
        }
    }

    // ==================== 内部辅助 ====================

    /** 与 PlanEvolver / GoalManager 一致的轻量模型流式收集 + token 簿记 */
    private String streamCollect(Msg sys, Msg user, String source) {
        StringBuilder sb = new StringBuilder();
        List<ChatResponse> responses = lightModel.stream(
                List.of(sys, user), List.of(), generateOptions
        ).collectList().block();
        if (responses == null) return "";
        for (ChatResponse resp : responses) {
            if (resp.getContent() == null) continue;
            for (ContentBlock block : resp.getContent()) {
                if (block instanceof TextBlock tb && tb.getText() != null) {
                    sb.append(tb.getText());
                }
            }
        }
        if (tokenTracker != null) {
            long[] usage = TokenTracker.extractUsage(responses);
            tokenTracker.recordModelUsage(source, usage[0], usage[1]);
        }
        return sb.toString();
    }
}
