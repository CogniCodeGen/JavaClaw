package com.javaclaw.builtin.contracts;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.ExecutionOverrides;

/** Memory 的增量契约；旧 Memory DTO 保持不变，有效性和学习生命周期单独版本化。 */
public final class MemoryV3Contracts {
    private MemoryV3Contracts() {}

    /** 当前语义状态；被替代记录保留历史但不得进入模型检索。 */
    public enum SemanticState {
        ACTIVE,
        SUPERSEDED
    }

    /** 人工冲突决议类型。 */
    public enum Resolution {
        KEEP_EXISTING,
        REPLACE,
        MERGE,
        COEXIST
    }

    /** 冲突审查状态。 */
    public enum ConflictState {
        PENDING,
        RESOLVED,
        REJECTED
    }

    /**
     * 有效性 sidecar，时间为闭开区间，自然语言条件仅展示不能自动匹配。
     *
     * @param memoryId 关联记忆
     * @param revision sidecar CAS 版本，无 sidecar 时为零
     * @param state 当前语义状态
     * @param validFrom 起始时间，空为无下界
     * @param validUntil 结束时间，空为无上界
     * @param condition 自然语言条件，非空时禁止自动检索注入
     * @param replacements 已确认替代关系
     */
    public record Effectivity(
            String memoryId,
            long revision,
            SemanticState state,
            Optional<Instant> validFrom,
            Optional<Instant> validUntil,
            String condition,
            Set<String> replacements) {
        /** 校验时间与版本并冻结关系集合。 */
        public Effectivity {
            memoryId = ContractValidation.text(memoryId, "memoryId");
            Objects.requireNonNull(state, "state");
            validFrom = Objects.requireNonNull(validFrom, "validFrom");
            validUntil = Objects.requireNonNull(validUntil, "validUntil");
            condition = Objects.requireNonNull(condition, "condition").strip();
            replacements = Set.copyOf(replacements);
            if (revision < 0
                    || (validFrom.isPresent()
                            && validUntil.isPresent()
                            && !validFrom.orElseThrow().isBefore(validUntil.orElseThrow()))) {
                throw new IllegalArgumentException("Memory effectivity interval or revision is invalid");
            }
        }

        /** @return 是否完全无条件，可安全供旧接口返回 */
        public boolean unconditional() {
            return validFrom.isEmpty() && validUntil.isEmpty() && condition.isEmpty();
        }

        /**
         * 检查指定时间是否可自动注入。
         *
         * @param now 服务器当前时间
         * @return 处于活动时间区间且没有无法判定的条件
         */
        public boolean effectiveAt(Instant now) {
            return state == SemanticState.ACTIVE
                    && condition.isEmpty()
                    && validFrom.map(value -> !now.isBefore(value)).orElse(true)
                    && validUntil.map(now::isBefore).orElse(true);
        }

        /**
         * 为旧记录创建无条件活动视图。
         *
         * @param memoryId 记忆标识
         * @return 未持久化的兼容 sidecar
         */
        public static Effectivity legacy(String memoryId) {
            return new Effectivity(memoryId, 0, SemanticState.ACTIVE, Optional.empty(), Optional.empty(), "", Set.of());
        }
    }

    /**
     * 检索结果包含正文与明确有效条件，防止模型将限时事实解释为永久事实。
     *
     * @param memory 已确认正文
     * @param effectivity 当前有效性
     */
    public record SearchMatch(MemoryContracts.Memory memory, Effectivity effectivity) {
        /** 校验记录对应关系。 */
        public SearchMatch {
            Objects.requireNonNull(memory, "memory");
            Objects.requireNonNull(effectivity, "effectivity");
            if (!memory.id().equals(effectivity.memoryId())) {
                throw new IllegalArgumentException("Memory effectivity owner differs");
            }
        }
    }

    /**
     * v2 检索响应。
     *
     * @param matches 当前可注入记忆
     * @param evaluatedAt 服务器判断时间
     * @param memoryRevision Workspace 语义版本
     */
    public record SearchResult(List<SearchMatch> matches, Instant evaluatedAt, long memoryRevision) {
        /** 冻结匹配项。 */
        public SearchResult {
            matches = List.copyOf(matches);
            Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        }
    }

    /**
     * 显式编辑有效性；请求 expectedRevision 固定目标 Memory 当前版本。
     *
     * @param id 目标记忆
     * @param validFrom 可选起始时间
     * @param validUntil 可选结束时间
     * @param condition 仅人工理解的条件
     */
    public record EffectivityUpdate(
            String id, Optional<Instant> validFrom, Optional<Instant> validUntil, String condition) {
        /** 复用有效性区间校验。 */
        public EffectivityUpdate {
            new Effectivity(id, 0, SemanticState.ACTIVE, validFrom, validUntil, condition, Set.of());
        }
    }

    /**
     * 学习定义；频率与启停由绑定 Schedule 唯一拥有。
     *
     * @param id 学习定义标识
     * @param revision 精确配置版本
     * @param name 显示名称
     * @param enabled 是否用户明确启用学习
     * @param execution 独立执行选择，实际冻结强制收窄预算与空工具目录
     * @param initialSince 首次启用时固定的最近三十天时间下界
     * @param updatedAt 更新时间
     */
    public record LearningDefinition(
            String id,
            long revision,
            String name,
            boolean enabled,
            ExecutionOverrides execution,
            Instant initialSince,
            Instant updatedAt)
            implements VersionedExtensionDocument {
        /** 校验定义和固定回扫边界。 */
        public LearningDefinition {
            id = ContractValidation.text(id, "id");
            revision = ContractValidation.revision(revision);
            name = ContractValidation.text(name, "name");
            Objects.requireNonNull(execution, "execution");
            Objects.requireNonNull(initialSince, "initialSince");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    /**
     * 学习配置保存请求；首次启用必须由用户明确提交。
     *
     * @param enabled 是否启用
     * @param execution 执行选择
     * @param reschedule 是否明确重新安排已删除的调度
     */
    public record LearningSave(boolean enabled, ExecutionOverrides execution, boolean reschedule) {
        /** 校验执行选择。 */
        public LearningSave {
            Objects.requireNonNull(execution, "execution");
        }
    }

    /**
     * 精确人工冲突决议，不从模型文本解释控制动作。
     *
     * @param id 冲突标识
     * @param resolution 决议
     * @param expectedMemoryRevisions 当前所有冲突对象的精确版本
     * @param expectedMemoryRevision 编辑器快照的 Workspace Memory head，防不同记录 ID 并发写入
     * @param content 合并或替换后正文
     * @param validFrom 新记忆起始时间
     * @param validUntil 新记忆结束时间
     * @param condition 无法自动判断的条件
     */
    public record ConflictDecision(
            String id,
            Resolution resolution,
            java.util.Map<String, Long> expectedMemoryRevisions,
            long expectedMemoryRevision,
            String content,
            Optional<Instant> validFrom,
            Optional<Instant> validUntil,
            String condition) {
        /** 校验决议和精确版本。 */
        public ConflictDecision {
            id = ContractValidation.text(id, "id");
            Objects.requireNonNull(resolution, "resolution");
            expectedMemoryRevisions = java.util.Map.copyOf(expectedMemoryRevisions);
            if (expectedMemoryRevision < 0) {
                throw new IllegalArgumentException("expected Memory head must not be negative");
            }
            content = Objects.requireNonNull(content, "content");
            new Effectivity(id, 0, SemanticState.ACTIVE, validFrom, validUntil, condition, Set.of());
        }
    }

    /**
     * 待人工处理的候选及相冲突记忆。
     *
     * @param id 冲突标识
     * @param revision 冲突版本
     * @param proposalId 提案标识
     * @param memoryIds 当前冲突记忆
     * @param fingerprint 防重复审查摘要
     * @param state 当前状态
     * @param updatedAt 更新时间
     */
    public record Conflict(
            String id,
            long revision,
            String proposalId,
            Set<String> memoryIds,
            String fingerprint,
            ConflictState state,
            Instant updatedAt) {
        /** 冻结冲突参与者。 */
        public Conflict {
            id = ContractValidation.text(id, "id");
            revision = ContractValidation.revision(revision);
            proposalId = ContractValidation.text(proposalId, "proposalId");
            memoryIds = Set.copyOf(memoryIds);
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    /**
     * 有界图谱请求。
     *
     * @param query 正文查询
     * @param limit 最大节点数，1 到 200
     */
    public record GraphRequest(String query, int limit) {
        /** 校验节点容量。 */
        public GraphRequest {
            query = Objects.requireNonNull(query, "query");
            if (limit < 1 || limit > 200) {
                throw new IllegalArgumentException("graph node limit must be between 1 and 200");
            }
        }
    }

    /**
     * 图谱节点仅包含纯数据，不包含脚本或可执行链接。
     *
     * @param id 记忆 ID
     * @param label 节点文本
     * @param revision Memory 版本
     * @param state 语义状态
     * @param effective 当前是否有效
     * @param condition 可读条件
     */
    public record GraphNode(
            String id, String label, long revision, SemanticState state, boolean effective, String condition) {}

    /**
     * 已确认关系边。
     *
     * @param source 被替代记忆
     * @param target 替代记忆
     * @param kind 固定关系名称
     */
    public record GraphEdge(String source, String target, String kind) {}

    /**
     * 图谱数据视图。
     *
     * @param nodes 有界节点
     * @param edges 仅连接返回节点的已确认关系
     * @param memoryRevision Workspace 语义版本
     * @param truncated 是否仍有未返回节点
     */
    public record Graph(List<GraphNode> nodes, List<GraphEdge> edges, long memoryRevision, boolean truncated) {
        /** 冻结图数据。 */
        public Graph {
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
        }
    }

    /**
     * 图谱过滤设置；历史包含已替代、过期和有人工条件的记录。
     *
     * @param query 正文过滤，空为全部
     * @param includeInactive 是否展示历史或当前不生效的记忆
     */
    public record GraphFilter(String query, boolean includeInactive) {
        /** 校验查询长度。 */
        public GraphFilter {
            query = Objects.requireNonNull(query, "query").strip();
            if (query.length() > 500) {
                throw new IllegalArgumentException("graph query exceeds 500 characters");
            }
        }
    }

    /**
     * 有界邻接查询；首轮最多 50 个邻居，afterId 为排他节点标识。
     *
     * @param id 中心节点
     * @param afterId 排他游标，首轮为空
     * @param includeInactive 是否包含历史记忆
     * @param limit 邻居数量，1 到 50
     */
    public record GraphNeighbors(String id, String afterId, boolean includeInactive, int limit) {
        /** 校验邻接边界。 */
        public GraphNeighbors {
            id = ContractValidation.text(id, "id");
            afterId = Objects.requireNonNull(afterId, "afterId");
            if (limit < 1 || limit > 50) {
                throw new IllegalArgumentException("graph expansion limit must be between 1 and 50");
            }
        }
    }

    /**
     * 邻接页，不隐瞒未返回边界。
     *
     * @param graph 当前页节点与页内关系
     * @param nextCursor 下一页游标，无余量时为空
     * @param hasMore 是否仍有邻接节点
     */
    public record GraphPage(Graph graph, String nextCursor, boolean hasMore) {}
    /**
     * 页面实例拥有的无状态图谱窗口；服务端不持久化客户端的过滤、位置或选择。
     *
     * @param query 正文过滤
     * @param includeInactive 是否包含历史记忆
     * @param nodeIds 页面已准入节点，空集合表示初始最多 200 个节点
     */
    public record GraphWindow(String query, boolean includeInactive, List<String> nodeIds) {
        /** 校验浏览窗口容量并冻结节点；不得通过重复 ID 绕过容量限制。 */
        public GraphWindow {
            query = new GraphFilter(query, includeInactive).query();
            nodeIds = List.copyOf(nodeIds);
            if (nodeIds.size() > 500 || nodeIds.stream().distinct().count() != nodeIds.size()) {
                throw new IllegalArgumentException("graph window exceeds 500 nodes or contains duplicates");
            }
            nodeIds.forEach(id -> ContractValidation.text(id, "nodeId"));
        }
    }
}
