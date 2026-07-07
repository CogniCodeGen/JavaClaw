package com.javaclaw.skill;

import com.javaclaw.config.AgentConfig;
import com.javaclaw.config.AppDatabase;
import com.javaclaw.util.DebouncedPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 技能使用统计 —— 数据驱动技能进化的依据。
 *
 * <p>按技能维度追踪三类信号：
 * <ul>
 *   <li>路由命中（ToolRouter 把技能选入本轮注入集）</li>
 *   <li>按需读取（模型调用 skill_read 拉取正文）</li>
 *   <li>轮次成败（注入该技能的对话轮结束时的滑窗成功率判定）</li>
 * </ul>
 * 低成功率技能经 {@link #lowSuccessCandidates()} 反哺 SkillCurator，引导模型优先产 patch 修补
 * 而非新建技能。</p>
 *
 * <p>持久化：全局 H2 {@code skill_usage} 表，按 {@code workspace_id} 隔离（技能本体是全局的，
 * 但同一技能在不同项目的命中率与成功率不同）。写入经 {@link DebouncedPersister} 防抖；
 * 切换工作区时由外部调用 {@link #reload()}。</p>
 *
 * @author JavaClaw
 */
public final class SkillUsageTracker {

    private static final Logger log = LoggerFactory.getLogger(SkillUsageTracker.class);

    private static final SkillUsageTracker INSTANCE = new SkillUsageTracker();

    /** 技能名 → 统计；技能以 name（而非目录 id）为键，与路由/注入层使用的标识一致 */
    private final Map<String, SkillUsageStat> stats = new ConcurrentHashMap<>();

    private final DebouncedPersister persister =
            new DebouncedPersister("skill-usage", Duration.ofSeconds(5), this::save);

    private SkillUsageTracker() {
        load();
    }

    public static SkillUsageTracker getInstance() {
        return INSTANCE;
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

    /** 记录路由命中（ToolRouter 把该技能选入本轮注入集） */
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
        AgentConfig config = AgentConfig.getInstance();
        double threshold = config.getSkillUsageLowSuccessThreshold();
        int minSamples = config.getSkillUsageLowSuccessMinSamples();
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

    /** 切换工作区后重新加载 H2 中该工作区的统计 */
    public synchronized void reload() {
        persister.flush();
        stats.clear();
        load();
        log.info("技能使用统计已随工作区切换重载: {} 条", stats.size());
    }

    /** 关键节点立即落盘（如应用退出） */
    public void flush() {
        persister.flush();
    }

    /** 应用退出：落盘并停掉防抖调度线程 */
    public void shutdown() {
        persister.flush();
        persister.shutdown();
    }

    private synchronized void save() {
        String insert = """
                INSERT INTO skill_usage(
                    workspace_id, skill_name, route_hits, reads, turn_success, turn_fail, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """;
        try (Connection c = AppDatabase.getConnection();
             PreparedStatement del = c.prepareStatement("DELETE FROM skill_usage WHERE workspace_id = ?");
             PreparedStatement ps = c.prepareStatement(insert)) {
            c.setAutoCommit(false);
            String workspaceId = AppDatabase.currentWorkspaceId();
            del.setString(1, workspaceId);
            del.executeUpdate();
            for (Map.Entry<String, SkillUsageStat> entry : stats.entrySet()) {
                SkillUsageStat stat = entry.getValue();
                ps.setString(1, workspaceId);
                ps.setString(2, entry.getKey());
                ps.setLong(3, stat.routeHits.get());
                ps.setLong(4, stat.reads.get());
                ps.setLong(5, stat.turnSuccess.get());
                ps.setLong(6, stat.turnFail.get());
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
        } catch (Exception e) {
            log.warn("保存技能使用统计失败: {}", e.getMessage());
        }
    }

    private void load() {
        try {
            String sql = """
                    SELECT skill_name, route_hits, reads, turn_success, turn_fail
                    FROM skill_usage
                    WHERE workspace_id = ?
                    """;
            try (Connection c = AppDatabase.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, AppDatabase.currentWorkspaceId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        SkillUsageStat stat = new SkillUsageStat();
                        stat.routeHits.set(rs.getLong("route_hits"));
                        stat.reads.set(rs.getLong("reads"));
                        stat.turnSuccess.set(rs.getLong("turn_success"));
                        stat.turnFail.set(rs.getLong("turn_fail"));
                        stats.put(rs.getString("skill_name"), stat);
                    }
                }
            }
            log.info("已从 H2 加载技能使用统计: {} 条", stats.size());
        } catch (Exception e) {
            log.warn("加载技能使用统计失败", e);
        }
    }

    /** 全部统计的只读快照（UI 列表用） */
    public Map<String, SkillUsageStat> snapshot() {
        return new HashMap<>(stats);
    }
}
