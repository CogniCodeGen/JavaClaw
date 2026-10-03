package com.javaclaw.framework.core;

import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.BudgetExceededException;
import com.javaclaw.framework.api.CancelReason;
import com.javaclaw.framework.api.ToolApprovalGrant;
import com.javaclaw.framework.spi.CancellationToken;
import com.javaclaw.framework.spi.EffectReceiptV1;
import com.javaclaw.framework.spi.ToolEffectPolicy;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;

/** Per-run mutable control state. It is never stored on an Agent singleton. */
public final class RunControl implements CancellationToken {
    private final AtomicReference<CancelReason> cancellationReason = new AtomicReference<>();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicReference<String> lastToolFingerprint = new AtomicReference<>();
    private final AtomicInteger consecutiveDuplicateCalls = new AtomicInteger();
    private final ConcurrentHashMap<String, ToolApprovalGrant> oneShotToolApprovals =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<EffectAttemptId, EffectAttempt> effectAttempts =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<EffectAttemptId, DesktopInputBinding> desktopInputBindings =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DesktopObservationBaseline.Frame> desktopObservations =
            new ConcurrentHashMap<>();
    private final String effectNamespace = java.util.UUID.randomUUID().toString();
    private final AtomicBoolean taskRepair = new AtomicBoolean();
    private volatile DesktopApplicationIdentityBindings desktopApplicationBindings;
    private volatile String desktopBindingsRunId = "";
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> cancellationListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Clock clock;
    private final Instant deadline;
    private final RunBudget budget;

    RunControl(RunBudget budget, Clock clock) {
        this(budget, clock, clock.instant().plus(budget.timeout()));
    }

    RunControl(RunBudget budget, Clock clock, Instant deadline) {
        this.budget = Objects.requireNonNull(budget, "budget");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
    }

    @Override
    public boolean cancelled() {
        return cancellationReason.get() != null || expired();
    }

    public boolean cancel() {
        return cancel(new CancelReason("CANCELLED_DURING_EXECUTION", ""));
    }

    /** Publish the reason before callbacks can report cancellation back to the engine. */
    public boolean cancel(CancelReason reason) {
        if (!cancellationReason.compareAndSet(null, Objects.requireNonNull(reason, "reason"))) return false;
        cancellationListeners.forEach(Runnable::run);
        cancellationListeners.clear();
        return true;
    }

    public boolean expired() { return !clock.instant().isBefore(deadline); }

    /** An explicit stop already requested by the owner takes precedence over clock expiry. */
    public Optional<CancelReason> cancellationReason() {
        CancelReason requested = cancellationReason.get();
        return requested != null ? Optional.of(requested)
                : expired() ? Optional.of(timeoutReason()) : Optional.empty();
    }

    CancelReason timeoutReason() {
        return new CancelReason("RUN_TIMEOUT", "run deadline exceeded: " + deadline);
    }

    @Override
    public CancellationRegistration onCancel(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        if (cancelled()) {
            callback.run();
            return () -> { };
        }
        cancellationListeners.add(callback);
        if (cancelled() && cancellationListeners.remove(callback)) callback.run();
        return () -> cancellationListeners.remove(callback);
    }

    public Instant deadline() { return deadline; }

    @Override
    public Duration remaining() {
        Duration value = Duration.between(clock.instant(), deadline);
        return value.isNegative() ? Duration.ZERO : value;
    }

    public int recordToolCall(String fingerprint) {
        int count = restoreToolCall(fingerprint);
        throwIfCancelled();
        return count;
    }

    int restoreToolCall(String fingerprint) {
        int count = toolCalls.incrementAndGet();
        if (count > budget.maxToolCalls()) {
            throw BudgetExceededException.toolCalls(count, budget.maxToolCalls());
        }
        String previous = lastToolFingerprint.getAndSet(Objects.requireNonNull(fingerprint));
        int duplicates;
        if (fingerprint.equals(previous)) {
            duplicates = consecutiveDuplicateCalls.incrementAndGet();
        } else {
            consecutiveDuplicateCalls.set(0);
            duplicates = 0;
        }
        if (duplicates >= 3) {
            throw BudgetExceededException.repeatedToolCalls(duplicates + 1, 3);
        }
        return count;
    }

    public int toolCallCount() { return toolCalls.get(); }

    /** Mark the same Run's bounded task repair phase; tool permissions and budget stay intact. */
    public void enterTaskRepair() { taskRepair.set(true); }

    /** Reject a repeated side effect on repair, including a crash with no terminal receipt. */
    public void assertRepairRetryAllowed(String fingerprint, boolean idempotent) {
        assertRepairRetryAllowed(fingerprint, fingerprint, idempotent);
    }

    public synchronized void assertRepairRetryAllowed(String fingerprint, String effectKey, boolean idempotent) {
        assertRepairRetryAllowed(fingerprint, effectKey, idempotent,
                ToolEffectPolicy.LEGACY, "");
    }

    public synchronized void assertRepairRetryAllowed(String fingerprint, String effectKey,
            boolean idempotent, ToolEffectPolicy policy, String resourceKey) {
        assertRepairRetryAllowed(fingerprint, effectKey, idempotent, policy, resourceKey, null);
    }

    public synchronized void assertRepairRetryAllowed(String fingerprint, String effectKey,
            boolean idempotent, ToolEffectPolicy policy, String resourceKey,
            com.fasterxml.jackson.databind.JsonNode arguments) {
        if (policy == ToolEffectPolicy.ENSURE_STATE) return;
        if (idempotent) return;
        var blocked = effectAttempts.entrySet().stream().filter(entry -> {
            EffectAttempt attempt = entry.getValue();
            if (policy == ToolEffectPolicy.DISCOVERY_GATED
                    && attempt.policy() == ToolEffectPolicy.DISCOVERY_GATED
                    && sameDiscoveryResource(attempt.resourceKey(), resourceKey)
                    && unresolvedInput(attempt)) return true;
            if (policy == ToolEffectPolicy.OBSERVATION_GATED
                    && attempt.policy() == ToolEffectPolicy.OBSERVATION_GATED) {
                if (sameResource(attempt.resourceKey(), resourceKey) && unresolvedInput(attempt)
                        && !observedAfterInput(entry.getKey(), attempt, resourceKey, arguments))
                    return true;
                // A completed call cannot be replayed from its original observation.
                // A fresh observation may support a genuinely new scroll or input.
                return (attempt.fingerprint().equals(fingerprint)
                        || attempt.effectKey().equals(effectKey))
                        && !(attempt.status() == EffectReceiptV1.Status.FAILED
                                && "NOT_SENT".equals(attempt.delivery()));
            }
            if (!attempt.fingerprint().equals(fingerprint)
                    && !attempt.effectKey().equals(effectKey)) return false;
            // This rejection proves only this attempt was not dispatched; other attempts
            // with the same effect identity still participate in the safety check.
            if (attempt.status() == EffectReceiptV1.Status.FAILED
                    && "NOT_SENT".equals(attempt.delivery())) return false;
            if (policy == ToolEffectPolicy.DISCOVERY_GATED) {
                // Only tools with host discovery/admission may revisit known state.
                // A lost receipt or partial delivery must never cause another dispatch.
                return !((attempt.status() == EffectReceiptV1.Status.ACCEPTED
                            || attempt.status() == EffectReceiptV1.Status.OBSERVED
                            || attempt.status() == EffectReceiptV1.Status.VERIFIED)
                        && ("SENT".equals(attempt.delivery())
                            || "NOT_SENT".equals(attempt.delivery())));
            }
            return taskRepair.get() || attempt.status() == null
                    || attempt.status() == EffectReceiptV1.Status.UNKNOWN
                    || attempt.status() == EffectReceiptV1.Status.ACCEPTED
                    || attempt.status() == EffectReceiptV1.Status.FAILED;
        }).findFirst();
        if (blocked.isPresent()) {
            var entry = blocked.get();
            EffectAttempt attempt = entry.getValue();
            var reason = unresolvedInput(attempt)
                    ? PendingEffectObservationRequiredException.Reason.DELIVERY_UNCERTAIN
                    : policy == ToolEffectPolicy.OBSERVATION_GATED
                        ? PendingEffectObservationRequiredException.Reason.OBSERVATION_ALREADY_CONSUMED
                        : PendingEffectObservationRequiredException.Reason.EFFECT_ALREADY_ATTEMPTED;
            throw new PendingEffectObservationRequiredException(entry.getKey().sourceRunId(),
                    entry.getKey().invocationId(), attempt.resourceKey(), attempt.status(),
                    attempt.delivery(), reason);
        }
    }

    private static boolean sameResource(String previous, String requested) {
        if (previous.startsWith("desktop:") && requested.startsWith("desktop:"))
            return previous.equals("desktop:unknown") || requested.equals("desktop:unknown")
                    || previous.equals(requested);
        return !previous.isBlank() && previous.equals(requested);
    }

    private static boolean sameDiscoveryResource(String previous, String requested) {
        if (previous.startsWith("desktop.application:") && requested.startsWith("desktop.application:"))
            return previous.equals("desktop.application:unknown")
                    || requested.equals("desktop.application:unknown") || previous.equals(requested);
        return !previous.isBlank() && previous.equals(requested);
    }

    /** Recover the current Run's complete host catalog without granting tool access. */
    void restoreDesktopApplicationBindings(DesktopApplicationIdentityBindings bindings, String runId) {
        desktopApplicationBindings = Objects.requireNonNull(bindings, "bindings");
        desktopBindingsRunId = Objects.requireNonNull(runId, "runId");
    }

    String desktopLaunchResourceKey(String requested) {
        var bindings = desktopApplicationBindings;
        String identity = bindings == null ? "" : bindings.canonicalIdentity(
                requested, desktopBindingsRunId, Long.MAX_VALUE);
        return "desktop.application:" + (identity.isBlank() ? "unknown" : identity);
    }

    private static boolean unresolvedInput(EffectAttempt attempt) {
        if (attempt.reconciled()) return false;
        if (attempt.status() == null || attempt.status() == EffectReceiptV1.Status.UNKNOWN)
            return true;
        if ("MAYBE_SENT".equals(attempt.delivery())) return true;
        return attempt.status() == EffectReceiptV1.Status.FAILED
                && !"NOT_SENT".equals(attempt.delivery());
    }

    /** A new input baseline never changes the old action's business effect or receipt. */
    private boolean observedAfterInput(EffectAttemptId id, EffectAttempt attempt,
            String resourceKey, com.fasterxml.jackson.databind.JsonNode arguments) {
        if (arguments == null || !attempt.resourceKey().equals(resourceKey)) return false;
        DesktopObservationBaseline.Frame frame = desktopObservations.get(resourceKey);
        return frame != null && frame.sessionId().equals(arguments.path("sessionId").asText(""))
                && frame.observationId().equals(arguments.path("observationId").asText(""))
                && arguments.path("generation").isIntegralNumber()
                && arguments.path("generation").canConvertToLong()
                && frame.windowGeneration() == arguments.path("generation").asLong()
                && observedAfterInput(id, attempt, frame);
    }

    private boolean observedAfterInput(EffectAttemptId id, EffectAttempt attempt,
            DesktopObservationBaseline.Frame frame) {
        DesktopInputBinding input = desktopInputBindings.get(id);
        return input != null && !attempt.resourceKey().equals("desktop:unknown")
                && attempt.resourceKey().equals("desktop:" + frame.targetId())
                && !input.observationId().equals(frame.observationId())
                && frame.capturedAtMillis() > input.attemptedAtMillis();
    }

    /** Read-only prerequisite state, also covering uncertain inputs inherited from older Runs. */
    public boolean hasPendingDesktopInput() {
        return effectAttempts.entrySet().stream().anyMatch(entry -> {
            EffectAttempt input = entry.getValue();
            DesktopObservationBaseline.Frame frame = desktopObservations.get(input.resourceKey());
            return input.policy() == ToolEffectPolicy.OBSERVATION_GATED && unresolvedInput(input)
                    && (frame == null || !observedAfterInput(entry.getKey(), input, frame));
        });
    }

    public boolean requiresDesktopObservation(String targetId, String sessionId, String observationId) {
        return effectAttempts.entrySet().stream().anyMatch(entry -> {
            EffectAttempt input = entry.getValue();
            if (input.policy() != ToolEffectPolicy.OBSERVATION_GATED || !unresolvedInput(input)) return false;
            if (targetId == null || targetId.isBlank()) return true;
            String resource = "desktop:" + targetId;
            if (!sameResource(input.resourceKey(), resource)) return false;
            DesktopObservationBaseline.Frame frame = desktopObservations.get(resource);
            return frame == null || !frame.sessionId().equals(sessionId)
                    || !frame.observationId().equals(observationId)
                    || !observedAfterInput(entry.getKey(), input, frame);
        });
    }

    public boolean hasDesktopObservationBaseline(String targetId, String sessionId, String observationId) {
        DesktopObservationBaseline.Frame frame = desktopObservations.get("desktop:" + targetId);
        return frame != null && frame.sessionId().equals(sessionId)
                && frame.observationId().equals(observationId);
    }

    /** Called only for a paired host start or while replaying its durable core journal. */
    void restoreDesktopInputStart(String invocationId, com.fasterxml.jackson.databind.JsonNode args,
            Instant startedAt) {
        EffectAttemptId id = new EffectAttemptId("", invocationId);
        EffectAttempt attempt = effectAttempts.get(id);
        if (attempt == null || attempt.policy() != ToolEffectPolicy.OBSERVATION_GATED) return;
        desktopObservations.remove(attempt.resourceKey());
        String session = args.path("sessionId").asText("");
        String observation = args.path("observationId").asText("");
        if (!session.isBlank() && !observation.isBlank() && startedAt.toEpochMilli() > 0)
            desktopInputBindings.put(id, new DesktopInputBinding(session, observation, startedAt.toEpochMilli()));
    }

    void restoreDesktopInputReceipt(String invocationId, com.fasterxml.jackson.databind.JsonNode args,
            EffectReceiptV1 receipt) {
        EffectAttemptId id = new EffectAttemptId("", invocationId);
        EffectAttempt attempt = effectAttempts.get(id);
        DesktopInputBinding binding = desktopInputBindings.get(id);
        var metadata = receipt.metadata();
        String target = metadata.getOrDefault("targetId", "");
        if (attempt == null || binding == null || !receipt.tool().startsWith("desktop_session_")
                || !java.util.Set.of("click", "type", "key", "scroll").contains(receipt.operation())
                || !receipt.tool().equals("desktop_session_" + receipt.operation())
                || !binding.sessionId().equals(args.path("sessionId").asText(""))
                || !binding.sessionId().equals(metadata.getOrDefault("sessionId", ""))
                || !binding.observationId().equals(metadata.getOrDefault("observationId", ""))
                || target.isBlank() || !(attempt.resourceKey().equals("desktop:" + target)
                    || attempt.resourceKey().equals("desktop:unknown"))) return;
        if (attempt.resourceKey().equals("desktop:unknown")) effectAttempts.put(id,
                new EffectAttempt(attempt.fingerprint(), attempt.effectKey(), attempt.policy(),
                        "desktop:" + target, attempt.status(), attempt.delivery(), attempt.reconciled()));
        desktopInputBindings.put(id, new DesktopInputBinding(binding.sessionId(), binding.observationId(),
                Math.max(binding.attemptedAtMillis(), receipt.observedAt().toEpochMilli())));
    }

    /** Lost platform results still have a durable host boundary time; they do not bind a target. */
    void restoreDesktopInputReceiptTime(String invocationId, Instant at) {
        desktopInputBindings.computeIfPresent(new EffectAttemptId("", invocationId),
                (id, input) -> new DesktopInputBinding(input.sessionId(), input.observationId(),
                        Math.max(input.attemptedAtMillis(), at.toEpochMilli())));
    }

    void restoreDesktopObservation(DesktopObservationBaseline.Frame frame) {
        desktopObservations.put("desktop:" + frame.targetId(), frame);
    }

    /** Import effect safety state only; no spending or approval grants transfer. */
    public void inheritUnresolvedEffects(RunControl previous) {
        inheritUnresolvedEffects(previous, Objects.requireNonNull(previous, "previous").effectNamespace);
    }

    /** Source-qualified invocation IDs keep old receipts and new receipts isolated. */
    public synchronized void inheritUnresolvedEffects(RunControl previous, String sourceRunId) {
        Objects.requireNonNull(previous, "previous");
        String source = Objects.requireNonNull(sourceRunId, "sourceRunId").trim();
        if (source.isBlank()) throw new IllegalArgumentException("effect source Run must not be blank");
        if (previous == this) throw new IllegalArgumentException("cannot inherit own control state");
        var snapshot = java.util.Map.copyOf(previous.effectAttempts);
        // A queued turn may refresh after the previous execution publishes its final receipt.
        effectAttempts.keySet().removeIf(id -> id.sourceRunId().equals(source));
        desktopInputBindings.keySet().removeIf(id -> id.sourceRunId().equals(source));
        snapshot.forEach((id, attempt) -> {
            boolean unresolved = attempt.policy() == ToolEffectPolicy.OBSERVATION_GATED
                    // Keep consumed observations as well as unresolved window fences.
                    ? !(attempt.status() == EffectReceiptV1.Status.FAILED
                            && "NOT_SENT".equals(attempt.delivery()))
                    : attempt.policy() == ToolEffectPolicy.DISCOVERY_GATED
                        ? unresolvedInput(attempt)
                    : attempt.policy() != ToolEffectPolicy.ENSURE_STATE && !attempt.reconciled()
                            && (attempt.status() == null
                                || attempt.status() == EffectReceiptV1.Status.UNKNOWN
                                || attempt.status() == EffectReceiptV1.Status.ACCEPTED
                                || (attempt.status() == EffectReceiptV1.Status.FAILED
                                    && !"NOT_SENT".equals(attempt.delivery())));
            if (unresolved) {
                EffectAttemptId inherited = new EffectAttemptId(
                        id.sourceRunId().isBlank() ? source : id.sourceRunId(), id.invocationId());
                effectAttempts.putIfAbsent(inherited, attempt);
                DesktopInputBinding binding = previous.desktopInputBindings.get(id);
                if (binding != null) desktopInputBindings.putIfAbsent(inherited, binding);
            }
        });
    }

    /** Persist the attempt before any external tool code runs. A failed append cannot dispatch. */
    public synchronized void reserveEffect(String invocationId, String fingerprint,
            String effectKey, boolean idempotent, ToolEffectPolicy policy,
            String resourceKey, Runnable persistStart) {
        reserveEffect(invocationId, fingerprint, effectKey, idempotent, policy, resourceKey, null, persistStart);
    }

    public synchronized void reserveEffect(String invocationId, String fingerprint,
            String effectKey, boolean idempotent, ToolEffectPolicy policy,
            String resourceKey, com.fasterxml.jackson.databind.JsonNode arguments, Runnable persistStart) {
        Objects.requireNonNull(persistStart, "persistStart");
        assertRepairRetryAllowed(fingerprint, effectKey, idempotent, policy, resourceKey, arguments);
        persistStart.run();
        restoreEffectStart(invocationId, fingerprint, effectKey, idempotent,
                policy, resourceKey);
    }

    public void recordEffectStart(String fingerprint, boolean idempotent) {
        restoreEffectStart(fingerprint, fingerprint, idempotent);
    }

    public void recordEffectStart(String fingerprint, String effectKey, boolean idempotent) {
        restoreEffectStart(fingerprint, effectKey, idempotent);
    }

    /** Restore from a durable core.tool.started event without changing the tool-call budget. */
    public void restoreEffectStart(String fingerprint, boolean idempotent) {
        restoreEffectStart(fingerprint, fingerprint, idempotent);
    }

    public void restoreEffectStart(String fingerprint, String effectKey, boolean idempotent) {
        restoreEffectStart(fingerprint, fingerprint, effectKey, idempotent,
                ToolEffectPolicy.LEGACY, "");
    }

    public void restoreEffectStart(String invocationId, String fingerprint, String effectKey,
            boolean idempotent, ToolEffectPolicy policy, String resourceKey) {
        if (!idempotent && policy != ToolEffectPolicy.ENSURE_STATE) effectAttempts.putIfAbsent(
                new EffectAttemptId("", Objects.requireNonNull(invocationId, "invocationId")),
                new EffectAttempt(Objects.requireNonNull(fingerprint, "fingerprint"),
                        Objects.requireNonNull(effectKey, "effectKey"),
                        policy == null ? ToolEffectPolicy.LEGACY : policy,
                        resourceKey == null ? "" : resourceKey, null, "", false));
    }

    public void recordEffectReceipt(String fingerprint, EffectReceiptV1.Status status) {
        restoreEffectReceipt(fingerprint, status);
    }

    /** Restore a durable receipt after its matching started event. */
    public void restoreEffectReceipt(String fingerprint, EffectReceiptV1.Status status) {
        restoreEffectReceipt(fingerprint, status, "");
    }

    public void restoreEffectReceipt(String invocationId, EffectReceiptV1.Status status,
            String delivery) {
        Objects.requireNonNull(status, "status");
        effectAttempts.computeIfPresent(new EffectAttemptId("", Objects.requireNonNull(invocationId, "invocationId")),
                (key, attempt) -> new EffectAttempt(attempt.fingerprint(), attempt.effectKey(),
                        attempt.policy(), attempt.resourceKey(), status,
                        delivery == null ? "" : delivery, attempt.reconciled()));
    }

    /** Bind a launch to its typed host receipt identity; never inspect model output or detail text. */
    public void restoreEffectResource(String invocationId, String resourceKey) {
        if (resourceKey == null || !resourceKey.startsWith("desktop.application:")
                || resourceKey.equals("desktop.application:unknown")) return;
        effectAttempts.computeIfPresent(new EffectAttemptId("", Objects.requireNonNull(invocationId)),
                (key, attempt) -> attempt.policy() != ToolEffectPolicy.DISCOVERY_GATED
                        || !attempt.resourceKey().startsWith("desktop.application:") ? attempt
                        : new EffectAttempt(attempt.fingerprint(), attempt.effectKey(), attempt.policy(),
                            attempt.resourceKey().equals("desktop.application:unknown")
                                    || attempt.resourceKey().equals(resourceKey)
                                ? resourceKey : "desktop.application:unknown",
                            attempt.status(), attempt.delivery(), attempt.reconciled()));
    }

    /** Restore a verifier-persisted satisfaction event, never an ordinary observation. */
    public void restoreEffectReconciliation(String invocationId, String resourceKey) {
        restoreEffectReconciliation("", invocationId, resourceKey);
    }

    /** The source Run is part of the proof identity; identical invocation IDs cannot alias. */
    public void restoreEffectReconciliation(String sourceRunId, String invocationId, String resourceKey) {
        effectAttempts.computeIfPresent(new EffectAttemptId(
                        Objects.requireNonNull(sourceRunId, "sourceRunId"),
                        Objects.requireNonNull(invocationId, "invocationId")),
                (key, attempt) -> attempt.policy() == ToolEffectPolicy.OBSERVATION_GATED
                        && attempt.resourceKey().equals(resourceKey)
                        && attempt.status() == EffectReceiptV1.Status.UNKNOWN
                        && "MAYBE_SENT".equals(attempt.delivery())
                        ? new EffectAttempt(attempt.fingerprint(), attempt.effectKey(),
                                attempt.policy(), attempt.resourceKey(), attempt.status(),
                                attempt.delivery(), true)
                        : attempt);
    }

    private record EffectAttemptId(String sourceRunId, String invocationId) { }
    private record DesktopInputBinding(String sessionId, String observationId, long attemptedAtMillis) { }

    private record EffectAttempt(String fingerprint, String effectKey,
                                 ToolEffectPolicy policy, String resourceKey,
                                 EffectReceiptV1.Status status, String delivery,
                                 boolean reconciled) { }

    /** Remaining calls under the effective Run budget, including catalog calls. */
    public int remainingToolCalls() {
        return Math.max(0, budget.maxToolCalls() - toolCalls.get());
    }

    public void approveToolCall(ToolApprovalGrant grant) {
        Objects.requireNonNull(grant, "grant");
        if (!grant.approved()) throw new IllegalArgumentException("cannot store a denied tool grant");
        oneShotToolApprovals.put(grant.fingerprint(), grant);
    }

    /** Consumes a grant only when it was issued for this exact tool invocation. */
    public Optional<ToolApprovalGrant> consumeToolApprovalGrant(
            String tool, String fingerprint) {
        ToolApprovalGrant grant = oneShotToolApprovals.remove(fingerprint);
        if (grant == null || !grant.tool().equals(Objects.requireNonNull(tool, "tool"))) {
            return Optional.empty();
        }
        return Optional.of(grant);
    }

    /** Removes an unused one-shot grant after its exact continuation finishes or fails. */
    public void discardToolApprovalGrant(String fingerprint) {
        if (fingerprint != null) oneShotToolApprovals.remove(fingerprint);
    }
}
