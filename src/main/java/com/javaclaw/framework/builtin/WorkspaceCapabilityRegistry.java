package com.javaclaw.framework.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.builtin.memory.MemoryMutationGateway;
import com.javaclaw.framework.builtin.memory.MemoryRecallGateway;
import com.javaclaw.framework.spi.ExtensionStateView;
import com.javaclaw.framework.spi.PromptContributor;
import com.javaclaw.framework.spi.RetrieverContribution;
import com.javaclaw.framework.spi.DeferredContextSource;
import com.javaclaw.framework.spi.DeferredContextCandidate;
import com.javaclaw.framework.spi.FixedContextSnapshot;
import com.javaclaw.framework.spi.FixedContextSource;
import com.javaclaw.framework.api.PermissionSet;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide routing bridge for workspace-scoped built-in capability adapters.
 *
 * <p>The extension graph is installed once in the root context. Workspace resources, however,
 * are opened and closed with child contexts. This registry keeps those lifecycles separate and
 * routes every operation exclusively from {@link RunRequest#scope()}; no Agent singleton retains
 * mutable workspace state.</p>
 */
public final class WorkspaceCapabilityRegistry implements
        MemoryRecallGateway, MemoryMutationGateway, RetrieverContribution, PromptContributor {

    private final Map<String, Entry> workspaces = new ConcurrentHashMap<>();

    public Registration register(
            String workspaceId,
            MemoryRecallGateway memoryRecall,
            MemoryMutationGateway memoryMutations,
            RetrieverContribution knowledgeRetriever,
            PromptContributor skillContributor,
            DeferredContextSource memorySource,
            DeferredContextSource knowledgeSource,
            DeferredContextSource skillSource,
            FixedContextSource personaSource) {
        String id = required(workspaceId);
        Map<String, DeferredContextSource> sources = Map.of(
                "memory", checkedSource(memorySource, "memory"),
                "knowledge", checkedSource(knowledgeSource, "knowledge"),
                "skills", checkedSource(skillSource, "skills"));
        Entry entry = new Entry(memoryRecall, memoryMutations, knowledgeRetriever,
                skillContributor, sources, checkedSource(personaSource, "memory.persona"));
        Entry existing = workspaces.putIfAbsent(id, entry);
        if (existing != null) {
            throw new IllegalStateException("workspace capabilities already registered: " + id);
        }
        return new Registration(id, entry);
    }

    /** A stable root-context proxy; workspace-scoped implementations remain owned by child contexts. */
    public DeferredContextSource contextSource(String sourceId) {
        String id = required(sourceId);
        String group = switch (id) {
            case "memory" -> "memory";
            case "knowledge" -> "knowledge";
            case "skills" -> "skill";
            default -> throw new IllegalArgumentException("unknown built-in context source: " + id);
        };
        return new DeferredContextSource() {
            @Override public String id() { return id; }
            @Override public String description() { return "Workspace " + id + " context"; }
            @Override public String group() { return group; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("tool.read");
            }
            @Override public List<DeferredContextCandidate> search(
                    RunRequest request, String query, int limit) {
                return source(request, id).search(request, query, limit);
            }
            @Override public String fetch(RunRequest request, String candidateId, String version) {
                return source(request, id).fetch(request, candidateId, version);
            }
        };
    }

    /** 固定来源代理仅按请求作用域路由到当前工作区实例。 */
    public FixedContextSource fixedContextSource(String sourceId) {
        if (!"memory.persona".equals(required(sourceId))) {
            throw new IllegalArgumentException("unknown fixed context source: " + sourceId);
        }
        return new FixedContextSource() {
            @Override public String id() { return "memory.persona"; }
            @Override public String group() { return "memory"; }
            @Override public PermissionSet requiredPermissions() {
                return PermissionSet.of("tool.read");
            }
            @Override public FixedContextSnapshot read(RunRequest request) {
                Entry entry = entry(request);
                return entry.personaSource.read(request);
            }
        };
    }

    private DeferredContextSource source(RunRequest request, String sourceId) {
        Entry entry = entry(request);
        DeferredContextSource source = entry.contextSources.get(sourceId);
        if (source == null) throw new IllegalStateException("context source is unavailable: " + sourceId);
        return source;
    }

    private static DeferredContextSource checkedSource(DeferredContextSource source, String id) {
        if (!id.equals(Objects.requireNonNull(source, "source").id())) {
            throw new IllegalArgumentException("context source ID mismatch: " + id);
        }
        return source;
    }

    private Entry entry(RunRequest request) {
        Entry selected = workspaces.get(request.scope().workspaceId());
        if (selected == null) throw new IllegalStateException(
                "workspace context source is unavailable: " + request.scope().workspaceId());
        return selected;
    }

    private static FixedContextSource checkedSource(FixedContextSource source, String id) {
        if (!id.equals(Objects.requireNonNull(source, "source").id())) {
            throw new IllegalArgumentException("fixed context source ID mismatch: " + id);
        }
        return source;
    }

    @Override
    public String recall(RunRequest request, String query, int topK) {
        Entry entry = workspaces.get(request.scope().workspaceId());
        return entry == null ? "" : entry.memoryRecall.recall(request, query, topK);
    }

    @Override
    public JsonNode applyCorrection(
            RunId runId, RunRequest request, String userInput, String previousReply) {
        Entry entry = workspaces.get(request.scope().workspaceId());
        return entry == null ? null : entry.memoryMutations.applyCorrection(
                runId, request, userInput, previousReply);
    }

    @Override
    public JsonNode protectOutput(RunId runId, RunRequest request, JsonNode output) {
        Entry entry = workspaces.get(request.scope().workspaceId());
        return entry == null ? output : entry.memoryMutations.protectOutput(runId, request, output);
    }

    @Override
    public void distill(RunId runId, RunRequest request, JsonNode completedOutput) {
        Entry entry = workspaces.get(request.scope().workspaceId());
        if (entry != null) entry.memoryMutations.distill(runId, request, completedOutput);
    }

    @Override
    public List<JsonNode> retrieve(String query, RunRequest request) {
        Entry entry = workspaces.get(request.scope().workspaceId());
        return entry == null ? List.of() : entry.knowledgeRetriever.retrieve(query, request);
    }

    @Override
    public String contribute(RunRequest request, ExtensionStateView state) {
        Entry entry = workspaces.get(request.scope().workspaceId());
        return entry == null ? "" : entry.skillContributor.contribute(request, state);
    }

    public int registeredWorkspaceCount() {
        return workspaces.size();
    }

    private static String required(String value) {
        String result = Objects.requireNonNull(value, "workspaceId").trim();
        if (result.isEmpty()) throw new IllegalArgumentException("workspaceId must not be blank");
        return result;
    }

    private record Entry(
            MemoryRecallGateway memoryRecall,
            MemoryMutationGateway memoryMutations,
            RetrieverContribution knowledgeRetriever,
            PromptContributor skillContributor,
            Map<String, DeferredContextSource> contextSources,
            FixedContextSource personaSource) {
        private Entry {
            Objects.requireNonNull(memoryRecall, "memoryRecall");
            Objects.requireNonNull(memoryMutations, "memoryMutations");
            Objects.requireNonNull(knowledgeRetriever, "knowledgeRetriever");
            Objects.requireNonNull(skillContributor, "skillContributor");
            contextSources = Map.copyOf(Objects.requireNonNull(contextSources, "contextSources"));
            Objects.requireNonNull(personaSource, "personaSource");
        }
    }

    public final class Registration implements AutoCloseable {
        private final String workspaceId;
        private final Entry entry;
        private boolean closed;

        private Registration(String workspaceId, Entry entry) {
            this.workspaceId = workspaceId;
            this.entry = entry;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            workspaces.remove(workspaceId, entry);
        }
    }
}
