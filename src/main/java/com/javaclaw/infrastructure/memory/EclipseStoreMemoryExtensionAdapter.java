package com.javaclaw.infrastructure.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.builtin.memory.MemoryMutationGateway;
import com.javaclaw.framework.builtin.memory.MemoryRecallGateway;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ModelTaskRequest;
import com.javaclaw.framework.spi.ModelTier;
import com.javaclaw.memory.MemoryService;
import com.javaclaw.memory.MemoryGraphScope;
import com.javaclaw.memory.correction.CorrectionEngine;
import com.javaclaw.memory.correction.CorrectionGuard;
import com.javaclaw.memory.correction.CorrectionTurnContext;

import java.time.Duration;
import java.util.Objects;

/** Workspace adapter exposing the authoritative EclipseStore graph through framework SPI only. */
public final class EclipseStoreMemoryExtensionAdapter
        implements MemoryRecallGateway, MemoryMutationGateway {
    private final MemoryService memory;
    private final ModelTaskGateway modelTasks;

    public EclipseStoreMemoryExtensionAdapter(MemoryService memory, ModelTaskGateway modelTasks) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.modelTasks = Objects.requireNonNull(modelTasks, "modelTasks");
    }

    @Override
    public String recall(RunRequest request, String query, int topK) {
        return memory.recall(MemoryGraphScope.thread(request.scope()), query, topK);
    }

    @Override
    public JsonNode applyCorrection(
            RunId runId, RunRequest request, String userInput, String previousReply) {
        MemoryGraphScope scope = MemoryGraphScope.thread(request.scope());
        boolean userTurn = java.util.Set.of("chat", "plan").contains(request.source().kind())
                && !request.attributes().containsKey("memory.originThreadId");
        if (userTurn) {
            memory.rememberExplicitPreference(scope, runId.value(), userInput);
        }
        CorrectionTurnContext context = userTurn ? memory.prepareCorrectionTurn(scope, userInput, previousReply)
                : memory.inScope(scope).prepareCorrectionTurn(userInput, previousReply);
        if (!context.hasCorrections()) return null;
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("prompt", context.toPrompt());
        result.put("applied", context.newlyApplied() != null);
        if (context.newlyApplied() != null) result.put("correctionId", context.newlyApplied().id);
        return result;
    }

    @Override
    public JsonNode protectOutput(RunId runId, RunRequest request, JsonNode output) {
        String reply = output.path("text").asText("");
        if (reply.isBlank()) return output;
        String query = textInput(request);
        MemoryGraphScope scope = MemoryGraphScope.thread(request.scope());
        var relevant = CorrectionEngine.selectRelevant(memory.corrections(scope), query, 6);
        var violation = CorrectionGuard.findViolation(reply, relevant);
        if (violation.isEmpty()) return output;
        memory.inScope(scope).recordCorrectionGuardViolation(violation.get());

        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("candidateReply", reply);
        input.put("userInput", query);
        input.put("requirement", "Rewrite the answer so it does not assert the rejected claim. "
                + "Preserve verified useful content, clearly state uncertainty, and return Simplified Chinese.");
        input.put("rejectedClaim", violation.get().wrongClaim());
        if (violation.get().correction().hasCorrectClaim()) {
            input.put("currentUserClaim", violation.get().correction().correctClaim);
        }
        try {
            JsonNode repaired = modelTasks.execute(new ModelTaskRequest(
                            "memory.correction.reply-repair", ModelTier.LIGHT, input,
                            repairSchema(), runId, "memory", Duration.ofSeconds(30), 1,
                            () -> Thread.currentThread().isInterrupted(), false))
                    .toCompletableFuture().join().output();
            String text = repaired.path("text").asText("").strip();
            if (!text.isBlank() && CorrectionGuard.findViolation(text, relevant).isEmpty()) {
                return withText(output, text, true);
            }
        } catch (RuntimeException ignored) {
            // Deterministic safe fallback below; the primary Run must not fail because repair failed.
        }
        String fallback = "检测到候选回答可能重复了你已明确纠正的信息，因此本次未直接输出该结论。"
                + "请让我基于当前文件、工具结果或可靠来源重新核实后再回答。";
        return withText(output, fallback, true);
    }

    @Override
    public void distill(RunId runId, RunRequest request, JsonNode completedOutput) {
        // The transactional Thread outbox projects terminal turns. A lifecycle callback executes
        // before that commit and must never create memory for a turn that may still fail.
    }

    private static JsonNode withText(JsonNode output, String text, boolean corrected) {
        ObjectNode result = output instanceof ObjectNode object
                ? object.deepCopy() : JsonNodeFactory.instance.objectNode();
        result.put("text", text);
        result.put("correctionGuardApplied", corrected);
        return result;
    }

    private static String textInput(RunRequest request) {
        return request.inputs().stream().filter(block -> block.type().equals("core.text"))
                .map(block -> block.data().path("text").asText())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static ObjectNode repairSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object").put("additionalProperties", false);
        schema.putArray("required").add("text");
        schema.putObject("properties").putObject("text").put("type", "string");
        return schema;
    }
}
