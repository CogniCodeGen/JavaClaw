package com.javaclaw.builtin.extensions;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.builtin.contracts.KnowledgeContracts;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobExecution;
import com.javaclaw.extension.spi.ExtensionJobExecutor;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ExtensionJobStepResult;
import com.javaclaw.extension.spi.ExtensionJobWorkUnit;

/** Knowledge 每个 Job 只构建并激活一个不可变 Generation。 */
final class KnowledgeJobExecutor implements ExtensionJobExecutor {
    private final ExtensionJobRuntimeContext context;
    private final KnowledgeGenerationBuilder generations;

    KnowledgeJobExecutor(ExtensionJobRuntimeContext context) {
        this.context = Objects.requireNonNull(context, "context");
        generations = new KnowledgeGenerationBuilder(context);
    }

    @Override
    public Optional<ExtensionJobWorkUnit> plan(ExtensionJob job) {
        requireType(job);
        KnowledgeJobContracts.Checkpoint checkpoint =
                context.payloads().decode(job.checkpoint(), KnowledgeJobContracts.Checkpoint.class);
        if (checkpoint.activeGenerationId().isPresent()) {
            return Optional.empty();
        }
        KnowledgeJobContracts.FrozenImport frozen = frozen(job);
        KnowledgeJobContracts.BuildIntent intent = new KnowledgeJobContracts.BuildIntent(
                generationId(job), frozen.request().attachment().digest());
        return Optional.of(
                new ExtensionJobWorkUnit("build-generation", context.payloads().encode(intent)));
    }

    @Override
    public ExtensionJobStepResult execute(ExtensionJobExecution execution, CancellationToken cancellation)
            throws Exception {
        ExtensionJob job = execution.job();
        requireType(job);
        KnowledgeJobContracts.BuildIntent intent =
                context.payloads().decode(execution.unit().intent(), KnowledgeJobContracts.BuildIntent.class);
        if (!intent.generationId().equals(generationId(job))) {
            throw new IllegalArgumentException("Knowledge Generation intent differs from Job");
        }
        KnowledgeContracts.Generation generation = generations.build(job, frozen(job), intent, cancellation);
        KnowledgeJobContracts.Checkpoint checkpoint = KnowledgeJobContracts.Checkpoint.completed(generation.id());
        return new ExtensionJobStepResult(
                context.payloads().encode(generation),
                context.payloads().encode(checkpoint),
                ExecutionState.COMPLETED,
                Optional.empty(),
                Optional.empty());
    }

    private KnowledgeJobContracts.FrozenImport frozen(ExtensionJob job) {
        KnowledgeJobContracts.FrozenImport frozen =
                context.payloads().decode(job.frozenInput(), KnowledgeJobContracts.FrozenImport.class);
        if (!frozen.request().id().equals(job.definitionId())
                || Math.addExact(frozen.expectedSourceRevision(), 1) != job.definitionRevision()) {
            throw new IllegalArgumentException("Knowledge frozen source identity differs from Job");
        }
        return frozen;
    }

    private static void requireType(ExtensionJob job) {
        Objects.requireNonNull(job, "job");
        if (!KnowledgeContracts.GENERATION_JOB_TYPE.equals(job.jobType())) {
            throw new IllegalArgumentException("unsupported Knowledge Job type");
        }
    }

    private static String generationId(ExtensionJob job) {
        return "generation-" + job.id();
    }
}
