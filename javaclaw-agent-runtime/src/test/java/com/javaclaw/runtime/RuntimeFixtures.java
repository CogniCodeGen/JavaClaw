package com.javaclaw.runtime;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.ItemPayload;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolCallResult;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;

final class RuntimeFixtures {
    static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private RuntimeFixtures() {}

    static CanonicalPayload payload() {
        return new CanonicalPayload("{\"value\":1}");
    }

    static PermissionProfile permissions() {
        return new PermissionProfile(
                "runtime-test",
                2,
                new FilePermission(List.of(Path.of("/workspace")), List.of(), false, false),
                new NetworkPermission(Set.of("example.com"), Set.of(443), true),
                new ProcessPermission(Set.of("java"), false, Duration.ofSeconds(30)),
                new ToolPermission(Set.of("read", "search"), ToolRisk.READ_ONLY, ApprovalRequirement.NONE),
                new ResourceLimits(1_024, 512, 2, 8));
    }

    static TurnBudget budget() {
        return new TurnBudget(100, 50, 4, 2, Duration.ofMinutes(1));
    }

    static AgentTurn turn(TurnStatus status, TurnBudget budget) {
        TurnId turnId = TurnId.random();
        PermissionProfile permission = permissions();
        ToolCatalogSnapshot catalog = new ToolCatalogSnapshot(turnId, 7, List.of(), permission, NOW);
        return turn(turnId, status, budget, catalog);
    }

    static TurnExecutionCommand command(TurnStatus status, TurnBudget budget) {
        return command(status, budget, List.of());
    }

    static TurnExecutionCommand command(TurnStatus status, TurnBudget budget, List<ToolDescriptor> tools) {
        TurnId turnId = TurnId.random();
        PermissionProfile permission = permissions();
        ToolCatalogSnapshot catalog = new ToolCatalogSnapshot(turnId, 7, tools, permission, NOW);
        AgentTurn turn = turn(turnId, status, budget, catalog);
        return new TurnExecutionCommand(turn, turn.provider(), "system", "hello", permission, catalog);
    }

    private static AgentTurn turn(TurnId turnId, TurnStatus status, TurnBudget budget, ToolCatalogSnapshot catalog) {
        return new AgentTurn(
                turnId,
                ThreadId.random(),
                status,
                1,
                budget,
                new AgentProfileRef("runtime-profile", 1),
                new ProviderRef("runtime-provider", 1, "model"),
                new PermissionProfileRef("runtime-test", 2),
                Path.of("."),
                "a".repeat(64),
                catalog.digest(),
                Optional.empty(),
                NOW,
                NOW);
    }

    static ToolDescriptor tool(String producer, String name, long revision) {
        return new ToolDescriptor(
                new ToolIdentity(producer, name, revision),
                "测试工具",
                payload(),
                payload(),
                ToolRisk.READ_ONLY,
                Set.of("test"));
    }

    static ModelInvocationResult complete(String text) {
        return new ModelInvocationResult(
                text,
                List.of(),
                new ModelUsage(2, 3, 0, 0),
                Optional.empty(),
                Optional.empty(),
                ModelFinishReason.COMPLETE);
    }

    static ModelInvocationResult tools(List<ModelToolCall> calls, ModelUsage usage) {
        return new ModelInvocationResult(
                "", calls, usage, Optional.empty(), Optional.empty(), ModelFinishReason.TOOL_CALLS);
    }

    static final class HarnessEnvironment {
        final QueueModelGateway models;
        final FakeJournal journal = new FakeJournal();
        final List<ToolDescriptor> frozenTools = new ArrayList<>();
        final List<ToolDescriptor> initialTools = new ArrayList<>();
        ConversationWindow window = new ConversationWindow(List.of(), Optional.empty(), 0);
        ContextAssembler contexts = (command, cancellation) -> window;
        ContextCompactor compactor = (command, original, gateway, cancellation) -> new CompactionOutcome(
                new ConversationWindow(original.messages(), original.providerState(), 1),
                new CorePayloads.Compaction("summary", original.estimatedInputTokens(), "compact", Optional.empty()));
        GovernedToolExecutor tools =
                (request, descriptor, snapshot, permissions, cancellation) -> ToolExecutionOutcome.resultOnly(
                        new ToolCallResult(request.callId(), true, payload(), Optional.empty()));

        HarnessEnvironment() {
            this(new QueueModelGateway());
        }

        HarnessEnvironment(QueueModelGateway models) {
            this.models = models;
        }

        DefaultTurnHarness harness() {
            ToolCatalogPort catalogs = new ToolCatalogPort() {
                @Override
                public ToolCatalogSnapshot freeze(
                        TurnId turnId, PermissionProfile permissions, CancellationToken cancellation) {
                    return new ToolCatalogSnapshot(turnId, 7, frozenTools, permissions, NOW);
                }

                @Override
                public List<ToolDescriptor> initialTools(ToolCatalogSnapshot snapshot) {
                    return List.copyOf(initialTools);
                }
            };
            TurnHarnessServices services = new TurnHarnessServices(
                    models, contexts, compactor, catalogs, tools, journal, (turnId, event, cancellation) -> {});
            return new DefaultTurnHarness(services, CLOCK);
        }

        TurnExecutionCommand command(TurnStatus status, TurnBudget budget) {
            return RuntimeFixtures.command(status, budget, frozenTools);
        }
    }

    static class QueueModelGateway implements ModelGateway {
        final Deque<Object> outcomes = new ArrayDeque<>();
        final List<ModelInvocation> invocations = new ArrayList<>();
        ModelCapabilities capabilities = new ModelCapabilities(true, true, true, true, true, false, false);

        @Override
        public ModelCapabilities capabilities(String modelId) {
            return capabilities;
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation)
                throws Exception {
            invocations.add(invocation);
            return next();
        }

        ModelInvocationResult next() throws Exception {
            Object outcome = outcomes.removeFirst();
            if (outcome instanceof Exception failure) {
                throw failure;
            }
            return (ModelInvocationResult) outcome;
        }
    }

    static final class NativeQueueModelGateway extends QueueModelGateway implements NativeConversationSupport {
        final List<ProviderState> continuedStates = new ArrayList<>();

        NativeQueueModelGateway() {
            capabilities = new ModelCapabilities(true, true, true, true, true, true, true);
        }

        @Override
        public ModelInvocationResult invokeContinuing(
                TurnId turnId,
                ModelInvocation invocation,
                ProviderState state,
                ModelEventSink events,
                CancellationToken cancellation)
                throws Exception {
            invocations.add(invocation);
            continuedStates.add(state);
            return next();
        }
    }

    static final class FakeJournal implements TurnJournal {
        final List<Transition> transitions = new ArrayList<>();
        final List<JournalItem> items = new ArrayList<>();
        final List<ProviderState> providerStates = new ArrayList<>();
        TurnRecoverySnapshot recovery = TurnRecoverySnapshot.initial();
        Optional<ToolCallResult> recovered = Optional.empty();
        ToolCallRequest recoveryRequest;

        @Override
        public TurnRecoverySnapshot beginOrRecover(TurnExecutionCommand command) {
            if (command.turn().status() == TurnStatus.QUEUED) {
                transitions.add(new Transition(TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty()));
            }
            return recovery;
        }

        @Override
        public Optional<TurnRecoverySnapshot> findRecovery(TurnId turnId) {
            return Optional.of(recovery);
        }

        @Override
        public TurnRecoverySnapshot readRecovery(TurnId turnId) {
            return recovery;
        }

        @Override
        public void recordModelIntent(TurnId turnId, int invocationNumber, String intentDigest) {
            recovery = new TurnRecoverySnapshot(
                    TurnExecutionPhase.MODEL_IN_FLIGHT,
                    recovery.usage(),
                    recovery.toolCalls(),
                    invocationNumber,
                    recovery.toolBatch(),
                    recovery.nextToolIndex(),
                    recovery.visibleTools(),
                    recovery.seenCallIds(),
                    recovery.assistantText(),
                    Optional.of(intentDigest));
        }

        @Override
        public void commitModelResult(
                TurnId turnId, int invocationNumber, ModelInvocationResult result, ModelUsage cumulativeUsage) {
            if (!result.text().isEmpty()) {
                items.add(new JournalItem(
                        "message",
                        com.javaclaw.api.CoreSchemas.MESSAGE,
                        new CorePayloads.Message(
                                com.javaclaw.api.MessageRole.ASSISTANT, result.text(), List.of(), Optional.empty()),
                        ItemStatus.COMPLETED));
            }
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>(recovery.seenCallIds());
            for (ModelToolCall call : result.toolCalls()) {
                seen.add(call.callId());
                items.add(new JournalItem(
                        "tool-call",
                        com.javaclaw.api.CoreSchemas.TOOL_CALL,
                        new CorePayloads.ToolCall(
                                call.callId(),
                                call.tool().producerId(),
                                call.tool().name(),
                                call.tool().revision(),
                                call.arguments()),
                        ItemStatus.COMPLETED));
            }
            result.providerState().ifPresent(providerStates::add);
            TurnExecutionPhase phase =
                    result.toolCalls().isEmpty() ? TurnExecutionPhase.MODEL_COMMITTED : TurnExecutionPhase.TOOLS_READY;
            recovery = new TurnRecoverySnapshot(
                    phase,
                    cumulativeUsage,
                    recovery.toolCalls(),
                    invocationNumber,
                    new TurnToolBatch(result.toolCalls()),
                    0,
                    recovery.visibleTools(),
                    seen,
                    recovery.assistantText() + result.text(),
                    Optional.empty());
        }

        @Override
        public void recordToolIntent(
                TurnId turnId, int toolIndex, ToolCallRequest request, int consumedToolCalls, String intentDigest) {
            recovery = new TurnRecoverySnapshot(
                    TurnExecutionPhase.TOOL_IN_FLIGHT,
                    recovery.usage(),
                    consumedToolCalls,
                    recovery.modelInvocations(),
                    recovery.toolBatch(),
                    toolIndex,
                    recovery.visibleTools(),
                    recovery.seenCallIds(),
                    recovery.assistantText(),
                    Optional.of(intentDigest));
        }

        @Override
        public void commitToolResult(
                TurnId turnId,
                int toolIndex,
                ToolCallRequest request,
                ToolExecutionOutcome outcome,
                List<ToolIdentity> visibleTools) {
            ToolCallResult result = outcome.result();
            items.add(new JournalItem(
                    "tool-result",
                    com.javaclaw.api.CoreSchemas.TOOL_RESULT,
                    new CorePayloads.ToolResult(result.callId(), result.success(), result.output(), result.receipt()),
                    ItemStatus.COMPLETED));
            result.receipt()
                    .ifPresent(receipt -> items.add(new JournalItem(
                            "effect-receipt",
                            com.javaclaw.api.CoreSchemas.EFFECT_RECEIPT,
                            receipt,
                            ItemStatus.COMPLETED)));
            int next = Math.addExact(toolIndex, 1);
            boolean completed = next == recovery.toolBatch().calls().size();
            recovery = new TurnRecoverySnapshot(
                    completed ? TurnExecutionPhase.READY_FOR_MODEL : TurnExecutionPhase.TOOLS_READY,
                    recovery.usage(),
                    recovery.toolCalls(),
                    recovery.modelInvocations(),
                    completed ? TurnToolBatch.empty() : recovery.toolBatch(),
                    completed ? 0 : next,
                    new TurnVisibleTools(visibleTools),
                    recovery.seenCallIds(),
                    recovery.assistantText(),
                    Optional.empty());
        }

        @Override
        public void transition(TurnId turnId, TurnStatus expected, TurnStatus next, Optional<String> errorCode) {
            transitions.add(new Transition(expected, next, errorCode));
        }

        @Override
        public void append(TurnId turnId, String kind, String schemaId, ItemPayload payload, ItemStatus status) {
            items.add(new JournalItem(kind, schemaId, payload, status));
        }

        @Override
        public void saveProviderState(TurnId turnId, String modelId, ProviderState state, long estimatedInputTokens) {
            providerStates.add(state);
        }

        @Override
        public Optional<ToolCallResult> recoverEffect(ToolCallRequest request) {
            recoveryRequest = request;
            return recovered;
        }
    }

    record Transition(TurnStatus expected, TurnStatus next, Optional<String> errorCode) {}

    record JournalItem(String kind, String schemaId, ItemPayload payload, ItemStatus status) {}
}
