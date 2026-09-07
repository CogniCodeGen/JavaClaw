package com.javaclaw.runtime;

import java.util.Optional;

import com.javaclaw.api.ItemPayload;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolCallResult;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;

/** Harness 使用的事务持久化端口。 */
public interface TurnJournal {
    /**
     * 提交压缩意图；原生请求须与主 checkpoint 的 MODEL_IN_FLIGHT 同事务。
     *
     * @param request 精确输入
     * @param nativeCall 是否调用外部 Provider
     * @return 持久意图身份
     */
    default CompactionTicket recordCompactionIntent(CompactionRequest request, boolean nativeCall) {
        if (nativeCall) {
            throw new UnsupportedOperationException("native compaction requires a durable journal");
        }
        return new CompactionTicket(1, "0".repeat(64), false);
    }

    /**
     * 原子提交压缩结果、真实用量、新窗口、审计与下一安全点。
     *
     * @param request 原请求
     * @param ticket 已提交意图
     * @param outcome 实际结果
     * @param cumulativeUsage 含本次压缩的真实累计用量，允许超限以保留账单证据
     */
    default void commitCompaction(
            CompactionRequest request, CompactionTicket ticket, CompactionOutcome outcome, ModelUsage cumulativeUsage) {
        append(
                request.command().turn().id(),
                "compaction",
                com.javaclaw.api.CoreSchemas.COMPACTION,
                outcome.item(),
                ItemStatus.COMPLETED);
    }

    /**
     * 绑定活动预算账户，并在允许子任务前恢复已经持久化的预留。
     *
     * @param turnId 活动 Turn
     * @param budget 当前唯一内存预算账户
     */
    default void activateBudget(TurnId turnId, BudgetAccount budget) {}

    /**
     * 解除活动账户绑定；持久预留继续保留用于恢复。
     *
     * @param turnId 离开 Harness 的 Turn
     * @param budget 本次绑定的预算，避免移除后继执行账户
     */
    default void deactivateBudget(TurnId turnId, BudgetAccount budget) {}

    /**
     * 原子进入 RUNNING，或读取硬崩溃前已经提交的恢复点。
     *
     * <p>新 Turn 必须在同一事务提交状态变化与初始 checkpoint；RUNNING Turn 缺少 checkpoint 属于数据损坏。
     *
     * @param command 冻结执行命令
     * @return 精确恢复状态
     */
    TurnRecoverySnapshot beginOrRecover(TurnExecutionCommand command);

    /**
     * 查找已经提交的恢复账本，不改变 Turn 状态。
     *
     * <p>空值只表示尚未创建 checkpoint，例如 Turn 在进入 Harness 前已取消。 调用方不得把 RUNNING 或 COMPLETED Turn 缺少 checkpoint 降级为初始状态。
     *
     * @param turnId Turn
     * @return 已提交的精确恢复状态；尚未创建时为空
     */
    Optional<TurnRecoverySnapshot> findRecovery(TurnId turnId);

    /**
     * 严格读取已提交的恢复账本，不改变 Turn 状态。
     *
     * @param turnId Turn
     * @return 精确恢复状态
     */
    TurnRecoverySnapshot readRecovery(TurnId turnId);

    /**
     * 在调用 Provider 前提交计费外部调用意图。
     *
     * @param turnId Turn
     * @param invocationNumber 从 1 开始的模型调用序号
     * @param intentDigest 脱敏输入摘要
     */
    void recordModelIntent(TurnId turnId, int invocationNumber, String intentDigest);

    /**
     * 原子提交模型结果、usage、Provider state、Assistant Item、工具调用批次与 checkpoint。
     *
     * @param turnId Turn
     * @param invocationNumber 当前模型调用序号
     * @param result 已完整返回并校验的模型结果
     * @param cumulativeUsage 本 Turn 累计 usage
     */
    void commitModelResult(
            TurnId turnId, int invocationNumber, ModelInvocationResult result, ModelUsage cumulativeUsage);

    /**
     * 在执行工具前提交精确调用意图和已扣减次数。
     *
     * @param turnId Turn
     * @param toolIndex 当前批次下标
     * @param request 冻结工具请求
     * @param consumedToolCalls 本 Turn 已扣减次数
     * @param intentDigest 脱敏调用摘要
     */
    void recordToolIntent(
            TurnId turnId, int toolIndex, ToolCallRequest request, int consumedToolCalls, String intentDigest);

    /**
     * 原子提交 ToolResult、EffectReceipt、可见工具与下一个安全恢复点。
     *
     * @param turnId Turn
     * @param toolIndex 当前批次下标
     * @param request 原工具请求
     * @param outcome 治理执行结果
     * @param visibleTools 提交结果后已向模型公开的完整冻结工具身份
     */
    void commitToolResult(
            TurnId turnId,
            int toolIndex,
            ToolCallRequest request,
            ToolExecutionOutcome outcome,
            java.util.List<ToolIdentity> visibleTools);

    /**
     * 条件更新 Turn 状态。
     *
     * @param turnId Turn
     * @param expected 当前期望状态
     * @param next 新状态
     * @param errorCode 失败码；其他状态为空
     */
    void transition(TurnId turnId, TurnStatus expected, TurnStatus next, Optional<String> errorCode);

    /**
     * 按 Thread sequence 原子追加 Core Item。
     *
     * @param turnId Turn
     * @param kind 展示类别
     * @param schemaId schema
     * @param payload 强类型 payload
     * @param status Item 状态
     */
    void append(TurnId turnId, String kind, String schemaId, ItemPayload payload, ItemStatus status);

    /**
     * 保存模型返回的 opaque state 及其输入规模。
     *
     * @param turnId Turn
     * @param modelId 模型端点
     * @param state Provider 私有状态
     * @param estimatedInputTokens 本次模型报告的输入 token
     */
    void saveProviderState(TurnId turnId, String modelId, ProviderState state, long estimatedInputTokens);

    /**
     * 按幂等键恢复已经提交的副作用。
     *
     * <p>实现必须同时核对工具、revision 与请求摘要；同键异参必须失败。
     *
     * @param request 当前请求
     * @return 已记录结果；无提交副作用时为空
     */
    Optional<ToolCallResult> recoverEffect(ToolCallRequest request);
}
