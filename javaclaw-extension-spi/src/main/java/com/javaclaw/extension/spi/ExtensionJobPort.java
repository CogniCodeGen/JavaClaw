package com.javaclaw.extension.spi;

import java.util.List;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.WorkspaceId;

/**
 * 内置扩展提交和管理可恢复后台执行的唯一平台端口。
 *
 * <p><strong>事务不变量：</strong>创建 Job、冻结输入与首个 Outbox 必须同事务提交；每个工作单元必须先提交意图，再执行副作用；结果、checkpoint、Turn/EffectReceipt 引用与下一
 * Outbox 必须同事务提交。
 */
public interface ExtensionJobPort {
    /**
     * 创建排队中的 Job。
     *
     * <p>平台先用 {@code requestIdentity} 恢复既有结果，确认是首次提交后才调用 {@code submissionFactory}。工厂只能解析和冻结输入，不能自行提交副作用。
     *
     * @param requestIdentity 不含 Secret 的稳定请求身份；不得包含随机 ID、捕获时间或其他本次冻结结果
     * @param mutation 幂等身份；expected revision 必须为 0
     * @param submissionFactory 首次提交时惰性创建冻结输入的工厂
     * @return 已提交 Job
     */
    ExtensionJob submit(
            CanonicalPayload requestIdentity,
            ExtensionJobMutation mutation,
            ExtensionJobSubmissionFactory submissionFactory)
            throws Exception;

    /**
     * 查询 Job。
     *
     * @param jobId Job ID
     * @return 当前快照
     */
    Optional<ExtensionJob> find(String jobId);

    /**
     * 按稳定更新时间列出 Job。
     *
     * @param workspaceId 可选 Workspace 过滤
     * @param extensionId 可选扩展过滤
     * @param states 状态过滤；空集合表示全部
     * @param limit 页大小，1 到 200
     * @return 不可变列表
     */
    List<ExtensionJob> list(
            Optional<WorkspaceId> workspaceId,
            Optional<ExtensionId> extensionId,
            java.util.Set<ExecutionState> states,
            int limit);

    /**
     * 使用 {@code (updatedAt DESC, id ASC)} keyset 稳定分页列出 Job。
     *
     * @param workspaceId 可选 Workspace 过滤
     * @param extensionId 可选扩展过滤
     * @param states 状态过滤；空集合表示全部
     * @param after 排他游标
     * @param limit 页大小，1 到 200
     * @return 当前页与下一页游标
     */
    ExtensionJobPage page(
            Optional<WorkspaceId> workspaceId,
            Optional<ExtensionId> extensionId,
            java.util.Set<ExecutionState> states,
            Optional<ExtensionJobCursor> after,
            int limit);

    /**
     * 暂停尚未进入终态且没有活动副作用的 Job。
     *
     * @param jobId Job ID
     * @param mutation 幂等身份与当前 revision
     * @return 已暂停快照
     */
    ExtensionJob pause(String jobId, ExtensionJobMutation mutation);

    /**
     * 恢复暂停或等待状态，并原子投递下一次推进。
     *
     * @param jobId Job ID
     * @param mutation 幂等身份与当前 revision
     * @return 已排队快照
     */
    ExtensionJob resume(String jobId, ExtensionJobMutation mutation);

    /**
     * 由所有者扩展提交经过校验的等待结果，并用新 checkpoint 恢复推进。
     *
     * <p>该入口不会暴露给通用客户端；领域扩展必须先校验 Job 所有权、Workspace、类型和等待语义。
     *
     * @param jobId Job ID
     * @param waitingState 当前等待状态，只允许 WAITING_INPUT 或 WAITING_APPROVAL
     * @param checkpoint 已合并领域决议的完整 checkpoint
     * @param mutation 幂等身份与当前 Job revision
     * @return 已重新排队的 Job
     */
    ExtensionJob continueWaiting(
            String jobId,
            ExecutionState waitingState,
            com.javaclaw.api.CanonicalPayload checkpoint,
            ExtensionJobMutation mutation);

    /**
     * 取消 Job；已经开始的副作用仍必须依赖 EffectReceipt 收敛未知结果。
     *
     * @param jobId Job ID
     * @param mutation 幂等身份与当前 revision
     * @return 已取消快照
     */
    ExtensionJob cancel(String jobId, ExtensionJobMutation mutation);
}
