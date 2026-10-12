package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.RunUsageLedger;
import com.javaclaw.framework.spi.RunStore;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import java.math.BigDecimal;
import java.util.Objects;

import static com.javaclaw.framework.springai.ReasoningGatewaySupport.token;

/** 统一记录主模型用量，并从持久事件恢复当前 Run 的累计费用。 */
final class SpringAiModelUsage {
    private final RunUsageLedger ledger;
    private final RunStore store;

    SpringAiModelUsage(RunUsageLedger ledger, RunStore store) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.store = Objects.requireNonNull(store, "store");
    }

    void meter(
            ReasoningRequest request,
            ChatResponse response,
            int attempt,
            int responseIndex) {
        if (response == null) return;
        Usage responseUsage = response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
        long inputTokens = token(responseUsage == null ? null : responseUsage.getPromptTokens());
        long outputTokens = token(responseUsage == null ? null : responseUsage.getCompletionTokens());
        String modelName = response.getMetadata() == null || response.getMetadata().getModel() == null
                ? request.plan().descriptor().modelPolicyRef()
                : response.getMetadata().getModel();
        meter(request, modelName, inputTokens, outputTokens, attempt, responseIndex, false);
    }

    void meterFailure(
            ReasoningRequest request,
            ManagedInferenceChatModel.ManagedInferenceModelException failure,
            int attempt,
            int responseIndex) {
        meter(request, failure.model(), failure.usage().promptTokens(),
                failure.usage().completionTokens(), attempt, responseIndex, true);
    }

    private void meter(
            ReasoningRequest request,
            String modelName,
            long inputTokens,
            long outputTokens,
            int attempt,
            int responseIndex,
            boolean failed) {
        BigDecimal estimatedCost = BigDecimal.valueOf(
                com.javaclaw.agent.PricingTable.estimateCostCny(
                        modelName, inputTokens, outputTokens));
        RuntimeException ledgerFailure = null;
        try {
            ledger.record(request.runId(), inputTokens, outputTokens, estimatedCost);
        } catch (RuntimeException failure) {
            ledgerFailure = failure;
        }
        try {
            ObjectNode usage = JsonNodeFactory.instance.objectNode();
            usage.put("model", modelName);
            usage.put("attempt", attempt);
            usage.put("responseIndex", responseIndex);
            usage.put("inputTokens", inputTokens);
            usage.put("outputTokens", outputTokens);
            usage.put("estimatedCostCny", estimatedCost);
            usage.put("failed", failed);
            request.events().emit("core.model.usage", 1, "framework.springai", usage);
        } catch (RuntimeException eventFailure) {
            if (ledgerFailure == null) throw eventFailure;
            ledgerFailure.addSuppressed(eventFailure);
        }
        if (ledgerFailure != null) throw ledgerFailure;
    }

    PrimaryUsage primaryUsage(ReasoningRequest request) {
        long inputTokens = 0;
        long outputTokens = 0;
        BigDecimal estimatedCost = BigDecimal.ZERO;
        for (var event : store.eventsAfter(request.runId(), 0)) {
            if (!event.type().equals("core.model.usage")) continue;
            inputTokens = Math.addExact(inputTokens,
                    Math.max(0, event.payload().path("inputTokens").asLong()));
            outputTokens = Math.addExact(outputTokens,
                    Math.max(0, event.payload().path("outputTokens").asLong()));
            JsonNode cost = event.payload().get("estimatedCostCny");
            if (cost != null && cost.isNumber()) {
                estimatedCost = estimatedCost.add(
                        cost.decimalValue().max(BigDecimal.ZERO));
            }
        }
        return new PrimaryUsage(inputTokens, outputTokens, estimatedCost);
    }

    record PrimaryUsage(long inputTokens, long outputTokens, BigDecimal estimatedCostCny) { }
}
