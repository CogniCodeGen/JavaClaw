package com.javaclaw.server.persistence;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.InputRequestRecord;
import com.javaclaw.api.InputRequestState;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobCursor;
import com.javaclaw.extension.spi.ExtensionJobMutation;
import com.javaclaw.extension.spi.ExtensionJobPage;
import com.javaclaw.extension.spi.ExtensionJobPort;
import com.javaclaw.extension.spi.ExtensionJobStepResult;
import com.javaclaw.extension.spi.ExtensionJobSubmission;
import com.javaclaw.extension.spi.ExtensionJobSubmissionFactory;
import com.javaclaw.extension.spi.ExtensionJobUnit;
import com.javaclaw.extension.spi.ExtensionJobWorkUnit;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.InputJobRpcContracts;
import com.javaclaw.protocol.WriteCommand;

/**
 * Extension Job 命令、查询、checkpoint 与 Outbox 的事务协调器。
 *
 * <p>公共写操作都先恢复持久幂等结果；Supervisor 内部推进则以 Job revision、活动单元和 Outbox 状态共同防止并发提交。
 */
public final class ExtensionJobService implements ExtensionJobPort {
    private static final Set<ExecutionState> PAUSABLE = Set.of(
            ExecutionState.QUEUED,
            ExecutionState.RUNNING,
            ExecutionState.WAITING_APPROVAL,
            ExecutionState.WAITING_INPUT);
    private static final Set<ExecutionState> RESUMABLE =
            Set.of(ExecutionState.PAUSED, ExecutionState.WAITING_APPROVAL, ExecutionState.WAITING_INPUT);
    private static final Set<ExecutionState> CANCELLABLE = Set.of(
            ExecutionState.QUEUED,
            ExecutionState.RUNNING,
            ExecutionState.WAITING_APPROVAL,
            ExecutionState.WAITING_INPUT,
            ExecutionState.PAUSED);

    private final H2Transactions transactions;
    private final ExtensionJobRepository jobs = new ExtensionJobRepository();
    private final ExtensionJobInputWaitRepository inputWaits = new ExtensionJobInputWaitRepository();
    private final InputRequestRepository inputRequests = new InputRequestRepository();
    private final ExtensionJobOutboxRepository outbox = new ExtensionJobOutboxRepository();
    private final IdempotentCommandStore commands = new IdempotentCommandStore();
    private final CanonicalJson json;
    private final Clock clock;
    private final ExtensionJobCancellations cancellations = new ExtensionJobCancellations();

    /**
     * 创建 Job 服务。
     *
     * @param database data-v6 数据库
     * @param json 共享规范 JSON codec
     * @param clock 平台时钟
     */
    public ExtensionJobService(H2Database database, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** {@inheritDoc} */
    @Override
    public ExtensionJob submit(
            CanonicalPayload requestIdentity,
            ExtensionJobMutation mutation,
            ExtensionJobSubmissionFactory submissionFactory)
            throws Exception {
        CanonicalPayload stableIdentity = Objects.requireNonNull(requestIdentity, "requestIdentity");
        ExtensionJobSubmissionFactory factory = Objects.requireNonNull(submissionFactory, "submissionFactory");
        CommandIdentity identity = identity("extension/job/submit", mutation, stableIdentity.sha256());
        if (identity.expectedRevision() != 0) {
            throw PersistenceException.invalidRequest("创建 Job 的 expected revision 必须为 0");
        }
        synchronized (CommandLocks.forKey(identity.idempotencyKey())) {
            Optional<ExtensionJob> recovered = recoverSubmitted(identity);
            if (recovered.isPresent()) {
                return recovered.orElseThrow();
            }
            ExtensionJobSubmission submission = Objects.requireNonNull(factory.create(), "submission");
            return command(identity, connection -> {
                String id = UUID.randomUUID().toString();
                ExtensionJob created = jobs.insert(connection, id, submission, clock.instant());
                enqueue(connection, created);
                return created;
            });
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ExtensionJob> find(String jobId) {
        String id = identifier(jobId);
        return execute(connection -> jobs.find(connection, id));
    }

    /** {@inheritDoc} */
    @Override
    public List<ExtensionJob> list(
            Optional<WorkspaceId> workspaceId,
            Optional<ExtensionId> extensionId,
            Set<ExecutionState> states,
            int limit) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(extensionId, "extensionId");
        Set<ExecutionState> copiedStates = Set.copyOf(states);
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        return execute(connection -> jobs.list(connection, workspaceId, extensionId, copiedStates, limit));
    }

    /** {@inheritDoc} */
    @Override
    public ExtensionJobPage page(
            Optional<WorkspaceId> workspaceId,
            Optional<ExtensionId> extensionId,
            Set<ExecutionState> states,
            Optional<ExtensionJobCursor> after,
            int limit) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(extensionId, "extensionId");
        Set<ExecutionState> copiedStates = Set.copyOf(states);
        Optional<ExtensionJobCursor> cursor = Objects.requireNonNull(after, "after");
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        List<ExtensionJob> fetched = execute(connection ->
                jobs.page(connection, workspaceId, extensionId, copiedStates, cursor, Math.addExact(limit, 1)));
        boolean hasMore = fetched.size() > limit;
        List<ExtensionJob> page = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        Optional<ExtensionJobCursor> next = hasMore
                ? Optional.of(new ExtensionJobCursor(
                        page.getLast().updatedAt(), page.getLast().id()))
                : Optional.empty();
        return new ExtensionJobPage(page, next);
    }

    /**
     * 读取 Job 和全部有界工作单元。
     *
     * @param jobId Job ID
     * @return Job 详情
     */
    public InputJobRpcContracts.JobReadResult read(String jobId) {
        String id = identifier(jobId);
        return execute(connection -> {
            ExtensionJob job =
                    jobs.find(connection, id).orElseThrow(() -> PersistenceException.invalidRequest("Job 不存在"));
            return new InputJobRpcContracts.JobReadResult(
                    com.javaclaw.extension.spi.ExtensionExecutionReceipt.from(job),
                    jobs.listUnits(connection, id).stream()
                            .map(InputJobRpcContracts.JobUnitSummary::from)
                            .toList());
        });
    }

    /**
     * 返回不包含 Workspace、Definition 或内容的后台队列计数。
     *
     * @return 当前持久 Job 状态计数
     */
    public JobCounts counts() {
        java.util.Map<ExecutionState, Integer> counts = execute(jobs::countByState);
        return new JobCounts(
                counts.getOrDefault(ExecutionState.QUEUED, 0),
                counts.getOrDefault(ExecutionState.RUNNING, 0),
                Math.addExact(
                        counts.getOrDefault(ExecutionState.WAITING_APPROVAL, 0),
                        counts.getOrDefault(ExecutionState.WAITING_INPUT, 0)),
                counts.getOrDefault(ExecutionState.PAUSED, 0));
    }

    /** {@inheritDoc} */
    @Override
    public ExtensionJob pause(String jobId, ExtensionJobMutation mutation) {
        return mutate("extension/job/pause", jobId, mutation, PAUSABLE, ExecutionState.PAUSED, false);
    }

    /** {@inheritDoc} */
    @Override
    public ExtensionJob resume(String jobId, ExtensionJobMutation mutation) {
        return mutate("extension/job/resume", jobId, mutation, RESUMABLE, ExecutionState.QUEUED, true);
    }

    /** {@inheritDoc} */
    @Override
    public ExtensionJob continueWaiting(
            String jobId, ExecutionState waitingState, CanonicalPayload checkpoint, ExtensionJobMutation mutation) {
        String id = identifier(jobId);
        ExecutionState expectedState = Objects.requireNonNull(waitingState, "waitingState");
        CanonicalPayload nextCheckpoint = Objects.requireNonNull(checkpoint, "checkpoint");
        ExtensionJobMutation checkedMutation = Objects.requireNonNull(mutation, "mutation");
        var digestInput = new WaitingContinuation(id, expectedState, nextCheckpoint);
        CommandIdentity identity = identity("extension/job/continue", checkedMutation, digestInput);
        return command(identity, connection -> {
            ExtensionJob current =
                    jobs.lock(connection, id).orElseThrow(() -> PersistenceException.invalidRequest("Job 不存在"));
            if (current.revision() != identity.expectedRevision()) {
                throw PersistenceException.revisionConflict("Job revision 已改变");
            }
            ExtensionJob updated =
                    jobs.continueWaiting(connection, current, expectedState, nextCheckpoint, clock.instant());
            if (expectedState == ExecutionState.WAITING_INPUT) {
                requireResolvedInputWait(connection, current);
                inputWaits.delete(connection, current.id());
            }
            enqueue(connection, updated);
            return updated;
        });
    }

    /** {@inheritDoc} */
    @Override
    public ExtensionJob cancel(String jobId, ExtensionJobMutation mutation) {
        var payload = new InputJobRpcContracts.JobMutationPayload(jobId);
        return cancel(identity("extension/job/cancel", mutation, payload), payload.jobId());
    }

    /**
     * 使用 wire 命令身份暂停 Job。
     *
     * @param identity 完整 RPC 命令身份
     * @param jobId Job ID
     * @return 已暂停 Job
     */
    public ExtensionJob pause(CommandIdentity identity, String jobId) {
        return mutate(identity, jobId, PAUSABLE, ExecutionState.PAUSED, false);
    }

    /**
     * 使用 wire 命令身份恢复 Job。
     *
     * @param identity 完整 RPC 命令身份
     * @param jobId Job ID
     * @return 已排队 Job
     */
    public ExtensionJob resume(CommandIdentity identity, String jobId) {
        return mutate(identity, jobId, RESUMABLE, ExecutionState.QUEUED, true);
    }

    /**
     * 使用 wire 命令身份取消 Job。
     *
     * @param identity 完整 RPC 命令身份
     * @param jobId Job ID
     * @return 无活动单元时已取消；活动单元仍运行时表示取消意图已持久化
     */
    public ExtensionJob cancel(CommandIdentity identity, String jobId) {
        String id = identifier(jobId);
        ExtensionJob result = command(identity, connection -> {
            ExtensionJob current =
                    jobs.lock(connection, id).orElseThrow(() -> PersistenceException.invalidRequest("Job 不存在"));
            if (current.revision() != identity.expectedRevision()) {
                throw PersistenceException.revisionConflict("Job revision 已改变");
            }
            if (!CANCELLABLE.contains(current.state())) {
                throw PersistenceException.invalidRequest("Job 当前状态不允许取消");
            }
            cancellations.request(connection, id, clock.instant());
            return current.activeUnitSequence().isPresent()
                    ? current
                    : jobs.transition(connection, current, CANCELLABLE, ExecutionState.CANCELLED, clock.instant());
        });
        // 只有意图事务提交后才发送信号；幂等重试也再次发布，恢复提交后进程退出的窗口。
        cancellations.publish(id);
        return result;
    }

    void bindCancellation(String jobId, com.javaclaw.api.CancellationSource cancellation) {
        cancellations.register(jobId, cancellation);
        try {
            if (execute(connection -> cancellations.requested(connection, jobId))) {
                cancellations.publish(jobId);
            }
        } catch (RuntimeException failure) {
            cancellations.remove(jobId, cancellation);
            throw failure;
        }
    }

    void unbindCancellation(String jobId, com.javaclaw.api.CancellationSource cancellation) {
        cancellations.remove(jobId, cancellation);
    }

    Optional<ClaimedJob> claimNext() {
        return execute(connection -> {
            Optional<ExtensionJobOutboxRepository.Delivery> claimed = outbox.claim(connection, clock.instant());
            if (claimed.isEmpty()) {
                return Optional.empty();
            }
            ExtensionJobOutboxRepository.Delivery delivery = claimed.orElseThrow();
            String jobId = json.decode(delivery.payload(), InputJobRpcContracts.JobReadPayload.class)
                    .jobId();
            Optional<ExtensionJob> existing = jobs.lock(connection, jobId);
            if (existing.isEmpty()) {
                outbox.delivered(connection, delivery.id());
                return Optional.empty();
            }
            ExtensionJob current = existing.orElseThrow();
            if (current.state() == ExecutionState.QUEUED) {
                current = jobs.markRunning(connection, current, clock.instant());
            } else if (current.state() != ExecutionState.RUNNING) {
                outbox.delivered(connection, delivery.id());
                return Optional.empty();
            }
            Optional<ExtensionJobUnit> active = jobs.activeUnit(connection, current);
            if (current.activeUnitSequence().isPresent() != active.isPresent()) {
                throw new PersistenceException("Job 活动单元引用损坏");
            }
            return Optional.of(new ClaimedJob(delivery.id(), current, active));
        });
    }

    ClaimedJob recordIntent(ClaimedJob claimed, ExtensionJobWorkUnit workUnit) {
        Objects.requireNonNull(claimed, "claimed");
        Objects.requireNonNull(workUnit, "workUnit");
        return execute(connection -> {
            ExtensionJob current = requireCurrent(connection, claimed.job());
            ExtensionJob updated = jobs.recordIntent(connection, current, workUnit, clock.instant());
            ExtensionJobUnit active = jobs.activeUnit(connection, updated).orElseThrow();
            return new ClaimedJob(claimed.deliveryId(), updated, Optional.of(active));
        });
    }

    ExtensionJob complete(ClaimedJob claimed, ExtensionJobStepResult step) {
        Objects.requireNonNull(step, "step");
        return execute(connection -> {
            ExtensionJob current = requireCurrent(connection, claimed.job());
            ExtensionJobUnit unit = claimed.unit().orElseThrow();
            ExtensionJobStepResult applied = cancellations.afterStep(connection, current.id(), step);
            validateInputWait(connection, applied);
            ExtensionJob completed = jobs.completeUnit(connection, current, unit, applied, clock.instant());
            if (applied.inputWait().isPresent()) {
                inputWaits.bind(connection, completed.id(), applied.inputWait().orElseThrow(), clock.instant());
            }
            outbox.delivered(connection, claimed.deliveryId());
            if (completed.state() == ExecutionState.RUNNING) {
                enqueue(connection, completed);
            }
            return completed;
        });
    }

    ExtensionJob completeWithoutUnit(ClaimedJob claimed) {
        return execute(connection -> {
            ExtensionJob current = requireCurrent(connection, claimed.job());
            ExtensionJob completed = jobs.completeWithoutUnit(connection, current, clock.instant());
            outbox.delivered(connection, claimed.deliveryId());
            return completed;
        });
    }

    ExtensionJob fail(ClaimedJob claimed, String errorCode) {
        return fail(claimed, ExtensionJobFailureEvidence.code(errorCode));
    }

    ExtensionJob fail(ClaimedJob claimed, ExtensionJobFailureEvidence evidence) {
        ExtensionJobFailureEvidence checked = Objects.requireNonNull(evidence, "evidence");
        return execute(connection -> {
            ExtensionJob current = requireCurrent(connection, claimed.job());
            CanonicalPayload result = json.encode(new FailureSummary(checked.errorCode()));
            ExtensionJob failed = claimed.unit().isPresent()
                    ? jobs.failUnit(connection, current, claimed.unit().orElseThrow(), checked, result, clock.instant())
                    : jobs.failWithoutUnit(connection, current, checked.errorCode(), clock.instant());
            outbox.delivered(connection, claimed.deliveryId());
            return failed;
        });
    }

    void retry(ClaimedJob claimed, Duration delay) {
        Objects.requireNonNull(delay, "delay");
        if (delay.isNegative() || delay.isZero()) {
            throw new IllegalArgumentException("retry delay must be positive");
        }
        execute(connection -> {
            outbox.retry(connection, claimed.deliveryId(), clock.instant().plus(delay));
            return null;
        });
    }

    int recoverOutbox() {
        return execute(outbox::recoverProcessing);
    }

    private ExtensionJob mutate(
            String method,
            String jobId,
            ExtensionJobMutation mutation,
            Set<ExecutionState> allowed,
            ExecutionState next,
            boolean enqueue) {
        InputJobRpcContracts.JobMutationPayload payload = new InputJobRpcContracts.JobMutationPayload(jobId);
        return mutate(identity(method, mutation, payload), payload.jobId(), allowed, next, enqueue);
    }

    private ExtensionJob mutate(
            CommandIdentity identity, String jobId, Set<ExecutionState> allowed, ExecutionState next, boolean enqueue) {
        String id = identifier(jobId);
        CommandIdentity checked = Objects.requireNonNull(identity, "identity");
        return command(checked, connection -> {
            ExtensionJob current =
                    jobs.lock(connection, id).orElseThrow(() -> PersistenceException.invalidRequest("Job 不存在"));
            if (current.revision() != checked.expectedRevision()) {
                throw PersistenceException.revisionConflict("Job revision 已改变");
            }
            ExtensionJob updated = jobs.transition(connection, current, allowed, next, clock.instant());
            if (enqueue) {
                enqueue(connection, updated);
            }
            return updated;
        });
    }

    private ExtensionJob command(CommandIdentity identity, JobWrite write) {
        synchronized (CommandLocks.forKey(identity.idempotencyKey())) {
            return execute(connection -> {
                Optional<CanonicalPayload> stored = commands.recover(connection, identity);
                if (stored.isPresent()) {
                    return json.decode(stored.orElseThrow(), ExtensionJob.class);
                }
                ExtensionJob result = write.execute(connection);
                commands.record(connection, identity, json.encode(result), clock.instant());
                return result;
            });
        }
    }

    private Optional<ExtensionJob> recoverSubmitted(CommandIdentity identity) {
        return execute(connection ->
                commands.recover(connection, identity).map(payload -> json.decode(payload, ExtensionJob.class)));
    }

    private ExtensionJob requireCurrent(java.sql.Connection connection, ExtensionJob expected) throws Exception {
        ExtensionJob current =
                jobs.lock(connection, expected.id()).orElseThrow(() -> PersistenceException.invalidRequest("Job 不存在"));
        if (current.revision() != expected.revision()) {
            throw PersistenceException.revisionConflict("Job revision 已改变");
        }
        return current;
    }

    private void validateInputWait(java.sql.Connection connection, ExtensionJobStepResult step) throws Exception {
        if (step.inputWait().isEmpty()) {
            return;
        }
        var wait = step.inputWait().orElseThrow();
        InputRequestRecord input = inputRequests
                .lock(connection, wait.requestId())
                .orElseThrow(() -> PersistenceException.invalidRequest("Job 等待的 InputRequest 不存在"));
        if (!input.pending() || !input.request().turnId().equals(wait.turnId())) {
            throw PersistenceException.invalidRequest("Job 等待身份与权威 InputRequest 不一致");
        }
    }

    private void requireResolvedInputWait(java.sql.Connection connection, ExtensionJob job) throws Exception {
        Optional<ExtensionJobInputWaitRepository.Link> linked = inputWaits.lock(connection, job.id());
        if (linked.isEmpty()) {
            return;
        }
        ExtensionJobInputWaitRepository.Link wait = linked.orElseThrow();
        InputRequestRecord input = inputRequests
                .lock(connection, wait.requestId())
                .orElseThrow(() -> PersistenceException.invalidRequest("Job 等待的 InputRequest 不存在"));
        boolean resolved = input.state() == InputRequestState.RESOLVED
                && input.request().turnId().equals(wait.turnId());
        if (!resolved) {
            throw PersistenceException.invalidRequest("权威 InputRequest 尚未有效决议");
        }
    }

    private void enqueue(java.sql.Connection connection, ExtensionJob job) throws Exception {
        String key = "extension-job:" + job.id() + ":revision:" + job.revision();
        outbox.enqueue(
                connection, key, json.encode(new InputJobRpcContracts.JobReadPayload(job.id())), clock.instant());
    }

    private CommandIdentity identity(String method, ExtensionJobMutation mutation, Object payload) {
        ExtensionJobMutation checked = Objects.requireNonNull(mutation, "mutation");
        WriteCommand command =
                new WriteCommand(checked.idempotencyKey(), checked.expectedRevision(), json.encode(payload));
        return CommandIdentity.from(method, command, json);
    }

    private CommandIdentity identity(String method, ExtensionJobMutation mutation, String requestDigest) {
        ExtensionJobMutation checked = Objects.requireNonNull(mutation, "mutation");
        return new CommandIdentity(method, checked.idempotencyKey(), checked.expectedRevision(), requestDigest);
    }

    private static String identifier(String value) {
        return new InputJobRpcContracts.JobReadPayload(value).jobId();
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Extension Job 事务失败", failure);
        }
    }

    record ClaimedJob(String deliveryId, ExtensionJob job, Optional<ExtensionJobUnit> unit) {
        ClaimedJob {
            Objects.requireNonNull(deliveryId, "deliveryId");
            Objects.requireNonNull(job, "job");
            Objects.requireNonNull(unit, "unit");
        }
    }

    @FunctionalInterface
    private interface JobWrite {
        ExtensionJob execute(java.sql.Connection connection) throws Exception;
    }

    private record FailureSummary(String errorCode) {}

    private record WaitingContinuation(String jobId, ExecutionState state, CanonicalPayload checkpoint) {}

    /**
     * Extension Job 脱敏状态计数。
     *
     * @param queued 等待领取
     * @param running 正在执行
     * @param waiting 等待审批或输入
     * @param paused 已暂停
     */
    public record JobCounts(int queued, int running, int waiting, int paused) {
        /** 校验计数。 */
        public JobCounts {
            if (queued < 0 || running < 0 || waiting < 0 || paused < 0) {
                throw new IllegalArgumentException("job counts must not be negative");
            }
        }
    }
}
