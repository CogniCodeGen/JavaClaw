package com.javaclaw.framework.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Recorded usage snapshot: aggregate includes the execution family plus attested maintenance sidecars.
 * Missing usage is not zero; terminalFamily concerns only the original attached execution family.
 */
public record InteractionMetrics(
        RunId parentRunId,
        Instant observedAt,
        RunState state,
        long elapsedMillis,
        Totals direct,
        Totals descendants,
        Totals aggregate,
        Map<String, Totals> byPurpose,
        List<RunMetrics> runs,
        long delegationCalls,
        long interactionChildren,
        long modeSelections,
        long modeSwitches,
        long clarificationRequests,
        long approvalRequests,
        TaskResult taskResult,
        long satisfiedCriterionCount,
        long unmetCriterionCount,
        long evidenceReferenceCount,
        boolean terminalFamily,
        Totals executionFamily,
        Totals associatedMaintenance,
        List<MaintenanceAssociation> associatedMaintenanceRuns,
        boolean provisional) {
    public InteractionMetrics {
        byPurpose = Map.copyOf(byPurpose);
        runs = List.copyOf(runs);
        boolean legacyAccounting = executionFamily == null || associatedMaintenance == null || associatedMaintenanceRuns == null;
        executionFamily = executionFamily == null ? aggregate : executionFamily;
        associatedMaintenance = associatedMaintenance == null ? Totals.zero() : associatedMaintenance;
        associatedMaintenanceRuns = associatedMaintenanceRuns == null ? List.of() : List.copyOf(associatedMaintenanceRuns);
        provisional = provisional || legacyAccounting;
    }

    /** Source compatibility: older producers measured only the attached execution family. */
    public InteractionMetrics(RunId parentRunId, Instant observedAt, RunState state, long elapsedMillis,
                              Totals direct, Totals descendants, Totals aggregate, Map<String, Totals> byPurpose,
                              List<RunMetrics> runs, long delegationCalls, long interactionChildren,
                              long modeSelections, long modeSwitches, long clarificationRequests, long approvalRequests,
                              TaskResult taskResult, long satisfiedCriterionCount, long unmetCriterionCount,
                              long evidenceReferenceCount, boolean terminalFamily) {
        this(parentRunId, observedAt, state, elapsedMillis, direct, descendants, aggregate, byPurpose, runs,
                delegationCalls, interactionChildren, modeSelections, modeSwitches, clarificationRequests,
                approvalRequests, taskResult, satisfiedCriterionCount, unmetCriterionCount, evidenceReferenceCount,
                terminalFamily, aggregate, Totals.zero(), List.of(), true);
    }

    /** Association is accounting provenance, never a parent/child permission or budget relationship. */
    public record MaintenanceAssociation(RunId runId, RunId originRunId, RunId maintenanceRootRunId) { }

    public record Totals(long providerAttempts, long primaryAttempts, long modelTaskAttempts,
                         long completedAttempts, long failedAttempts, long unfinishedAttempts,
                         long retryAttempts, long cacheHits, long inputTokens, long outputTokens,
                         BigDecimal estimatedCostCny, long unknownUsageAttempts, long unknownCostAttempts,
                         long journaledPromptCharacters, long unknownPromptAttempts,
                         long toolSchemaCharacters, long mediaInputs, long lateUsageAttempts) {
        public static Totals zero() {
            return new Totals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO, 0, 0, 0, 0, 0, 0, 0);
        }
        public Totals(long providerAttempts, long primaryAttempts, long modelTaskAttempts,
                      long completedAttempts, long failedAttempts, long unfinishedAttempts,
                      long retryAttempts, long cacheHits, long inputTokens, long outputTokens,
                      BigDecimal estimatedCostCny, long unknownUsageAttempts, long unknownCostAttempts,
                      long journaledPromptCharacters, long unknownPromptAttempts,
                      long toolSchemaCharacters, long mediaInputs) {
            this(providerAttempts, primaryAttempts, modelTaskAttempts, completedAttempts, failedAttempts,
                    unfinishedAttempts, retryAttempts, cacheHits, inputTokens, outputTokens, estimatedCostCny,
                    unknownUsageAttempts, unknownCostAttempts, journaledPromptCharacters, unknownPromptAttempts,
                    toolSchemaCharacters, mediaInputs, 0);
        }
    }

    public record RunMetrics(RunId runId, RunId parentRunId, String sourceKind, RunState state,
                             long lastSequence, long elapsedMillis, Totals totals) { }
}
