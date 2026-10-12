package com.javaclaw.framework.api;

/** The durable turn remains open and requires recovery before its caller may continue. */
public class TurnPausedException extends RuntimeException {
    public enum Reason { UNSPECIFIED, UNAUTHORIZED_CONTEXT_SOURCE }

    private final Reason reason;
    private final String contextSourceId;

    public TurnPausedException(String message) {
        this(message, Reason.UNSPECIFIED, "");
    }

    private TurnPausedException(String message, Reason reason, String contextSourceId) {
        super(message);
        this.reason = reason;
        this.contextSourceId = contextSourceId;
    }

    public static TurnPausedException unauthorizedContextSource(String sourceId) {
        return new TurnPausedException("planner selected an unauthorized context source",
                Reason.UNAUTHORIZED_CONTEXT_SOURCE, sourceId == null ? "" : sourceId);
    }

    public Reason reason() { return reason; }
    public String contextSourceId() { return contextSourceId; }
}
