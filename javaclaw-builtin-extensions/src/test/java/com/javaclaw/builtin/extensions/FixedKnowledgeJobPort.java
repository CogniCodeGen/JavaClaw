package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobCursor;
import com.javaclaw.extension.spi.ExtensionJobMutation;
import com.javaclaw.extension.spi.ExtensionJobPage;
import com.javaclaw.extension.spi.ExtensionJobPort;
import com.javaclaw.extension.spi.ExtensionJobSubmissionFactory;

/** Knowledge 重放测试使用的固定 Job 端口，可选择是否解析首次提交工厂。 */
final class FixedKnowledgeJobPort implements ExtensionJobPort {
    private final ExtensionJob job;
    private final boolean invokeFactory;

    FixedKnowledgeJobPort(ExtensionJob job, boolean invokeFactory) {
        this.job = job;
        this.invokeFactory = invokeFactory;
    }

    @Override
    public ExtensionJob submit(
            CanonicalPayload requestIdentity,
            ExtensionJobMutation mutation,
            ExtensionJobSubmissionFactory submissionFactory)
            throws Exception {
        if (invokeFactory) {
            submissionFactory.create();
        }
        return job;
    }

    @Override
    public Optional<ExtensionJob> find(String jobId) {
        return job.id().equals(jobId) ? Optional.of(job) : Optional.empty();
    }

    @Override
    public List<ExtensionJob> list(
            Optional<WorkspaceId> workspaceId,
            Optional<ExtensionId> extensionId,
            Set<ExecutionState> states,
            int limit) {
        return List.of(job);
    }

    @Override
    public ExtensionJobPage page(
            Optional<WorkspaceId> workspaceId,
            Optional<ExtensionId> extensionId,
            Set<ExecutionState> states,
            Optional<ExtensionJobCursor> after,
            int limit) {
        return new ExtensionJobPage(List.of(job), Optional.empty());
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
