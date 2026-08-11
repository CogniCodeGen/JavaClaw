package com.javaclaw.skill.curation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSubmitter;
import com.javaclaw.platform.json.JsonCodec;
import com.javaclaw.skill.SkillChangeRequest;
import com.javaclaw.skill.SkillManageTools;
import com.javaclaw.skill.SkillManager;
import com.javaclaw.util.DebouncedPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 技能变更提案队列 —— suggest 模式的审阅闸门 + 双轨去重的统一拦截点。
 *
 * <p>职责：
 * <ul>
 *   <li><b>入队</b>（实现 {@link SkillManageTools.ProposalSink}）：智能体主动提案与
 *       SkillCurator 自动蒸馏两路都经此入口，按变更指纹统一去重</li>
 *   <li><b>去重</b>：同指纹在 {@code skill.curation.dedup.hours} 窗口内不重复入队；
 *       被用户拒绝的指纹在 {@code skill.curation.cooldown.days} 冷却期内不再提案</li>
 *   <li><b>审阅</b>：{@link #approve} 真正落盘（经 {@link SkillChangeRequest#apply(SkillManager)}）；
 *       {@link #reject} 记冷却指纹</li>
 * </ul>
 *
 * <p>持久化：全局 H2 {@code skill_proposals} 表，按 {@code workspace_id} 隔离（防抖落盘）；
 * 工作区切换时关闭旧实例并由子 Context 创建新实例。监听器用于 UI 角标/列表增量刷新。</p>
 *
 * @author JavaClaw
 */
public final class SkillProposalQueue implements SkillManageTools.ProposalSink, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SkillProposalQueue.class);

    private final String workspaceId;
    private final SkillManager skills;
    private final AgentConfig settings;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JsonCodec json;

    /** 全量提案（含终态，终态保留用于指纹冷却判定与审阅历史） */
    private final List<SkillProposal> proposals = new CopyOnWriteArrayList<>();

    private final DebouncedPersister persister;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 待审提案变化监听器；回调线程不固定，订阅方负责切换到自己的展示线程。 */
    private final List<Runnable> pendingChangedListeners = new CopyOnWriteArrayList<>();

    public SkillProposalQueue(
            String workspaceId,
            SkillManager skills,
            AgentConfig settings,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            JsonCodec json,
            ManagedTaskExecutor scheduler,
            TaskSubmitter tasks) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId 不能为空");
        }
        this.workspaceId = workspaceId;
        this.skills = Objects.requireNonNull(skills, "skills");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.json = Objects.requireNonNull(json, "json");
        this.persister = new DebouncedPersister(
                "skill-proposals-" + workspaceId, Duration.ofSeconds(3),
                Objects.requireNonNull(scheduler, "scheduler"),
                Objects.requireNonNull(tasks, "tasks"), this::save);
        load();
    }

    /**
     * 订阅待审提案变化。
     *
     * <p>句柄关闭幂等，且不会影响其他窗口或工作区订阅。监听器可能从任意线程执行。</p>
     */
    public AutoCloseable addPendingChangedListener(Runnable listener) {
        Runnable checked = java.util.Objects.requireNonNull(listener, "listener");
        pendingChangedListeners.add(checked);
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) pendingChangedListeners.remove(checked);
        };
    }

    // ==================== 入队（ProposalSink 实现） ====================

    @Override
    public String submit(SkillChangeRequest request) {
        if (request == null || request.action == null || request.skillName == null) {
            return null;
        }
        String fingerprint = request.fingerprint();
        long now = System.currentTimeMillis();
        long dedupWindowMs = Duration.ofHours(settings.getSkillCurationDedupHours()).toMillis();
        long cooldownMs = Duration.ofDays(settings.getSkillCurationCooldownDays()).toMillis();

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
     * 采纳提案：经 {@link SkillChangeRequest#apply(SkillManager)} 真正落盘。
     *
     * @return null 表示成功；否则返回失败原因（如 patch 的 old_string 已对不上当前正文）
     */
    public String approve(String proposalId) {
        SkillProposal proposal = find(proposalId);
        if (proposal == null || proposal.status != SkillProposal.Status.PENDING) {
            return "提案不存在或已处理";
        }
        String error = proposal.request.apply(skills);
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
        for (Runnable listener : pendingChangedListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                log.debug("待审提案订阅回调异常（忽略）: {}", e.getMessage());
            }
        }
    }

    // ==================== 持久化 ====================

    public void flush() {
        if (!closed.get()) persister.flush();
    }

    /** 落盘并取消未执行的防抖任务；可重复调用。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        persister.flush();
        persister.shutdown();
        pendingChangedListeners.clear();
    }

    private synchronized void save() {
        String insert = """
                INSERT INTO skill_proposals(
                    workspace_id, id, request_json, created_at, status, resolved_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """;
        List<Object[]> rows = new ArrayList<>(proposals.size());
        try {
            for (SkillProposal p : proposals) {
                rows.add(new Object[]{workspaceId, p.id, json.encode(p.request),
                        p.createdAt, p.status.name(), p.resolvedAt});
            }
        } catch (JsonProcessingException failure) {
            log.warn("序列化技能提案失败: {}", failure.getMessage());
            return;
        }
        try {
            transactions.executeWithoutResult(status -> {
                jdbc.update("DELETE FROM skill_proposals WHERE workspace_id = ?", workspaceId);
                if (!rows.isEmpty()) jdbc.batchUpdate(insert, rows);
            });
        } catch (DataAccessException failure) {
            log.warn("保存技能提案队列失败: {}", failure.getMessage());
        }
    }

    private void load() {
        try {
            String sql = """
                    SELECT id, request_json, created_at, status, resolved_at
                    FROM skill_proposals
                    WHERE workspace_id = ?
                    ORDER BY created_at
                    """;
            List<SkillProposal> loaded = jdbc.query(sql, (rs, rowNumber) -> {
                SkillProposal proposal = new SkillProposal();
                proposal.id = rs.getString("id");
                try {
                    proposal.request = json.decode(
                            rs.getString("request_json"), SkillChangeRequest.class);
                } catch (JsonProcessingException failure) {
                    throw new IllegalStateException("技能提案 JSON 损坏: " + proposal.id, failure);
                }
                proposal.createdAt = rs.getLong("created_at");
                proposal.status = SkillProposal.Status.valueOf(rs.getString("status"));
                proposal.resolvedAt = rs.getLong("resolved_at");
                return proposal;
            }, workspaceId);
            proposals.addAll(loaded);
            log.info("已从 H2 加载技能提案队列: {} 条（待审 {}）", proposals.size(), pendingCount());
        } catch (DataAccessException | IllegalStateException failure) {
            log.warn("加载技能提案队列失败", failure);
        }
    }
}
