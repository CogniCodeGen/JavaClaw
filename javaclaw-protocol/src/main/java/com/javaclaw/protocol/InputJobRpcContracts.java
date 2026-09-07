package com.javaclaw.protocol;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.InputRequestRecord;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.ExtensionJobCursor;
import com.javaclaw.extension.spi.ExtensionJobUnit;
import com.javaclaw.extension.spi.ExtensionJobUnitState;

/** Turn 输入与 Extension Job 管理方法的 Protocol v3 强类型契约。 */
public final class InputJobRpcContracts {
    private InputJobRpcContracts() {}

    /**
     * 输入请求列表条件。
     *
     * @param turnId 可选 Turn 过滤
     * @param includeResolved 是否包含终态请求
     */
    public record InputListPayload(Optional<TurnId> turnId, boolean includeResolved) {
        /** 复制可选容器。 */
        public InputListPayload {
            Objects.requireNonNull(turnId, "turnId");
        }
    }

    /**
     * 输入请求列表结果。
     *
     * @param requests 按创建时间排序的请求
     */
    public record InputListResult(List<InputRequestRecord> requests) {
        /** 取得列表所有权。 */
        public InputListResult {
            requests = List.copyOf(requests);
        }
    }

    /**
     * 用户输入决议。
     *
     * @param requestId 输入请求 ID
     * @param response 符合请求 Schema 的规范 JSON 对象
     */
    public record InputResolvePayload(String requestId, CanonicalPayload response) {
        /** 校验请求标识与响应。 */
        public InputResolvePayload {
            requestId = identifier(requestId, "requestId");
            Objects.requireNonNull(response, "response");
        }
    }

    /**
     * Job 列表条件。
     *
     * @param workspaceId 可选 Workspace 过滤
     * @param extensionId 可选所有者扩展过滤
     * @param states 状态过滤；空集合表示全部
     * @param after 上一页返回的稳定游标
     * @param limit 页大小，1 到 200
     */
    public record JobListPayload(
            Optional<WorkspaceId> workspaceId,
            Optional<String> extensionId,
            Set<ExecutionState> states,
            Optional<ExtensionJobCursor> after,
            int limit) {
        /** 校验并复制过滤条件。 */
        public JobListPayload {
            Objects.requireNonNull(workspaceId, "workspaceId");
            extensionId =
                    Objects.requireNonNull(extensionId, "extensionId").map(value -> identifier(value, "extensionId"));
            states = Set.copyOf(states);
            after = Objects.requireNonNull(after, "after");
            if (limit < 1 || limit > 200) {
                throw new IllegalArgumentException("limit must be between 1 and 200");
            }
        }
    }

    /**
     * Job 列表结果。
     *
     * @param jobs 当前页的 Job 快照
     * @param nextCursor 下一页游标；到达末页时为空
     */
    public record JobListResult(List<ExtensionExecutionReceipt> jobs, Optional<ExtensionJobCursor> nextCursor) {
        /** 取得列表所有权。 */
        public JobListResult {
            jobs = List.copyOf(jobs);
            nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
            if (jobs.isEmpty() && nextCursor.isPresent()) {
                throw new IllegalArgumentException("empty Job page cannot have a next cursor");
            }
        }
    }

    /**
     * Job 读取参数。
     *
     * @param jobId Job ID
     */
    public record JobReadPayload(String jobId) {
        /** 校验 Job ID。 */
        public JobReadPayload {
            jobId = identifier(jobId, "jobId");
        }
    }

    /**
     * Job 详情及执行时间线。
     *
     * @param job 不含恢复 payload 的当前 Job
     * @param units 按 sequence 排序的工作单元
     */
    public record JobReadResult(ExtensionExecutionReceipt job, List<JobUnitSummary> units) {
        /** 校验详情并取得列表所有权。 */
        public JobReadResult {
            Objects.requireNonNull(job, "job");
            units = List.copyOf(units);
            if (units.stream().anyMatch(unit -> !job.id().equals(unit.jobId()))) {
                throw new IllegalArgumentException("all units must belong to the job");
            }
        }
    }

    /**
     * 不暴露 intent、result 或 checkpoint 的工作单元时间线。
     *
     * @param jobId 所属 Job
     * @param sequence 单调序号
     * @param unitId 扩展生成的确定性单元标识
     * @param state 单元状态
     * @param turnId 本单元创建的 Turn
     * @param effectReceiptKey 已提交副作用的 EffectReceipt 键
     * @param errorCode 脱敏错误码
     * @param createdAt 意图提交时间
     * @param completedAt 终态提交时间
     */
    public record JobUnitSummary(
            String jobId,
            long sequence,
            String unitId,
            ExtensionJobUnitState state,
            Optional<TurnId> turnId,
            Optional<String> effectReceiptKey,
            Optional<String> errorCode,
            java.time.Instant createdAt,
            Optional<java.time.Instant> completedAt) {
        /** 校验脱敏时间线字段。 */
        public JobUnitSummary {
            jobId = identifier(jobId, "jobId");
            if (sequence < 1) {
                throw new IllegalArgumentException("sequence must be positive");
            }
            unitId = identifier(unitId, "unitId");
            Objects.requireNonNull(state, "state");
            turnId = Objects.requireNonNull(turnId, "turnId");
            effectReceiptKey = Objects.requireNonNull(effectReceiptKey, "effectReceiptKey");
            errorCode = Objects.requireNonNull(errorCode, "errorCode");
            Objects.requireNonNull(createdAt, "createdAt");
            completedAt = Objects.requireNonNull(completedAt, "completedAt");
        }

        /**
         * 从服务端内部工作单元创建脱敏时间线行。
         *
         * @param unit 完整恢复记录
         * @return 不含 payload 的公开行
         */
        public static JobUnitSummary from(ExtensionJobUnit unit) {
            ExtensionJobUnit value = Objects.requireNonNull(unit, "unit");
            return new JobUnitSummary(
                    value.jobId(),
                    value.sequence(),
                    value.unitId(),
                    value.state(),
                    value.turnId(),
                    value.effectReceiptKey(),
                    value.errorCode(),
                    value.createdAt(),
                    value.completedAt());
        }
    }

    /**
     * pause、resume 与 cancel 共用的 Job 目标。
     *
     * @param jobId Job ID
     */
    public record JobMutationPayload(String jobId) {
        /** 校验 Job ID。 */
        public JobMutationPayload {
            jobId = identifier(jobId, "jobId");
        }
    }

    private static String identifier(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return normalized;
    }
}
