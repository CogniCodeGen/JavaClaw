package com.javaclaw.builtin.extensions;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import com.javaclaw.extension.spi.ExtensionJobSubmission;
import com.javaclaw.extension.spi.ExtensionJobSubmissionFactory;

import static com.javaclaw.builtin.extensions.BuiltinExtensionTestSupport.NOW;

final class RecordingExtensionJobPort implements ExtensionJobPort {
    private final Map<String, ExtensionJob> values = new HashMap<>();
    private final Map<String, String> mutationResults = new HashMap<>();

    @Override
    public synchronized ExtensionJob submit(
            CanonicalPayload requestIdentity,
            ExtensionJobMutation mutation,
            ExtensionJobSubmissionFactory submissionFactory)
            throws Exception {
        String existingId = mutationResults.get(mutation.idempotencyKey());
        if (existingId != null) {
            return values.get(existingId);
        }
        ExtensionJobSubmission submission = submissionFactory.create();
        String id = "job-" + (values.size() + 1);
        ExtensionJob job = new ExtensionJob(
                id,
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
        values.put(id, job);
        mutationResults.put(mutation.idempotencyKey(), id);
        return job;
    }

    @Override
    public Optional<ExtensionJob> find(String jobId) {
        return Optional.ofNullable(values.get(jobId));
    }

    void record(ExtensionJob job) {
        values.put(job.id(), job);
    }

    @Override
    public List<ExtensionJob> list(
            Optional<WorkspaceId> workspaceId,
            Optional<ExtensionId> extensionId,
            Set<ExecutionState> states,
            int limit) {
        return values.values().stream()
                .filter(job -> workspaceId.map(job.workspaceId()::equals).orElse(true))
                .filter(job -> extensionId.map(job.extensionId()::equals).orElse(true))
                .limit(limit)
                .toList();
    }

    @Override
    public ExtensionJobPage page(
            Optional<WorkspaceId> workspaceId,
            Optional<ExtensionId> extensionId,
            Set<ExecutionState> states,
            Optional<ExtensionJobCursor> after,
            int limit) {
        List<ExtensionJob> available = list(workspaceId, extensionId, states, Integer.MAX_VALUE).stream()
                .sorted(Comparator.comparing(ExtensionJob::updatedAt).reversed().thenComparing(ExtensionJob::id))
                .toList();
        int start = after.map(cursor -> pageStart(available, cursor)).orElse(0);
        int end = Math.min(available.size(), Math.addExact(start, limit));
        List<ExtensionJob> page = List.copyOf(available.subList(start, end));
        Optional<ExtensionJobCursor> next = end < available.size()
                ? Optional.of(new ExtensionJobCursor(
                        page.getLast().updatedAt(), page.getLast().id()))
                : Optional.empty();
        return new ExtensionJobPage(page, next);
    }

    @Override
    public ExtensionJob pause(String jobId, ExtensionJobMutation mutation) {
        throw new UnsupportedOperationException("test job port is unavailable");
    }

    @Override
    public ExtensionJob resume(String jobId, ExtensionJobMutation mutation) {
        throw new UnsupportedOperationException("test job port is unavailable");
    }

    @Override
    public ExtensionJob continueWaiting(
            String jobId, ExecutionState waitingState, CanonicalPayload checkpoint, ExtensionJobMutation mutation) {
        ExtensionJob current = requireMutation(jobId, mutation);
        if (current.state() != waitingState) {
            throw new IllegalArgumentException("test Job is not in the expected waiting state");
        }
        return replace(current, ExecutionState.RUNNING, checkpoint);
    }

    @Override
    public ExtensionJob cancel(String jobId, ExtensionJobMutation mutation) {
        ExtensionJob current = requireMutation(jobId, mutation);
        return replace(current, ExecutionState.CANCELLED, current.checkpoint());
    }

    private int pageStart(List<ExtensionJob> available, ExtensionJobCursor cursor) {
        for (int index = 0; index < available.size(); index++) {
            ExtensionJob job = available.get(index);
            if (job.updatedAt().equals(cursor.updatedAt()) && job.id().equals(cursor.id())) {
                return index + 1;
            }
        }
        throw new IllegalArgumentException("test Job cursor is stale");
    }

    private ExtensionJob requireMutation(String jobId, ExtensionJobMutation mutation) {
        ExtensionJob current = find(jobId).orElseThrow();
        if (current.revision() != mutation.expectedRevision()) {
            throw new IllegalArgumentException("test Job revision changed");
        }
        return current;
    }

    private ExtensionJob replace(ExtensionJob current, ExecutionState state, CanonicalPayload checkpoint) {
        ExtensionJob updated = new ExtensionJob(
                current.id(),
                current.extensionId(),
                current.workspaceId(),
                current.jobType(),
                current.definitionId(),
                current.definitionRevision(),
                current.frozenInput(),
                state,
                Math.addExact(current.revision(), 1),
                checkpoint,
                current.nextUnitSequence(),
                Optional.empty(),
                Optional.empty(),
                current.createdAt(),
                NOW.plusMillis(current.revision()));
        values.put(updated.id(), updated);
        return updated;
    }
}
