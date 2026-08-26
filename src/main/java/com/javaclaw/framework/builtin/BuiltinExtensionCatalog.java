package com.javaclaw.framework.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.builtin.gepa.AdaptiveGepaEvaluationPolicy;
import com.javaclaw.framework.builtin.memory.MemoryMutationGateway;
import com.javaclaw.framework.builtin.memory.MemoryRecallExtension;
import com.javaclaw.framework.builtin.memory.MemoryRecallGateway;
import com.javaclaw.framework.extension.ExtensionArtifact;
import com.javaclaw.framework.spi.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Installs JavaClaw capabilities through the same SPI exposed to trusted system extensions. */
public final class BuiltinExtensionCatalog {
    private BuiltinExtensionCatalog() {}

    public static List<ExtensionArtifact> create(
            MemoryRecallGateway memoryRecall,
            MemoryMutationGateway memoryMutations,
            RetrieverContribution knowledgeRetriever,
            PromptContributor skillContributor,
            ToolProviderFactory hostTools,
            com.javaclaw.framework.builtin.context.ContextCompactionAdvisorFactory contextCompaction) {
        return create(memoryRecall, memoryMutations, knowledgeRetriever, skillContributor,
                hostTools, contextCompaction, null);
    }

    public static List<ExtensionArtifact> create(
            MemoryRecallGateway memoryRecall,
            MemoryMutationGateway memoryMutations,
            RetrieverContribution knowledgeRetriever,
            PromptContributor skillContributor,
            ToolProviderFactory hostTools,
            com.javaclaw.framework.builtin.context.ContextCompactionAdvisorFactory contextCompaction,
            RunStore runStore) {
        Objects.requireNonNull(memoryRecall, "memoryRecall");
        Objects.requireNonNull(memoryMutations, "memoryMutations");
        Objects.requireNonNull(knowledgeRetriever, "knowledgeRetriever");
        Objects.requireNonNull(skillContributor, "skillContributor");
        Objects.requireNonNull(hostTools, "hostTools");
        Objects.requireNonNull(contextCompaction, "contextCompaction");
        List<AgentFrameworkExtension> extensions = new ArrayList<>();

        extensions.add(declarative("memory.graph", "Memory Graph",
                "EclipseStore authoritative graph with a Spring AI VectorStore adapter",
                schema(true), 10, List.of(), registrar -> {}));
        extensions.add(new MemoryRecallExtension(memoryRecall));
        extensions.add(declarative("memory.correction", "Memory Correction",
                "Durable corrections, conflict/supersede/undo and final-answer protection",
                schema(true), 30, dependsOnGraph(), registrar -> {
                    registrar.promptContributor((request, state) -> {
                        String input = textInput(request);
                        String previous = request.attributes().containsKey("previousAssistantReply")
                                ? request.attributes().get("previousAssistantReply").asText("") : "";
                        JsonNode correction = memoryMutations.applyCorrection(
                                state.runId(), request, input, previous);
                        if (correction == null || correction.isNull()) return "";
                        String prompt = correction.path("prompt").asText("");
                        return prompt.isBlank() ? "" : prompt;
                    });
                    registrar.outputGuard((output, request, runId) ->
                            memoryMutations.protectOutput(runId, request, output));
                }));
        extensions.add(declarative("memory.distillation", "Memory Distillation",
                "Post-run asynchronous distillation workflow using ModelTaskGateway",
                schema(true), 40, dependsOnGraph(), registrar ->
                        registrar.outputGuard((output, request, runId) -> {
                            memoryMutations.distill(runId, request, output);
                            return output;
                        })));
        extensions.add(declarative("memory.habit", "Habit Review",
                "Due-checked habit-review workflow after durable episode distillation",
                intervalSchema(), 50,
                List.of(new ExtensionDependency(
                        "memory.distillation", ">=2.0.0 <3.0.0", false)), registrar -> {}));

        extensions.add(declarative("gepa.signal", "GEPA Signals",
                "Derives execution signals from RunEvent and Spring AI Observation streams",
                schema(true), 10, List.of(), registrar -> {}));
        extensions.add(declarative("gepa.evaluate", "GEPA Assessment",
                "Rule assessment for all runs with adaptive LIGHT-model escalation",
                schema(true), 20,
                List.of(new ExtensionDependency("gepa.signal", ">=2.0.0 <3.0.0", false)),
                registrar -> {
                    registrar.evaluationPolicy(new AdaptiveGepaEvaluationPolicy());
                    registrar.eventType(new EventTypeDescriptor(
                            "gepa.evaluate.assessment", 1, gepaAssessmentSchema()),
                            EventCodec.jsonNode());
                }));
        extensions.add(declarative("gepa.revise", "GEPA Revision",
                "Reply repair and PlanRevision without a second execution loop",
                schema(true), 30,
                List.of(new ExtensionDependency("gepa.evaluate", ">=2.0.0 <3.0.0", false)),
                registrar -> registrar.outputGuard((output, request, runId) -> output)));
        extensions.add(declarative("gepa.goal", "Structured Goals",
                "Goal decomposition contributed as reasoning context",
                schema(true), 40, List.of(), registrar ->
                        registrar.promptContributor((request, state) ->
                                "For complex work, keep goals explicit and emit progress through run events. "
                                        + "Do not create a second plan state machine.")));

        extensions.add(declarative("knowledge.rag", "Knowledge / RAG",
                "Spring AI Retriever and RAG Advisor over workspace knowledge",
                topKSchema(), 10, List.of(), registrar -> registrar.retriever((query, request) ->
                        boundedKnowledgeContext(knowledgeRetriever, query, request))));
        extensions.add(declarative("skill.runtime", "Skills",
                "Reusable prompt, tool and curation workflow contributions",
                schema(true), 20, List.of(), registrar -> registrar.promptContributor(skillContributor)));
        extensions.add(declarative("plan.readonly", "Read-only Plan Mode",
                "PlanRevision with a permission profile that cannot execute mutations",
                schema(true), 30, List.of(), registrar ->
                        registrar.permissionPolicy((current, configuration, request) ->
                                new PermissionSet(current.values().stream()
                                        .filter(permission -> readOnly(permission.value()))
                                        .collect(java.util.stream.Collectors.toCollection(
                                                java.util.LinkedHashSet::new))))));
        extensions.add(declarative("subagent.run", "SubAgent",
                "Parent/child AgentClient runs with shared kernel budgets and cancellation",
                schema(true), 40, List.of(), registrar -> {}));
        extensions.add(declarative("context.compaction", "Context Compaction",
                "Advisor-driven context compaction with audited summary tasks",
                schema(true), 50, List.of(), registrar -> registrar.advisor(contextCompaction)));
        extensions.add(declarative("tool.result-eviction", "Tool Result Eviction",
                "Size-aware tool-result post-processing without losing durable events",
                resultEvictionSchema(), 60, List.of(), registrar -> registrar
                        .toolResultPostProcessor(new com.javaclaw.framework.builtin.context
                                .BoundedToolResultProcessor(runStore))));
        extensions.add(declarative("sandbox.execution", "Sandbox",
                "ToolExecution provider for process/container isolation",
                schema(false), 70, List.of(), registrar -> {}));
        extensions.add(declarative("mcp.tools", "MCP Tools",
                "MCP ToolProvider and lifecycle management through the common tool pipeline",
                schema(true), 80, List.of(), registrar -> {}));
        extensions.add(declarative("host.tools", "Host Tools",
                "Run-scoped Spring AI annotated host tools routed through ToolInvocationGateway",
                schema(true), 90, List.of(), registrar -> registrar.toolProvider(hostTools)));

        return extensions.stream().map(ExtensionArtifact::builtin).toList();
    }

    private static BuiltinCapabilityExtension declarative(
            String id, String name, String description, JsonNode schema, int order,
            List<ExtensionDependency> dependencies,
            java.util.function.Consumer<ExtensionRegistrar> contributions) {
        return new BuiltinCapabilityExtension(id, name, description, schema,
                BuiltinSchemas.ui(group(id), order), dependencies, contributions);
    }

    private static List<ExtensionDependency> dependsOnGraph() {
        return List.of(new ExtensionDependency("memory.graph", ">=2.0.0 <3.0.0", false));
    }

    private static ObjectNode schema(boolean enabled) {
        ObjectNode schema = BuiltinSchemas.objectSchema();
        return BuiltinSchemas.booleanProperty(schema, "enabled", enabled);
    }

    private static ObjectNode topKSchema() {
        ObjectNode schema = schema(true);
        BuiltinSchemas.integerProperty(schema, "topK", 3, 1, 50);
        return BuiltinSchemas.authoringMaximum(schema, "topK", 3);
    }

    private static ObjectNode intervalSchema() {
        ObjectNode schema = schema(true);
        return BuiltinSchemas.integerProperty(schema, "intervalHours", 24, 1, 720);
    }

    private static ObjectNode resultEvictionSchema() {
        ObjectNode schema = schema(true);
        BuiltinSchemas.integerProperty(schema, "maxCharacters", 4_000, 1_000, 200_000);
        BuiltinSchemas.authoringMaximum(schema, "maxCharacters", 8_000);
        return BuiltinSchemas.integerProperty(
                schema, "largeMaxCharacters", 8_000, 1_000, 8_000);
    }

    private static ObjectNode gepaAssessmentSchema() {
        ObjectNode schema = BuiltinSchemas.objectSchema();
        ObjectNode properties = (ObjectNode) schema.withObject("/properties");
        properties.putObject("mode").put("type", "string")
                .putArray("enum").add("rules").add("model");
        properties.putObject("score").put("type", "number")
                .put("minimum", 0).put("maximum", 1);
        properties.putObject("needsRevision").put("type", "boolean");
        properties.putObject("summary").put("type", "string").put("maxLength", 500);
        schema.putArray("required")
                .add("mode").add("score").add("needsRevision").add("summary");
        return schema;
    }

    private static String group(String id) {
        String prefix = id.substring(0, id.indexOf('.'));
        return prefix.substring(0, 1).toUpperCase(Locale.ROOT) + prefix.substring(1);
    }

    private static String textInput(com.javaclaw.framework.api.RunRequest request) {
        return request.inputs().stream().filter(block -> block.type().equals("core.text"))
                .map(block -> block.data().path("text").asText())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static boolean readOnly(String permission) {
        String value = permission.toLowerCase(Locale.ROOT);
        return !(value.contains("write") || value.contains("delete") || value.contains("execute")
                || value.contains("send") || value.contains("mutate") || value.contains("admin"));
    }

    static List<JsonNode> boundedKnowledgeContext(
            RetrieverContribution retriever, String query,
            com.javaclaw.framework.api.RunRequest request) {
        JsonNode enabled = request.attributes().get("framework.enableKnowledgeContext");
        // This attribute is an interactive-routing override. Its absence must retain the
        // pre-routing behavior used by schedule/workflow/plugin and other legacy entry points.
        if (enabled != null && !enabled.asBoolean(false)) return List.of();
        List<JsonNode> raw = retriever.retrieve(query, request);
        List<JsonNode> bounded = new ArrayList<>();
        int remaining = 1_500;
        for (JsonNode document : raw.stream().limit(3).toList()) {
            String rendered = document.toString();
            int tokens = com.javaclaw.util.TokenEstimator.estimate(rendered);
            if (tokens <= remaining) {
                bounded.add(document);
                remaining -= tokens;
            } else if (remaining > 0) {
                JsonNode truncated = textNodeWithinTokenBudget(rendered, remaining);
                if (truncated != null) bounded.add(truncated);
                break;
            }
        }
        return List.copyOf(bounded);
    }

    private static JsonNode textNodeWithinTokenBudget(String value, int tokenBudget) {
        int low = 0;
        int high = tokenBudget;
        JsonNode best = null;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            JsonNode candidate = JsonNodeFactory.instance.textNode(
                    com.javaclaw.util.TokenEstimator.truncateToTokens(value, middle));
            int tokens = com.javaclaw.util.TokenEstimator.estimate(candidate.toString());
            if (tokens <= tokenBudget) {
                best = candidate;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return best;
    }
}
