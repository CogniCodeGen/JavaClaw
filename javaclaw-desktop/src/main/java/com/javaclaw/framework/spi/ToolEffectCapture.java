package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * Captures a host ToolResponse construction on the executing thread. A string containing
 * "[成功]" is never examined; only the Java call that constructed a response is recorded.
 * The annotated bridge applies a separate exact-class allowlist before using this signal.
 */
public final class ToolEffectCapture {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private ToolEffectCapture() { }

    public enum Signal { SUCCESS, ERROR, PENDING, TIMEOUT, UNCERTAIN, REOBSERVE }

    public static Scope begin(String tool) {
        Scope scope = new Scope(Objects.requireNonNull(tool, "tool"), CURRENT.get());
        CURRENT.set(scope);
        return scope;
    }

    public static void note(String tool, Signal signal) {
        Scope scope = CURRENT.get();
        if (scope != null && scope.tool.equals(tool)) scope.signal = signal;
    }

    /** Record a target established by the host operation, not by its returned text. */
    public static void noteTarget(String tool, String target) {
        Scope scope = CURRENT.get();
        if (scope != null && scope.tool.equals(tool)) scope.target = target;
    }

    /** Publish machine-readable data separately from the localized tool message. */
    public static void noteData(String tool, JsonNode data) {
        Scope scope = CURRENT.get();
        if (scope != null && scope.tool.equals(tool)) {
            scope.data = data == null ? null : data.deepCopy();
        }
    }

    public static final class Scope implements AutoCloseable {
        private final String tool;
        private final Scope previous;
        private Signal signal;
        private String target;
        private JsonNode data;
        private boolean closed;

        private Scope(String tool, Scope previous) {
            this.tool = tool;
            this.previous = previous;
        }

        public Signal signal() { return signal; }

        public String target() { return target; }
        public JsonNode data() { return data == null ? null : data.deepCopy(); }

        @Override public void close() {
            if (closed) return;
            closed = true;
            if (CURRENT.get() != this) {
                CURRENT.remove();
                throw new IllegalStateException("tool effect capture scopes closed out of order");
            }
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
