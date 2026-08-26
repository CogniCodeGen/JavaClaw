package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.RunId;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Every auxiliary LLM call (router, GEPA, distillation, critic, vision) uses this command. */
public record ModelTaskRequest(
        String purpose,
        ModelTier tier,
        JsonNode input,
        List<InputBlock> mediaInputs,
        JsonNode outputSchema,
        RunId ownerRunId,
        String budgetAccount,
        Duration timeout,
        int maxRetries,
        CancellationToken cancellation,
        boolean cacheAllowed,
        ModelTaskAttribution attribution) {

    public ModelTaskRequest {
        purpose = Objects.requireNonNull(purpose, "purpose").trim();
        tier = Objects.requireNonNull(tier, "tier");
        input = Objects.requireNonNull(input, "input").deepCopy();
        mediaInputs = List.copyOf(mediaInputs == null ? List.of() : mediaInputs);
        outputSchema = Objects.requireNonNull(outputSchema, "outputSchema").deepCopy();
        ownerRunId = Objects.requireNonNull(ownerRunId, "ownerRunId");
        budgetAccount = Objects.requireNonNull(budgetAccount, "budgetAccount").trim();
        timeout = Objects.requireNonNull(timeout, "timeout");
        cancellation = Objects.requireNonNull(cancellation, "cancellation");
        attribution = Objects.requireNonNull(attribution, "attribution");
        if (purpose.isEmpty() || budgetAccount.isEmpty() || timeout.isNegative()
                || timeout.isZero() || maxRetries < 0) {
            throw new IllegalArgumentException("invalid model task request");
        }
    }

    /** Source-compatible constructor for text-only helper tasks. */
    public ModelTaskRequest(
            String purpose,
            ModelTier tier,
            JsonNode input,
            List<InputBlock> mediaInputs,
            JsonNode outputSchema,
            RunId ownerRunId,
            String budgetAccount,
            Duration timeout,
            int maxRetries,
            CancellationToken cancellation,
            boolean cacheAllowed) {
        this(purpose, tier, input, mediaInputs, outputSchema, ownerRunId, budgetAccount,
                timeout, maxRetries, cancellation, cacheAllowed, ModelTaskAttribution.INLINE);
    }

    /** Source-compatible constructor for text-only helper tasks. */
    public ModelTaskRequest(
            String purpose,
            ModelTier tier,
            JsonNode input,
            JsonNode outputSchema,
            RunId ownerRunId,
            String budgetAccount,
            Duration timeout,
            int maxRetries,
            CancellationToken cancellation,
            boolean cacheAllowed) {
        this(purpose, tier, input, List.of(), outputSchema, ownerRunId, budgetAccount,
                timeout, maxRetries, cancellation, cacheAllowed, ModelTaskAttribution.INLINE);
    }

    public ModelTaskRequest(
            String purpose,
            ModelTier tier,
            JsonNode input,
            JsonNode outputSchema,
            RunId ownerRunId,
            String budgetAccount,
            Duration timeout,
            int maxRetries,
            CancellationToken cancellation,
            boolean cacheAllowed,
            ModelTaskAttribution attribution) {
        this(purpose, tier, input, List.of(), outputSchema, ownerRunId, budgetAccount,
                timeout, maxRetries, cancellation, cacheAllowed, attribution);
    }
    @Override public JsonNode input() { return input.deepCopy(); }
    @Override public JsonNode outputSchema() { return outputSchema.deepCopy(); }
}
