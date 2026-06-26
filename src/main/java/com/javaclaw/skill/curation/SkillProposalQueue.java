package com.javaclaw.skill.curation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.config.DataManager;
import com.javaclaw.skill.SkillChangeRequest;
import com.javaclaw.skill.SkillManageTools;
import com.javaclaw.util.DebouncedPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 技能变更提案队列 —— suggest 模式的审阅闸门 + 双轨去重的统一拦截点。
 *
 * <p>职责：
 * <ul>
 *   <li><b>入队</b>（实现 {@link SkillManageTools.ProposalSink}）：智能体主动提案与
 *       SkillCurator 自动蒸馏两路都经此入口，按变更指纹统一去重</li>
 *   <li><b>去重</b>：同指纹在 {@code skill.curation.dedup.hours} 窗口内不重复入队；
 *       被用户拒绝的指纹在 {@code skill.curation.cooldown.days} 冷却期内不再提案</li>
 *   <li><b>审阅</b>：{@link #approve} 真正落盘（经 {@link SkillChangeRequest#apply()}）；
 *       {@link #reject} 记冷却指纹</li>
 * </ul>
 *
 * <p>持久化：工作区维度 {@code {workspace}/data/skill-proposals.json}（防抖落盘）；
 * 切换工作区时由外部调用 {@link #reload()}。监听器用于 UI 角标/列表增量刷新。</p>
 *
 * @author JavaClaw
 */
public final class SkillProposalQueue implements SkillManageTools.ProposalSink {

    private static final Logger log = LoggerFactory.getLogger(SkillProposalQueue.class);

    private static final String DATA_FILE = "skill-proposals.json";

    private static final SkillProposalQueue INSTANCE = new SkillProposalQueue();

    private final ObjectMapper mapper = new ObjectMapper();

    /** 全量提案（含终态，终态保留用于指纹冷却判定与审阅历史） */
    private final List<SkillProposal> proposals = new CopyOnWriteArrayList<>();

    private final DebouncedPersister persister =
            new DebouncedPersister("skill-proposals", Duration.ofSeconds(3), this::save);

    /** 待审提案变化监听器（UI 角标刷新），在 JavaFX 线程外回调，监听方自行 Platform.runLater */
    private volatile Runnable onPendingChanged;

    private SkillProposalQueue() {
        load();
    }

    public static SkillProposalQueue getInstance() {
        return INSTANCE;
    }

    public void setOnPendingChanged(Runnable listener) {
        this.onPendingChanged = listener;
    }

    // ==================== 入队（ProposalSink 实现） ====================

    @Override
    public String submit(SkillChangeRequest request) {
        if (request == null || request.action == null || request.skillName == null) {
            return null;
        }
        String fingerprint = request.fingerprint();
        long now = System.currentTimeMillis();
        long dedupWindowMs = Duration.ofHours(AgentConfig.getInstance().getSkillCurationDedupHours()).toMillis();
        long cooldownMs = Duration.ofDays(AgentConfig.getInstance().getSkillCurationCooldownDays()).toMillis();

        for (SkillProposal p : proposals) {
            if (p.request == null || !fingerprint.equals(p.request.fingerprint())) {
                continue;
            }
            // 待审中的同指纹：不重复入队
            if (p.status == SkillProposal.Status.PENDING) {
                log.debug("同指纹提案待审中，跳过入队: {}", fingerprint);
                return null;
            }
            // 被拒绝且在冷却期内：不再提案
            if (p.status == SkillProposal.Status.REJECTED && now - p.resolvedAt < cooldownMs) {
                log.debug("同指纹提案被拒冷却中，跳过入队: {}", fingerprint);
                return null;
            }
            // 近期已采纳的同指纹（去重窗口内）：内容已落盘，无需重复
            if (p.status == SkillProposal.Status.APPROVED && now - p.resolvedAt < dedupWindowMs) {
                log.debug("同指纹提案近期已采纳，跳过入队: {}", fingerprint);
                return null;
            }
        }

        SkillProposal proposal = new SkillProposal(
                UUID.randomUUID().toString().substring(0, 8), request, now);
        proposals.add(proposal);
        persister.request();
        notifyPendingChanged();
        log.info("技能变更提案入队: [{}] {} ({})", request.action, request.skillName, proposal.id);
        return proposal.id;
    }

    // ==================== 审阅 ====================

    /** 待审提案列表（按创建时间倒序，最新在前） */
    public List<SkillProposal> pending() {
        List<SkillProposal> result = new ArrayList<>();
        for (SkillProposal p : proposals) {
            if (p.status == SkillProposal.Status.PENDING) {
                result.add(p);
            }
        }
        result.sort(Comparator.comparingLong((SkillProposal p) -> p.createdAt).reversed());
        return result;
    }

    /** 待审提案数量（UI 角标） */
    public int pendingCount() {
        int count = 0;
        for (SkillProposal p : proposals) {
            if (p.status == SkillProposal.Status.PENDING) {
                count++;
            }
        }
        return count;
    }

    /**
     * 采纳提案：经 {@link SkillChangeRequest#apply()} 真正落盘。
     *
     * @return null 表示成功；否则返回失败原因（如 patch 的 old_string 已对不上当前正文）
     */
    public String approve(String proposalId) {
        SkillProposal proposal = find(proposalId);
        if (proposal == null || proposal.status != SkillProposal.Status.PENDING) {
            return "提案不存在或已处理";
        }
        String error = proposal.request.apply();
        if (error != null) {
            log.warn("提案采纳落盘失败: {} — {}", proposalId, error);
            return error;
        }
        proposal.status = SkillProposal.Status.APPROVED;
        proposal.resolvedAt = System.currentTimeMillis();
        persister.request();
        notifyPendingChanged();
        log.info("提案已采纳并落盘: [{}] {} ({})",
                proposal.request.action, proposal.request.skillName, proposalId);
        return null;
    }

    /** 拒绝提案：记冷却指纹，冷却期内同指纹不再入队 */
    public void reject(String proposalId) {
        SkillProposal proposal = find(proposalId);
        if (proposal == null || proposal.status != SkillProposal.Status.PENDING) {
            return;
        }
        proposal.status = SkillProposal.Status.REJECTED;
        proposal.resolvedAt = System.currentTimeMillis();
        persister.request();
        notifyPendingChanged();
        log.info("提案已拒绝（进入冷却）: [{}] {} ({})",
                proposal.request.action, proposal.request.skillName, proposalId);
    }

    private SkillProposal find(String proposalId) {
        for (SkillProposal p : proposals) {
            if (p.id.equals(proposalId)) {
                return p;
            }
        }
        return null;
    }

    private void notifyPendingChanged() {
        Runnable listener = onPendingChanged;
        if (listener != null) {
            try {
                listener.run();
            } catch (Exception e) {
                log.debug("待审提案变化回调异常（忽略）: {}", e.getMessage());
            }
        }
    }

    // ==================== 持久化 ====================

    /** 切换工作区后重新加载 */
    public synchronized void reload() {
        persister.flush();
        proposals.clear();
        load();
        notifyPendingChanged();
        log.info("技能提案队列已随工作区切换重载: {} 条（待审 {}）", proposals.size(), pendingCount());
    }

    public void flush() {
        persister.flush();
    }

    private synchronized void save() {
        try {
            File file = new File(DataManager.getInstance().getDataRoot().toFile(), DATA_FILE);
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, new ArrayList<>(proposals));
        } catch (Exception e) {
            log.warn("保存技能提案队列失败: {}", e.getMessage());
        }
    }

    private void load() {
        try {
            File file = new File(DataManager.getInstance().getDataRoot().toFile(), DATA_FILE);
            if (!file.exists()) {
                return;
            }
            SkillProposal[] loaded = mapper.readValue(file, SkillProposal[].class);
            proposals.addAll(List.of(loaded));
            log.info("已加载技能提案队列: {} 条（待审 {}）", proposals.size(), pendingCount());
        } catch (Exception e) {
            log.warn("加载技能提案队列失败", e);
        }
    }
}
