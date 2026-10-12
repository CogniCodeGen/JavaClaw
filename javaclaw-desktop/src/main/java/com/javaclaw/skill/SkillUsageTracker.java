package com.javaclaw.skill;

import com.javaclaw.config.AgentConfig;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSubmitter;
import com.javaclaw.util.DebouncedPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 技能使用统计 —— 数据驱动技能进化的依据。
 *
 * <p>按技能维度追踪三类信号：
 * <ul>
 *   <li>编译命中（AgentCompiler 把技能选入本轮 ExecutionPlan）</li>
 *   <li>按需读取（模型调用 skill_read 拉取正文）</li>
 *   <li>轮次成败（注入该技能的对话轮结束时的滑窗成功率判定）</li>
 * </ul>
 * 低成功率技能经 {@link #lowSuccessCandidates()} 反哺 SkillCurator，引导模型优先产 patch 修补
 * 而非新建技能。</p>
 *
 * <p>持久化：全局 H2 {@code skill_usage} 表，按 {@code workspace_id} 隔离（技能本体是全局的，
 * 但同一技能在不同项目的命中率与成功率不同）。写入经 {@link DebouncedPersister} 防抖；
 * 工作区切换时关闭旧实例并由子 Context 创建新实例。</p>
 *
 * @author JavaClaw
 */
public final class SkillUsageTracker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SkillUsageTracker.class);

    /** 技能名 → 统计；技能以 name（而非目录 id）为键，与路由/注入层使用的标识一致 */
    private final Map<String, SkillUsageStat> stats = new ConcurrentHashMap<>();

    private final String workspaceId;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AgentConfig settings;
    private final DebouncedPersister persister;
    private final AtomicBoolean closed = new AtomicBoolean();

    public SkillUsageTracker(
            String workspaceId,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            AgentConfig settings,
            ManagedTaskExecutor scheduler,
            TaskSubmitter tasks) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId 不能为空");
        }
        this.workspaceId = workspaceId;
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.settings = Objects.requireNonNull(settings, "settings");
        this.persister = new DebouncedPersister(
                "skill-usage-" + workspaceId, Duration.ofSeconds(5),
                Objects.requireNonNull(scheduler, "scheduler"),
                Objects.requireNonNull(tasks, "tasks"), this::save);
        load();
    }

    /**
     * 单技能统计模型（Jackson 序列化友好）
     */
    public static class SkillUsageStat {
        /** 路由命中次数（多线程并发埋点，须原子累加；Jackson 对 AtomicLong 按数值序列化，JSON 格式不变） */
        public AtomicLong routeHits = new AtomicLong();
        /** skill_read 读取次数 */
        public AtomicLong reads = new AtomicLong();
        /** 注入后轮次成功数 */
        public AtomicLong turnSuccess = new AtomicLong();
        /** 注入后轮次失败数 */
        public AtomicLong turnFail = new AtomicLong();

        /** 轮次成功率；无样本时返回 -1（不可判定） */
        public double successRate() {
            long total = turnSuccess.get() + turnFail.get();
            return total == 0 ? -1 : (double) turnSuccess.get() / total;
        }

        /** 轮次样本数 */
        public long samples() {
            return turnSuccess.get() + turnFail.get();
        }
    }

    // ==================== 埋点入口 ====================

    /** 记录编译命中（AgentCompiler 把该技能选入本轮 ExecutionPlan）。 */
    public void recordRouteHit(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return;
        }
        statOf(skillName).routeHits.incrementAndGet();
        persister.request();
    }

    /** 记录 skill_read 按需读取 */
    public void recordSkillRead(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return;
        }
        statOf(skillName).reads.incrementAndGet();
        persister.request();
    }

    /**
     * 记录注入技能的轮次成败（对话轮结束时调用，injectedSkills 为本轮注入的技能名集合）
     */
    public void recordTurnOutcome(List<String> injectedSkills, boolean success) {
        if (injectedSkills == null || injectedSkills.isEmpty()) {
            return;
        }
        for (String name : injectedSkills) {
            if (name == null || name.isBlank()) {
                continue;
            }
            SkillUsageStat stat = statOf(name);
            if (success) {
                stat.turnSuccess.incrementAndGet();
            } else {
                stat.turnFail.incrementAndGet();
            }
        }
        persister.request();
    }

    // ==================== 查询 ====================

    /** 获取（或惰性创建）某技能的统计 */
    public SkillUsageStat statOf(String skillName) {
        return stats.computeIfAbsent(skillName.strip(), k -> new SkillUsageStat());
    }

    /** 获取某技能的统计（只读，不存在时返回 null），供 UI 展示 */
    public SkillUsageStat peek(String skillName) {
        return skillName == null ? null : stats.get(skillName.strip());
    }

    /**
     * 低成功率技能候选（样本充足且成功率低于阈值），反哺 SkillCurator 优先产 patch。
     * 阈值与最小样本数从 AgentConfig 读取。
     */
    public List<String> lowSuccessCandidates() {
        double threshold = settings.getSkillUsageLowSuccessThreshold();
        int minSamples = settings.getSkillUsageLowSuccessMinSamples();
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, SkillUsageStat> entry : stats.entrySet()) {
            SkillUsageStat stat = entry.getValue();
            double rate = stat.successRate();
            if (stat.samples() >= minSamples && rate >= 0 && rate < threshold) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    // ==================== 持久化 ====================

    /** 关键节点立即落盘（如应用退出） */
    public void flush() {
        if (!closed.get()) persister.flush();
    }

    /** 落盘并取消未执行的防抖任务；可重复调用。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        persister.flush();
        persister.shutdown();
    }

    private synchronized void save() {
        String insert = """
                INSERT INTO skill_usage(
                    workspace_id, skill_name, route_hits, reads, turn_success, turn_fail, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """;
        Map<String, SkillUsageStat> snapshot = new HashMap<>(stats);
        try {
            transactions.executeWithoutResult(status -> {
                jdbc.update("DELETE FROM skill_usage WHERE workspace_id = ?", workspaceId);
                List<Object[]> rows = new ArrayList<>(snapshot.size());
                for (Map.Entry<String, SkillUsageStat> entry : snapshot.entrySet()) {
                    SkillUsageStat stat = entry.getValue();
                    rows.add(new Object[]{workspaceId, entry.getKey(), stat.routeHits.get(),
                            stat.reads.get(), stat.turnSuccess.get(), stat.turnFail.get()});
                }
                if (!rows.isEmpty()) jdbc.batchUpdate(insert, rows);
            });
        } catch (DataAccessException failure) {
            log.warn("保存技能使用统计失败: {}", failure.getMessage());
        }
    }

    private void load() {
        try {
            String sql = """
                    SELECT skill_name, route_hits, reads, turn_success, turn_fail
                    FROM skill_usage
                    WHERE workspace_id = ?
                    """;
            jdbc.query(sql, rs -> {
                SkillUsageStat stat = new SkillUsageStat();
                stat.routeHits.set(rs.getLong("route_hits"));
                stat.reads.set(rs.getLong("reads"));
                stat.turnSuccess.set(rs.getLong("turn_success"));
                stat.turnFail.set(rs.getLong("turn_fail"));
                stats.put(rs.getString("skill_name"), stat);
            }, workspaceId);
            log.info("已从 H2 加载技能使用统计: {} 条", stats.size());
        } catch (DataAccessException failure) {
            log.warn("加载技能使用统计失败", failure);
        }
    }

    /** 全部统计的只读快照（UI 列表用） */
    public Map<String, SkillUsageStat> snapshot() {
        return new HashMap<>(stats);
    }
}
