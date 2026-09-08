package com.javaclaw.builtin.extensions;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.AutomationExecutionSnapshot;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.builtin.contracts.MemoryV3Contracts;
import com.javaclaw.extension.spi.ConversationEvidencePort;
import com.javaclaw.extension.spi.ScheduleDefinitionBindingPort;

/** 学习作业的私有持久状态；恢复不得重新选择模型、证据、策略上限或尝试身份。 */
final class MemoryLearningState {
    static final String DEFINITION_ID = "conversation-learning";
    static final String JOB_TYPE = "conversation-learning";
    static final String BINDING_JOB_TYPE = "learning-schedule-binding";

    private MemoryLearningState() {}

    static String definitions(WorkspaceId workspaceId) {
        return "learning-definitions." + workspaceId;
    }

    static String intents(WorkspaceId workspaceId) {
        return "learning-binding-intents." + workspaceId;
    }

    static String batches(WorkspaceId workspaceId) {
        return "learning-batches." + workspaceId;
    }

    static String progress(WorkspaceId workspaceId) {
        return "learning-progress." + workspaceId;
    }

    /**
     * 绑定意图与学习定义同事务写入；只有独立绑定工作单元确认后才标记完成。
     *
     * @param id 稳定意图标识
     * @param revision 意图版本
     * @param change 冻结绑定请求
     * @param acknowledged 是否已确认
     */
    record BindingIntent(String id, long revision, ScheduleDefinitionBindingPort.Change change, boolean acknowledged) {}

    /**
     * 冻结执行输入。
     *
     * @param definition 精确学习定义
     * @param platform 无工具且有界的权威执行快照
     * @param policy 启动策略上限
     */
    record Frozen(
            MemoryV3Contracts.LearningDefinition definition,
            AutomationExecutionSnapshot platform,
            MemoryContracts.LearningPolicy policy) {}

    /**
     * Workspace 增量进度；未决 batch 阻止下一游标发布。
     *
     * @param revision 记录版本
     * @param cursor 已处理完成游标
     * @param pendingBatch 未决批次
     */
    record Progress(long revision, ConversationEvidencePort.Cursor cursor, Optional<String> pendingBatch) {}

    /** 可恢复批次状态；UNKNOWN 必须由用户显式重试或跳过。 */
    enum BatchState {
        FROZEN,
        UNKNOWN,
        COMMITTED,
        SKIPPED
    }

    /**
     * 一个有界批次的不可变证据清单与提交审计。
     *
     * @param id 稳定批次标识
     * @param revision 记录版本
     * @param ownerJobId 创建此批次的作业
     * @param attempt 模型尝试身份，只有显式重试可递增
     * @param from 起始游标
     * @param next 消费后的游标
     * @param upperSequence 冻结完成上界
     * @param evidence 完整可装入模型的证据
     * @param deferred 单条超预算证据标识，仅审计，不静默截断
     * @param state 当前状态
     * @param reason 可见处理结果或错误
     * @param updatedAt 更新时间
     */
    record Batch(
            String id,
            long revision,
            String ownerJobId,
            int attempt,
            ConversationEvidencePort.Cursor from,
            ConversationEvidencePort.Cursor next,
            long upperSequence,
            List<ConversationEvidencePort.Evidence> evidence,
            List<String> deferred,
            BatchState state,
            String reason,
            Instant updatedAt) {
        Batch {
            evidence = List.copyOf(evidence);
            deferred = List.copyOf(deferred);
        }
    }

    /**
     * 工作单元恢复指针。
     *
     * @param phase prepare 或 learn
     * @param batchId 已冻结批次，准备前为空
     */
    record Checkpoint(String phase, String batchId) {}

    /**
     * 模型只输出候选，不允许输出可执行动作或自动授权。
     *
     * @param candidates 至多 20 条候选
     */
    record ModelOutput(List<Candidate> candidates) {
        ModelOutput {
            candidates = List.copyOf(candidates);
            if (candidates.size() > 20) {
                throw new IllegalArgumentException("learning output exceeds 20 candidates");
            }
        }
    }

    /**
     * 模型建议，所有派生内容均进入人工审查。
     *
     * @param evidenceIndex 冻结证据位置
     * @param content 候选正文
     * @param verbatim 来源原文片段
     * @param scope 候选作用域
     * @param summary 是否摘要或推断
     */
    record Candidate(int evidenceIndex, String content, String verbatim, String scope, boolean summary) {}
}
