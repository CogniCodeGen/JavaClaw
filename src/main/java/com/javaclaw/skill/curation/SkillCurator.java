package com.javaclaw.skill.curation;

import com.javaclaw.agent.TokenTracker;
import com.javaclaw.agent.execution.ExecutionTrace;
import com.javaclaw.agent.model.ModelFactory;
import com.javaclaw.agent.model.ModelTier;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.prompt.SkillPrompts;
import com.javaclaw.skill.Skill;
import com.javaclaw.skill.SkillChangeRequest;
import com.javaclaw.skill.SkillManager;
import com.javaclaw.skill.SkillUsageTracker;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.util.JsonSchemaUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 技能蒸馏器 —— 自学习闭环的被动兜底路径（借鉴 hermes-agent「复杂任务后自主创建技能」）。
 *
 * <p>与 {@code MemoryCurator}（陈述性记忆：记住"是什么"）互补，本类沉淀<b>程序性记忆</b>
 * （记住"怎么做"）：在一轮对话 / 一个 SDD 托管任务结束后，用轻量模型从执行轨迹中蒸馏
 * 「是否有值得沉淀的工作流经验」，产出 create 新技能或 patch 既有技能的结构化提案。</p>
 *
 * <p>触发门槛（对齐 Hermes）：
 * <ul>
 *   <li>工具调用 ≥ {@code skill.evolution.min.tools}（默认 5）且成功收尾</li>
 *   <li>或存在「踩坑恢复」信号（失败后转成功的轨迹），不足 min.tools 也触发</li>
 * </ul>
 *
 * <p>三态分流：off 不蒸馏；suggest 提案入 {@link SkillProposalQueue} 待审；
 * auto 直接落盘 + Toast 通知（user-modified 技能强制降级为提案）。
 * 与智能体主动 skill_manage 路径在队列层按指纹统一去重。</p>
 *
 * <p>关键设计（照 MemoryCurator）：boundedElastic 异步、失败静默、CAS 防重、轻量模型控成本。</p>
 *
 * @author JavaClaw
 */
public class SkillCurator {

    private static final Logger log = LoggerFactory.getLogger(SkillCurator.class);

    /** 结构化蒸馏调用超时（秒） */
    private static final long STRUCTURED_TIMEOUT_SEC = 120;

    /** 参与蒸馏的对话/任务描述最大字符数（超过截尾） */
    private static final int MAX_CONTEXT_CHARS = 6000;

    private final ModelFactory modelFactory;
    private final TokenTracker tokenTracker;
    private final SkillProposalQueue proposalQueue;

    /** 获取交互端口（auto 模式 Toast 通知用；为 null 时静默跳过通知） */
    private final Supplier<UserInteractionPort> portSupplier;

    /** 蒸馏互斥：同时最多一个蒸馏在跑，避免连续轮次并发烧 token */
    private final AtomicBoolean distilling = new AtomicBoolean(false);

    public SkillCurator(ModelFactory modelFactory,
                        TokenTracker tokenTracker,
                        SkillProposalQueue proposalQueue,
                        Supplier<UserInteractionPort> portSupplier) {
        this.modelFactory = modelFactory;
        this.tokenTracker = tokenTracker;
        this.proposalQueue = proposalQueue;
        this.portSupplier = portSupplier != null ? portSupplier : () -> null;
    }

    // ==================== 入口 1：聊天轮蒸馏 ====================

    /**
     * 异步从一轮完整对话蒸馏技能。失败静默，不影响主流程。
     *
     * @param userInput   用户输入
     * @param replyText   助手回复
     * @param traces      本轮执行轨迹（ExecutionMonitor.getTraces() 快照）
     * @param successRate 滑窗成功率
     */
    public Mono<Void> distillFromChatTurn(String userInput, String replyText,
                                          List<ExecutionTrace> traces, double successRate) {
        if (!shouldDistill(traces, successRate)) {
            return Mono.empty();
        }
        String context = "[用户请求]\n" + truncate(nz(userInput))
                + "\n\n[最终回复摘要]\n" + truncate(nz(replyText));
        return distillAsync(context, traces);
    }

    // ==================== 入口 2：SDD 任务蒸馏 ====================

    /**
     * 异步从一个完成的 SDD 托管任务蒸馏技能。SDD 任务天然是「复杂任务成功」的最强信号，
     * 不再按 min.tools 设门槛（轨迹可为空，凭任务描述与产出蒸馏）。
     *
     * @param title       任务标题
     * @param description 任务描述
     * @param outcomeSummary 任务产出摘要（如 proposal 的 why/whatChanges + 验收结论）
     */
    public Mono<Void> distillFromSddTask(String title, String description, String outcomeSummary) {
        String context = "[已完成的托管任务]\n标题：" + nz(title)
                + "\n描述：" + truncate(nz(description))
                + "\n\n[任务产出摘要]\n" + truncate(nz(outcomeSummary));
        return distillAsync(context, List.of());
    }

    // ==================== 触发门槛 ====================

    /**
     * 聊天轮蒸馏门槛：off 不蒸馏；工具调用达阈值且成功收尾；
     * 或存在「踩坑恢复」信号（有失败轨迹但最终成功率达标）——这正是 Hermes
     * 「踩坑后找到可行路径」最值得沉淀的时机。
     */
    private boolean shouldDistill(List<ExecutionTrace> traces, double successRate) {
        AgentConfig config = AgentConfig.getInstance();
        if ("off".equals(config.getSkillEvolutionMode())) {
            return false;
        }
        if (traces == null || traces.isEmpty()) {
            return false;
        }
        boolean succeeded = successRate >= config.getSkillEvolutionSuccessThreshold();
        if (!succeeded) {
            return false;
        }
        if (traces.size() >= config.getSkillEvolutionMinTools()) {
            return true;
        }
        // 踩坑恢复：存在失败轨迹但整体成功收尾，即使不足 min.tools 也值得蒸馏
        return traces.stream().anyMatch(t -> !t.isSuccess());
    }

    // ==================== 蒸馏执行 ====================

    private Mono<Void> distillAsync(String context, List<ExecutionTrace> traces) {
        if ("off".equals(AgentConfig.getInstance().getSkillEvolutionMode())) {
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> distillSync(context, traces))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("技能蒸馏失败（已静默忽略）: {}", e.getMessage());
                    distilling.set(false);
                    return Mono.empty();
                })
                .then();
    }

    private void distillSync(String context, List<ExecutionTrace> traces) {
        if (!distilling.compareAndSet(false, true)) {
            log.debug("已有技能蒸馏在执行，本次跳过");
            return;
        }
        try {
            SkillCurationDraft draft = callStructured(buildUserPrompt(context, traces));
            if (draft == null || !draft.worthLearning
                    || draft.action == null || "none".equalsIgnoreCase(draft.action)) {
                log.debug("本次经历无值得沉淀的技能经验");
                return;
            }
            dispatch(draft);
        } finally {
            distilling.set(false);
        }
    }

    /** 把蒸馏结果按三态分流：suggest 入队；auto 直接落盘 + Toast（user-modified 降级入队） */
    private void dispatch(SkillCurationDraft draft) {
        SkillChangeRequest request = toRequest(draft);
        if (request == null) {
            return;
        }

        String mode = AgentConfig.getInstance().getSkillEvolutionMode();
        boolean targetUserModified = false;
        if ("patch".equals(request.action)) {
            Skill target = SkillManager.getInstance().getSkillByName(request.skillName);
            if (target == null) {
                log.info("蒸馏产出 patch 但目标技能「{}」不存在，丢弃", request.skillName);
                return;
            }
            targetUserModified = target.isUserModified();
            request.userModifiedWarning = targetUserModified;
        }

        if ("auto".equals(mode) && !targetUserModified) {
            String error = request.apply();
            if (error != null) {
                log.info("自动沉淀技能失败（转提案待审）: {}", error);
                proposalQueue.submit(request);
                return;
            }
            log.info("已自动沉淀技能: [{}] {}", request.action, request.skillName);
            toast("技能自学习",
                    ("create".equals(request.action) ? "已沉淀新技能「" : "已更新技能「")
                            + request.skillName + "」：" + nz(request.reason));
        } else {
            // suggest 模式，或 auto 模式下 user-modified 保护降级
            String proposalId = proposalQueue.submit(request);
            if (proposalId != null) {
                log.info("蒸馏提案已入队待审: [{}] {} ({})", request.action, request.skillName, proposalId);
            }
        }
    }

    private SkillChangeRequest toRequest(SkillCurationDraft draft) {
        SkillChangeRequest request = new SkillChangeRequest();
        request.skillName = nz(draft.skillName).strip();
        request.reason = nz(draft.reason);
        if (request.skillName.isEmpty()) {
            return null;
        }
        if ("create".equalsIgnoreCase(draft.action)) {
            if (nz(draft.content).isBlank()) {
                return null;
            }
            // 蒸馏可能对既有技能产出 create：转为入队 patch 不可行（无 old/new），直接丢弃避免覆盖
            if (SkillManager.getInstance().getSkillByName(request.skillName) != null) {
                log.info("蒸馏产出 create 但技能「{}」已存在，丢弃", request.skillName);
                return null;
            }
            request.action = "create";
            request.description = nz(draft.description);
            request.category = nz(draft.category);
            request.tags = draft.tags != null ? draft.tags : new ArrayList<>();
            request.content = draft.content;
            return request;
        }
        if ("patch".equalsIgnoreCase(draft.action)) {
            if (nz(draft.oldString).isEmpty()) {
                return null;
            }
            request.action = "patch";
            request.oldString = draft.oldString;
            request.newString = nz(draft.newString);
            return request;
        }
        return null;
    }

    // ==================== 结构化模型调用（照 AgentScopeCriticJudge 范式） ====================

    private SkillCurationDraft callStructured(String userPrompt) {
        ReActAgent curator = ReActAgent.builder()
                .name("技能蒸馏器")
                .sysPrompt(SkillPrompts.CURATION_PROMPT)
                .model(modelFactory.createStructuredChatModel(ModelTier.LIGHT))
                .maxIters(3)
                .hooks(List.of(new com.javaclaw.task.TaskTokenHook((in, out) -> {
                    if (tokenTracker != null) {
                        tokenTracker.recordModelUsage("SkillCurator", in, out);
                    }
                })))
                .build();

        Msg userMsg = Msg.builder().role(MsgRole.USER).name("user").textContent(userPrompt).build();
        AtomicReference<Msg> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Disposable d = curator.call(List.of(userMsg), SkillCurationDraft.class)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(ref::set, e -> { err.set(e); latch.countDown(); }, latch::countDown);
        try {
            if (!latch.await(STRUCTURED_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                d.dispose();
                log.warn("技能蒸馏调用超时（{}s）", STRUCTURED_TIMEOUT_SEC);
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            d.dispose();
            return null;
        }
        if (err.get() != null) {
            log.warn("技能蒸馏调用异常: {}", err.get().getMessage());
            return null;
        }
        return extract(ref.get());
    }

    private static SkillCurationDraft extract(Msg msg) {
        if (msg == null || msg.getMetadata() == null) {
            return null;
        }
        Object raw = msg.getMetadata().get("_structured_output");
        if (raw == null) {
            return null;
        }
        try {
            return JsonSchemaUtils.convertToObject(raw, SkillCurationDraft.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 提示词构建 ====================

    private String buildUserPrompt(String context, List<ExecutionTrace> traces) {
        StringBuilder sb = new StringBuilder();
        sb.append(context);

        if (traces != null && !traces.isEmpty()) {
            sb.append("\n\n[执行轨迹]（共 ").append(traces.size()).append(" 次工具调用）\n");
            int idx = 0;
            for (ExecutionTrace trace : traces) {
                sb.append(++idx).append(". ").append(trace.getToolName())
                        .append(trace.isSuccess() ? " [成功]" : " [失败:" + trace.getFailureKind() + "]")
                        .append("\n");
            }
        }

        // 现有技能目录：供模型判断是 create 新技能还是 patch 既有技能
        List<Skill> enabled = SkillManager.getInstance().getEnabledSkills();
        sb.append("\n\n[现有技能目录]\n");
        if (enabled.isEmpty()) {
            sb.append("（无）\n");
        } else {
            for (Skill skill : enabled) {
                sb.append("- ").append(skill.getName()).append("：").append(nz(skill.getDescription())).append("\n");
            }
        }

        // 低成功率技能：引导优先 patch 修补（使用统计反哺）
        List<String> lowSuccess = SkillUsageTracker.getInstance().lowSuccessCandidates();
        if (!lowSuccess.isEmpty()) {
            sb.append("\n[低成功率技能]（这些技能被使用后任务常失败，若本次经验与之相关，优先产 patch 修正它们）\n");
            for (String name : lowSuccess) {
                sb.append("- ").append(name).append("\n");
            }
        }
        return sb.toString();
    }

    // ==================== 内部辅助 ====================

    private void toast(String title, String message) {
        try {
            UserInteractionPort port = portSupplier.get();
            if (port != null) {
                port.notify(new ToastRequest(title, message));
            }
        } catch (Exception e) {
            log.debug("技能沉淀 Toast 通知失败（忽略）: {}", e.getMessage());
        }
    }

    private static String truncate(String text) {
        return text.length() > MAX_CONTEXT_CHARS
                ? text.substring(0, MAX_CONTEXT_CHARS) + "...(截断)"
                : text;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
