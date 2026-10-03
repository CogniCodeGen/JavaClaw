package com.javaclaw.framework.api;

/** The sole application-facing entry into Agent execution. */
public interface AgentClient {
    RunHandle start(RunRequest request);

    RunHandle resume(RunId runId, ResumeCommand command);

    boolean cancel(RunId runId, CancelReason reason);

    RunSnapshot get(RunId runId);

    /** Original host request, subject to the same scope checks as get(). */
    default java.util.Optional<RunRequest> request(RunId runId) {
        return java.util.Optional.empty();
    }

    /** Locked run deadline; resuming a turn does not grant a fresh budget. */
    default java.util.Optional<java.time.Instant> deadline(RunId runId) {
        return java.util.Optional.empty();
    }

    /**
     * Whether the locked Run deadline has expired, subject to the same scope checks as get().
     * Engine implementations use their execution clock; clients without a known deadline return false.
     */
    default boolean expired(RunId runId) {
        return deadline(runId).map(value -> !java.time.Instant.now().isBefore(value)).orElse(false);
    }

    /** Latest durable user-task acceptance result, absent for historical runs. */
    default java.util.Optional<TaskResult> taskResult(RunId runId) {
        return java.util.Optional.empty();
    }

    /** Host-registered capability for an exact tool receipt operation. */
    default java.util.Optional<CapabilityMetadata> capabilityForReceipt(
            String tool, String operation) {
        return java.util.Optional.empty();
    }

    /** Exact host capability and target binding for a statically declared tool call. */
    default java.util.Optional<CapabilityMetadata> capabilityForTool(String tool) {
        return java.util.Optional.empty();
    }

    default RunHandle startTurn(RunRequest request) { return start(request); }
    default RunHandle resumeTurn(TurnId id, ResumeCommand command) { return resume(id.runId(), command); }
    default boolean interruptTurn(TurnId id, CancelReason reason) { return cancel(id.runId(), reason); }
    default RunSnapshot getTurn(TurnId id) { return get(id.runId()); }
    default boolean supportsManagedTurns() { return false; }
    default java.util.Optional<RunSnapshot> activeTurn(RunScope scope) { return java.util.Optional.empty(); }
    default ManagedTurn beginTurn(RunRequest request) {
        throw new UnsupportedOperationException("managed turns are not supported by this client");
    }
}
