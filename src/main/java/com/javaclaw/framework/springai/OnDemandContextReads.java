package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.StepId;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.RunStepQuery;
import com.javaclaw.framework.core.ToolGroupAccess;
import com.javaclaw.framework.core.ToolInvocationGateway;
import com.javaclaw.framework.core.ToolApprovalRequiredException;
import com.javaclaw.framework.core.ToolInputRequiredException;
import com.javaclaw.framework.core.ToolRecoveryRequiredException;
import com.javaclaw.framework.spi.DeferredContextCandidate;
import com.javaclaw.framework.spi.DeferredContextSource;
import com.javaclaw.framework.spi.DeferredContextUse;
import com.javaclaw.framework.spi.FrameworkTool;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.RunCancelledException;
import com.javaclaw.framework.spi.ToolDescriptor;
import com.javaclaw.framework.spi.ToolExecutionContext;
import com.javaclaw.framework.spi.ToolPolicyDecision;
import com.javaclaw.framework.springai.OnDemandContextSession.SourceCandidate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.javaclaw.framework.springai.OnDemandContextSession.digest;
import static com.javaclaw.framework.springai.OnDemandContextSession.excerpt;
import static com.javaclaw.framework.springai.OnDemandContextSession.pause;

/** Executes and validates deferred-context reads within this Run. */
final class OnDemandContextReads {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private final ReasoningRequest request;
    private final ToolInvocationGateway tools;
    private final RunStore runs;
    private final RunStepQuery steps;
    private final ObjectMapper json;

    OnDemandContextReads(ReasoningRequest request, ToolInvocationGateway tools,
            RunStore runs, RunStepQuery steps, ObjectMapper json) {
        this.request = request;
        this.tools = tools;
        this.runs = runs;
        this.steps = steps;
        this.json = json;
    }

    JsonNode invokeRead(FrameworkTool tool, JsonNode arguments, String invocation, int reservedCalls) {
        AgentStep old = existingRead(invocation);
        if (old != null) {
            if (old.output().path("waitingInput").asBoolean(false)) {
                throw new ToolInputRequiredException(old.output().path("modelOutput"),
                        "context read still requires user input");
            }
            JsonNode value = old.output().path("modelOutput");
            if (!value.isObject()) throw pause("persisted context read is unavailable: " + old.id().value());
            return value;
        }
        if (request.control().remainingToolCalls() <= reservedCalls) {
            throw pause("tool-call budget is insufficient for context reads and the selected business tool");
        }
        try {
            SpringAiToolCallback.invokeInline(tool, arguments, request, tools, invocation);
            AgentStep completed = existingRead(invocation);
            if (completed == null) throw pause("context read was not durably recorded: " + invocation);
            return completed.output().path("modelOutput");
        } catch (ToolApprovalRequiredException | ToolInputRequiredException
                | ToolRecoveryRequiredException | RunCancelledException control) {
            throw control;
        } catch (RuntimeException failure) {
            throw pause("context read failed: " + failure.getMessage(), failure);
        }
    }

    void ensureReadBudget(List<String> invocations, int reservedCalls) {
        long pending = invocations.stream().distinct().filter(invocation ->
                existingRead(invocation) == null).count();
        if (pending + reservedCalls > request.control().remainingToolCalls()) {
            throw pause("tool-call budget is insufficient for the planned context reads "
                    + "and selected business tool");
        }
    }

    private AgentStep existingRead(String invocation) {
        int separator = invocation.indexOf('/');
        if (separator < 0) throw new IllegalArgumentException("invalid context invocation ID");
        String suffix = invocation.substring(separator);
        boolean search = suffix.startsWith("/search/");
        boolean fetch = suffix.startsWith("/fetch/");
        if (!search && !fetch) throw new IllegalArgumentException("invalid context invocation kind");
        String source = suffix.substring(search ? "/search/".length() : "/fetch/".length(),
                suffix.lastIndexOf('/'));
        String expectedTool = "framework_context_" + (search ? "search_" : "fetch_")
                + digest(source).substring(0, 12);
        AgentStep newest = null;
        for (AgentStep step : steps.steps(request.runId())) {
            if (step.kind() != AgentStep.Kind.TOOL || step.input() == null
                    || !step.input().path("tool").asText().equals(expectedTool)) continue;
            String recorded = step.input().path("invocationId").asText();
            if (!(search ? recorded.equals(invocation) : recorded.endsWith(suffix))) continue;
            if (step.state() != AgentStep.State.COMPLETED) {
                throw pause("context read outcome is unknown; reconcile step " + step.id().value());
            }
            if (step.output() == null || readOutputRedacted(step.id())) {
                throw pause("persisted context read was redacted: " + step.id().value());
            }
            JsonNode raw = step.output().path("rawOutput");
            JsonNode model = step.output().path("modelOutput");
            if (!raw.isObject() || !raw.equals(model)) {
                throw pause("persisted context read is incomplete or modified: " + step.id().value());
            }
            newest = step;
        }
        return newest;
    }

    boolean readOutputRedacted(StepId id) {
        return runs.eventsAfter(request.runId(), 0).stream().anyMatch(event ->
                event.type().equals("core.step.completed")
                        && event.payload().path("stepId").asText().equals(id.value())
                        && event.payload().path("credentialRedacted").asBoolean(false));
    }

    static String searchInvocation(String key, String source, String query, int limit) {
        return key + "/search/" + source + "/" + digest(query + "\n" + limit);
    }

    static String fetchInvocation(String key, SourceCandidate candidate) {
        return key + "/fetch/" + candidate.source().id() + "/"
                + digest(candidate.id() + "\n" + candidate.version());
    }

    FrameworkTool searchTool(DeferredContextSource source) {
        JsonNode schema;
        try { schema = json.readTree("""
                {"type":"object","properties":{"query":{"type":"string"},
                "limit":{"type":"integer","minimum":1}},
                "required":["query","limit"],"additionalProperties":false}
                """); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
        ToolDescriptor descriptor = new ToolDescriptor("framework_context_search_" + digest(source.id()).substring(0, 12),
                "Search context source " + source.id(), schema, source.group(),
                source.requiredPermissions(), true);
        return new com.javaclaw.framework.spi.FrameworkContextReadTool() {
            @Override public ToolDescriptor descriptor() { return descriptor; }
            @Override public JsonNode execute(JsonNode arguments, ToolExecutionContext context) {
                List<DeferredContextCandidate> candidates = source.search(request.runRequest(),
                        arguments.path("query").asText(), arguments.path("limit").asInt());
                ObjectNode output = NODES.objectNode();
                ArrayNode list = output.putArray("candidates");
                for (DeferredContextCandidate candidate : candidates) {
                    if (list.size() >= arguments.path("limit").asInt()) break;
                    if (!request.plan().descriptor().permissions()
                            .containsAll(candidate.requiredPermissions())) continue;
                    SourceCandidate selected = new SourceCandidate(source, candidate.id(),
                            candidate.version(), candidate.summary(), candidate.requiredPermissions(),
                            candidate.use());
                    if (!allowedByToolPolicy(fetchTool(selected).descriptor())) continue;
                    ObjectNode item = list.addObject().put("id", candidate.id())
                            .put("version", candidate.version())
                            .put("use", candidate.use().name())
                            .put("summary", excerpt(candidate.summary(), 500));
                    ArrayNode grants = item.putArray("requiredPermissions");
                    candidate.requiredPermissions().values().forEach(value -> grants.add(value.value()));
                }
                return output;
            }
        };
    }

    FrameworkTool fetchTool(SourceCandidate candidate) {
        JsonNode schema;
        try { schema = json.readTree("""
                {"type":"object","properties":{"id":{"type":"string"},
                "version":{"type":"string"}},"required":["id","version"],
                "additionalProperties":false}
                """); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
        ToolDescriptor descriptor = new ToolDescriptor("framework_context_fetch_"
                + digest(candidate.source().id()).substring(0, 12),
                "Fetch selected context from " + candidate.source().id(), schema,
                candidate.source().group(), combinedPermissions(candidate), true);
        return new com.javaclaw.framework.spi.FrameworkContextReadTool() {
            @Override public ToolDescriptor descriptor() { return descriptor; }
            @Override public JsonNode execute(JsonNode arguments, ToolExecutionContext context) {
                String body = candidate.source().fetch(request.runRequest(),
                        arguments.path("id").asText(), arguments.path("version").asText());
                return NODES.objectNode().put("body", body);
            }
        };
    }

    boolean sourceAllowed(DeferredContextSource source) {
        JsonNode disabled = request.runRequest().attributes().get("framework.disableTools");
        if (disabled != null && disabled.asBoolean(false)) return false;
        if (!ToolGroupAccess.allows(request.runRequest(), source.group())
                || !request.plan().descriptor().permissions()
                        .containsAll(source.requiredPermissions())) return false;
        ToolDescriptor search = searchTool(source).descriptor();
        ToolDescriptor fetch = fetchTool(new SourceCandidate(source, "", "", "",
                PermissionSet.NONE, DeferredContextUse.REFERENCE)).descriptor();
        return allowedByToolPolicy(search) && allowedByToolPolicy(fetch);
    }

    boolean allowedByToolPolicy(ToolDescriptor descriptor) {
        for (var policy : request.plan().toolPolicies()) {
            if (policy.evaluate(descriptor,
                    request.plan().descriptor().toolPolicy(), request.runRequest())
                    == ToolPolicyDecision.DENY) return false;
        }
        return true;
    }

    private static PermissionSet combinedPermissions(SourceCandidate candidate) {
        Set<com.javaclaw.framework.api.Permission> required = new HashSet<>(
                candidate.source().requiredPermissions().values());
        required.addAll(candidate.requiredPermissions().values());
        return new PermissionSet(required);
    }
}
