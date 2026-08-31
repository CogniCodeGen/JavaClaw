package com.javaclaw.server.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.javaclaw.agent.conversation.ProfileUseCases;
import com.javaclaw.agent.runtime.ThreadUseCases;
import com.javaclaw.agent.runtime.TurnUseCases;
import com.javaclaw.agent.runtime.WorkspaceUseCases;
import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.ThreadEvent;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadSnapshot;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStartCommand;
import com.javaclaw.core.api.Workspace;
import com.javaclaw.core.api.WorkspaceId;
import com.javaclaw.protocol.RpcMethods;

/** Thread, Turn and durable event protocol adapter. */
final class ThreadRpcHandler implements RpcHandler {
    private static final Set<String> METHODS = Set.of(
            RpcMethods.THREAD_START,
            RpcMethods.THREAD_RESUME,
            RpcMethods.THREAD_FORK,
            RpcMethods.THREAD_READ,
            RpcMethods.THREAD_LIST,
            RpcMethods.THREAD_ARCHIVE,
            RpcMethods.THREAD_UNARCHIVE,
            RpcMethods.THREAD_DELETE,
            RpcMethods.THREAD_COMPACT_START,
            RpcMethods.THREAD_UPDATE,
            RpcMethods.THREAD_PLAN_ADOPT,
            RpcMethods.THREAD_EXECUTION_SUMMARY,
            RpcMethods.THREAD_RETRY_IN_NEW_BRANCH,
            RpcMethods.WORKTREE_LIST,
            RpcMethods.WORKTREE_PATCH,
            RpcMethods.WORKTREE_CLEANUP,
            RpcMethods.TURN_START,
            RpcMethods.TURN_STEER,
            RpcMethods.TURN_INTERRUPT,
            RpcMethods.EVENT_LIST);

    private final WorkspaceUseCases workspaces;
    private final ThreadUseCases threads;
    private final TurnUseCases turns;
    private final ProfileUseCases profiles;
    private final com.javaclaw.agent.conversation.PlanAdoptionUseCases plans;
    private final com.javaclaw.agent.conversation.CompactionUseCases compaction;
    private final com.javaclaw.server.collaboration.WorktreeRecoveryUseCases worktreeRecovery;
    private final ThreadSubscriptionAccess subscriptions;
    private final ObjectMapper json;
    private final ProtocolMapper wire;

    ThreadRpcHandler(
            WorkspaceUseCases workspaces,
            ThreadUseCases threads,
            TurnUseCases turns,
            ProfileUseCases profiles,
            com.javaclaw.agent.conversation.PlanAdoptionUseCases plans,
            com.javaclaw.agent.conversation.CompactionUseCases compaction,
            com.javaclaw.server.collaboration.WorktreeRecoveryUseCases worktreeRecovery,
            ThreadSubscriptionAccess subscriptions,
            ObjectMapper json,
            ProtocolMapper wire) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.threads = Objects.requireNonNull(threads, "threads");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.profiles = profiles;
        this.plans = plans;
        this.compaction = compaction;
        this.worktreeRecovery = worktreeRecovery;
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
        this.json = Objects.requireNonNull(json, "json");
        this.wire = Objects.requireNonNull(wire, "wire");
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public JsonNode handle(String method, JsonNode params) {
        return switch (method) {
            case RpcMethods.THREAD_START -> startThread(params);
            case RpcMethods.THREAD_RESUME -> resumeThread(params);
            case RpcMethods.THREAD_FORK -> forkThread(params);
            case RpcMethods.THREAD_READ ->
                json.valueToTree(wire.snapshot(requireSnapshot(RequestParameters.threadId(params))));
            case RpcMethods.THREAD_LIST ->
                json.valueToTree(
                        threads
                                .listThreads(RequestParameters.optionalBoolean(params, "includeArchived", false))
                                .stream()
                                .map(wire::thread)
                                .toList());
            case RpcMethods.THREAD_ARCHIVE ->
                json.valueToTree(wire.thread(threads.archiveThread(
                        RequestParameters.threadId(params),
                        RequestParameters.optionalLong(params, "expectedRevision", -1),
                        RequestParameters.optionalText(params, "idempotencyKey", null))));
            case RpcMethods.THREAD_UNARCHIVE ->
                json.valueToTree(wire.thread(threads.unarchiveThread(
                        RequestParameters.threadId(params),
                        RequestParameters.optionalLong(params, "expectedRevision", -1),
                        RequestParameters.optionalText(params, "idempotencyKey", null))));
            case RpcMethods.THREAD_DELETE -> deleteThread(params);
            case RpcMethods.THREAD_UPDATE ->
                json.valueToTree(wire.thread(threads.updateThread(
                        RequestParameters.threadId(params),
                        RequestParameters.optionalText(params, "title", ""),
                        RequestParameters.optionalLong(params, "expectedRevision", -1),
                        RequestParameters.optionalText(params, "idempotencyKey", null))));
            case RpcMethods.TURN_START -> startTurn(params);
            case RpcMethods.THREAD_PLAN_ADOPT -> adoptPlan(params);
            case RpcMethods.THREAD_EXECUTION_SUMMARY -> executionSummary(params);
            case RpcMethods.THREAD_RETRY_IN_NEW_BRANCH -> retryInNewBranch(params);
            case RpcMethods.THREAD_COMPACT_START -> startCompaction(params);
            case RpcMethods.WORKTREE_LIST, RpcMethods.WORKTREE_PATCH, RpcMethods.WORKTREE_CLEANUP ->
                worktree(method, params);
            case RpcMethods.TURN_STEER ->
                RpcResults.flag(
                        "accepted",
                        turns.steer(
                                new TurnId(RequestParameters.requiredText(params, "turnId")),
                                RequestParameters.steeringInput(params)));
            case RpcMethods.TURN_INTERRUPT ->
                RpcResults.flag(
                        "interrupted", turns.interrupt(new TurnId(RequestParameters.requiredText(params, "turnId"))));
            case RpcMethods.EVENT_LIST -> listEvents(params);
            default -> throw new RpcRouter.MethodNotFound(method);
        };
    }

    private JsonNode startThread(JsonNode params) {
        AgentThread thread = threads.startThread(
                new WorkspaceId(RequestParameters.requiredText(params, "workspaceId")),
                RequestParameters.optionalText(params, "title", ""),
                RequestParameters.optionalText(params, "idempotencyKey", null));
        subscriptions.register(thread.id(), thread.lastSequence());
        return json.valueToTree(wire.thread(thread));
    }

    private JsonNode worktree(String method, JsonNode params) {
        if (worktreeRecovery == null) {
            throw new IllegalStateException("worktree recovery is unavailable");
        }
        if (RpcMethods.WORKTREE_LIST.equals(method)) {
            return json.valueToTree(
                    worktreeRecovery
                            .listRecovery(new WorkspaceId(RequestParameters.requiredText(params, "workspaceId")))
                            .stream()
                            .map(wire::worktree)
                            .toList());
        }
        ThreadId child = new ThreadId(RequestParameters.requiredText(params, "childThreadId"));
        long revision = RequestParameters.optionalLong(params, "expectedRevision", -1);
        if (RpcMethods.WORKTREE_PATCH.equals(method)) {
            return json.valueToTree(wire.worktreePatch(worktreeRecovery.exportPatch(child, revision)));
        }
        return json.valueToTree(wire.worktree(
                worktreeRecovery.cleanup(new com.javaclaw.server.collaboration.WorktreeRecoveryUseCases.CleanupRequest(
                        child,
                        revision,
                        RequestParameters.optionalBoolean(params, "discardUnmerged", false),
                        RequestParameters.requiredText(params, "idempotencyKey")))));
    }

    private JsonNode startCompaction(JsonNode params) {
        if (compaction == null) {
            throw new IllegalStateException("model compaction is unavailable");
        }
        ThreadId thread = RequestParameters.threadId(params);
        subscriptions.register(thread, requireSnapshot(thread).thread().lastSequence());
        compaction.start(thread);
        return JsonNodeFactory.instance.objectNode();
    }

    private JsonNode adoptPlan(JsonNode params) {
        if (plans == null) {
            throw new IllegalStateException("Plan adoption is not configured");
        }
        ThreadId thread = RequestParameters.threadId(params);
        subscriptions.register(thread, requireSnapshot(thread).thread().lastSequence());
        var turn = plans.adopt(
                thread,
                new com.javaclaw.core.api.ItemId(RequestParameters.requiredText(params, "planItemId")),
                RequestParameters.requiredText(params, "profileId"),
                RequestParameters.optionalLong(params, "expectedProfileRevision", -1),
                RequestParameters.optionalText(params, "decisions", ""),
                RequestParameters.requiredText(params, "idempotencyKey"));
        return json.valueToTree(wire.turn(turn));
    }

    private JsonNode resumeThread(JsonNode params) {
        ThreadId id = RequestParameters.threadId(params);
        long after = RequestParameters.optionalLong(params, "afterSequence", 0);
        ThreadSnapshot snapshot = requireSnapshot(id);
        List<ThreadEvent> replay = subscriptions.subscribeAndRead(id, after);
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.set("snapshot", json.valueToTree(wire.snapshot(snapshot)));
        result.set("events", json.valueToTree(replay.stream().map(wire::event).toList()));
        result.set(
                "liveItems",
                json.valueToTree(subscriptions.activeItems(id).stream()
                        .map(wire::liveItem)
                        .toList()));
        return result;
    }

    private JsonNode executionSummary(JsonNode params) {
        ThreadId threadId = RequestParameters.threadId(params);
        ThreadSnapshot snapshot = requireSnapshot(threadId);
        var usage = new java.util.LinkedHashMap<TurnId, ThreadEvent>();
        long afterSequence = 0;
        while (true) {
            List<ThreadEvent> page = threads.eventsAfter(threadId, afterSequence, 10_000);
            for (ThreadEvent event : page) {
                if (event.turnId() != null && "usage/updated".equals(event.type())) {
                    usage.put(event.turnId(), event);
                }
            }
            if (page.size() < 10_000) {
                break;
            }
            afterSequence = page.getLast().sequence();
        }
        var turns = snapshot.turns().stream()
                .map(turn -> {
                    ThreadEvent event = usage.get(turn.id());
                    return new com.javaclaw.protocol.WireTurnExecutionSummary(
                            turn.id().value(),
                            turn.config().attributes().getOrDefault("profileId", ""),
                            turn.config().provider(),
                            turn.config().model(),
                            turn.status().name(),
                            turn.startedAt(),
                            turn.completedAt(),
                            usageValue(event, "inputTokens"),
                            usageValue(event, "outputTokens"),
                            usageValue(event, "reasoningTokens"));
                })
                .toList();
        return json.valueToTree(new com.javaclaw.protocol.WireThreadExecutionSummary(threadId.value(), turns));
    }

    private JsonNode retryInNewBranch(JsonNode params) {
        if (profiles == null) {
            throw new IllegalStateException("Profile resolution is unavailable");
        }
        ThreadId sourceId = RequestParameters.threadId(params);
        TurnId targetId = new TurnId(RequestParameters.requiredText(params, "targetTurnId"));
        String idempotencyKey = RequestParameters.requiredText(params, "idempotencyKey");
        if (idempotencyKey.length() > 230) {
            throw new IllegalArgumentException("idempotencyKey must not exceed 230 characters");
        }
        ThreadSnapshot source = requireSnapshot(sourceId);
        AgentTurn target = source.turns().stream()
                .filter(turn -> turn.id().equals(targetId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("target Turn does not belong to source Thread"));
        if (!target.status().terminal()) {
            throw new IllegalStateException("cannot retry a non-terminal Turn");
        }
        List<TurnInput> input = retryInput(target, params);
        TurnConfig config = resolveConfig(params, source);
        String branchKey = idempotencyKey + ":branch";
        String turnKey = idempotencyKey + ":turn";
        AgentThread fork = null;
        try {
            fork = threads.forkBeforeTurn(sourceId, targetId, source.thread().title() + " · 重试分支", null, branchKey);
            subscriptions.register(fork.id(), fork.lastSequence());
            AgentTurn started = turns.startTurn(new TurnStartCommand(fork.id(), input, config, turnKey));
            AgentThread current = requireSnapshot(fork.id()).thread();
            return json.valueToTree(
                    new com.javaclaw.protocol.WireThreadBranchStart(wire.thread(current), wire.turn(started)));
        } catch (RuntimeException failure) {
            if (fork != null) {
                try {
                    threads.rollbackRetryBranch(fork.id(), branchKey, turnKey);
                    subscriptions.remove(fork.id());
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    private static List<TurnInput> retryInput(AgentTurn target, JsonNode params) {
        if (!RequestParameters.optionalBoolean(params, "replaceInput", false)) {
            return target.input();
        }
        String replacement = RequestParameters.optionalText(params, "replacementText", "");
        ArrayList<TurnInput> input = new ArrayList<>();
        if (!replacement.isBlank()) {
            input.add(new TurnInput.Text(replacement));
        }
        target.input().stream()
                .filter(TurnInput.AttachmentRef.class::isInstance)
                .forEach(input::add);
        if (input.isEmpty()) {
            throw new IllegalArgumentException("replacement input must contain text or a referenced attachment");
        }
        return List.copyOf(input);
    }

    private static Long usageValue(ThreadEvent event, String name) {
        if (event == null) {
            return null;
        }
        try {
            return Long.valueOf(event.payload().get(name));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private JsonNode forkThread(JsonNode params) {
        AgentThread fork = threads.forkThread(
                RequestParameters.threadId(params),
                new TurnId(RequestParameters.requiredText(params, "throughTurnId")),
                RequestParameters.optionalText(params, "title", ""),
                null,
                RequestParameters.optionalText(params, "idempotencyKey", null));
        subscriptions.register(fork.id(), fork.lastSequence());
        return json.valueToTree(wire.thread(fork));
    }

    private JsonNode deleteThread(JsonNode params) {
        ThreadId id = RequestParameters.threadId(params);
        if (worktreeRecovery != null) {
            worktreeRecovery.assertCanDeleteThread(id);
        }
        threads.deleteThread(
                id,
                RequestParameters.optionalLong(params, "expectedRevision", -1),
                RequestParameters.optionalText(params, "idempotencyKey", null));
        subscriptions.remove(id);
        return RpcResults.flag("deleted", true);
    }

    private JsonNode startTurn(JsonNode params) {
        ThreadId threadId = RequestParameters.threadId(params);
        ThreadSnapshot snapshot = requireSnapshot(threadId);
        subscriptions.register(threadId, snapshot.thread().lastSequence());
        TurnConfig config = resolveConfig(params, snapshot);
        AgentTurn turn = turns.startTurn(new TurnStartCommand(
                threadId,
                RequestParameters.inputs(params, config.workingDirectory()),
                config,
                RequestParameters.optionalText(params, "idempotencyKey", null)));
        return json.valueToTree(wire.turn(turn));
    }

    private TurnConfig resolveConfig(JsonNode params, ThreadSnapshot snapshot) {
        if (profiles == null) {
            return RequestParameters.turnConfig(params, snapshot.thread().workingDirectory());
        }
        JsonNode clientConfig = params == null ? null : params.get("config");
        if (clientConfig != null
                && clientConfig.isObject()
                && (clientConfig.has("provider")
                        || clientConfig.has("model")
                        || clientConfig.has("accessMode")
                        || clientConfig.has("workingDirectory")
                        || clientConfig.has("sandboxPolicy"))) {
            throw new IllegalArgumentException("provider, model and sandbox authority are resolved from profileId");
        }
        String profileId = RequestParameters.requiredText(params, "profileId");
        Workspace workspace = workspaces
                .readWorkspace(new WorkspaceId(snapshot.thread().workspaceId()))
                .orElseThrow(() -> new NoSuchElementException("workspace not found for thread"));
        ApprovalPolicy approval = ApprovalPolicy.valueOf(
                RequestParameters.optionalText(clientConfig, "approvalPolicy", ApprovalPolicy.ON_RISK.name()));
        var resolved = profiles.resolve(
                profileId,
                workspace,
                approval,
                RequestParameters.optionalText(clientConfig, "reasoningEffort", "medium"));
        if (!Set.of(com.javaclaw.core.api.ProfileKind.CHAT, com.javaclaw.core.api.ProfileKind.PLAN)
                .contains(resolved.profile().kind())) {
            throw new IllegalArgumentException(
                    "use automation/schedule or collaboration methods for this Profile kind");
        }
        return resolved.turnConfig();
    }

    private JsonNode listEvents(JsonNode params) {
        ThreadId id = RequestParameters.threadId(params);
        long after = RequestParameters.optionalLong(params, "afterSequence", 0);
        if (after < 0) {
            throw new IllegalArgumentException("afterSequence must be non-negative");
        }
        long requestedLimit = RequestParameters.optionalLong(params, "limit", 1_000);
        if (requestedLimit < 1 || requestedLimit > 10_000) {
            throw new IllegalArgumentException("limit must be between 1 and 10000");
        }
        return json.valueToTree(threads.eventsAfter(id, after, Math.toIntExact(requestedLimit)).stream()
                .map(wire::event)
                .toList());
    }

    private ThreadSnapshot requireSnapshot(ThreadId id) {
        return threads.readThread(id).orElseThrow(() -> new NoSuchElementException("thread not found: " + id));
    }
}
