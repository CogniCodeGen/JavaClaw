package com.javaclaw.chat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.InteractionMetrics;
import com.javaclaw.framework.api.RunId;

/** 交互指标的只读导出格式；不包含提示词、工具原文、验收条件文本或模型回答。 */
final class InteractionMetricsExport {
    private InteractionMetricsExport() { }

    static ObjectNode toJson(InteractionMetrics metrics) {
        var value = JsonNodeFactory.instance.objectNode();
        value.put("schemaVersion", 2);
        value.put("parentRunId", metrics.parentRunId().value());
        value.put("observedAt", metrics.observedAt().toString());
        value.put("state", metrics.state().name());
        value.put("elapsedMillis", metrics.elapsedMillis());
        value.put("terminalFamily", metrics.terminalFamily());
        value.set("direct", totals(metrics.direct()));
        value.set("descendants", totals(metrics.descendants()));
        value.set("aggregate", totals(metrics.aggregate()));
        value.set("executionFamily", totals(metrics.executionFamily()));
        value.set("associatedMaintenance", totals(metrics.associatedMaintenance()));
        value.put("provisional", metrics.provisional());
        value.put("accountingScope", "readable execution family plus host-attested maintenance sidecars available at read time");
        var associations = value.putArray("associatedMaintenanceRuns");
        var maintenance = new java.util.HashMap<RunId, InteractionMetrics.MaintenanceAssociation>();
        for (var association : metrics.associatedMaintenanceRuns()) {
            maintenance.put(association.runId(), association);
            associations.addObject().put("runId", association.runId().value())
                    .put("originRunId", association.originRunId().value())
                    .put("maintenanceRootRunId", association.maintenanceRootRunId().value());
        }
        var purposes = value.putObject("byPurpose");
        metrics.byPurpose().forEach((purpose, totals) -> purposes.set(purpose, totals(totals)));
        value.put("delegationCalls", metrics.delegationCalls());
        value.put("interactionChildren", metrics.interactionChildren());
        value.put("modeSelections", metrics.modeSelections());
        value.put("modeSwitches", metrics.modeSwitches());
        value.put("clarificationRequests", metrics.clarificationRequests());
        value.put("approvalRequests", metrics.approvalRequests());
        value.put("taskOutcome", metrics.taskResult() == null ? "UNKNOWN" : metrics.taskResult().outcome().name());
        value.put("satisfiedCriterionCount", metrics.satisfiedCriterionCount());
        value.put("unmetCriterionCount", metrics.unmetCriterionCount());
        value.put("evidenceReferenceCount", metrics.evidenceReferenceCount());
        var runs = value.putArray("runs");
        for (var run : metrics.runs()) {
            var entry = runs.addObject();
            entry.put("runId", run.runId().value());
            if (run.parentRunId() == null) entry.putNull("parentRunId");
            else entry.put("parentRunId", run.parentRunId().value());
            var association = maintenance.get(run.runId());
            entry.put("attribution", association == null ? "EXECUTION_FAMILY" : "ASSOCIATED_MAINTENANCE");
            if (association != null) {
                entry.put("originRunId", association.originRunId().value());
                entry.put("maintenanceRootRunId", association.maintenanceRootRunId().value());
            }
            entry.put("sourceKind", run.sourceKind());
            entry.put("state", run.state().name());
            entry.put("lastSequence", run.lastSequence());
            entry.put("elapsedMillis", run.elapsedMillis());
            entry.set("totals", totals(run.totals()));
        }
        return value;
    }

    private static ObjectNode totals(InteractionMetrics.Totals totals) {
        var value = JsonNodeFactory.instance.objectNode();
        value.put("providerAttempts", totals.providerAttempts());
        value.put("primaryAttempts", totals.primaryAttempts());
        value.put("modelTaskAttempts", totals.modelTaskAttempts());
        value.put("completedAttempts", totals.completedAttempts());
        value.put("failedAttempts", totals.failedAttempts());
        value.put("unfinishedAttempts", totals.unfinishedAttempts());
        value.put("retryAttempts", totals.retryAttempts());
        value.put("cacheHits", totals.cacheHits());
        value.put("inputTokensRecorded", totals.inputTokens());
        value.put("outputTokensRecorded", totals.outputTokens());
        value.put("estimatedCostCnyRecorded", totals.estimatedCostCny());
        value.put("unknownUsageAttempts", totals.unknownUsageAttempts());
        value.put("lateUsageAttempts", totals.lateUsageAttempts());
        value.put("unknownCostAttempts", totals.unknownCostAttempts());
        value.put("journaledPromptCharacters", totals.journaledPromptCharacters());
        value.put("unknownPromptAttempts", totals.unknownPromptAttempts());
        value.put("toolSchemaCharacters", totals.toolSchemaCharacters());
        value.put("mediaInputs", totals.mediaInputs());
        return value;
    }
}
