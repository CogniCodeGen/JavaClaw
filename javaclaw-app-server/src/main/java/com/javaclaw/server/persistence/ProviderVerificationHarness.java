package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.ItemPayload;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ProviderCapabilities;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderVerificationResult;
import com.javaclaw.api.ProviderVerificationState;
import com.javaclaw.api.ProviderVerificationUsage;
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
import com.javaclaw.runtime.CompactionOutcome;
import com.javaclaw.runtime.ConversationWindow;
import com.javaclaw.runtime.DefaultTurnHarness;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelMessage;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.runtime.ProviderState;
import com.javaclaw.runtime.ToolCatalogPort;
import com.javaclaw.runtime.ToolExecutionOutcome;
import com.javaclaw.runtime.TurnExecutionCommand;
import com.javaclaw.runtime.TurnExecutionResult;
import com.javaclaw.runtime.TurnHarnessServices;
import com.javaclaw.runtime.TurnJournal;
import com.javaclaw.runtime.TurnRecoverySnapshot;

/**
 * 用 Thin Harness 执行不持久化正文的最小 Provider 验证。
 *
 * <p>并发不变量：每次执行只公开空工具目录和拒绝型工具端口；超时或外部取消会先发布协作式 token，再中断所拥有的虚拟线程。Prompt、响应正文、流事件和 opaque state 仅存在于调用栈内，全部丢弃。
 */
final class ProviderVerificationHarness implements AutoCloseable {
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(25);
    private static final String SYSTEM_INSTRUCTION = "这是连通性验证。不得调用工具，只返回 OK。";
    private static final String USER_MESSAGE = "返回 OK";
    private static final PermissionProfile NO_PERMISSIONS = noPermissions();

    private final ModelGateway models;
    private final Clock clock;
    private final Duration maximumTimeout;
    private final ExecutorService executor;

    ProviderVerificationHarness(ModelGateway models, Clock clock) {
        this(models, clock, DEFAULT_TIMEOUT, Executors.newVirtualThreadPerTaskExecutor());
    }

    ProviderVerificationHarness(ModelGateway models, Clock clock, Duration maximumTimeout, ExecutorService executor) {
        this.models = java.util.Objects.requireNonNull(models, "models");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.maximumTimeout = requirePositive(maximumTimeout);
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
    }

    ProviderVerificationResult execute(
            ProviderEndpoint endpoint, ProviderRef provider, CancellationToken cancellation) {
        java.util.Objects.requireNonNull(endpoint, "endpoint");
        java.util.Objects.requireNonNull(provider, "provider");
        CancellationToken checkedCancellation = java.util.Objects.requireNonNull(cancellation, "cancellation");
        ModelCapabilities runtimeCapabilities = models.capabilities(provider.routeKey());
        ProviderCapabilities capabilities = capabilities(endpoint, runtimeCapabilities);
        if (checkedCancellation.isCancelled()) {
            return cancelled(provider, capabilities, 0);
        }
        Duration timeout = minimum(maximumTimeout, endpoint.spec().timeout());
        long startedNanos = System.nanoTime();
        CancellationSource localCancellation = new CancellationSource();
        CancellationToken combined = new CombinedCancellationToken(checkedCancellation, localCancellation);
        Future<TurnExecutionResult> task =
                executor.submit(() -> harness().execute(command(provider, timeout), combined));
        return await(task, provider, capabilities, combined, localCancellation, startedNanos, timeout);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private ProviderVerificationResult await(
            Future<TurnExecutionResult> task,
            ProviderRef provider,
            ProviderCapabilities capabilities,
            CancellationToken cancellation,
            CancellationSource localCancellation,
            long startedNanos,
            Duration timeout) {
        long deadline = Math.addExact(startedNanos, timeout.toNanos());
        while (true) {
            if (cancellation.isCancelled()) {
                localCancellation.cancel("Provider verification cancelled");
                task.cancel(true);
                return cancelled(provider, capabilities, elapsedMillis(startedNanos));
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return timedOut(task, provider, capabilities, localCancellation, startedNanos);
            }
            try {
                long wait = Math.min(remaining, POLL_INTERVAL.toNanos());
                TurnExecutionResult result = task.get(wait, TimeUnit.NANOSECONDS);
                return completed(provider, capabilities, result, startedNanos);
            } catch (TimeoutException ignored) {
                // 周期性检查外部取消和平台时限。
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                localCancellation.cancel("Provider verification interrupted");
                task.cancel(true);
                return cancelled(provider, capabilities, elapsedMillis(startedNanos));
            } catch (ExecutionException failure) {
                return failed(provider, capabilities, "VERIFICATION_EXECUTION_FAILED", startedNanos);
            }
        }
    }

    private ProviderVerificationResult timedOut(
            Future<TurnExecutionResult> task,
            ProviderRef provider,
            ProviderCapabilities capabilities,
            CancellationSource cancellation,
            long startedNanos) {
        cancellation.cancel("Provider verification timeout");
        task.cancel(true);
        return result(
                provider,
                ProviderVerificationState.TIMED_OUT,
                elapsedMillis(startedNanos),
                Optional.empty(),
                capabilities,
                Optional.of("VERIFICATION_TIMED_OUT"));
    }

    private ProviderVerificationResult completed(
            ProviderRef provider, ProviderCapabilities capabilities, TurnExecutionResult execution, long startedNanos) {
        ProviderVerificationUsage usage = usage(execution.usage());
        long elapsed = elapsedMillis(startedNanos);
        return switch (execution.status()) {
            case COMPLETED ->
                result(
                        provider,
                        ProviderVerificationState.SUCCEEDED,
                        elapsed,
                        Optional.of(usage),
                        capabilities,
                        Optional.empty());
            case CANCELLED -> cancelled(provider, capabilities, elapsed);
            case FAILED ->
                result(
                        provider,
                        ProviderVerificationState.FAILED,
                        elapsed,
                        Optional.of(usage),
                        capabilities,
                        execution.errorCode());
            default -> failed(provider, capabilities, "VERIFICATION_NON_TERMINAL", startedNanos);
        };
    }

    private ProviderVerificationResult cancelled(
            ProviderRef provider, ProviderCapabilities capabilities, long latencyMillis) {
        return result(
                provider,
                ProviderVerificationState.CANCELLED,
                latencyMillis,
                Optional.empty(),
                capabilities,
                Optional.of("VERIFICATION_CANCELLED"));
    }

    private ProviderVerificationResult failed(
            ProviderRef provider, ProviderCapabilities capabilities, String code, long startedNanos) {
        return result(
                provider,
                ProviderVerificationState.FAILED,
                elapsedMillis(startedNanos),
                Optional.empty(),
                capabilities,
                Optional.of(code));
    }

    private ProviderVerificationResult result(
            ProviderRef provider,
            ProviderVerificationState state,
            long latencyMillis,
            Optional<ProviderVerificationUsage> usage,
            ProviderCapabilities capabilities,
            Optional<String> errorCode) {
        return new ProviderVerificationResult(
                provider,
                ProviderModelPurpose.CHAT,
                state,
                latencyMillis,
                usage,
                capabilities,
                errorCode,
                clock.instant());
    }

    private DefaultTurnHarness harness() {
        TurnHarnessServices services = new TurnHarnessServices(
                models,
                (command, cancellation) -> new ConversationWindow(
                        List.of(new ModelMessage(
                                MessageRole.USER,
                                command.userMessage(),
                                List.of(),
                                Optional.empty(),
                                Optional.empty())),
                        Optional.empty(),
                        4),
                ProviderVerificationHarness::rejectCompaction,
                new EmptyToolCatalog(),
                (request, descriptor, snapshot, permissions, cancellation) -> {
                    throw new IllegalStateException("Provider verification cannot execute tools");
                },
                new EphemeralJournal(),
                (turnId, event, cancellation) -> {});
        return new DefaultTurnHarness(services, clock);
    }

    private TurnExecutionCommand command(ProviderRef provider, Duration timeout) {
        TurnId turnId = TurnId.random();
        ToolCatalogSnapshot catalog = new ToolCatalogSnapshot(turnId, 1, List.of(), NO_PERMISSIONS, clock.instant());
        AgentTurn turn = new AgentTurn(
                turnId,
                ThreadId.random(),
                TurnStatus.QUEUED,
                1,
                new TurnBudget(64, 8, 1, 0, timeout),
                new AgentProfileRef("provider-verification", 1),
                provider,
                new PermissionProfileRef(NO_PERMISSIONS.id(), NO_PERMISSIONS.version()),
                Path.of("."),
                "0".repeat(64),
                catalog.digest(),
                Optional.empty(),
                clock.instant(),
                clock.instant());
        return new TurnExecutionCommand(turn, provider, SYSTEM_INSTRUCTION, USER_MESSAGE, NO_PERMISSIONS, catalog);
    }

    private static CompactionOutcome rejectCompaction(
            TurnExecutionCommand command,
            ConversationWindow window,
            ModelGateway gateway,
            CancellationToken cancellation) {
        throw new IllegalStateException("Provider verification context must fit the fixed budget");
    }

    private static ProviderCapabilities capabilities(ProviderEndpoint endpoint, ModelCapabilities value) {
        return new ProviderCapabilities(
                Set.of(ProviderModelPurpose.CHAT),
                value.streaming(),
                value.toolCalls(),
                value.structuredOutput(),
                value.images(),
                value.reasoningSummary(),
                value.opaqueState(),
                value.nativeCompaction());
    }

    private static ProviderVerificationUsage usage(ModelUsage value) {
        return new ProviderVerificationUsage(
                value.inputTokens(), value.outputTokens(), value.reasoningTokens(), value.cachedInputTokens());
    }

    private static PermissionProfile noPermissions() {
        return new PermissionProfile(
                "provider-verification",
                1,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(1)),
                new ToolPermission(Set.of(), ToolRisk.READ_ONLY, ApprovalRequirement.EVERY_CALL),
                new ResourceLimits(1, 1, 1, 1));
    }

    private static Duration minimum(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static Duration requirePositive(Duration value) {
        Duration checked = java.util.Objects.requireNonNull(value, "maximumTimeout");
        if (checked.isZero() || checked.isNegative()) {
            throw new IllegalArgumentException("maximumTimeout must be positive");
        }
        return checked;
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }

    private static final class EmptyToolCatalog implements ToolCatalogPort {
        @Override
        public ToolCatalogSnapshot freeze(
                TurnId turnId, PermissionProfile permissions, CancellationToken cancellation) {
            return new ToolCatalogSnapshot(turnId, 1, List.of(), permissions, Instant.now());
        }

        @Override
        public List<ToolDescriptor> initialTools(ToolCatalogSnapshot snapshot) {
            return List.of();
        }
    }

    private static final class EphemeralJournal implements TurnJournal {
        @Override
        public TurnRecoverySnapshot beginOrRecover(TurnExecutionCommand command) {
            return TurnRecoverySnapshot.initial();
        }

        @Override
        public Optional<TurnRecoverySnapshot> findRecovery(TurnId turnId) {
            return Optional.of(TurnRecoverySnapshot.initial());
        }

        @Override
        public TurnRecoverySnapshot readRecovery(TurnId turnId) {
            return TurnRecoverySnapshot.initial();
        }

        @Override
        public void recordModelIntent(TurnId turnId, int invocationNumber, String intentDigest) {}

        @Override
        public void commitModelResult(
                TurnId turnId, int invocationNumber, ModelInvocationResult result, ModelUsage cumulativeUsage) {}

        @Override
        public void recordToolIntent(
                TurnId turnId, int toolIndex, ToolCallRequest request, int consumedToolCalls, String intentDigest) {}

        @Override
        public void commitToolResult(
                TurnId turnId,
                int toolIndex,
                ToolCallRequest request,
                ToolExecutionOutcome outcome,
                List<ToolIdentity> visibleTools) {}

        @Override
        public void transition(TurnId turnId, TurnStatus expected, TurnStatus next, Optional<String> errorCode) {}

        @Override
        public void append(TurnId turnId, String kind, String schemaId, ItemPayload payload, ItemStatus status) {}

        @Override
        public void saveProviderState(TurnId turnId, String modelId, ProviderState state, long estimatedInputTokens) {}

        @Override
        public Optional<ToolCallResult> recoverEffect(ToolCallRequest request) {
            return Optional.empty();
        }
    }
}
