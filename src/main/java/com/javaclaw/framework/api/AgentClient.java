package com.javaclaw.framework.api;

/** The sole application-facing entry into Agent execution. */
public interface AgentClient {
    RunHandle start(RunRequest request);

    RunHandle resume(RunId runId, ResumeCommand command);

    boolean cancel(RunId runId, CancelReason reason);

    RunSnapshot get(RunId runId);

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
