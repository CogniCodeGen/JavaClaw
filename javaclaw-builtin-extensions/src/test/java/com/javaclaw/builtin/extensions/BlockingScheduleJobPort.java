package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobCursor;
import com.javaclaw.extension.spi.ExtensionJobMutation;
import com.javaclaw.extension.spi.ExtensionJobPage;
import com.javaclaw.extension.spi.ExtensionJobPort;
import com.javaclaw.extension.spi.ExtensionJobSubmission;
import com.javaclaw.extension.spi.ExtensionJobSubmissionFactory;

import static com.javaclaw.builtin.extensions.BuiltinExtensionTestSupport.NOW;

/** 用闩锁冻结首次 Schedule Job 提交，确定性验证命令与 Reconcile 的单一领取。 */
final class BlockingScheduleJobPort implements ExtensionJobPort {
    private final BuiltinExtensionTestSupport support;
    private final AtomicInteger submissions = new AtomicInteger();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile ExtensionJob job;

    BlockingScheduleJobPort(BuiltinExtensionTestSupport support) {
        this.support = support;
    }

    boolean awaitSubmission() throws InterruptedException {
        return entered.await(10, TimeUnit.SECONDS);
    }

    void releaseSubmission() {
        release.countDown();
    }

    int submissionCount() {
        return submissions.get();
    }

    @Override
    public ExtensionJob submit(
            CanonicalPayload requestIdentity,
            ExtensionJobMutation mutation,
            ExtensionJobSubmissionFactory submissionFactory)
            throws Exception {
        submissions.incrementAndGet();
        entered.countDown();
        release.await();
        ExtensionJobSubmission submission = submissionFactory.create();
        ExtensionJob created = new ExtensionJob(
                "job-single-claim",
                submission.extensionId(),
                submission.workspaceId(),
                submission.jobType(),
                submission.definitionId(),
                submission.definitionRevision(),
                submission.frozenInput(),
                ExecutionState.QUEUED,
                1,
                submission.initialCheckpoint(),
                1,
                Optional.empty(),
                Optional.empty(),
                NOW,
                NOW);
        job = created;
        return created;
    }

    @Override
    public Optional<ExtensionJob> find(String jobId) {
        return Optional.ofNullable(job).filter(value -> value.id().equals(jobId));
    }

    @Override
    public List<ExtensionJob> list(
            Optional<WorkspaceId> workspaceId,
            Optional<ExtensionId> extensionId,
            Set<ExecutionState> states,
            int limit) {
        return Optional.ofNullable(job).stream().toList();
    }

    @Override
    public ExtensionJobPage page(
            Optional<WorkspaceId> workspaceId,
            Optional<ExtensionId> extensionId,
            Set<ExecutionState> states,
            Optional<ExtensionJobCursor> after,
            int limit) {
        return new ExtensionJobPage(list(workspaceId, extensionId, states, limit), Optional.empty());
    }

    @Override
    public ExtensionJob pause(String jobId, ExtensionJobMutation mutation) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ExtensionJob resume(String jobId, ExtensionJobMutation mutation) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ExtensionJob continueWaiting(
            String jobId, ExecutionState waitingState, CanonicalPayload checkpoint, ExtensionJobMutation mutation) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ExtensionJob cancel(String jobId, ExtensionJobMutation mutation) {
        throw new UnsupportedOperationException();
    }
}
