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
import com.javaclaw.framework.spi.AgentFrameworkExtension;
import com.javaclaw.framework.spi.EventCodec;
import com.javaclaw.framework.spi.EventTypeDescriptor;
import com.javaclaw.framework.spi.ExtensionDependency;
import com.javaclaw.framework.spi.ExtensionRegistrar;
import com.javaclaw.framework.spi.PromptContributor;
import com.javaclaw.framework.spi.RetrieverContribution;
import com.javaclaw.framework.spi.DeferredContextSource;
import com.javaclaw.framework.spi.FixedContextSource;
import com.javaclaw.framework.spi.OnDemandContributions;
import com.javaclaw.framework.spi.TurnPreparation;
import com.javaclaw.framework.spi.ToolProviderFactory;

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
            ToolProviderFactory hostTools) {
        return create(memoryRecall, memoryMutations, knowledgeRetriever, skillContributor,
                hostTools, context -> List.of(), null, null, null);
    }

    public static List<ExtensionArtifact> create(
            MemoryRecallGateway memoryRecall, MemoryMutationGateway memoryMutations,
            RetrieverContribution knowledgeRetriever, PromptContributor skillContributor,
            ToolProviderFactory hostTools, ToolProviderFactory subagents) {
        return create(memoryRecall, memoryMutations, knowledgeRetriever, skillContributor,
                hostTools, subagents, null, null, null);
    }

    public static List<ExtensionArtifact> create(
            MemoryRecallGateway memoryRecall, MemoryMutationGateway memoryMutations,
            RetrieverContribution knowledgeRetriever, PromptContributor skillContributor,
            ToolProviderFactory hostTools, ToolProviderFactory subagents,
            DeferredContextSource memorySource, DeferredContextSource knowledgeSource,
            DeferredContextSource skillSource) {
        return create(memoryRecall, memoryMutations, knowledgeRetriever, skillContributor,
                hostTools, subagents, memorySource, knowledgeSource, skillSource, null);
    }

    public static List<ExtensionArtifact> create(
            MemoryRecallGateway memoryRecall, MemoryMutationGateway memoryMutations,
            RetrieverContribution knowledgeRetriever, PromptContributor skillContributor,
            ToolProviderFactory hostTools, ToolProviderFactory subagents,
            DeferredContextSource memorySource, DeferredContextSource knowledgeSource,
            DeferredContextSource skillSource, FixedContextSource personaSource) {
        Objects.requireNonNull(memoryRecall, "memoryRecall");
        Objects.requireNonNull(memoryMutations, "memoryMutations");
        Objects.requireNonNull(knowledgeRetriever, "knowledgeRetriever");
        Objects.requireNonNull(skillContributor, "skillContributor");
        Objects.requireNonNull(hostTools, "hostTools");
        List<AgentFrameworkExtension> extensions = new ArrayList<>();

        extensions.add(declarative("memory.graph", "Memory Graph",
                "EclipseStore authoritative graph with a Spring AI VectorStore adapter",
                schema(true), 10, List.of(), registrar -> {}));
        extensions.add(new MemoryRecallExtension(memoryRecall, memorySource, personaSource));
        extensions.add(declarative("memory.correction", "Memory Correction",
                "Durable corrections, conflict/supersede/undo and final-answer protection",
                schema(true), 30, dependsOnGraph(), registrar -> {
                    PromptContributor correctionPrompt = (request, state) -> {
                        String input = textInput(request);
                        String previous = request.attributes().containsKey("previousAssistantReply")
                                ? request.attributes().get("previousAssistantReply").asText("") : "";
                        JsonNode correction = memoryMutations.applyCorrection(
                                state.runId(), request, input, previous);
                        if (correction == null || correction.isNull()) return "";
                        String prompt = correction.path("prompt").asText("");
                        return prompt.isBlank() ? "" : prompt;
                    };
                    registrar.promptContributor(OnDemandContributions.eagerOnly(correctionPrompt));
                    registrar.turnPreparation(new TurnPreparation() {
                        @Override public String id() { return "memory.correction"; }
                        @Override public String prepare(com.javaclaw.framework.api.RunRequest request,
                                                        com.javaclaw.framework.spi.ExtensionStateView state) {
                            return correctionPrompt.contribute(request, state);
                        }
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
                    registrar.eventType(new EventTypeDescriptor(
                            "gepa.evaluate.assessment", 2, gepaUnavailableAssessmentSchema()),
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
                        registrar.promptContributor(OnDemandContributions.fixed((request, state) ->
                                "For complex work, keep goals explicit and emit progress through run events. "
                                        + "Do not create a second plan state machine."))));

        extensions.add(declarative("knowledge.rag", "Knowledge / RAG",
                "Spring AI Retriever and RAG Advisor over workspace knowledge",
                topKSchema(), 10, List.of(), registrar -> {
                    registrar.retriever(OnDemandContributions.deferred(knowledgeRetriever, "knowledge"));
                    if (knowledgeSource != null) registrar.deferredContextSource(knowledgeSource);
                }));
        extensions.add(declarative("skill.runtime", "Skills",
                "Reusable prompt, tool and curation workflow contributions",
                schema(true), 20, List.of(), registrar -> {
                    registrar.promptContributor(OnDemandContributions.deferred(skillContributor, "skills"));
                    if (skillSource != null) registrar.deferredContextSource(skillSource);
                }));
        extensions.add(declarative("plan.readonly", "Read-only Plan Mode",
                "PlanRevision with a permission profile that cannot execute mutations",
                schema(true), 30, List.of(), registrar ->
                        registrar.permissionPolicy((current, configuration, request) ->
                                current.intersect(PermissionSet.of(
                                        "tool.read", "memory.read", "knowledge.read",
                                        "interaction.request", "subagent.delegate")))));
        extensions.add(declarative("subagent.run", "SubAgent",
                "Parent/child AgentClient runs with shared kernel budgets and cancellation",
                schema(true), 40, List.of(), registrar -> registrar.toolProvider(subagents)));
        extensions.add(declarative("context.compaction", "Context Compaction",
                "Bounded model-visible messages and tool schemas with durable full evidence",
                contextCompactionSchema(), 50, List.of(), registrar -> {}));
        extensions.add(declarative("context.on_demand", "On-demand Context",
                "Select per-Step context and tools before each provider call",
                onDemandContextSchema(), 55, List.of(), registrar -> {}));
        extensions.add(declarative("tool.result-eviction", "Tool Result Eviction",
                "Size-aware tool-result post-processing without losing durable events",
                resultEvictionSchema(), 60, List.of(), registrar ->
                        registrar.toolResultPostProcessor((current, tool, context, request) -> {
                            if (context.internalContextRead()) return current;
                            String rendered = current.isTextual()
                                    ? current.asText() : current.toString();
                            int limit = com.javaclaw.framework.api.CapabilityRuntime.configuration(
                                    request, "tool.result-eviction")
                                    .path("maxCharacters").asInt(16_000);
                            if (rendered.length() <= limit) return current;
                            ObjectNode bounded = JsonNodeFactory.instance.objectNode();
                            bounded.put("truncated", true);
                            bounded.put("tool", tool.name());
                            bounded.put("originalCharacters", rendered.length());
                            bounded.put("preview", rendered.substring(0, limit));
                            bounded.put("note", "Full output is retained in core.tool.completed");
                            return bounded;
                        })));
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
        return BuiltinSchemas.integerProperty(schema, "topK", 8, 1, 50);
    }

    private static ObjectNode intervalSchema() {
        ObjectNode schema = schema(true);
        return BuiltinSchemas.integerProperty(schema, "intervalHours", 24, 1, 720);
    }

    private static ObjectNode contextCompactionSchema() {
        ObjectNode schema = schema(true);
        BuiltinSchemas.integerProperty(schema, "maxMessageCharacters", 48_000, 4_000, 200_000);
        BuiltinSchemas.integerProperty(schema, "maxToolSchemaCharacters", 48_000, 4_000, 200_000);
        BuiltinSchemas.integerProperty(schema, "retainedToolExchanges", 4, 1, 32);
        BuiltinSchemas.integerProperty(schema, "maxToolResultCharacters", 16_000, 1_000, 100_000);
        return BuiltinSchemas.integerProperty(schema, "maxTools", 64, 1, 256);
    }

    private static ObjectNode onDemandContextSchema() {
        ObjectNode schema = schema(true);
        BuiltinSchemas.integerProperty(schema, "searches", 2, 1, 32);
        BuiltinSchemas.integerProperty(schema, "fetches", 3, 1, 64);
        BuiltinSchemas.integerProperty(schema, "candidates", 32, 1, 256);
        BuiltinSchemas.integerProperty(schema, "plannerInputChars", 8_000, 1_000, 200_000);
        BuiltinSchemas.integerProperty(schema, "selectedBodyChars", 12_000, 1_000, 200_000);
        return BuiltinSchemas.integerProperty(schema, "selectedTools", 8, 1, 256);
    }

    private static ObjectNode resultEvictionSchema() {
        ObjectNode schema = schema(true);
        return BuiltinSchemas.integerProperty(
                schema, "maxCharacters", 16_000, 1_000, 200_000);
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

    private static ObjectNode gepaUnavailableAssessmentSchema() {
        ObjectNode schema = BuiltinSchemas.objectSchema();
        ObjectNode properties = (ObjectNode) schema.withObject("/properties");
        properties.putObject("mode").put("const", "unavailable");
        properties.putObject("reason").put("const", "answer_too_large");
        properties.putObject("summary").put("type", "string").put("maxLength", 500);
        schema.putArray("required").add("mode").add("reason").add("summary");
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

}
