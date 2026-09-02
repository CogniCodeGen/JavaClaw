package com.javaclaw.builtin.extensions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.PlanContracts;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionBundle;
import com.javaclaw.extension.spi.ExtensionContext;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionJobRegistration;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ExtensionSchema;

/** Plan Definition 与可恢复逐步骤 Execution。 */
final class PlanExtension implements ExtensionBundle {
    private final ManagedDocumentResource<PlanContracts.Definition> documents = new ManagedDocumentResource<>(
            BuiltinExtensionIds.PLAN,
            "计划",
            PlanContracts.Definition.class,
            Set.of(ContributionKind.ORCHESTRATOR, ContributionKind.SCHEDULABLE_ACTION));
    private final PlanManagement management = new PlanManagement(documents);
    private final PlanProposalResource proposals = new PlanProposalResource(documents);
    private final AutomationExecutionResource<PlanContracts.Definition> executions = new AutomationExecutionResource<>(
            documents, "计划", PlanJobExecutor::new, this::initialCheckpoint, PlanExtension::validateStart);

    @Override
    public ExtensionDescriptor descriptor() {
        return documents.descriptor();
    }

    @Override
    public List<ExtensionContribution> start(ExtensionContext context) {
        List<ExtensionContribution> contributions = new ArrayList<>(documents.startWithManagedWrites(context));
        contributions.addAll(management.contributions());
        contributions.addAll(proposals.contributions());
        contributions.addAll(executions.contributions(List.of()));
        return List.copyOf(contributions);
    }

    @Override
    public List<ExtensionSchema> schemas() {
        List<ExtensionSchema> schemas = new ArrayList<>(documents.schemas());
        schemas.addAll(proposals.schemas());
        schemas.addAll(executions.schemas());
        return List.copyOf(schemas);
    }

    @Override
    public List<ExtensionJobRegistration> jobExecutors(ExtensionJobRuntimeContext context) {
        return executions.jobExecutors(context);
    }

    @Override
    public void close() {
        documents.close();
    }

    private CanonicalPayload initialCheckpoint(PlanContracts.Definition definition) {
        return documents.payloads().encode(new PlanJobExecutor.Checkpoint(0));
    }

    private static void validateStart(PlanContracts.Definition definition) {
        if (!definition.decisionsComplete()) {
            throw new IllegalArgumentException("Plan 存在未决开放问题，不能启动执行");
        }
    }

    static List<PlanContracts.Step> executionOrder(PlanContracts.Definition definition) {
        List<PlanContracts.Step> ordered = new ArrayList<>();
        Set<String> complete = new HashSet<>();
        while (ordered.size() < definition.steps().size()) {
            PlanContracts.Step next = definition.steps().stream()
                    .filter(step -> !complete.contains(step.id()))
                    .filter(step -> complete.containsAll(step.dependencies()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Plan step graph cannot advance"));
            ordered.add(next);
            complete.add(next.id());
        }
        return List.copyOf(ordered);
    }
}
