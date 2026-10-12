package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.StepId;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.RunStepQuery;
import com.javaclaw.framework.spi.RunStore;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.javaclaw.framework.springai.OnDemandContextSession.pause;

/** 从宿主步骤历史核验重放工具目录，拒绝脱敏、缺失或已撤销的候选。 */
final class OnDemandReplayValidator {
    private final ReasoningRequest request;
    private final RunStore runs;
    private final RunStepQuery steps;
    private final ToolCatalogSession catalog;
    private final OnDemandContextReads reads;
    private final boolean hasDecision;

    OnDemandReplayValidator(ReasoningRequest request, RunStore runs, ToolCatalogSession catalog,
            OnDemandContextReads reads, boolean hasDecision) {
        this.request = request;
        this.runs = runs;
        this.steps = new RunStepQuery(runs);
        this.catalog = catalog;
        this.reads = reads;
        this.hasDecision = hasDecision;
    }

    /** 恢复未结束模型步骤前，重验冻结候选映射及当前授权。 */
    void validate(
            List<String> exposedTools, String candidateStepId) {
        List<AgentStep> all = steps.steps(request.runId());
        List<AgentStep> models = all.stream()
                .filter(step -> step.kind() == AgentStep.Kind.MODEL)
                .sorted(java.util.Comparator.comparingLong(AgentStep::startSequence)).toList();
        if (models.isEmpty()) throw pause("persisted provider step is unavailable");
        long upper = models.getLast().startSequence();
        long lower = models.stream()
                .filter(step -> step.state() == AgentStep.State.COMPLETED
                        && step.startSequence() < upper)
                .mapToLong(AgentStep::startSequence).max().orElse(0);
        Set<String> frozen = new HashSet<>();
        if (candidateStepId != null) {
            if (candidateStepId.isBlank()) {
                throw pause("persisted tool candidate mapping ID is blank");
            }
            AgentStep step = steps.step(request.runId(), new StepId(candidateStepId))
                    .orElseThrow(() -> pause("persisted tool candidate mapping is unavailable: "
                            + candidateStepId));
            if (step.state() != AgentStep.State.COMPLETED || step.output() == null
                    || step.kind() != AgentStep.Kind.ORCHESTRATION
                    || step.startSequence() <= lower || step.startSequence() >= upper
                    || !step.input().path("phase").asText().equals("tool_search_v2")
                    || stepInputRedacted(step.id()) || reads.readOutputRedacted(step.id())
                    || !step.output().path("candidates").isArray()) {
                throw pause("persisted tool candidate mapping is unavailable: " + step.id().value());
            }
            int index = 0;
            for (JsonNode candidate : step.output().path("candidates")) {
                String name = candidate.path("name").asText();
                String fingerprint = candidate.path("fingerprint").asText();
                if (!candidate.path("id").asText().equals("t" + index++)
                        || name.isBlank() || fingerprint.isBlank()
                        || catalog == null || !catalog.matchesCandidate(name, fingerprint)
                        || !frozen.add(name)) {
                    throw pause("persisted tool candidate is no longer authorized or has changed: "
                            + name);
                }
            }
        }
        Set<String> active = catalog == null ? Set.of() : Set.copyOf(catalog.activeNames());
        for (String name : exposedTools) {
            if (name.equals(HarnessDecisionToolCallback.NAME) && hasDecision) continue;
            if (name.equals(ToolCatalogSession.NAME) || frozen.contains(name)
                    || active.contains(name)) continue;
            throw pause("persisted provider tool has no authorized candidate mapping: " + name);
        }
        if (hasDecision
                && !exposedTools.contains(HarnessDecisionToolCallback.NAME)) {
            throw pause("persisted provider step omitted harness decision tool");
        }
    }

    private boolean stepInputRedacted(StepId id) {
        return runs.eventsAfter(request.runId(), 0).stream().anyMatch(event ->
                event.type().equals("core.step.started")
                        && event.payload().path("stepId").asText().equals(id.value())
                        && event.payload().path("credentialRedacted").asBoolean(false));
    }

}
