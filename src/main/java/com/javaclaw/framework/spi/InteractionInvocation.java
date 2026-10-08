package com.javaclaw.framework.spi;

/** Same-thread observation correlation, deliberately not a claim that an action caused a window. */
public final class InteractionInvocation {
    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();
    private InteractionInvocation() { }
    public static String current() { return CURRENT.get() == null ? "" : CURRENT.get().invocationId(); }
    public static com.javaclaw.framework.api.RunId currentRun() { return CURRENT.get() == null ? null : CURRENT.get().runId(); }
    public static String currentTool() { return CURRENT.get() == null ? "" : CURRENT.get().tool(); }
    public static Scope begin(com.javaclaw.framework.api.RunId runId, String invocationId, String tool) {
        Binding previous = CURRENT.get();
        CURRENT.set(new Binding(runId, invocationId, tool));
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    private record Binding(com.javaclaw.framework.api.RunId runId, String invocationId, String tool) { }
    @FunctionalInterface public interface Scope extends AutoCloseable { @Override void close(); }
}
