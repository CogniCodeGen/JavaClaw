package com.javaclaw.framework.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.builtin.memory.MemoryMutationGateway;
import com.javaclaw.framework.builtin.memory.MemoryRecallGateway;
import com.javaclaw.framework.spi.ExtensionStateView;
import com.javaclaw.framework.spi.PromptContributor;
import com.javaclaw.framework.spi.RetrieverContribution;

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
            PromptContributor skillContributor) {
        String id = required(workspaceId);
        Entry entry = new Entry(memoryRecall, memoryMutations, knowledgeRetriever, skillContributor);
        Entry existing = workspaces.putIfAbsent(id, entry);
        if (existing != null) {
            throw new IllegalStateException("workspace capabilities already registered: " + id);
        }
        return new Registration(id, entry);
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
            PromptContributor skillContributor) {
        private Entry {
            Objects.requireNonNull(memoryRecall, "memoryRecall");
            Objects.requireNonNull(memoryMutations, "memoryMutations");
            Objects.requireNonNull(knowledgeRetriever, "knowledgeRetriever");
            Objects.requireNonNull(skillContributor, "skillContributor");
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
